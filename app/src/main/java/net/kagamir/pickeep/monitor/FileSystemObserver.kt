package net.kagamir.pickeep.monitor

import android.os.FileObserver
import java.io.File

/**
 * 文件系统观察者
 * 监听 DCIM 和 Pictures 目录的文件变化
 */
class FileSystemObserver(
    private val paths: List<String>,
    private val onFileChanged: () -> Unit
) {
    
    private val observers = mutableListOf<FileObserver>()
    
    /**
     * 开始观察
     */
    fun startObserving() {
        paths.forEach { path ->
            val dir = File(path)
            if (dir.exists() && dir.isDirectory) {
                val observer = object : FileObserver(
                    dir,
                    CREATE or MODIFY or DELETE or MOVED_FROM or MOVED_TO
                ) {
                    override fun onEvent(event: Int, path: String?) {
                        // 只关注图片和视频文件
                        if (path != null && isMediaFile(path)) {
                            onFileChanged()
                        }
                    }
                }
                observer.startWatching()
                observers.add(observer)
            }
        }
    }
    
    /**
     * 停止观察
     */
    fun stopObserving() {
        observers.forEach { it.stopWatching() }
        observers.clear()
    }
    
    /**
     * 判断是否为媒体文件
     */
    private fun isMediaFile(filename: String): Boolean {
        val extension = filename.substringAfterLast('.', "").lowercase()
        return extension in setOf(
            "jpg", "jpeg", "png", "gif", "webp", "heic", "heif",
            "mp4", "mkv", "avi", "mov", "3gp"
        )
    }
    
    companion object {
        /**
         * 获取默认监控路径
         */
        fun getDefaultPaths(): List<String> {
            val paths = mutableListOf<String>()
            
            // DCIM 目录
            val dcim = File(android.os.Environment.getExternalStorageDirectory(), "DCIM")
            if (dcim.exists()) {
                paths.add(dcim.absolutePath)
                // 添加 DCIM 子目录（如 Camera）
                dcim.listFiles()?.forEach { subDir ->
                    if (subDir.isDirectory) {
                        paths.add(subDir.absolutePath)
                    }
                }
            }
            
            // Pictures 目录
            val pictures = File(android.os.Environment.getExternalStorageDirectory(), "Pictures")
            if (pictures.exists()) {
                paths.add(pictures.absolutePath)
            }
            
            return paths
        }
    }
}

