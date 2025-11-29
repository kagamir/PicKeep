package net.kagamir.pickeep

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import net.kagamir.pickeep.data.local.PicKeepDatabase
import net.kagamir.pickeep.data.repository.SettingsRepository
import net.kagamir.pickeep.ui.navigation.AppNavGraph
import net.kagamir.pickeep.ui.navigation.Routes
import net.kagamir.pickeep.ui.theme.PicKeepTheme
import net.kagamir.pickeep.util.PermissionHelper
import net.kagamir.pickeep.worker.WorkManagerScheduler

class MainActivity : ComponentActivity() {

    private lateinit var database: PicKeepDatabase
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var workManagerScheduler: WorkManagerScheduler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 初始化
        database = PicKeepDatabase.getInstance(applicationContext)
        settingsRepository = SettingsRepository(applicationContext)
        workManagerScheduler = WorkManagerScheduler(applicationContext)
        
        setContent {
            PicKeepTheme {
                MainContent()
            }
        }
    }
    
    @Composable
    private fun MainContent() {
        var hasPermissions by remember { mutableStateOf(PermissionHelper.hasAllPermissions(this)) }
        
        // 权限请求启动器
        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            hasPermissions = permissions.values.all { it }
        }
        
        // 检查并请求权限
        LaunchedEffect(Unit) {
            if (!hasPermissions) {
                permissionLauncher.launch(PermissionHelper.getRequiredPermissions())
            }
        }
        
        if (hasPermissions) {
            // 权限已授予，显示主界面
            val navController = rememberNavController()
            
            // 确定起始路由
            val startDestination = when {
                !settingsRepository.isInitialized() -> {
                    // 未初始化，进入设置界面
                    Routes.SETUP
                }
                !settingsRepository.isUnlocked() -> {
                    // 已初始化但未解锁，进入解锁界面
                    Routes.UNLOCK
                }
                else -> {
                    // 已解锁，进入主界面
                    Routes.STATUS
                }
            }
            
            AppNavGraph(
                navController = navController,
                startDestination = startDestination,
                database = database,
                settingsRepository = settingsRepository,
                workManagerScheduler = workManagerScheduler
            )
        } else {
            // 权限未授予，显示权限请求界面
            PermissionRequestScreen(
                onRequestPermissions = {
                    permissionLauncher.launch(PermissionHelper.getRequiredPermissions())
                },
                onOpenSettings = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                    startActivity(intent)
                }
            )
        }
    }
    
    @Composable
    private fun PermissionRequestScreen(
        onRequestPermissions: () -> Unit,
        onOpenSettings: () -> Unit
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "需要权限",
                    style = MaterialTheme.typography.headlineMedium
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = "PicKeep 需要访问您的照片和视频以进行备份。",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "所有数据都将进行端到端加密，服务器无法读取您的照片内容。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(32.dp))
                
                Button(
                    onClick = onRequestPermissions,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("授予权限")
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                TextButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("在设置中授予")
                }
            }
        }
    }
}