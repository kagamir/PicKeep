package net.kagamir.pickeep.storage

/**
 * 远程文件信息
 */
data class RemoteFileInfo(
    /** 文件路径 */
    val path: String,
    
    /** 文件大小 (bytes) */
    val size: Long,
    
    /** ETag（版本标识） */
    val etag: String?,
    
    /** 最后修改时间 (ms) */
    val lastModified: Long,
    
    /** 是否为目录 */
    val isDirectory: Boolean = false
)

