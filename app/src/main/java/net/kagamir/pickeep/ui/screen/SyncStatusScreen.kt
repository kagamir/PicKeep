package net.kagamir.pickeep.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.kagamir.pickeep.data.local.PicKeepDatabase
import net.kagamir.pickeep.data.local.entity.SyncStatus
import net.kagamir.pickeep.data.repository.SettingsRepository
import net.kagamir.pickeep.monitor.PhotoScanner
import net.kagamir.pickeep.sync.FileUploadState
import net.kagamir.pickeep.sync.SyncState
import net.kagamir.pickeep.sync.UploadStep
import net.kagamir.pickeep.worker.WorkManagerScheduler

/**
 * 同步状态界面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncStatusScreen(
    database: PicKeepDatabase,
    settingsRepository: SettingsRepository,
    workManagerScheduler: WorkManagerScheduler,
    onNavigateToSettings: () -> Unit
) {
    var pendingCount by remember { mutableStateOf(0) }
    var uploadingCount by remember { mutableStateOf(0) }
    var syncedCount by remember { mutableStateOf(0) }
    var failedCount by remember { mutableStateOf(0) }
    var isScanning by remember { mutableStateOf(false) }
    
    val syncState = remember { SyncState.getInstance() }
    val syncStateValue by syncState.state.collectAsState()
    
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    
    // 进入首页时清理未完成的同步任务
    LaunchedEffect(Unit) {
        scope.launch {
            // 将状态为 UPLOADING 的任务重置为 PENDING，以便下次重新上传
            database.photoDao().updateStatusByStatus(SyncStatus.UPLOADING, SyncStatus.PENDING)
            
            // 重置同步状态
            syncState.stopSync()
        }
        
        launch {
            database.photoDao().countByStatus(SyncStatus.PENDING).collect {
                pendingCount = it
            }
        }
        launch {
            database.photoDao().countByStatus(SyncStatus.UPLOADING).collect {
                uploadingCount = it
            }
        }
        launch {
            database.photoDao().countByStatus(SyncStatus.SYNCED).collect {
                syncedCount = it
            }
        }
        launch {
            database.photoDao().countByStatus(SyncStatus.FAILED).collect {
                failedCount = it
            }
        }
        
        // 注意: 移除了自动扫描，现在只通过手动点击"刷新"按钮来扫描照片
        // 但图片监控功能（MediaStoreObserver/FileSystemObserver）依然保持活跃
    }
    
    // 扫描照片的辅助函数
    fun performScan() {
        scope.launch {
            isScanning = true
            try {
                val monitoredExtensions = settingsRepository.syncSettings.first().monitoredExtensions
                val photoScanner = PhotoScanner(context, database, monitoredExtensions)
                val newCount = photoScanner.scanForChanges()
                android.util.Log.d("SyncStatusScreen", "扫描完成，发现 $newCount 个新照片")
                snackbarHostState.showSnackbar(
                    if (newCount > 0) "发现 $newCount 个新照片" else "没有发现新照片"
                )
            } catch (e: Exception) {
                android.util.Log.e("SyncStatusScreen", "扫描照片失败", e)
                snackbarHostState.showSnackbar("扫描失败: ${e.message}")
            } finally {
                isScanning = false
            }
        }
    }
    
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("PicKeep") },
                actions = {
                    IconButton(
                        onClick = { performScan() },
                        enabled = !isScanning && !syncStateValue.isSyncing
                    ) {
                        Icon(Icons.Default.Refresh, "重新扫描")
                    }
                    IconButton(
                        onClick = {
                            if (syncStateValue.isSyncing) {
                                // 正在同步时，显示提示
                                scope.launch {
                                    snackbarHostState.showSnackbar("请先取消当前同步任务")
                                }
                            } else {
                                // 正常导航到设置页面
                                onNavigateToSettings()
                            }
                        },
                        enabled = !syncStateValue.isSyncing
                    ) {
                        Icon(Icons.Default.Settings, "设置")
                    }
                }
            )
        },
        floatingActionButton = {
            if (!syncStateValue.isSyncing && !isScanning) {
                FloatingActionButton(
                    onClick = {
                        scope.launch {
                            // 检查是否已解锁
                            if (!settingsRepository.isUnlocked()) {
                                // 需要解锁（这里简化处理）
                                return@launch
                            }
                            
                            // 检查 WebDAV 配置
                            val webdavSettings = settingsRepository.webdavSettings.first()
                            if (!webdavSettings.isValid()) {
                                // 需要配置 WebDAV
                                onNavigateToSettings()
                                return@launch
                            }
                            
                            // 开始同步
                            workManagerScheduler.scheduleSyncNow()
                        }
                    }
                ) {
                    Icon(Icons.Default.Sync, "同步")
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "同步状态",
                style = MaterialTheme.typography.headlineMedium
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 状态卡片
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatusRow("待同步", pendingCount)
                    StatusRow("正在上传", uploadingCount)
                    StatusRow("已同步", syncedCount)
                    if (failedCount > 0) {
                        StatusRow("失败", failedCount, isError = true)
                    }
                }
            }
            
            if (isScanning) {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("正在扫描照片...")
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }
            }
            
            if (syncStateValue.isSyncing) {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (syncStateValue.isPaused) "同步已暂停" else "正在同步...",
                                style = MaterialTheme.typography.titleMedium
                            )
                            if (!syncStateValue.isPaused) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        }
                        
                        // 显示所有正在上传的文件
                        if (syncStateValue.activeUploads.isNotEmpty()) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                syncStateValue.activeUploads.values.forEach { fileState ->
                                    FileUploadItem(fileState)
                                }
                            }
                        } else {
                            Text(
                                text = "准备中...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        
                        // 暂停/继续按钮
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (syncStateValue.isPaused) {
                                Button(
                                    onClick = { syncState.resumeSync() },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("继续同步")
                                }
                                OutlinedButton(
                                    onClick = { 
                                        syncState.stopSync()
                                        workManagerScheduler.cancelSync()
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("取消同步")
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { syncState.pauseSync() },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("暂停同步")
                                }
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 提示信息
            if (!settingsRepository.isUnlocked()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "请先解锁应用以启用同步功能",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusRow(
    label: String,
    count: Int,
    isError: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.bodyLarge,
            color = if (isError)
                MaterialTheme.colorScheme.error
            else
                MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * 格式化文件大小
 */
private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

/**
 * 获取步骤文本
 */
private fun getStepText(step: UploadStep): String {
    return when (step) {
        UploadStep.HASHING -> "计算哈希"
        UploadStep.ENCRYPTING -> "加密文件"
        UploadStep.GENERATING_PATH -> "生成路径"
        UploadStep.UPLOADING_FILE -> "上传文件"
        UploadStep.UPLOADING_METADATA -> "上传元数据"
    }
}

/**
 * 文件上传项组件
 */
@Composable
private fun FileUploadItem(fileState: FileUploadState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 文件名和大小
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = fileState.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Text(
                    text = formatFileSize(fileState.fileSize),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // 当前步骤
            Text(
                text = getStepText(fileState.currentStep),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            
            // 进度条
            if (fileState.progress >= 0) {
                // 有确定进度的线性进度条
                LinearProgressIndicator(
                    progress = { fileState.progress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                // 显示进度百分比
                Text(
                    text = "${fileState.progress}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.End)
                )
            } else {
                // 不确定进度的线性进度条
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

