package net.kagamir.pickeep.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.kagamir.pickeep.crypto.Bip39
import net.kagamir.pickeep.crypto.MasterKeyStore
import net.kagamir.pickeep.data.local.PicKeepDatabase

/**
 * 设置仓库
 * 管理应用设置和配置
 */
class SettingsRepository(context: Context, private val database: PicKeepDatabase) {
    
    private val sharedPreferences: SharedPreferences
    private val masterKeyStore = MasterKeyStore.getInstance(context)
    
    private val _webdavSettings = MutableStateFlow(WebDavSettings())
    val webdavSettings: StateFlow<WebDavSettings> = _webdavSettings.asStateFlow()
    
    private val _syncSettings = MutableStateFlow(SyncSettings())
    val syncSettings: StateFlow<SyncSettings> = _syncSettings.asStateFlow()
    
    val isUnlockedFlow: StateFlow<Boolean> = masterKeyStore.isUnlockedFlow
    
    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        sharedPreferences = EncryptedSharedPreferences.create(
            context,
            "pickeep_settings",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        
        // 加载设置
        loadSettings()
    }
    
    /**
     * 加载设置
     */
    private fun loadSettings() {
        _webdavSettings.value = WebDavSettings(
            url = sharedPreferences.getString(KEY_WEBDAV_URL, "") ?: "",
            username = sharedPreferences.getString(KEY_WEBDAV_USERNAME, "") ?: "",
            password = sharedPreferences.getString(KEY_WEBDAV_PASSWORD, "") ?: ""
        )
        
        _syncSettings.value = SyncSettings(
            autoSync = sharedPreferences.getBoolean(KEY_AUTO_SYNC, true),
            syncIntervalHours = sharedPreferences.getLong(KEY_SYNC_INTERVAL, 12),
            syncOnlyOnWifi = sharedPreferences.getBoolean(KEY_SYNC_WIFI_ONLY, true),
            syncOnlyWhenCharging = sharedPreferences.getBoolean(KEY_SYNC_CHARGING_ONLY, false)
        )
    }
    
    /**
     * 保存 WebDAV 设置
     */
    fun saveWebDavSettings(settings: WebDavSettings) {
        sharedPreferences.edit()
            .putString(KEY_WEBDAV_URL, settings.url)
            .putString(KEY_WEBDAV_USERNAME, settings.username)
            .putString(KEY_WEBDAV_PASSWORD, settings.password)
            .apply()
        
        _webdavSettings.value = settings
    }
    
    /**
     * 保存同步设置
     */
    fun saveSyncSettings(settings: SyncSettings) {
        sharedPreferences.edit()
            .putBoolean(KEY_AUTO_SYNC, settings.autoSync)
            .putLong(KEY_SYNC_INTERVAL, settings.syncIntervalHours)
            .putBoolean(KEY_SYNC_WIFI_ONLY, settings.syncOnlyOnWifi)
            .putBoolean(KEY_SYNC_CHARGING_ONLY, settings.syncOnlyWhenCharging)
            .apply()
        
        _syncSettings.value = settings
    }
    
    /**
     * 重置上传记录
     */
    suspend fun resetUploadHistory() {
        database.photoDao().deleteAll()
    }
    
    /**
     * 获取 Master Key Store
     */
    fun getMasterKeyStore(): MasterKeyStore = masterKeyStore
    
    /**
     * 检查是否已初始化
     */
    fun isInitialized(): Boolean = masterKeyStore.isInitialized()
    
    /**
     * 初始化（首次设置）
     * 返回生成的助记词（如果未提供）
     */
    fun initialize(password: String, importMnemonic: List<String>? = null): List<String> {
        return masterKeyStore.initialize(password, importMnemonic)
    }
    
    /**
     * 解锁
     */
    fun unlock(password: String): Boolean = masterKeyStore.unlock(password)
    
    /**
     * 锁定
     */
    fun lock() = masterKeyStore.lock()
    
    /**
     * 检查是否已解锁
     */
    fun isUnlocked(): Boolean = masterKeyStore.isUnlocked()
    
    /**
     * 修改密码
     */
    fun changePassword(oldPassword: String, newPassword: String): Boolean =
        masterKeyStore.changePassword(oldPassword, newPassword)
    
    /**
     * 导出恢复数据 (Legacy)
     */
    fun exportRecoveryData(): MasterKeyStore.RecoveryData? =
        masterKeyStore.exportRecoveryData()
        
    /**
     * 导出助记词 (Requires Unlock)
     */
    fun exportMnemonic(): List<String> {
        if (!isUnlocked()) throw IllegalStateException("Locked")
        val mk = masterKeyStore.getMasterKey()
        return Bip39.toMnemonic(mk)
    }
    
    /**
     * 从恢复数据导入 (Legacy)
     */
    fun importFromRecovery(recoveryData: MasterKeyStore.RecoveryData, password: String) {
        masterKeyStore.importFromRecovery(recoveryData, password)
    }
    
    /**
     * WebDAV 设置
     */
    data class WebDavSettings(
        val url: String = "",
        val username: String = "",
        val password: String = ""
    ) {
        fun isValid(): Boolean = url.isNotBlank() && username.isNotBlank() && password.isNotBlank()
    }
    
    /**
     * 同步设置
     */
    data class SyncSettings(
        val autoSync: Boolean = true,
        val syncIntervalHours: Long = 12,
        val syncOnlyOnWifi: Boolean = true,
        val syncOnlyWhenCharging: Boolean = false
    )
    
    private companion object {
        const val KEY_WEBDAV_URL = "webdav_url"
        const val KEY_WEBDAV_USERNAME = "webdav_username"
        const val KEY_WEBDAV_PASSWORD = "webdav_password"
        const val KEY_AUTO_SYNC = "auto_sync"
        const val KEY_SYNC_INTERVAL = "sync_interval"
        const val KEY_SYNC_WIFI_ONLY = "sync_wifi_only"
        const val KEY_SYNC_CHARGING_ONLY = "sync_charging_only"
    }
}
