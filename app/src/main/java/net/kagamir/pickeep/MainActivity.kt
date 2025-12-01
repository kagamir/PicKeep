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
import androidx.compose.ui.res.stringResource
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
        settingsRepository = SettingsRepository(applicationContext, database)
        workManagerScheduler = WorkManagerScheduler(applicationContext)
        
        setContent {
            PicKeepTheme {
                MainContent()
            }
        }
    }
    
    @Composable
    private fun MainContent() {
        var hasReadPermissions by remember { mutableStateOf(PermissionHelper.hasAllPermissions(this)) }
        var hasWritePermissions by remember { mutableStateOf(PermissionHelper.hasWritePermission(this)) }
        val hasPermissions = hasReadPermissions && hasWritePermissions
        val isUnlocked by settingsRepository.isUnlockedFlow.collectAsState()
        
        // 权限请求启动器（读取权限）
        val readPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            hasReadPermissions = permissions.values.all { it }
        }
        
        // 权限请求启动器（写入权限）
        val writePermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            hasWritePermissions = permissions.values.all { it }
        }
        
        // 检查并请求权限
        LaunchedEffect(Unit) {
            if (!hasReadPermissions) {
                readPermissionLauncher.launch(PermissionHelper.getRequiredPermissions())
            } else if (!hasWritePermissions) {
                writePermissionLauncher.launch(PermissionHelper.getWritePermissions())
            }
        }
        
        if (hasPermissions) {
            // 权限已授予，显示主界面
            val navController = rememberNavController()
            
            // 监听锁定状态
            LaunchedEffect(isUnlocked) {
                if (!isUnlocked && settingsRepository.isInitialized()) {
                    // 如果已初始化但被锁定，跳转到解锁界面
                    // 检查当前是否已经在解锁界面，避免重复跳转（简单处理）
                    if (navController.currentDestination?.route != Routes.UNLOCK) {
                        navController.navigate(Routes.UNLOCK) {
                            // 清除回退栈，防止返回
                            popUpTo(0)
                        }
                    }
                }
            }
            
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
                onRequestReadPermissions = {
                    readPermissionLauncher.launch(PermissionHelper.getRequiredPermissions())
                },
                onRequestWritePermissions = {
                    writePermissionLauncher.launch(PermissionHelper.getWritePermissions())
                },
                hasReadPermissions = hasReadPermissions,
                hasWritePermissions = hasWritePermissions,
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
        onRequestReadPermissions: () -> Unit,
        onRequestWritePermissions: () -> Unit,
        hasReadPermissions: Boolean,
        hasWritePermissions: Boolean,
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
                    text = stringResource(R.string.title_permission_required),
                    style = MaterialTheme.typography.headlineMedium
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = stringResource(R.string.msg_permission_storage_explain),
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = stringResource(R.string.msg_permission_encrypted_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(32.dp))
                
                if (!hasReadPermissions) {
                    Button(
                        onClick = onRequestReadPermissions,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = stringResource(R.string.btn_grant_read_permission))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                
                if (!hasWritePermissions) {
                Button(
                        onClick = onRequestWritePermissions,
                    modifier = Modifier.fillMaxWidth()
                ) {
                        Text(text = stringResource(R.string.btn_grant_write_permission))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                TextButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = stringResource(R.string.btn_open_settings))
                }
            }
        }
    }
}
