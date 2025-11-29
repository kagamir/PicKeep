package net.kagamir.pickeep.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 内容加密密钥实体
 * CEK (Content Encryption Key) 用 Master Key 加密后存储
 */
@Entity(tableName = "content_encryption_keys")
data class ContentEncryptionKeyEntity(
    @PrimaryKey
    val id: String,  // UUID
    
    /** 加密后的 CEK（用 Master Key 加密） */
    @ColumnInfo(name = "encrypted_key", typeAffinity = ColumnInfo.BLOB)
    val encryptedKey: ByteArray,
    
    /** 设备 ID（随机 UUID，用于区分多设备） */
    @ColumnInfo(name = "device_id")
    val deviceId: String,
    
    /** 创建时间 (ms) */
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ContentEncryptionKeyEntity

        if (id != other.id) return false
        if (!encryptedKey.contentEquals(other.encryptedKey)) return false
        if (deviceId != other.deviceId) return false
        if (createdAt != other.createdAt) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + encryptedKey.contentHashCode()
        result = 31 * result + deviceId.hashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }
}

