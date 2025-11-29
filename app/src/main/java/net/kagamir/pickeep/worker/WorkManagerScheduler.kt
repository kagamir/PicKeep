package net.kagamir.pickeep.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * WorkManager 调度器
 * 封装后台任务调度逻辑
 */
class WorkManagerScheduler(private val context: Context) {
    
    private val workManager = WorkManager.getInstance(context)
    
    /**
     * 立即同步
     */
    fun scheduleSyncNow() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        
        val workRequest = OneTimeWorkRequestBuilder<PhotoSyncWorker>()
            .setConstraints(constraints)
            .build()
        
        workManager.enqueueUniqueWork(
            PhotoSyncWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }
    
    /**
     * 调度定期同步
     * 
     * @param intervalHours 同步间隔（小时）
     * @param requireCharging 是否需要充电
     * @param requireBatteryNotLow 是否需要电池非低电量
     */
    fun schedulePeriodicSync(
        intervalHours: Long = 12,
        requireCharging: Boolean = false,
        requireBatteryNotLow: Boolean = true
    ) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresCharging(requireCharging)
            .setRequiresBatteryNotLow(requireBatteryNotLow)
            .build()
        
        val workRequest = PeriodicWorkRequestBuilder<PhotoSyncWorker>(
            intervalHours,
            TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()
        
        workManager.enqueueUniquePeriodicWork(
            "${PhotoSyncWorker.WORK_NAME}_periodic",
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
    }
    
    /**
     * 取消同步
     */
    fun cancelSync() {
        workManager.cancelUniqueWork(PhotoSyncWorker.WORK_NAME)
    }
    
    /**
     * 取消定期同步
     */
    fun cancelPeriodicSync() {
        workManager.cancelUniqueWork("${PhotoSyncWorker.WORK_NAME}_periodic")
    }
    
    /**
     * 取消所有同步任务
     */
    fun cancelAllSync() {
        cancelSync()
        cancelPeriodicSync()
    }
    
    /**
     * 获取同步状态
     */
    fun getSyncStatus() = workManager.getWorkInfosForUniqueWorkLiveData(PhotoSyncWorker.WORK_NAME)
}

