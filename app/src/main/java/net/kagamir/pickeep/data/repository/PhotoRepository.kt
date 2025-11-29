package net.kagamir.pickeep.data.repository

import kotlinx.coroutines.flow.Flow
import net.kagamir.pickeep.data.local.PicKeepDatabase
import net.kagamir.pickeep.data.local.entity.PhotoEntity
import net.kagamir.pickeep.data.local.entity.SyncStatus

/**
 * 照片仓库
 * 统一的照片数据访问接口
 */
class PhotoRepository(private val database: PicKeepDatabase) {
    
    private val photoDao = database.photoDao()
    
    /**
     * 获取所有照片
     */
    fun getAllPhotos(): Flow<List<PhotoEntity>> = photoDao.getAllPhotos()
    
    /**
     * 根据状态获取照片
     */
    fun getPhotosByStatus(status: SyncStatus): Flow<List<PhotoEntity>> =
        photoDao.getPhotosByStatus(status)
    
    /**
     * 根据 ID 获取照片
     */
    suspend fun getPhotoById(id: Long): PhotoEntity? = photoDao.getById(id)
    
    /**
     * 根据本地路径获取照片
     */
    suspend fun getPhotoByLocalPath(path: String): PhotoEntity? =
        photoDao.getByLocalPath(path)
    
    /**
     * 插入照片
     */
    suspend fun insertPhoto(photo: PhotoEntity): Long = photoDao.insert(photo)
    
    /**
     * 更新照片
     */
    suspend fun updatePhoto(photo: PhotoEntity) = photoDao.update(photo)
    
    /**
     * 删除照片
     */
    suspend fun deletePhoto(photo: PhotoEntity) = photoDao.delete(photo)
    
    /**
     * 标记为已同步
     */
    suspend fun markAsSynced(
        photoId: Long,
        remotePath: String,
        remoteEtag: String?,
        timestamp: Long = System.currentTimeMillis()
    ) {
        photoDao.markAsSynced(photoId, remotePath, remoteEtag, SyncStatus.SYNCED, timestamp)
    }
    
    /**
     * 更新同步状态
     */
    suspend fun updateSyncStatus(photoId: Long, status: SyncStatus) {
        photoDao.updateStatus(photoId, status)
    }
    
    /**
     * 更新同步状态（带错误信息）
     */
    suspend fun updateSyncStatusWithError(
        photoId: Long,
        status: SyncStatus,
        errorMessage: String
    ) {
        photoDao.updateStatusWithError(photoId, status, errorMessage, System.currentTimeMillis())
    }
    
    /**
     * 按状态统计数量
     */
    fun countByStatus(status: SyncStatus): Flow<Int> = photoDao.countByStatus(status)
    
    /**
     * 获取总数
     */
    suspend fun getTotalCount(): Int = photoDao.getTotalCount()
    
    /**
     * 获取待同步照片
     */
    suspend fun getPendingPhotos(limit: Int = 100): List<PhotoEntity> =
        photoDao.getPendingPhotos(SyncStatus.PENDING, limit)
    
    /**
     * 清理旧的失败记录
     */
    suspend fun cleanupOldFailedPhotos(daysOld: Int = 30) {
        val cutoffTime = System.currentTimeMillis() - (daysOld * 24 * 60 * 60 * 1000L)
        photoDao.deleteOldFailedPhotos(SyncStatus.FAILED, cutoffTime)
    }
}

