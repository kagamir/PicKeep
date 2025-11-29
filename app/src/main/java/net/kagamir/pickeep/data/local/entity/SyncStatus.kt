package net.kagamir.pickeep.data.local.entity

/**
 * 同步状态枚举
 */
enum class SyncStatus {
    /** 等待上传 */
    PENDING,
    
    /** 正在上传 */
    UPLOADING,
    
    /** 已同步 */
    SYNCED,
    
    /** 上传失败 */
    FAILED,
    
    /** 冲突状态 */
    CONFLICT
}

