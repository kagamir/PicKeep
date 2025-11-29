package net.kagamir.pickeep.monitor

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * MediaStore 观察者
 * 监听照片和视频的变化
 */
class MediaStoreObserver(
    private val context: Context,
    private val onMediaChanged: () -> Unit
) {
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var imageObserver: ContentObserver? = null
    private var videoObserver: ContentObserver? = null
    
    /**
     * 开始观察
     */
    fun startObserving() {
        val handler = Handler(Looper.getMainLooper())
        
        // 观察图片
        imageObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                scope.launch {
                    onMediaChanged()
                }
            }
            
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                scope.launch {
                    onMediaChanged()
                }
            }
        }
        
        // 观察视频
        videoObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                scope.launch {
                    onMediaChanged()
                }
            }
            
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                scope.launch {
                    onMediaChanged()
                }
            }
        }
        
        context.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            imageObserver!!
        )
        
        context.contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            true,
            videoObserver!!
        )
    }
    
    /**
     * 停止观察
     */
    fun stopObserving() {
        imageObserver?.let {
            context.contentResolver.unregisterContentObserver(it)
        }
        videoObserver?.let {
            context.contentResolver.unregisterContentObserver(it)
        }
        imageObserver = null
        videoObserver = null
    }
}

