package net.kagamir.pickeep.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * 权限辅助类
 */
object PermissionHelper {
    
    /**
     * 获取所需权限列表
     */
    fun getRequiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            // Android 12 及以下
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        }
    }
    
    /**
     * 检查所有权限是否已授予
     */
    fun hasAllPermissions(context: Context): Boolean {
        return getRequiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(
                context,
                permission
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * 请求权限
     */
    fun requestPermissions(
        activity: ComponentActivity,
        onResult: (Boolean) -> Unit
    ) {
        val launcher = activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val allGranted = permissions.values.all { it }
            onResult(allGranted)
        }
        
        launcher.launch(getRequiredPermissions())
    }
    
    /**
     * 检查是否有写入权限
     * 注意：Android 10+ (API 29+) 使用 MediaStore API，不需要 WRITE_EXTERNAL_STORAGE
     * 只需要读取权限即可通过 MediaStore API 写入文件
     */
    fun hasWritePermission(context: Context): Boolean {
        // Android 10+ 使用 MediaStore API，只需要读取权限
        return hasAllPermissions(context)
    }
    
    /**
     * 获取写入权限列表
     */
    fun getWritePermissions(): Array<String> {
        // Android 10+ 使用 MediaStore，返回读取权限
        return getRequiredPermissions()
    }
    
    /**
     * 请求写入权限
     */
    fun requestWritePermission(
        activity: ComponentActivity,
        onResult: (Boolean) -> Unit
    ) {
        val launcher = activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val allGranted = permissions.values.all { it }
            onResult(allGranted)
        }
        
        launcher.launch(getWritePermissions())
    }
}

