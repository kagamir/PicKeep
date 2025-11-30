package net.kagamir.pickeep

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.work.Configuration
import net.kagamir.pickeep.crypto.MasterKeyStore
import net.kagamir.pickeep.data.local.PicKeepDatabase
import org.opencv.android.OpenCVLoader

/**
 * PicKeep 应用类
 */
class PicKeepApplication : Application(), Configuration.Provider, Application.ActivityLifecycleCallbacks {
    
    lateinit var database: PicKeepDatabase
        private set
        
    private var activityReferences = 0
    private var isActivityChangingConfigurations = false
    private var lastBackgroundTime: Long = 0
    
    override fun onCreate() {
        super.onCreate()
        
        // 初始化数据库
        database = PicKeepDatabase.getInstance(applicationContext)
        
        // 初始化OpenCV（用于视频指纹计算）
        try {
            if (!OpenCVLoader.initLocal()) {
                android.util.Log.e("PicKeep", "OpenCV初始化失败")
            }
        } catch (e: Exception) {
            android.util.Log.e("PicKeep", "OpenCV初始化异常", e)
        }
        
        // 注册生命周期回调
        registerActivityLifecycleCallbacks(this)
    }
    
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

    override fun onActivityStarted(activity: Activity) {
        if (++activityReferences == 1 && !isActivityChangingConfigurations) {
            // App 进入前台
            if (lastBackgroundTime > 0) {
                val duration = System.currentTimeMillis() - lastBackgroundTime
                if (duration > 5 * 60 * 1000) { // 5 分钟
                    MasterKeyStore.getInstance(this).lock()
                }
            }
        }
    }

    override fun onActivityResumed(activity: Activity) {}

    override fun onActivityPaused(activity: Activity) {}

    override fun onActivityStopped(activity: Activity) {
        isActivityChangingConfigurations = activity.isChangingConfigurations
        if (--activityReferences == 0 && !isActivityChangingConfigurations) {
            // App 进入后台
            lastBackgroundTime = System.currentTimeMillis()
        }
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    override fun onActivityDestroyed(activity: Activity) {}
}
