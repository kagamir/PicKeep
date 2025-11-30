package net.kagamir.pickeep.sync

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import net.kagamir.pickeep.data.local.PicKeepDatabase
import net.kagamir.pickeep.data.local.entity.SyncStatus
import net.kagamir.pickeep.monitor.PhotoScanner
import net.kagamir.pickeep.storage.StorageClient
import net.kagamir.pickeep.storage.webdav.ChunkedUploader
import java.io.File
import java.util.UUID

/**
 * 同步引擎
 * 负责照片的增量检测和上传流程
 */
class SyncEngine(
    private val context: Context,
    private val database: PicKeepDatabase,
    private val storageClient: StorageClient,
    private val masterKey: ByteArray,
    private val syncState: SyncState
) {
    
    private val photoDao = database.photoDao()
    private val deviceId: String = getOrCreateDeviceId()
    private val photoScanner = PhotoScanner(context, database)
    
    /**
     * 计算最优并发数
     * 根据文件大小和可用内存动态计算，最大不超过3
     * 
     * @param photos 待上传的照片列表
     * @return 最优并发数（1-3）
     */
    private fun calculateOptimalConcurrency(
        photos: List<net.kagamir.pickeep.data.local.entity.PhotoEntity>
    ): Int {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory()
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val availableMemory = maxMemory - totalMemory + freeMemory
        
        // 保留30%内存给系统和其他应用
        val usableMemory = (availableMemory * 0.7).toLong()
        
        // 估算每个上传任务的内存占用
        // 基础开销：源文件读取缓冲区(8KB) + 其他开销(2MB)
        val baseMemoryPerTask = 2 * 1024 * 1024 + 8 * 1024 // ~2MB
        
        // 计算这批文件的总内存需求
        var totalMemoryNeeded = 0L
        val chunkSizeBytes = 5 * 1024 * 1024L // 5MB 分片大小
        
        photos.forEach { photo ->
            val fileSize = photo.localSize
            // 加密后文件可能略大（假设增加5%）
            val encryptedSize = (fileSize * 1.05).toLong()
            
            // 判断是否需要分片上传（视频或大于10MB的文件）
            val needsChunking = fileSize > 10 * 1024 * 1024 || 
                photo.localPath.let { path ->
                    val mimeType = context.contentResolver.getType(android.net.Uri.fromFile(File(path)))
                    mimeType?.startsWith("video/") == true
                }
            
            if (needsChunking) {
                // 分片上传：需要源文件 + 加密文件 + 分片缓冲区
                totalMemoryNeeded += fileSize + encryptedSize + chunkSizeBytes + baseMemoryPerTask
            } else {
                // 直接上传：需要源文件 + 加密文件
                totalMemoryNeeded += fileSize + encryptedSize + baseMemoryPerTask
            }
        }
        
        // 如果内存充足，尝试使用最大并发数3
        // 否则根据可用内存计算
        val maxConcurrency = 3
        if (totalMemoryNeeded == 0L) {
            return 1
        }
        
        // 计算基于内存的并发数
        val memoryBasedConcurrency = (usableMemory / (totalMemoryNeeded / photos.size)).toInt().coerceAtLeast(1)
        
        // 返回不超过3的并发数
        return minOf(memoryBasedConcurrency, maxConcurrency, photos.size)
    }
    
    /**
     * 执行完整同步
     */
    suspend fun syncPhotos() = withContext(Dispatchers.IO) {
        if (syncState.state.value.isSyncing) {
            return@withContext
        }
        
        try {
            syncState.startSync()
            
            // 1. 扫描增量变化
            photoScanner.scanForChanges()
            
            // 2. 获取待上传的照片
            val pendingPhotos = photoDao.getPhotosByStatusesSync(
                listOf(SyncStatus.PENDING, SyncStatus.FAILED)
            )
            
            if (pendingPhotos.isEmpty()) {
                syncState.stopSync()
                return@withContext
            }
            
            // 3. 批量上传（限制并发）
            val batchSize = 10
            
            pendingPhotos.chunked(batchSize).forEach { batch ->
                // 检查是否暂停
                while (syncState.state.value.isPaused) {
                    kotlinx.coroutines.delay(500) // 暂停时等待
                }
                
                // 检查是否取消
                if (!syncState.state.value.isSyncing) {
                    return@withContext
                }
                
                // 动态计算并发数（最大不超过3）
                val concurrency = calculateOptimalConcurrency(batch)
                uploadBatch(batch, concurrency)
            }
            
            syncState.stopSync()
            
        } catch (e: Exception) {
            syncState.setError(e.message ?: "同步失败")
        }
    }
    
    /**
     * 上传一批照片
     */
    private suspend fun uploadBatch(
        photos: List<net.kagamir.pickeep.data.local.entity.PhotoEntity>,
        concurrency: Int
    ) = withContext(Dispatchers.IO) {
        // 创建分片上传器（用于视频和大文件）
        val chunkedUploader = ChunkedUploader(storageClient, chunkSizeBytes = 5 * 1024 * 1024)
        
        val uploadTask = UploadTask(
            context,
            database,
            storageClient,
            masterKey,
            deviceId,
            chunkedUploader
        )
        
        // 使用 Semaphore 控制并发
        val semaphore = kotlinx.coroutines.sync.Semaphore(concurrency)
        
        coroutineScope {
            val jobs = photos.map { photo ->
                async(Dispatchers.IO) {
                    semaphore.acquire()
                    try {
                        // 检查是否暂停
                        while (syncState.state.value.isPaused) {
                            kotlinx.coroutines.delay(500)
                        }
                        
                        // 检查是否取消
                        if (!syncState.state.value.isSyncing) {
                            return@async
                        }
                        
                        // 更新状态
                        updateSyncCounts()
                        
                        // 上传
                        syncState.updateCurrentUpload(File(photo.localPath).name, 0)
                        
                        val result = uploadTask.uploadWithRetry(photo) { uploaded, total ->
                            val progress = if (total > 0) {
                                ((uploaded * 100) / total).toInt()
                            } else {
                                0
                            }
                            syncState.updateCurrentUpload(File(photo.localPath).name, progress)
                        }
                        
                        if (result.isFailure) {
                            // 记录失败
                            val error = result.exceptionOrNull()
                            android.util.Log.e("SyncEngine", "上传失败: ${photo.localPath}", error)
                        }
                        
                    } finally {
                        semaphore.release()
                        syncState.updateCurrentUpload(null, 0)
                    }
                }
            }
            
            // 等待所有任务完成
            jobs.awaitAll()
        }
        
        // 最后更新一次状态
        updateSyncCounts()
    }
    
    /**
     * 更新同步统计
     */
    private suspend fun updateSyncCounts() {
        val pending = photoDao.countByStatus(SyncStatus.PENDING).first()
        val uploading = photoDao.countByStatus(SyncStatus.UPLOADING).first()
        val synced = photoDao.countByStatus(SyncStatus.SYNCED).first()
        val failed = photoDao.countByStatus(SyncStatus.FAILED).first()
        
        syncState.updateCounts(pending, uploading, synced, failed)
    }
    
    /**
     * 处理冲突（占位实现）
     */
    suspend fun handleConflict(photo: net.kagamir.pickeep.data.local.entity.PhotoEntity) {
        // 简化实现：冲突时创建副本
        val conflictPath = "${photo.remotePath}-conflict-$deviceId"
        
        // 标记为需要重新上传
        photoDao.update(
            photo.copy(
                remotePath = conflictPath,
                syncStatus = SyncStatus.PENDING,
                retryCount = 0
            )
        )
    }
    
    /**
     * 获取或创建设备 ID
     */
    private fun getOrCreateDeviceId(): String {
        val prefs = context.getSharedPreferences("pickeep_prefs", Context.MODE_PRIVATE)
        var deviceId = prefs.getString("device_id", null)
        
        if (deviceId == null) {
            deviceId = UUID.randomUUID().toString()
            prefs.edit().putString("device_id", deviceId).apply()
        }
        
        return deviceId
    }
    
    /**
     * 取消同步
     */
    fun cancelSync() {
        syncState.stopSync()
    }
}

