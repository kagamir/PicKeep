package net.kagamir.pickeep.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 照片实体，记录照片的本地信息和同步状态
 */
@Entity(tableName = "photos")
data class PhotoEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    /** 本地路径 */
    @ColumnInfo(name = "local_path")
    val localPath: String,
    
    /** 本地文件修改时间 (ms) */
    @ColumnInfo(name = "local_mtime")
    val localMtime: Long,
    
    /** 本地文件大小 (bytes) */
    @ColumnInfo(name = "local_size")
    val localSize: Long,
    
    /** 本地文件 SHA-256 哈希 (仅客户端内部使用，不上传) */
    @ColumnInfo(name = "local_blob_hash")
    val localBlobHash: String? = null,
    
    /** 远程路径（UUID 文件名） */
    @ColumnInfo(name = "remote_path")
    val remotePath: String? = null,
    
    /** 远程 ETag（版本标识） */
    @ColumnInfo(name = "remote_etag")
    val remoteEtag: String? = null,
    
    /** 同步状态 */
    @ColumnInfo(name = "sync_status")
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    
    /** 内容加密密钥 ID */
    @ColumnInfo(name = "cek_id")
    val cekId: String? = null,
    
    /** 上次尝试上传时间 (ms) */
    @ColumnInfo(name = "last_attempt_at")
    val lastAttemptAt: Long? = null,
    
    /** 上传进度 (0-100) */
    @ColumnInfo(name = "upload_progress")
    val uploadProgress: Int = 0,
    
    /** 重试次数 */
    @ColumnInfo(name = "retry_count")
    val retryCount: Int = 0,
    
    /** 错误信息 */
    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,
    
    /** 创建时间 (ms) */
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    
    /** 最后同步时间 (ms) */
    @ColumnInfo(name = "last_synced_at")
    val lastSyncedAt: Long? = null
)

