package net.kagamir.pickeep.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
        
        /** 当前上传的文件名 */
        val currentFileName: String? = null,
        
        /** 当前上传进度 (0-100) */
        val currentProgress: Int = 0,
        
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
            currentFileName = null,
            currentProgress = 0,
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
        val total = pending + uploading + synced + failed
        val progress = if (total > 0) {
            ((synced + failed) * 100 / total)
        } else {
            100
        }
        
        _state.value = _state.value.copy(
            pendingCount = pending,
            uploadingCount = uploading,
            syncedCount = synced,
            failedCount = failed,
            totalProgress = progress
        )
    }
    
    /**
     * 更新当前上传信息
     */
    fun updateCurrentUpload(fileName: String?, progress: Int) {
        _state.value = _state.value.copy(
            currentFileName = fileName,
            currentProgress = progress
        )
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

