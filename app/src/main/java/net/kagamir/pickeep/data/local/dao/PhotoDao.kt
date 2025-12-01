package net.kagamir.pickeep.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import net.kagamir.pickeep.data.local.entity.PhotoEntity
import net.kagamir.pickeep.data.local.entity.SyncStatus

/**
 * 照片数据访问对象
 */
@Dao
interface PhotoDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(photo: PhotoEntity): Long
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(photos: List<PhotoEntity>)
    
    @Update
    suspend fun update(photo: PhotoEntity)
    
    @Delete
    suspend fun delete(photo: PhotoEntity)
    
    @Query("SELECT * FROM photos WHERE id = :id")
    suspend fun getById(id: Long): PhotoEntity?
    
    @Query("SELECT * FROM photos WHERE local_path = :localPath")
    suspend fun getByLocalPath(localPath: String): PhotoEntity?
    
    @Query("SELECT * FROM photos WHERE remote_path = :remotePath")
    suspend fun getByRemotePath(remotePath: String): PhotoEntity?
    
    @Query("SELECT * FROM photos")
    fun getAllPhotos(): Flow<List<PhotoEntity>>
    
    @Query("SELECT * FROM photos WHERE sync_status = :status")
    fun getPhotosByStatus(status: SyncStatus): Flow<List<PhotoEntity>>
    
    @Query("SELECT * FROM photos WHERE sync_status = :status")
    suspend fun getPhotosByStatusSync(status: SyncStatus): List<PhotoEntity>
    
    @Query("SELECT * FROM photos WHERE sync_status IN (:statuses)")
    suspend fun getPhotosByStatusesSync(statuses: List<SyncStatus>): List<PhotoEntity>
    
    @Query("SELECT COUNT(*) FROM photos WHERE sync_status = :status")
    fun countByStatus(status: SyncStatus): Flow<Int>
    
    @Query("UPDATE photos SET sync_status = :status WHERE id = :photoId")
    suspend fun updateStatus(photoId: Long, status: SyncStatus)
    
    /**
     * 原子性地将文件状态从旧状态更新为新状态
     * 只有当文件当前状态匹配 oldStatus 时才会更新
     * @return 更新的行数（0 表示状态不匹配，1 表示更新成功）
     */
    @Query("UPDATE photos SET sync_status = :newStatus WHERE id = :photoId AND sync_status = :oldStatus")
    suspend fun updateStatusIfMatch(photoId: Long, oldStatus: SyncStatus, newStatus: SyncStatus): Int
    
    @Query("UPDATE photos SET sync_status = :newStatus WHERE sync_status = :oldStatus")
    suspend fun updateStatusByStatus(oldStatus: SyncStatus, newStatus: SyncStatus)
    
    @Query("UPDATE photos SET sync_status = :status, error_message = :errorMessage, retry_count = retry_count + 1, last_attempt_at = :timestamp WHERE id = :photoId")
    suspend fun updateStatusWithError(photoId: Long, status: SyncStatus, errorMessage: String, timestamp: Long)
    
    @Query("UPDATE photos SET sync_status = :status, upload_progress = :progress WHERE id = :photoId")
    suspend fun updateStatusAndProgress(photoId: Long, status: SyncStatus, progress: Int)
    
    @Query("UPDATE photos SET remote_path = :remotePath, remote_etag = :remoteEtag, sync_status = :status, last_synced_at = :timestamp WHERE id = :photoId")
    suspend fun markAsSynced(photoId: Long, remotePath: String, remoteEtag: String?, status: SyncStatus, timestamp: Long)
    
    @Query("DELETE FROM photos WHERE sync_status = :status AND last_attempt_at < :olderThan")
    suspend fun deleteOldFailedPhotos(status: SyncStatus, olderThan: Long)
    
    @Query("SELECT * FROM photos WHERE sync_status = :status ORDER BY created_at ASC LIMIT :limit")
    suspend fun getPendingPhotos(status: SyncStatus, limit: Int): List<PhotoEntity>
    
    @Query("SELECT * FROM photos WHERE sync_status = :status ORDER BY last_synced_at DESC LIMIT :limit OFFSET :offset")
    suspend fun getSyncedPhotosPaged(status: SyncStatus, limit: Int, offset: Int): List<PhotoEntity>
    
    @Query("SELECT COUNT(*) FROM photos WHERE sync_status = :status")
    suspend fun getSyncedPhotosCount(status: SyncStatus): Int
    
    @Query("SELECT COUNT(*) FROM photos")
    suspend fun getTotalCount(): Int

    @Query("DELETE FROM photos")
    suspend fun deleteAll()
}

