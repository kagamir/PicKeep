package net.kagamir.pickeep.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.kagamir.pickeep.R
import net.kagamir.pickeep.data.repository.SettingsRepository
import net.kagamir.pickeep.storage.webdav.WebDavClient
import net.kagamir.pickeep.util.QrCodeHelper

/**
 * 设置界面
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    var monitoredExtensions by remember { mutableStateOf(setOf<String>()) }
    var extensionInput by remember { mutableStateOf("") }
    var isTestingConnection by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var saveResult by remember { mutableStateOf<String?>(null) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showQrCodeDialog by remember { mutableStateOf(false) }
    var qrCodeBitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val isUnlocked = settingsRepository.isUnlocked()
    val context = LocalContext.current
    
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
            monitoredExtensions = settings.monitoredExtensions
        }
    }
    
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(R.string.title_reset_upload_records)) },
            text = { Text(stringResource(R.string.msg_reset_upload_records_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            settingsRepository.resetUploadHistory()
                            showResetDialog = false
                            snackbarHostState.showSnackbar(
                                message = context.getString(R.string.msg_upload_records_reset)
                            )
                        }
                    }
                ) {
                    Text(stringResource(R.string.btn_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }
    
    if (showQrCodeDialog) {
        AlertDialog(
            onDismissRequest = { showQrCodeDialog = false },
            title = { Text(stringResource(R.string.title_recovery_qr)) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.msg_recovery_qr_hint),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (qrCodeBitmap != null) {
                        Image(
                            bitmap = qrCodeBitmap!!,
                            contentDescription = stringResource(R.string.title_recovery_qr),
                            modifier = Modifier
                                .size(300.dp)
                                .padding(16.dp)
                        )
                    } else {
                        CircularProgressIndicator()
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showQrCodeDialog = false }) {
                    Text(stringResource(R.string.btn_ok))
                }
            }
        )
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_settings)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.content_desc_back)
                        )
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
                text = stringResource(R.string.title_webdav_config),
                style = MaterialTheme.typography.titleMedium
            )
            
            OutlinedTextField(
                value = webdavUrl,
                onValueChange = { webdavUrl = it },
                label = { Text(stringResource(R.string.label_webdav_server)) },
                placeholder = { Text("https://example.com/webdav") },
                modifier = Modifier.fillMaxWidth()
            )
            
            OutlinedTextField(
                value = webdavUsername,
                onValueChange = { webdavUsername = it },
                label = { Text(stringResource(R.string.label_webdav_username)) },
                modifier = Modifier.fillMaxWidth()
            )
            
            OutlinedTextField(
                value = webdavPassword,
                onValueChange = { webdavPassword = it },
                label = { Text(stringResource(R.string.label_webdav_password)) },
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

                                testResult =
                                    if (result.isSuccess && result.getOrNull() == true) {
                                        context.getString(R.string.msg_connection_success)
                                    } else {
                                        context.getString(
                                            R.string.msg_connection_failed,
                                            result.exceptionOrNull()?.message ?: ""
                                        )
                                    }
                            } catch (e: Exception) {
                                testResult = context.getString(
                                    R.string.msg_connection_failed_exception,
                                    e.message ?: ""
                                )
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
                        Text(stringResource(R.string.btn_test_connection))
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
                                saveResult = context.getString(R.string.msg_webdav_saved)
                                snackbarHostState.showSnackbar(
                                    message = context.getString(R.string.msg_webdav_saved),
                                    duration = SnackbarDuration.Short
                                )
                            } catch (e: Exception) {
                                saveResult = context.getString(
                                    R.string.msg_save_failed,
                                    e.message ?: ""
                                )
                                snackbarHostState.showSnackbar(
                                    message = context.getString(
                                        R.string.msg_save_failed,
                                        e.message ?: ""
                                    ),
                                    duration = SnackbarDuration.Short
                                )
                            }
                        }
                    },
                    enabled = webdavUrl.isNotBlank() && webdavUsername.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.btn_save))
                }
            }

            if (testResult != null) {
                Text(
                    text = testResult!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (testResult == stringResource(R.string.msg_connection_success))
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.error
                )
            }

            HorizontalDivider()

            // 同步设置
            Text(
                text = stringResource(R.string.title_sync_settings),
                style = MaterialTheme.typography.titleMedium
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.switch_auto_sync))
                Switch(
                    checked = autoSync,
                    onCheckedChange = {
                        autoSync = it
                        settingsRepository.saveSyncSettings(
                            SettingsRepository.SyncSettings(
                                autoSync = autoSync,
                                syncIntervalHours = syncInterval,
                                syncOnlyOnWifi = syncOnlyOnWifi,
                                syncOnlyWhenCharging = syncOnlyWhenCharging,
                                monitoredExtensions = monitoredExtensions
                            )
                        )
                    }
                )
            }
            
            if (autoSync) {
                Text(
                    text = stringResource(
                        R.string.text_sync_interval_hours,
                        syncInterval
                    ),
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
                                syncOnlyWhenCharging = syncOnlyWhenCharging,
                                monitoredExtensions = monitoredExtensions
                            )
                        )
                    }
                )
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.switch_sync_wifi_only))
                Switch(
                    checked = syncOnlyOnWifi,
                    onCheckedChange = {
                        syncOnlyOnWifi = it
                        settingsRepository.saveSyncSettings(
                            SettingsRepository.SyncSettings(
                                autoSync = autoSync,
                                syncIntervalHours = syncInterval,
                                syncOnlyOnWifi = syncOnlyOnWifi,
                                syncOnlyWhenCharging = syncOnlyWhenCharging,
                                monitoredExtensions = monitoredExtensions
                            )
                        )
                    }
                )
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.switch_sync_charging_only))
                Switch(
                    checked = syncOnlyWhenCharging,
                    onCheckedChange = {
                        syncOnlyWhenCharging = it
                        settingsRepository.saveSyncSettings(
                            SettingsRepository.SyncSettings(
                                autoSync = autoSync,
                                syncIntervalHours = syncInterval,
                                syncOnlyOnWifi = syncOnlyOnWifi,
                                syncOnlyWhenCharging = syncOnlyWhenCharging,
                                monitoredExtensions = monitoredExtensions
                            )
                        )
                    }
                )
            }
            
            HorizontalDivider()
            
            // 监控的文件类型
            Text(
                text = stringResource(R.string.title_monitored_file_types),
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                text = stringResource(R.string.msg_monitored_file_types_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            // 显示当前配置的标签
            if (monitoredExtensions.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    monitoredExtensions.forEach { ext ->
                        InputChip(
                            selected = false,
                            onClick = { },
                            label = { Text(ext) },
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        val newExtensions = monitoredExtensions.toMutableSet().apply {
                                            remove(ext)
                                        }
                                        // 如果全部删除，恢复默认值
                                        val finalExtensions = if (newExtensions.isEmpty()) {
                                            SettingsRepository.SyncSettings.DEFAULT_MONITORED_EXTENSIONS
                                        } else {
                                            newExtensions
                                        }
                                        monitoredExtensions = finalExtensions
                                        settingsRepository.saveSyncSettings(
                                            SettingsRepository.SyncSettings(
                                                autoSync = autoSync,
                                                syncIntervalHours = syncInterval,
                                                syncOnlyOnWifi = syncOnlyOnWifi,
                                                syncOnlyWhenCharging = syncOnlyWhenCharging,
                                                monitoredExtensions = finalExtensions
                                            )
                                        )
                                    },
                                    modifier = Modifier.size(18.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = stringResource(R.string.content_desc_delete),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        )
                    }
                }
            }
            
            // 添加新后缀的输入框
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = extensionInput,
                    onValueChange = { 
                        // 只允许字母和数字
                        extensionInput = it.filter { char -> char.isLetterOrDigit() }.lowercase()
                    },
                    label = { Text(stringResource(R.string.label_file_extension)) },
                    placeholder = { Text(stringResource(R.string.placeholder_example_jpg)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Button(
                    onClick = {
                        val trimmed = extensionInput.trim().lowercase()
                        if (trimmed.isNotBlank() && trimmed.all { it.isLetterOrDigit() }) {
                            val newExtensions = monitoredExtensions.toMutableSet().apply {
                                add(trimmed)
                            }
                            monitoredExtensions = newExtensions
                            extensionInput = ""
                            settingsRepository.saveSyncSettings(
                                SettingsRepository.SyncSettings(
                                    autoSync = autoSync,
                                    syncIntervalHours = syncInterval,
                                    syncOnlyOnWifi = syncOnlyOnWifi,
                                    syncOnlyWhenCharging = syncOnlyWhenCharging,
                                    monitoredExtensions = newExtensions
                                )
                            )
                        }
                    },
                    enabled = extensionInput.trim().isNotBlank()
                ) {
                    Text(stringResource(R.string.btn_add))
                }
            }
            
            // 恢复默认按钮
            OutlinedButton(
                onClick = {
                    val defaultExtensions = SettingsRepository.SyncSettings.DEFAULT_MONITORED_EXTENSIONS
                    monitoredExtensions = defaultExtensions
                    settingsRepository.saveSyncSettings(
                        SettingsRepository.SyncSettings(
                            autoSync = autoSync,
                            syncIntervalHours = syncInterval,
                            syncOnlyOnWifi = syncOnlyOnWifi,
                            syncOnlyWhenCharging = syncOnlyWhenCharging,
                            monitoredExtensions = defaultExtensions
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.btn_restore_default))
            }
            
            HorizontalDivider()
            
            // 账户恢复
            Text(
                text = stringResource(R.string.title_account_recovery),
                style = MaterialTheme.typography.titleMedium
            )
            
            Button(
                onClick = {
                    if (!isUnlocked) {
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                message = context.getString(R.string.msg_unlock_app_first)
                            )
                        }
                        return@Button
                    }
                    try {
                        val mnemonic = settingsRepository.exportMnemonic()
                        qrCodeBitmap = QrCodeHelper.generateRecoveryQrCode(mnemonic)
                        showQrCodeDialog = true
                    } catch (e: Exception) {
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                message = context.getString(
                                    R.string.msg_generate_qr_failed,
                                    e.message ?: ""
                                )
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = isUnlocked
            ) {
                Text(stringResource(R.string.btn_show_recovery_qr))
            }

            Text(
                text = stringResource(R.string.msg_recovery_qr_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            HorizontalDivider()
            
            // 高级设置
            Text(
                text = stringResource(R.string.title_advanced_settings),
                style = MaterialTheme.typography.titleMedium
            )

            Button(
                onClick = { showResetDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.title_reset_upload_records))
            }
        }
    }
}
