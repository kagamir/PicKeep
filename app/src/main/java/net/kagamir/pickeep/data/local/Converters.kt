package net.kagamir.pickeep.data.local

import androidx.room.TypeConverter
import net.kagamir.pickeep.data.local.entity.SyncStatus

/**
 * Room 数据库类型转换器
 */
class Converters {
    
    @TypeConverter
    fun fromSyncStatus(status: SyncStatus): String {
        return status.name
    }
    
    @TypeConverter
    fun toSyncStatus(value: String): SyncStatus {
        return SyncStatus.valueOf(value)
    }
}

