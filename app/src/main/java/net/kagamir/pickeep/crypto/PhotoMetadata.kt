package net.kagamir.pickeep.crypto

import kotlinx.serialization.Serializable

/**
 * 照片元数据（加密后上传）
 */
@Serializable
data class PhotoMetadata(
    /** 版本号 */
    val version: Int = 1,
    
    /** 原始文件名 */
    val originalName: String,
    
    /** 时间戳 (ms) */
    val timestamp: Long,
    
    /** 地理位置（可选） */
    val location: Location? = null,
    
    /** MIME 类型 */
    val mimeType: String,
    
    /** 文件大小 (bytes) */
    val size: Long,
    
    /** SHA-256 哈希 */
    val sha256: String,
    
    /** 设备 ID */
    val deviceId: String,
    
    /** CEK ID */
    val cekId: String,
    
    /** 创建时间 */
    val createdAt: Long = System.currentTimeMillis()
) {
    @Serializable
    data class Location(
        val latitude: Double,
        val longitude: Double,
        val altitude: Double? = null
    )
}

