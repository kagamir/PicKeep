package net.kagamir.pickeep.sync

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kagamir.pickeep.data.local.PicKeepDatabase
import net.kagamir.pickeep.data.local.entity.SyncStatus
import net.kagamir.pickeep.monitor.PhotoScanner
import net.kagamir.pickeep.storage.StorageClient
import net.kagamir.pickeep.storage.webdav.ChunkedUploader
import net.kagamir.pickeep.sync.UploadStep
import net.kagamir.pickeep.sync.FileUploadState
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
    private val syncState: SyncState,
    private val monitoredExtensions: Set<String>
) {
    
    private val photoDao = database.photoDao()
    private val deviceId: String = getOrCreateDeviceId()
    private val photoScanner = PhotoScanner(context, database, monitoredExtensions)
    
    // 当前同步任务的 Job，用于取消
    @Volatile
    private var currentSyncJob: Job? = null
    
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
        // 如果已经在同步，取消之前的同步任务
        currentSyncJob?.cancel()
        
        if (syncState.state.value.isSyncing) {
            android.util.Log.w("SyncEngine", "同步已在进行中，取消之前的任务")
            return@withContext
        }
        
        try {
            syncState.startSync()
            
            // 保存当前同步任务的 Job
            currentSyncJob = kotlinx.coroutines.currentCoroutineContext()[Job]
            
            // 0. 清理中断的同步任务和临时文件
            // 0.1. 将所有 UPLOADING 状态的文件重置为 PENDING
            // 这确保如果上次同步被中断，这些文件会在本次同步中被重新处理
            photoDao.updateStatusByStatus(SyncStatus.UPLOADING, SyncStatus.PENDING)
            
            // 0.2. 清理缓存目录中的临时加密文件（.enc 文件）
            // 这确保上次中断留下的临时文件被清理，避免占用磁盘空间和导致上传失败
            try {
                val cacheDir = context.cacheDir
                if (cacheDir.exists() && cacheDir.isDirectory) {
                    cacheDir.listFiles()?.forEach { file ->
                        if (file.isFile && file.name.endsWith(".enc")) {
                            file.delete()
                        }
                    }
                }
            } catch (e: Exception) {
                // 清理临时文件失败不应该阻止同步继续
                android.util.Log.w("SyncEngine", "清理临时文件失败", e)
            }
            
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
            
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 同步被取消，清理状态
            android.util.Log.d("SyncEngine", "同步被取消")
            syncState.stopSync()
            // 将所有 UPLOADING 状态的文件重置为 PENDING
            photoDao.updateStatusByStatus(SyncStatus.UPLOADING, SyncStatus.PENDING)
            throw e
        } catch (e: Exception) {
            android.util.Log.e("SyncEngine", "同步失败", e)
            syncState.setError(e.message ?: "同步失败")
        } finally {
            currentSyncJob = null
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
                    val fileId = photo.id.toString()
                    val fileName = File(photo.localPath).name
                    val fileSize = photo.localSize
                    
                    try {
                        // 检查协程是否被取消
                        ensureActive()
                        
                        // 检查文件是否已经在 activeUploads 中（防止重复处理）
                        if (syncState.state.value.activeUploads.containsKey(fileId)) {
                            android.util.Log.w(
                                "SyncEngine",
                                "文件已在处理中，跳过 - 文件ID: ${photo.id}, 路径: ${photo.localPath}"
                            )
                            return@async
                        }
                        
                        // 检查是否暂停
                        while (syncState.state.value.isPaused) {
                            ensureActive() // 检查是否被取消
                            kotlinx.coroutines.delay(500)
                        }
                        
                        // 检查是否取消
                        ensureActive()
                        if (!syncState.state.value.isSyncing) {
                            android.util.Log.d("SyncEngine", "同步已取消，跳过文件: ${photo.localPath}")
                            return@async
                        }
                        
                        android.util.Log.d(
                            "SyncEngine",
                            "开始上传 - 文件ID: ${photo.id}, 路径: ${photo.localPath}, " +
                            "大小: ${photo.localSize} 字节"
                        )
                        
                        // 立即添加文件状态到 activeUploads，确保 uploadingCount 准确
                        // 这也可以作为锁，防止其他任务处理同一文件
                        syncState.updateFileUploadState(
                            fileId,
                            FileUploadState(
                                fileName = fileName,
                                fileSize = fileSize,
                                currentStep = UploadStep.HASHING,
                                progress = 0,
                                processedBytes = 0,
                                totalBytes = fileSize
                            )
                        )
                        
                        // 再次检查，确保文件没有被其他任务处理
                        ensureActive()
                        
                        // 创建步骤回调
                        val onStep: (UploadStep, Long, Long?) -> Unit = { step, processed, total ->
                            // 检查是否被取消
                            try {
                                ensureActive()
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                // 被取消，停止更新状态
                                throw e
                            }
                            
                            val progress = if (total != null && total > 0) {
                                ((processed * 100) / total).toInt().coerceIn(0, 100)
                            } else {
                                -1 // 不确定进度
                            }
                            
                            val fileState = FileUploadState(
                                fileName = fileName,
                                fileSize = fileSize,
                                currentStep = step,
                                progress = progress,
                                processedBytes = processed,
                                totalBytes = total ?: 0
                            )
                            
                            syncState.updateFileUploadState(fileId, fileState)
                        }
                        
                        // 创建上传进度回调（用于兼容旧代码）
                        val onProgress: ((Long, Long) -> Unit)? = { uploaded, total ->
                            // 上传进度已经在 onStep 中处理，这里可以留空或用于其他目的
                        }
                        
                        // 上传
                        val result = uploadTask.uploadWithRetry(photo, onProgress = onProgress, onStep = onStep)
                        
                        if (result.isFailure) {
                            // 记录失败（UploadTask 已经记录了详细错误，这里只记录简要信息）
                            val error = result.exceptionOrNull()
                            android.util.Log.e(
                                "SyncEngine",
                                "上传任务失败 - 文件ID: ${photo.id}, 路径: ${photo.localPath}, " +
                                "错误: ${error?.message ?: "未知错误"}"
                            )
                            // 失败的文件已由 UploadTask 更新为 FAILED 状态
                            // 立即从 activeUploads 中移除，确保状态一致
                            syncState.updateFileUploadState(fileId, null)
                            // 立即更新统计，确保失败数量准确
                            updateSyncCounts()
                        } else {
                            android.util.Log.d(
                                "SyncEngine",
                                "上传成功 - 文件ID: ${photo.id}, 路径: ${photo.localPath}"
                            )
                        }
                        
                    } finally {
                        semaphore.release()
                        // 清除该文件的状态（如果还没有清除）
                        syncState.updateFileUploadState(fileId, null)
                    }
                }
            }
            
            // 等待所有任务完成
            jobs.awaitAll()
            
            // 批量完成后更新一次状态（减少数据库查询频率）
            updateSyncCounts()
        }
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
        android.util.Log.d("SyncEngine", "取消同步")
        // 取消当前同步任务（这会触发 CancellationException，在 syncPhotos 中被捕获）
        currentSyncJob?.cancel("用户取消同步")
        // 停止同步状态
        syncState.stopSync()
    }
}

