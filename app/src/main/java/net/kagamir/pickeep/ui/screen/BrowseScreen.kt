package net.kagamir.pickeep.ui.screen

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.kagamir.pickeep.crypto.MasterKeyStore
import net.kagamir.pickeep.data.local.PicKeepDatabase
import net.kagamir.pickeep.data.local.entity.PhotoEntity
import net.kagamir.pickeep.data.repository.MetadataCache
import net.kagamir.pickeep.data.repository.PhotoRepository
import net.kagamir.pickeep.data.repository.SettingsRepository
import net.kagamir.pickeep.storage.webdav.WebDavClient
import net.kagamir.pickeep.sync.DownloadStep
import net.kagamir.pickeep.sync.DownloadTask
import net.kagamir.pickeep.util.PermissionHelper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 浏览已上传文件界面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    database: PicKeepDatabase,
    settingsRepository: SettingsRepository,
    onNavigateBack: () -> Unit
) {
    val photoRepository = remember { PhotoRepository(database) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as? androidx.activity.ComponentActivity
    val snackbarHostState = remember { SnackbarHostState() }

    // 权限请求启动器
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            // 权限已授予，可以继续下载
            // 这里不需要做什么，因为下载会在权限检查后自动继续
        } else {
            scope.launch {
                snackbarHostState.showSnackbar("需要存储权限才能下载文件")
            }
        }
    }

    // 元数据缓存
    val metadataCache = remember { MetadataCache() }

    // 分页加载状态
    val pageSize = 30
    var currentPage by remember { mutableStateOf(0) }
    var allPhotos by remember { mutableStateOf<List<PhotoEntity>>(emptyList()) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var hasMoreData by remember { mutableStateOf(true) }

    // 文件名映射：photoId -> 原始文件名
    val fileNames = remember { mutableStateMapOf<Long, String>() }

    // 列表状态（用于监听滚动）
    val listState = rememberLazyListState()

    // 下载状态：photoId -> DownloadState
    val downloadStates = remember { mutableStateMapOf<Long, DownloadState>() }

    // 预览状态
    var previewPhoto by remember { mutableStateOf<PhotoEntity?>(null) }
    var previewImagePath by remember { mutableStateOf<String?>(null) }
    var previewFileName by remember { mutableStateOf<String?>(null) }
    var isPreviewLoading by remember { mutableStateOf(false) }

    // 检查是否已解锁
    val isUnlocked = settingsRepository.isUnlocked()

    // 为可见的照片加载元数据
    val loadMetadataForVisiblePhotos: (List<PhotoEntity>) -> Unit = { photos ->
        photos.forEach { photo ->
            // 如果已经有文件名，跳过
            if (fileNames.containsKey(photo.id)) return@forEach

            // 如果正在加载，跳过
            if (metadataCache.isLoading(photo.id)) return@forEach

            // 检查缓存
            val cachedMetadata = metadataCache.get(photo.id)
            if (cachedMetadata != null) {
                fileNames[photo.id] = cachedMetadata.originalName
                return@forEach
            }

            // 异步加载元数据
            scope.launch {
                if (!metadataCache.markLoading(photo.id)) {
                    return@launch
                }

                try {
                    if (!isUnlocked) {
                        metadataCache.clearLoading(photo.id)
                        return@launch
                    }

                    val webdavSettings = settingsRepository.webdavSettings.first()
                    if (!webdavSettings.isValid()) {
                        metadataCache.clearLoading(photo.id)
                        return@launch
                    }

                    val masterKeyStore = MasterKeyStore.getInstance(context)
                    val masterKey = masterKeyStore.getMasterKey()
                    val storageClient = WebDavClient(
                        webdavSettings.url,
                        webdavSettings.username,
                        webdavSettings.password
                    )
                    val downloadTask = DownloadTask(context, database, storageClient, masterKey)

                    val result = downloadTask.downloadMetadataOnly(photo)
                    if (result.isSuccess) {
                        val metadata = result.getOrNull()!!
                        metadataCache.put(photo.id, metadata)
                        fileNames[photo.id] = metadata.originalName
                    } else {
                        // 加载失败，使用哈希文件名
                        fileNames[photo.id] =
                            photo.remotePath?.substringAfterLast('/') ?: "未知文件"
                    }
                } catch (e: Exception) {
                    android.util.Log.e("BrowseScreen", "加载元数据失败: ${photo.id}", e)
                    fileNames[photo.id] = photo.remotePath?.substringAfterLast('/') ?: "未知文件"
                } finally {
                    metadataCache.clearLoading(photo.id)
                }
            }
        }
    }

    // 加载照片页面
    val loadPhotosPage: suspend (Int) -> Unit = { page ->
        if (!isLoadingMore && hasMoreData) {
            isLoadingMore = true
            try {
                val photos = photoRepository.getSyncedPhotosPaged(pageSize, page * pageSize)
                if (photos.isEmpty()) {
                    hasMoreData = false
                } else {
                    if (page == 0) {
                        allPhotos = photos
                    } else {
                        allPhotos = allPhotos + photos
                    }
                    currentPage = page

                    // 为可见的照片加载元数据
                    loadMetadataForVisiblePhotos(photos)
                }
            } catch (e: Exception) {
                android.util.Log.e("BrowseScreen", "加载照片失败", e)
            } finally {
                isLoadingMore = false
            }
        }
    }

    // 执行下载的函数
    val performDownload: suspend (PhotoEntity) -> Unit = { photo ->
        // 获取 WebDAV 设置
        val webdavSettings = try {
            settingsRepository.webdavSettings.first()
        } catch (e: Exception) {
            snackbarHostState.showSnackbar("获取配置失败: ${e.message}")
            null
        }

        if (webdavSettings != null && webdavSettings.isValid()) {
            try {
                // 获取 Master Key
                val masterKeyStore = MasterKeyStore.getInstance(context)
                val masterKey = masterKeyStore.getMasterKey()

                // 创建存储客户端
                val storageClient = WebDavClient(
                    webdavSettings.url,
                    webdavSettings.username,
                    webdavSettings.password
                )

                // 创建下载任务
                val downloadTask = DownloadTask(context, database, storageClient, masterKey)

                // 更新下载状态
                downloadStates[photo.id] = DownloadState(
                    isDownloading = true,
                    progress = 0,
                    currentStep = DownloadStep.DOWNLOADING_FILE
                )

                // 执行下载
                val result = downloadTask.download(
                    photo,
                    onProgress = { downloaded, total ->
                        val progress = if (total > 0) {
                            ((downloaded * 100) / total).toInt()
                        } else {
                            -1
                        }
                        downloadStates[photo.id] = DownloadState(
                            isDownloading = true,
                            progress = progress,
                            currentStep = DownloadStep.DOWNLOADING_FILE
                        )
                    },
                    onStep = { step, processed, total ->
                        val progress = if (total != null && total > 0) {
                            ((processed * 100) / total).toInt()
                        } else {
                            -1
                        }
                        downloadStates[photo.id] = DownloadState(
                            isDownloading = true,
                            progress = progress,
                            currentStep = step
                        )
                    }
                )

                if (result.isSuccess) {
                    val downloadResult = result.getOrNull()
                    downloadStates[photo.id] = DownloadState(
                        isDownloading = false,
                        isSuccess = true,
                        progress = 100
                    )
                    // 使用解析出的文件名，如果没有则使用哈希文件名
                    val displayFileName =
                        fileNames[photo.id] ?: photo.remotePath?.substringAfterLast('/') ?: "文件"
                    snackbarHostState.showSnackbar(
                        "下载成功: $displayFileName"
                    )
                } else {
                    val error = result.exceptionOrNull()?.message ?: "下载失败"
                    downloadStates[photo.id] = DownloadState(
                        isDownloading = false,
                        isSuccess = false,
                        errorMessage = error
                    )
                    snackbarHostState.showSnackbar("下载失败: $error")
                }
            } catch (e: Exception) {
                android.util.Log.e("BrowseScreen", "下载失败", e)
                downloadStates[photo.id] = DownloadState(
                    isDownloading = false,
                    isSuccess = false,
                    errorMessage = e.message
                )
                snackbarHostState.showSnackbar("下载失败: ${e.message}")
            }
        } else {
            snackbarHostState.showSnackbar("请先配置 WebDAV 服务器")
        }
    }

    // 下载函数
    fun downloadPhoto(photo: PhotoEntity) {
        if (!isUnlocked) {
            scope.launch {
                snackbarHostState.showSnackbar("请先解锁应用")
            }
            return
        }

        val webdavSettings = settingsRepository.webdavSettings.value
        if (!webdavSettings.isValid()) {
            scope.launch {
                snackbarHostState.showSnackbar("请先配置 WebDAV 服务器")
            }
            return
        }

        if (photo.remotePath.isNullOrBlank()) {
            scope.launch {
                snackbarHostState.showSnackbar("远程路径为空")
            }
            return
        }

        // 检查写入权限
        if (!PermissionHelper.hasWritePermission(context)) {
            // 请求权限
            if (activity != null) {
                PermissionHelper.requestWritePermission(activity) { granted ->
                    if (granted) {
                        // 权限已授予，继续下载
                        scope.launch {
                            performDownload(photo)
                        }
                    } else {
                        scope.launch {
                            snackbarHostState.showSnackbar("需要存储权限才能下载文件")
                        }
                    }
                }
            } else {
                scope.launch {
                    snackbarHostState.showSnackbar("无法请求权限，请重启应用")
                }
            }
            return
        }

        // 有权限，直接下载
        scope.launch {
            performDownload(photo)
        }
    }

    // 初始加载和分页加载
    LaunchedEffect(Unit) {
        if (isUnlocked) {
            loadPhotosPage(0)
        }
    }

    // 监听滚动，实现分页加载
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastVisibleIndex ->
                if (lastVisibleIndex != null &&
                    lastVisibleIndex >= allPhotos.size - 5 &&
                    !isLoadingMore &&
                    hasMoreData
                ) {
                    loadPhotosPage(currentPage + 1)
                }
            }
    }

    // 预览照片函数
    fun previewPhoto(photo: PhotoEntity) {
        if (!isUnlocked) {
            scope.launch {
                snackbarHostState.showSnackbar("请先解锁应用")
            }
            return
        }

        val webdavSettings = settingsRepository.webdavSettings.value
        if (!webdavSettings.isValid()) {
            scope.launch {
                snackbarHostState.showSnackbar("请先配置 WebDAV 服务器")
            }
            return
        }

        if (photo.remotePath.isNullOrBlank()) {
            scope.launch {
                snackbarHostState.showSnackbar("远程路径为空")
            }
            return
        }

        previewPhoto = photo
        isPreviewLoading = true

        scope.launch {
            try {
                // 获取 Master Key
                val masterKeyStore = MasterKeyStore.getInstance(context)
                val masterKey = masterKeyStore.getMasterKey()

                // 创建存储客户端
                val storageClient = WebDavClient(
                    webdavSettings.url,
                    webdavSettings.username,
                    webdavSettings.password
                )

                // 创建下载任务
                val downloadTask = DownloadTask(context, database, storageClient, masterKey)

                // 先下载元数据，判断是否为图片
                val metadataResult = downloadTask.downloadMetadataOnly(photo)
                if (metadataResult.isFailure) {
                    val error = metadataResult.exceptionOrNull()?.message ?: "获取文件信息失败"
                    snackbarHostState.showSnackbar("获取文件信息失败: $error")
                    previewPhoto = null
                    isPreviewLoading = false
                    return@launch
                }

                val metadata = metadataResult.getOrNull()!!

                // 保存文件名到映射中（如果还没有）
                if (!fileNames.containsKey(photo.id)) {
                    fileNames[photo.id] = metadata.originalName
                }

                // 设置预览文件名
                previewFileName = metadata.originalName

                // 根据元数据判断是否为图片
                val mimeType = metadata.mimeType
                val fileName = metadata.originalName
                val extension = fileName.substringAfterLast('.', "").lowercase()
                val isImage = mimeType.startsWith("image/") ||
                        (mimeType == "application/octet-stream" && extension in setOf(
                            "jpg",
                            "jpeg",
                            "png",
                            "gif",
                            "webp",
                            "heic",
                            "heif",
                            "bmp"
                        ))

                if (!isImage) {
                    snackbarHostState.showSnackbar("仅支持预览图片文件")
                    previewPhoto = null
                    previewFileName = null
                    isPreviewLoading = false
                    return@launch
                }

                // 下载并解密到临时文件
                val result = downloadTask.downloadToTempFile(photo)

                if (result.isSuccess) {
                    previewImagePath = result.getOrNull()
                } else {
                    val error = result.exceptionOrNull()?.message ?: "预览失败"
                    snackbarHostState.showSnackbar("预览失败: $error")
                    previewPhoto = null
                    previewFileName = null
                }
            } catch (e: Exception) {
                android.util.Log.e("BrowseScreen", "预览失败", e)
                snackbarHostState.showSnackbar("预览失败: ${e.message}")
                previewPhoto = null
                previewFileName = null
            } finally {
                isPreviewLoading = false
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("已上传文件") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            // 刷新列表（实际上列表会自动更新，因为使用的是 Flow）
                        }
                    ) {
                        Icon(Icons.Default.Refresh, "刷新")
                    }
                }
            )
        }
    ) { paddingValues ->
        if (!isUnlocked) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = "请先解锁应用以浏览和下载文件",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        } else if (allPhotos.isEmpty() && !isLoadingMore) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "暂无已上传的文件",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(allPhotos.size) { index ->
                    val photo = allPhotos[index]
                    PhotoItem(
                        photo = photo,
                        fileName = fileNames[photo.id] ?: photo.remotePath?.substringAfterLast('/')
                        ?: "加载中...",
                        isLoadingMetadata = metadataCache.isLoading(photo.id) && !fileNames.containsKey(
                            photo.id
                        ),
                        downloadState = downloadStates[photo.id],
                        onDownloadClick = { downloadPhoto(photo) },
                        onPreviewClick = { previewPhoto(photo) }
                    )
                }

                // 加载更多指示器
                if (isLoadingMore) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }

        // 图片预览对话框
        if (previewPhoto != null) {
            ImagePreviewDialog(
                photo = previewPhoto!!,
                fileName = previewFileName ?: fileNames[previewPhoto!!.id]
                ?: previewPhoto!!.remotePath?.substringAfterLast('/') ?: "未知文件",
                imagePath = previewImagePath,
                isLoading = isPreviewLoading,
                onDismiss = {
                    previewPhoto = null
                    previewFileName = null
                    // 清理临时文件
                    previewImagePath?.let { path ->
                        File(path).delete()
                    }
                    previewImagePath = null
                }
            )
        }
    }
}

