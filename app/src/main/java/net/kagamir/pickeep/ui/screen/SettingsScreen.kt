package net.kagamir.pickeep.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.kagamir.pickeep.data.repository.SettingsRepository
import net.kagamir.pickeep.storage.webdav.WebDavClient

/**
 * 设置界面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsRepository: SettingsRepository,
    onNavigateBack: () -> Unit
) {
    var webdavUrl by remember { mutableStateOf("") }
    var webdavUsername by remember { mutableStateOf("") }
    var webdavPassword by remember { mutableStateOf("") }
    var autoSync by remember { mutableStateOf(true) }
    var syncInterval by remember { mutableStateOf(12L) }
    var syncOnlyOnWifi by remember { mutableStateOf(true) }
    var syncOnlyWhenCharging by remember { mutableStateOf(false) }
    var isTestingConnection by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var saveResult by remember { mutableStateOf<String?>(null) }
    var showResetDialog by remember { mutableStateOf(false) }
    
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    
    // 加载现有设置
    LaunchedEffect(Unit) {
        settingsRepository.webdavSettings.collect { settings ->
            webdavUrl = settings.url
            webdavUsername = settings.username
            webdavPassword = settings.password
        }
    }
    
    LaunchedEffect(Unit) {
        settingsRepository.syncSettings.collect { settings ->
            autoSync = settings.autoSync
            syncInterval = settings.syncIntervalHours
            syncOnlyOnWifi = settings.syncOnlyOnWifi
            syncOnlyWhenCharging = settings.syncOnlyWhenCharging
        }
    }
    
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("重置上传记录") },
            text = { Text("确定要清除所有本地上传记录吗？这不会删除服务器上的文件，但会导致下次同步时重新扫描所有照片。如果服务器上已存在同名文件，它们将被覆盖。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            settingsRepository.resetUploadHistory()
                            showResetDialog = false
                            snackbarHostState.showSnackbar("上传记录已重置")
                        }
                    }
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // WebDAV 配置
            Text(
                text = "WebDAV 配置",
                style = MaterialTheme.typography.titleMedium
            )
            
            OutlinedTextField(
                value = webdavUrl,
                onValueChange = { webdavUrl = it },
                label = { Text("服务器地址") },
                placeholder = { Text("https://example.com/webdav") },
                modifier = Modifier.fillMaxWidth()
            )
            
            OutlinedTextField(
                value = webdavUsername,
                onValueChange = { webdavUsername = it },
                label = { Text("用户名") },
                modifier = Modifier.fillMaxWidth()
            )
            
            OutlinedTextField(
                value = webdavPassword,
                onValueChange = { webdavPassword = it },
                label = { Text("密码") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            isTestingConnection = true
                            testResult = null
                            
                            try {
                                val client = WebDavClient(webdavUrl, webdavUsername, webdavPassword)
                                val result = client.checkConnection()
                                
                                testResult = if (result.isSuccess && result.getOrNull() == true) {
                                    "连接成功"
                                } else {
                                    "连接失败: ${result.exceptionOrNull()?.message}"
                                }
                            } catch (e: Exception) {
                                testResult = "连接失败: ${e.message}"
                            } finally {
                                isTestingConnection = false
                            }
                        }
                    },
                    enabled = !isTestingConnection && webdavUrl.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    if (isTestingConnection) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text("测试连接")
                    }
                }
                
                Button(
                    onClick = {
                        scope.launch {
                            try {
                                settingsRepository.saveWebDavSettings(
                                    SettingsRepository.WebDavSettings(
                                        url = webdavUrl,
                                        username = webdavUsername,
                                        password = webdavPassword
                                    )
                                )
                                saveResult = "WebDAV 配置已保存"
                                snackbarHostState.showSnackbar(
                                    message = "WebDAV 配置已保存",
                                    duration = SnackbarDuration.Short
                                )
                            } catch (e: Exception) {
                                saveResult = "保存失败: ${e.message}"
                                snackbarHostState.showSnackbar(
                                    message = "保存失败: ${e.message}",
                                    duration = SnackbarDuration.Short
                                )
                            }
                        }
                    },
                    enabled = webdavUrl.isNotBlank() && webdavUsername.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("保存")
                }
            }
            
            if (testResult != null) {
                Text(
                    text = testResult!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (testResult!!.startsWith("连接成功"))
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.error
                )
            }
            
            HorizontalDivider()
            
            // 同步设置
            Text(
                text = "同步设置",
                style = MaterialTheme.typography.titleMedium
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("自动同步")
                Switch(
                    checked = autoSync,
                    onCheckedChange = {
                        autoSync = it
                        settingsRepository.saveSyncSettings(
                            SettingsRepository.SyncSettings(
                                autoSync = autoSync,
                                syncIntervalHours = syncInterval,
                                syncOnlyOnWifi = syncOnlyOnWifi,
                                syncOnlyWhenCharging = syncOnlyWhenCharging
                            )
                        )
                    }
                )
            }
            
            if (autoSync) {
                Text(
                    text = "同步间隔: ${syncInterval} 小时",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Slider(
                    value = syncInterval.toFloat(),
                    onValueChange = { syncInterval = it.toLong() },
                    valueRange = 1f..24f,
                    steps = 22,
                    onValueChangeFinished = {
                        settingsRepository.saveSyncSettings(
                            SettingsRepository.SyncSettings(
                                autoSync = autoSync,
                                syncIntervalHours = syncInterval,
                                syncOnlyOnWifi = syncOnlyOnWifi,
                                syncOnlyWhenCharging = syncOnlyWhenCharging
                            )
                        )
                    }
                )
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("仅在 WiFi 下同步")
                Switch(
                    checked = syncOnlyOnWifi,
                    onCheckedChange = {
                        syncOnlyOnWifi = it
                        settingsRepository.saveSyncSettings(
                            SettingsRepository.SyncSettings(
                                autoSync = autoSync,
                                syncIntervalHours = syncInterval,
                                syncOnlyOnWifi = syncOnlyOnWifi,
                                syncOnlyWhenCharging = syncOnlyWhenCharging
                            )
                        )
                    }
                )
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("仅在充电时同步")
                Switch(
                    checked = syncOnlyWhenCharging,
                    onCheckedChange = {
                        syncOnlyWhenCharging = it
                        settingsRepository.saveSyncSettings(
                            SettingsRepository.SyncSettings(
                                autoSync = autoSync,
                                syncIntervalHours = syncInterval,
                                syncOnlyOnWifi = syncOnlyOnWifi,
                                syncOnlyWhenCharging = syncOnlyWhenCharging
                            )
                        )
                    }
                )
            }
            
            HorizontalDivider()
            
            // 高级设置
            Text(
                text = "高级设置",
                style = MaterialTheme.typography.titleMedium
            )
            
            Button(
                onClick = { showResetDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("重置上传记录")
            }
        }
    }
}
