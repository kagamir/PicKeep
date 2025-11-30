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
    private val deviceId: String
) {
    
    private val photoDao = database.photoDao()
    private val cekDao = database.cekDao()
    
    /**
     * 上传照片
     * 
     * @param photo 照片实体
     * @param onProgress 进度回调 (已上传字节, 总字节)
     * @return 上传结果
     */
    suspend fun upload(
        photo: PhotoEntity,
        onProgress: ((Long, Long) -> Unit)? = null
    ): Result<Unit> {
        return try {
            // 更新状态为上传中
            photoDao.updateStatus(photo.id, SyncStatus.UPLOADING)
            
            // 1. 生成或获取 CEK
            val cekId = photo.cekId ?: UUID.randomUUID().toString()
            val cek = getCekOrGenerate(cekId)
            
            // 2. 加密文件
            val sourceFile = File(photo.localPath)
            if (!sourceFile.exists()) {
                throw Exception("源文件不存在: ${photo.localPath}")
            }
            
            val encryptedFile = File(context.cacheDir, "${UUID.randomUUID()}.enc")
            val sha256 = try {
                FileInputStream(sourceFile).use { input ->
                    FileOutputStream(encryptedFile).use { output ->
                        FileEncryptor.encryptFile(input, output, cek)
                    }
                }
            } catch (e: Exception) {
                encryptedFile.delete()
                throw e
            }
            
            // 3. 生成远程路径（基于文件内容的 HMAC-SHA256）
            val filenameSalt = KeyDerivation.deriveFilenameSalt(masterKey)
            val fileHash = KeyDerivation.calculateFileHash(sourceFile, filenameSalt)
            val remotePath = "$fileHash.enc"
            val remoteMetaPath = "$fileHash.meta"
            
            // 4. 上传加密文件
            val uploadResult = FileInputStream(encryptedFile).use { input ->
                storageClient.uploadFile(
                    input,
                    remotePath,
                    encryptedFile.length(),
                    onProgress
                )
            }
            
            // 清理临时文件
            encryptedFile.delete()
            
            if (uploadResult.isFailure) {
                throw uploadResult.exceptionOrNull() ?: Exception("上传失败")
            }
            
            val etag = uploadResult.getOrNull()
            
            // 5. 生成并上传元数据
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
            photoDao.updateStatusWithError(
                photo.id,
                SyncStatus.FAILED,
                e.message ?: "未知错误",
                System.currentTimeMillis()
            )
            Result.failure(e)
        }
    }
    
    /**
     * 带重试的上传
     */
    suspend fun uploadWithRetry(
        photo: PhotoEntity,
        maxRetries: Int = 5,
        onProgress: ((Long, Long) -> Unit)? = null
    ): Result<Unit> {
        var lastException: Exception? = null
        
        repeat(maxRetries) { attempt ->
            val result = upload(photo, onProgress)
            
            if (result.isSuccess) {
                return Result.success(Unit)
            }
            
            lastException = result.exceptionOrNull() as? Exception
            
            if (attempt < maxRetries - 1) {
                // 指数退避：1s, 2s, 4s, 8s, 16s
                val delayMs = 1000L * (1 shl attempt)
                delay(delayMs)
            }
        }
        
        return Result.failure(lastException ?: Exception("上传失败"))
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