/**
 * 下载状态
 */
data class DownloadState(
    val isDownloading: Boolean = false,
    val isSuccess: Boolean = false,
    val progress: Int = 0,
    val currentStep: DownloadStep? = null,
    val errorMessage: String? = null
)

/**
 * 照片列表项
 */
@Composable
private fun PhotoItem(
    photo: PhotoEntity,
    fileName: String,
    isLoadingMetadata: Boolean,
    downloadState: DownloadState?,
    onDownloadClick: () -> Unit,
    onPreviewClick: () -> Unit
) {
    // 下载状态变量（在函数开始处定义，整个函数可用）
    val isDownloading = downloadState?.isDownloading == true
    val isSuccess = downloadState?.isSuccess == true

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 文件名和大小（可点击预览）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onPreviewClick),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = fileName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (isLoadingMetadata) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    }
                    Text(
                        text = formatFileSize(photo.localSize),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 下载按钮

                if (isDownloading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else if (isSuccess) {
                    Icon(
                        Icons.Default.Download,
                        "已下载",
                        tint = MaterialTheme.colorScheme.primary
                    )
                } else {
                    IconButton(onClick = onDownloadClick) {
                        Icon(Icons.Default.Download, "下载")
                    }
                }
            }

            // 时间戳
            if (photo.lastSyncedAt != null) {
                Text(
                    text = "上传时间: ${formatTimestamp(photo.lastSyncedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 下载进度
            if (isDownloading) {
                val stepText = getStepText(downloadState?.currentStep)
                if (stepText.isNotEmpty()) {
                    Text(
                        text = stepText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (downloadState?.progress != null && downloadState.progress >= 0) {
                    LinearProgressIndicator(
                        progress = { downloadState.progress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "${downloadState.progress}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.End)
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            // 错误信息
            if (downloadState?.errorMessage != null) {
                Text(
                    text = "错误: ${downloadState.errorMessage}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * 根据文件扩展名判断是否为图片
 */
private fun isImageByExtension(fileName: String): Boolean {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    return extension in setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp")
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
 * 格式化时间戳
 */
private fun formatTimestamp(timestamp: Long): String {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    return dateFormat.format(Date(timestamp))
}

/**
 * 获取步骤文本
 */
private fun getStepText(step: DownloadStep?): String {
    return when (step) {
        DownloadStep.DOWNLOADING_FILE -> "下载文件"
        DownloadStep.DOWNLOADING_METADATA -> "下载元数据"
        DownloadStep.DECRYPTING_METADATA -> "解密元数据"
        DownloadStep.GETTING_CEK -> "获取密钥"
        DownloadStep.DECRYPTING_FILE -> "解密文件"
        DownloadStep.SAVING_FILE -> "保存文件"
        null -> ""
        else -> ""
    }
}

/**
 * 图片预览对话框
 */
@Composable
private fun ImagePreviewDialog(
    photo: PhotoEntity,
    fileName: String,
    imagePath: String?,
    isLoading: Boolean,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Card(
            modifier = Modifier.fillMaxSize(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // 关闭按钮
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        "关闭",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }

                // 文件名
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp, start = 48.dp, end = 48.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // 图片内容
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 56.dp, bottom = 16.dp, start = 16.dp, end = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isLoading) {
                        CircularProgressIndicator()
                    } else if (imagePath != null) {
                        val bitmap = remember(imagePath) {
                            try {
                                BitmapFactory.decodeFile(imagePath)
                            } catch (e: Exception) {
                                android.util.Log.e("ImagePreviewDialog", "加载图片失败", e)
                                null
                            }
                        }

                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "预览图片",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        } else {
                            Text(
                                text = "无法加载图片",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    } else {
                        Text(
                            text = "图片加载失败",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

