package net.kagamir.pickeep.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import net.kagamir.pickeep.data.local.dao.CekDao
import net.kagamir.pickeep.data.local.dao.PhotoDao
import net.kagamir.pickeep.data.local.entity.ContentEncryptionKeyEntity
import net.kagamir.pickeep.data.local.entity.PhotoEntity

/**
 * PicKeep 数据库
 */
@Database(
    entities = [
        PhotoEntity::class,
        ContentEncryptionKeyEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class PicKeepDatabase : RoomDatabase() {
    
    abstract fun photoDao(): PhotoDao
    abstract fun cekDao(): CekDao
    
    companion object {
        private const val DATABASE_NAME = "pickeep.db"
        
        @Volatile
        private var INSTANCE: PicKeepDatabase? = null
        
        fun getInstance(context: Context): PicKeepDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    PicKeepDatabase::class.java,
                    DATABASE_NAME
                )
                    .fallbackToDestructiveMigration() // 开发阶段可用，生产环境需要实现迁移策略
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

