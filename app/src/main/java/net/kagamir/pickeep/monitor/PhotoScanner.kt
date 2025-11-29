package net.kagamir.pickeep.monitor

import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.kagamir.pickeep.data.local.PicKeepDatabase
import net.kagamir.pickeep.data.local.entity.PhotoEntity
import net.kagamir.pickeep.data.local.entity.SyncStatus
import java.io.File

/**
 * 照片扫描器
 * 扫描 MediaStore 并检测增量变化
 */
class PhotoScanner(
    private val context: Context,
    private val database: PicKeepDatabase
) {
    
    private val photoDao = database.photoDao()
    
    /**
     * 扫描所有照片并检测变化
     * 
     * @return 新增的照片数量
     */
    suspend fun scanForChanges(): Int = withContext(Dispatchers.IO) {
        val mediaPhotos = queryMediaStore()
        val existingPhotos = photoDao.getAllPhotos().hashCode()  // 简化，实际应该用 Map
        
        var newCount = 0
        
        mediaPhotos.forEach { mediaPhoto ->
            val existingPhoto = photoDao.getByLocalPath(mediaPhoto.localPath)
            
            when {
                existingPhoto == null -> {
                    // 新照片
                    photoDao.insert(mediaPhoto)
                    newCount++
                }
                hasChanged(existingPhoto, mediaPhoto) -> {
                    // 照片已修改
                    val updated = existingPhoto.copy(
                        localMtime = mediaPhoto.localMtime,
                        localSize = mediaPhoto.localSize,
                        syncStatus = SyncStatus.PENDING,
                        retryCount = 0
                    )
                    photoDao.update(updated)
                    newCount++
                }
            }
        }
        
        newCount
    }
    
    /**
     * 从 MediaStore 查询所有照片和视频
     */
    private suspend fun queryMediaStore(): List<PhotoEntity> = withContext(Dispatchers.IO) {
        val photos = mutableListOf<PhotoEntity>()
        
        // 查询图片
        photos.addAll(queryMediaType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI))
        
        // 查询视频
        photos.addAll(queryMediaType(MediaStore.Video.Media.EXTERNAL_CONTENT_URI))
        
        photos
    }
    
    /**
     * 查询指定类型的媒体
     */
    private fun queryMediaType(uri: android.net.Uri): List<PhotoEntity> {
        val photos = mutableListOf<PhotoEntity>()
        
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE
        )
        
        val selection = "${MediaStore.MediaColumns.SIZE} > 0"
        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        
        context.contentResolver.query(
            uri,
            projection,
            selection,
            null,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            
            while (cursor.moveToNext()) {
                val path = cursor.getString(dataColumn)
                val mtime = cursor.getLong(modifiedColumn) * 1000  // 转换为毫秒
                val size = cursor.getLong(sizeColumn)
                
                // 验证文件是否存在
                val file = File(path)
                if (file.exists() && file.isFile) {
                    photos.add(
                        PhotoEntity(
                            localPath = path,
                            localMtime = mtime,
                            localSize = size,
                            syncStatus = SyncStatus.PENDING
                        )
                    )
                }
            }
        }
        
        return photos
    }
    
    /**
     * 检查照片是否已变化
     */
    private fun hasChanged(existing: PhotoEntity, new: PhotoEntity): Boolean {
        return existing.localMtime != new.localMtime ||
                existing.localSize != new.localSize
    }
    
    /**
     * 扫描指定目录
     */
    suspend fun scanDirectory(directoryPath: String): Int = withContext(Dispatchers.IO) {
        val dir = File(directoryPath)
        if (!dir.exists() || !dir.isDirectory) {
            return@withContext 0
        }
        
        var newCount = 0
        
        dir.walkTopDown().forEach { file ->
            if (file.isFile && isMediaFile(file.name)) {
                val existingPhoto = photoDao.getByLocalPath(file.absolutePath)
                
                if (existingPhoto == null) {
                    val photo = PhotoEntity(
                        localPath = file.absolutePath,
                        localMtime = file.lastModified(),
                        localSize = file.length(),
                        syncStatus = SyncStatus.PENDING
                    )
                    photoDao.insert(photo)
                    newCount++
                } else if (existingPhoto.localMtime < file.lastModified()) {
                    val updated = existingPhoto.copy(
                        localMtime = file.lastModified(),
                        localSize = file.length(),
                        syncStatus = SyncStatus.PENDING,
                        retryCount = 0
                    )
                    photoDao.update(updated)
                    newCount++
                }
            }
        }
        
        newCount
    }
    
    /**
     * 判断是否为媒体文件
     */
    private fun isMediaFile(filename: String): Boolean {
        val extension = filename.substringAfterLast('.', "").lowercase()
        return extension in setOf(
            "jpg", "jpeg", "png", "gif", "webp", "heic", "heif",
            "mp4", "mkv", "avi", "mov", "3gp"
        )
    }
}

