package net.kagamir.pickeep.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 上传步骤枚举
 */
enum class UploadStep {
    /** 哈希计算 */
    HASHING,
    
    /** 加密 */
    ENCRYPTING,
    
    /** 生成远程路径 */
    GENERATING_PATH,
    
    /** 上传文件 */
    UPLOADING_FILE,
    
    /** 上传元数据 */
    UPLOADING_METADATA
}

/**
 * 文件上传状态
 */
data class FileUploadState(
    /** 文件名 */
    val fileName: String,
    
    /** 文件大小（字节） */
    val fileSize: Long,
    
    /** 当前步骤 */
    val currentStep: UploadStep,
    
    /** 进度 (0-100)，-1 表示不确定 */
    val progress: Int,
    
    /** 已处理字节数 */
    val processedBytes: Long = 0,
    
    /** 总字节数 */
    val totalBytes: Long = 0
)

/**
 * 同步状态管理（单例模式）
 */
class SyncState private constructor() {
    
    companion object {
        @Volatile
        private var instance: SyncState? = null
        
        fun getInstance(): SyncState {
            return instance ?: synchronized(this) {
                instance ?: SyncState().also { instance = it }
            }
        }
    }
    
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    
    data class State(
        /** 是否正在同步 */
        val isSyncing: Boolean = false,
        
        /** 是否已暂停 */
        val isPaused: Boolean = false,
        
        /** 待同步数量 */
        val pendingCount: Int = 0,
        
        /** 正在上传数量 */
        val uploadingCount: Int = 0,
        
        /** 已同步数量 */
        val syncedCount: Int = 0,
        
        /** 失败数量 */
        val failedCount: Int = 0,
        
        /** 正在上传的文件状态 Map，key 为文件 ID */
        val activeUploads: Map<String, FileUploadState> = emptyMap(),
        
        /** 总进度 (0-100) */
        val totalProgress: Int = 0,
        
        /** 错误信息 */
        val errorMessage: String? = null,
        
        /** 最后同步时间 */
        val lastSyncTime: Long? = null
    )
    
    /**
     * 开始同步
     */
    fun startSync() {
        _state.value = _state.value.copy(
            isSyncing = true,
            errorMessage = null
        )
    }
    
    /**
     * 停止同步
     */
    fun stopSync() {
        _state.value = _state.value.copy(
            isSyncing = false,
            isPaused = false,
            activeUploads = emptyMap(),
            lastSyncTime = System.currentTimeMillis()
        )
    }
    
    /**
     * 暂停同步
     */
    fun pauseSync() {
        _state.value = _state.value.copy(
            isPaused = true
        )
    }
    
    /**
     * 继续同步
     */
    fun resumeSync() {
        _state.value = _state.value.copy(
            isPaused = false
        )
    }
    
    /**
     * 更新统计信息
     */
    fun updateCounts(
        pending: Int,
        uploading: Int,
        synced: Int,
        failed: Int
    ) {
        val currentState = _state.value
        val activeUploadsCount = currentState.activeUploads.size
        
        // 优先使用 activeUploads 的大小作为正在上传的数量
        // 因为它是实时更新的，比数据库查询更准确
        val actualUploading = if (activeUploadsCount > 0) {
            activeUploadsCount
        } else {
            uploading
        }
        
        val total = pending + actualUploading + synced + failed
        val progress = if (total > 0) {
            ((synced + failed) * 100 / total)
        } else {
            100
        }
        
        _state.value = currentState.copy(
            pendingCount = pending,
            uploadingCount = actualUploading,
            syncedCount = synced,
            failedCount = failed,
            totalProgress = progress
        )
    }
    
    /**
     * 更新文件上传状态
     * @param fileId 文件 ID（通常为 photo.id.toString()）
     * @param state 文件上传状态，为 null 时移除该文件的状态
     */
    fun updateFileUploadState(fileId: String, state: FileUploadState?) {
        val currentState = _state.value
        val newUploads = if (state == null) {
            currentState.activeUploads - fileId
        } else {
            currentState.activeUploads + (fileId to state)
        }
        
        _state.value = currentState.copy(
            activeUploads = newUploads
        )
    }
    
    /**
     * 更新当前上传信息（向后兼容，保留但标记为废弃）
     * @deprecated 使用 updateFileUploadState 代替
     */
    @Deprecated("使用 updateFileUploadState 代替", ReplaceWith("updateFileUploadState(fileId, state)"))
    fun updateCurrentUpload(fileName: String?, progress: Int) {
        // 为了向后兼容，暂时保留空实现
    }
    
    /**
     * 设置错误信息
     */
    fun setError(message: String) {
        _state.value = _state.value.copy(
            errorMessage = message,
            isSyncing = false
        )
    }
    
    /**
     * 清除错误信息
     */
    fun clearError() {
        _state.value = _state.value.copy(
            errorMessage = null
        )
    }
}

