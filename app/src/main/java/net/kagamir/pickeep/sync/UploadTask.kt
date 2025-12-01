package net.kagamir.pickeep.sync

import android.content.Context
import kotlinx.coroutines.delay
import androidx.exifinterface.media.ExifInterface
import net.kagamir.pickeep.crypto.CekManager
import net.kagamir.pickeep.crypto.FileEncryptor
import net.kagamir.pickeep.crypto.KeyDerivation
import net.kagamir.pickeep.crypto.MetadataEncryptor
import net.kagamir.pickeep.crypto.PhotoMetadata
import net.kagamir.pickeep.data.local.PicKeepDatabase
import net.kagamir.pickeep.data.local.entity.ContentEncryptionKeyEntity
import net.kagamir.pickeep.data.local.entity.PhotoEntity
import net.kagamir.pickeep.data.local.entity.SyncStatus
import net.kagamir.pickeep.storage.StorageClient
import net.kagamir.pickeep.storage.webdav.ChunkedUploader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

/**
 * 上传任务
 * 封装单个照片的上传流程
 */
class UploadTask(
    private val context: Context,
    private val database: PicKeepDatabase,
    private val storageClient: StorageClient,
    private val masterKey: ByteArray,
    private val deviceId: String,
    private val chunkedUploader: ChunkedUploader? = null
) {
    
    private val photoDao = database.photoDao()
    private val cekDao = database.cekDao()
    
    companion object {
        // 文件大小阈值：超过此大小的文件使用分片上传（10MB）
        private const val CHUNK_UPLOAD_SIZE_THRESHOLD = 10 * 1024 * 1024L
    }
    
    /**
     * 上传照片
     * 
     * @param photo 照片实体
     * @param onProgress 进度回调 (已上传字节, 总字节)
     * @param onStep 步骤回调 (步骤, 已处理字节, 总字节，总字节为null表示不确定)
     * @return 上传结果
     */
    suspend fun upload(
        photo: PhotoEntity,
        onProgress: ((Long, Long) -> Unit)? = null,
        onStep: ((UploadStep, Long, Long?) -> Unit)? = null
    ): Result<Unit> {
        var encryptedFile: File? = null
        return try {
            // 原子性地更新状态为上传中（只有当文件状态是 PENDING 或 FAILED 时才更新）
            // 这防止多个任务同时处理同一个文件
            val updated = photoDao.updateStatusIfMatch(
                photo.id,
                SyncStatus.PENDING,
                SyncStatus.UPLOADING
            )
            if (updated == 0) {
                // 如果 PENDING 状态更新失败，尝试 FAILED 状态
                val updatedFromFailed = photoDao.updateStatusIfMatch(
                    photo.id,
                    SyncStatus.FAILED,
                    SyncStatus.UPLOADING
                )
                if (updatedFromFailed == 0) {
                    // 文件状态已经不是 PENDING 或 FAILED，可能已被其他任务处理
                    val currentPhoto = photoDao.getById(photo.id)
                    val currentStatus = currentPhoto?.syncStatus ?: "未知"
                    android.util.Log.w(
                        "UploadTask",
                        "文件状态已变更，跳过上传 - 文件ID: ${photo.id}, " +
                        "路径: ${photo.localPath}, 当前状态: $currentStatus"
                    )
                    return Result.failure(Exception("文件状态已变更（当前: $currentStatus），跳过上传"))
                }
            }
            
            val sourceFile = File(photo.localPath)
            if (!sourceFile.exists()) {
                throw Exception("源文件不存在: ${photo.localPath}")
            }
            
            val fileSize = sourceFile.length()
            
            // 1. 生成或获取 CEK
            val cekId = photo.cekId ?: UUID.randomUUID().toString()
            val cek = getCekOrGenerate(cekId)
            
            // 2. 哈希计算和加密
            onStep?.invoke(UploadStep.HASHING, 0, null)
            encryptedFile = File(context.cacheDir, "${UUID.randomUUID()}.enc")
            val sha256 = FileOutputStream(encryptedFile).use { output ->
                // 使用文件路径版本，支持进度回调
                FileEncryptor.encryptFile(
                    photo.localPath,
                    output,
                    cek,
                    onHashProgress = { processed, total ->
                        onStep?.invoke(UploadStep.HASHING, processed, total)
                    },
                    onEncryptProgress = { processed, total ->
                        onStep?.invoke(UploadStep.ENCRYPTING, processed, total)
                    }
                )
            }
            
            // 3. 生成远程路径（基于文件内容的 HMAC-SHA256）
            onStep?.invoke(UploadStep.GENERATING_PATH, 0, null)
            val filenameSalt = KeyDerivation.deriveFilenameSalt(masterKey)
            val fileHash = KeyDerivation.calculateFileHash(sourceFile, filenameSalt)
            val remotePath = "$fileHash.enc"
            val remoteMetaPath = "$fileHash.meta"
            
            // 4. 上传加密文件（根据文件类型和大小决定是否使用分片上传）
            onStep?.invoke(UploadStep.UPLOADING_FILE, 0, null)
            val encryptedFileSize = encryptedFile.length()
            val mimeType = getMimeType(photo.localPath) ?: "application/octet-stream"
            val isVideo = mimeType.startsWith("video/")
            val shouldUseChunked = isVideo || encryptedFileSize > CHUNK_UPLOAD_SIZE_THRESHOLD
            
            // 包装进度回调，同时更新步骤和上传进度
            val wrappedProgress: ((Long, Long) -> Unit)? = if (onProgress != null || onStep != null) {
                { uploaded, total ->
                    onProgress?.invoke(uploaded, total)
                    onStep?.invoke(UploadStep.UPLOADING_FILE, uploaded, total)
                }
            } else {
                null
            }
            
            val uploadResult = if (shouldUseChunked && chunkedUploader != null) {
                // 使用分片上传（视频或大文件）
                chunkedUploader.uploadFile(encryptedFile, remotePath, wrappedProgress)
            } else {
                // 直接上传（小文件）
                FileInputStream(encryptedFile).use { input ->
                    storageClient.uploadFile(
                        input,
                        remotePath,
                        encryptedFileSize,
                        wrappedProgress
                    )
                }
            }
            
            if (uploadResult.isFailure) {
                throw uploadResult.exceptionOrNull() ?: Exception("上传失败")
            }
            
            val etag = uploadResult.getOrNull()
            
            // 5. 生成并上传元数据
            onStep?.invoke(UploadStep.UPLOADING_METADATA, 0, null)
            val metadata = createMetadata(photo, sha256, cekId)
            val encryptedMetadata = MetadataEncryptor.encryptMetadata(metadata, cek)
            
            val metaResult = storageClient.uploadMetadata(remoteMetaPath, encryptedMetadata)
            if (metaResult.isFailure) {
                // 元数据上传失败
                throw metaResult.exceptionOrNull() ?: Exception("元数据上传失败")
            }
            
            // 6. 更新数据库
            photoDao.markAsSynced(
                photo.id,
                remotePath,
                etag,
                SyncStatus.SYNCED,
                System.currentTimeMillis()
            )
            
            // 保存 CEK ID
            if (photo.cekId == null) {
                val updatedPhoto = photoDao.getById(photo.id)?.copy(cekId = cekId)
                if (updatedPhoto != null) {
                    photoDao.update(updatedPhoto)
                }
            }
            
            Result.success(Unit)
            
        } catch (e: Exception) {
            // 上传失败，更新状态
            val errorMessage = e.message ?: "未知错误"
            val errorDetails = buildString {
                append("上传失败 - 文件ID: ${photo.id}, ")
                append("路径: ${photo.localPath}, ")
                append("大小: ${photo.localSize} 字节, ")
                append("错误: $errorMessage, ")
                append("异常类型: ${e.javaClass.simpleName}")
            }
            
            android.util.Log.e("UploadTask", errorDetails, e)
            
            photoDao.updateStatusWithError(
                photo.id,
                SyncStatus.FAILED,
                errorMessage,
                System.currentTimeMillis()
            )
            Result.failure(e)
        } finally {
            // 确保临时文件总是被删除，无论成功、失败还是取消
            encryptedFile?.delete()
        }
    }
    
    /**
     * 带重试的上传
     * 对于某些错误（如 HTTP 4xx 客户端错误），立即失败不重试
     */
    suspend fun uploadWithRetry(
        photo: PhotoEntity,
        maxRetries: Int = 5,
        onProgress: ((Long, Long) -> Unit)? = null,
        onStep: ((UploadStep, Long, Long?) -> Unit)? = null
    ): Result<Unit> {
        var lastException: Exception? = null
        
        repeat(maxRetries) { attempt ->
            val result = upload(photo, onProgress, onStep)
            
            if (result.isSuccess) {
                return Result.success(Unit)
            }
            
            lastException = result.exceptionOrNull() as? Exception
            
            // 检查是否为不应重试的错误
            if (shouldNotRetry(lastException)) {
                android.util.Log.w(
                    "UploadTask",
                    "遇到不应重试的错误，立即失败 - 文件ID: ${photo.id}, " +
                    "路径: ${photo.localPath}, 错误: ${lastException?.message}"
                )
                // upload 方法已经将状态更新为 FAILED，直接返回失败
                return Result.failure(lastException ?: Exception("上传失败"))
            }
            
            if (attempt < maxRetries - 1) {
                // 指数退避：1s, 2s, 4s, 8s, 16s
                val delayMs = 1000L * (1 shl attempt)
                android.util.Log.d(
                    "UploadTask",
                    "上传失败，${delayMs}ms 后重试 (${attempt + 1}/$maxRetries) - 文件ID: ${photo.id}"
                )
                delay(delayMs)
            }
        }
        
        return Result.failure(lastException ?: Exception("上传失败"))
    }
    
    /**
     * 判断错误是否不应重试
     * HTTP 4xx 错误（客户端错误）通常不应重试，因为重试可能没有意义
     */
    private fun shouldNotRetry(exception: Throwable?): Boolean {
        if (exception == null) return false
        
        val message = exception.message ?: ""
        
        // HTTP 4xx 错误（客户端错误）不应重试
        // 423 Locked - 资源被锁定
        // 400 Bad Request - 请求错误
        // 401 Unauthorized - 未授权
        // 403 Forbidden - 禁止访问
        // 404 Not Found - 资源不存在
        // 409 Conflict - 冲突
        val http4xxPattern = Regex("上传失败: (40[0-9]|41[0-9]|42[0-9]|43[0-9]|44[0-9])")
        if (http4xxPattern.containsMatchIn(message)) {
            return true
        }
        
        // 其他明确的客户端错误
        if (message.contains("源文件不存在") || 
            message.contains("文件状态已变更") ||
            message.contains("Bad Request") ||
            message.contains("Unauthorized") ||
            message.contains("Forbidden") ||
            message.contains("Not Found") ||
            message.contains("Conflict") ||
            message.contains("Locked")) {
            return true
        }
        
        return false
    }
    
    /**
     * 获取或生成 CEK
     */
    private suspend fun getCekOrGenerate(cekId: String): ByteArray {
        val existing = cekDao.getById(cekId)
        
        return if (existing != null) {
            // 解密已存在的 CEK
            CekManager.decryptCek(existing.encryptedKey, masterKey)
        } else {
            // 生成新的 CEK
            val cek = CekManager.generateCek()
            val encryptedCek = CekManager.encryptCek(cek, masterKey)
            
            // 保存到数据库
            val cekEntity = ContentEncryptionKeyEntity(
                id = cekId,
                encryptedKey = encryptedCek,
                deviceId = deviceId
            )
            cekDao.insert(cekEntity)
            
            cek
        }
    }
    
    /**
     * 创建照片元数据
     */
    private fun createMetadata(
        photo: PhotoEntity,
        sha256: String,
        cekId: String
    ): PhotoMetadata {
        val file = File(photo.localPath)
        val mimeType = getMimeType(photo.localPath) ?: "application/octet-stream"
        val location = getLocation(photo.localPath)
        
        return PhotoMetadata(
            originalName = file.name,
            timestamp = photo.localMtime,
            location = location,
            mimeType = mimeType,
            size = photo.localSize,
            sha256 = sha256,
            deviceId = deviceId,
            cekId = cekId
        )
    }
    
    /**
     * 获取 MIME 类型
     */
    private fun getMimeType(path: String): String? {
        val uri = android.net.Uri.fromFile(File(path))
        return context.contentResolver.getType(uri)
    }
    
    /**
     * 获取地理位置（如果有）
     */
    private fun getLocation(path: String): PhotoMetadata.Location? {
        return try {
            val exif = ExifInterface(path)
            val latLong = FloatArray(2)
            if (exif.getLatLong(latLong)) {
                val lat = latLong[0].toDouble()
                val lng = latLong[1].toDouble()
                if (lat != 0.0 || lng != 0.0) {
                    PhotoMetadata.Location(lat, lng)
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
