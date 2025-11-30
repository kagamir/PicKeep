package net.kagamir.pickeep.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import net.kagamir.pickeep.R
import net.kagamir.pickeep.crypto.MasterKeyStore
import net.kagamir.pickeep.data.local.PicKeepDatabase
import net.kagamir.pickeep.storage.webdav.WebDavClient
import net.kagamir.pickeep.sync.SyncEngine
import net.kagamir.pickeep.sync.SyncState

/**
 * 照片同步 Worker
 * 后台执行照片同步任务
 */
class PhotoSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    private val database = PicKeepDatabase.getInstance(context)
    private val syncState = SyncState.getInstance()
    
    override suspend fun doWork(): Result {
        try {
            // 创建通知渠道
            createNotificationChannel()
            
            // 设置前台服务
            setForeground(createForegroundInfo("准备同步..."))
            
            // 检查 Master Key 是否已解锁
            val masterKeyStore = MasterKeyStore.getInstance(applicationContext)
            if (!masterKeyStore.isUnlocked()) {
                // Master Key 未解锁，无法同步
                showNotification("同步失败", "请先解锁应用")
                return Result.failure()
            }
            
            val masterKey = masterKeyStore.getMasterKey()
            
            // 获取 WebDAV 配置（使用 SettingsRepository）
            val settingsRepository = net.kagamir.pickeep.data.repository.SettingsRepository(applicationContext, database)
            val webdavSettings = settingsRepository.webdavSettings.value
            
            if (!webdavSettings.isValid()) {
                showNotification("同步失败", "请先配置 WebDAV 服务器")
                return Result.failure()
            }
            
            val webdavUrl = webdavSettings.url
            val webdavUsername = webdavSettings.username
            val webdavPassword = webdavSettings.password
            
            // 创建存储客户端
            val storageClient = WebDavClient(webdavUrl, webdavUsername, webdavPassword)
            
            // 创建同步引擎
            val syncEngine = SyncEngine(
                applicationContext,
                database,
                storageClient,
                masterKey,
                syncState
            )
            
            // 使用 coroutineScope 确保所有协程在返回前完成
            return coroutineScope {
                // 监听同步状态并更新通知
                val stateJob = launch(Dispatchers.IO) {
                    try {
                        syncState.state.collect { state ->
                            ensureActive() // 检查是否已取消
                            if (state.isSyncing) {
                                val message = if (state.isPaused) {
                                    "同步已暂停"
                                } else if (state.currentFileName != null) {
                                    "正在上传: ${state.currentFileName} (${state.currentProgress}%)"
                                } else {
                                    "同步中... (${state.syncedCount}/${state.pendingCount + state.syncedCount})"
                                }
                                setForeground(createForegroundInfo(message, state.totalProgress))
                            }
                        }
                    } catch (e: Exception) {
                        // 忽略取消异常
                        if (e !is kotlinx.coroutines.CancellationException) {
                            android.util.Log.e("PhotoSyncWorker", "状态监听错误", e)
                        }
                    }
                }
                
                // 执行同步
                syncEngine.syncPhotos()
                
                // 取消状态监听并等待完成（确保所有 setForeground 调用都完成）
                stateJob.cancelAndJoin()
                
                // 获取最终状态
                val finalState = syncState.state.first()
                
                if (finalState.failedCount > 0) {
                    showNotification(
                        "同步完成（部分失败）",
                        "成功: ${finalState.syncedCount}, 失败: ${finalState.failedCount}"
                    )
                    Result.retry()
                } else {
                    showNotification(
                        "同步完成",
                        "成功同步 ${finalState.syncedCount} 张照片"
                    )
                    Result.success()
                }
            }
            
        } catch (e: Exception) {
            showNotification("同步失败", e.message ?: "未知错误")
            return Result.retry()
        }
    }
    
    /**
     * 创建前台信息
     */
    private fun createForegroundInfo(
        message: String,
        progress: Int = 0
    ): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("PicKeep 同步")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setProgress(100, progress, progress == 0)
            .build()
        
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }
    
    /**
     * 显示通知
     */
    private fun showNotification(title: String, message: String) {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
        
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setAutoCancel(true)
            .build()
        
        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }
    
    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "照片同步",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "照片后台同步通知"
            }
            
            val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
                    as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    companion object {
        const val CHANNEL_ID = "photo_sync_channel"
        const val NOTIFICATION_ID = 1001
        const val WORK_NAME = "photo_sync_work"
    }
}

