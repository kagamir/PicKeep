package net.kagamir.pickeep

import android.app.Application
import androidx.work.Configuration
import net.kagamir.pickeep.data.local.PicKeepDatabase

/**
 * PicKeep 应用类
 */
class PicKeepApplication : Application(), Configuration.Provider {
    
    lateinit var database: PicKeepDatabase
        private set
    
    override fun onCreate() {
        super.onCreate()
        
        // 初始化数据库
        database = PicKeepDatabase.getInstance(applicationContext)
    }
    
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}

