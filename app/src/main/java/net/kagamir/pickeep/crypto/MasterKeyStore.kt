package net.kagamir.pickeep.crypto

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Master Key 存储管理器
 * 负责 Master Key 的安全存储和访问控制
 * 使用单例模式确保整个应用共享同一个实例
 */
class MasterKeyStore private constructor(context: Context) {
    
    private val sharedPreferences: SharedPreferences
    private val lock = ReentrantReadWriteLock()
    
    private val _isUnlockedFlow = MutableStateFlow(false)
    val isUnlockedFlow: StateFlow<Boolean> = _isUnlockedFlow.asStateFlow()
    
    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        sharedPreferences = EncryptedSharedPreferences.create(
            context.applicationContext,
            "pickeep_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    
    companion object {
        @Volatile
        private var INSTANCE: MasterKeyStore? = null
        
        // 内存中的 Master Key 缓存（单例级别）
        @Volatile
        private var masterKeyCache: ByteArray? = null
        
        fun getInstance(context: Context): MasterKeyStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MasterKeyStore(context).also { INSTANCE = it }
            }
        }
        
        // 常量定义
        private const val KEY_ITERATIONS = "kdf_iterations"
        private const val KEY_INITIALIZED = "initialized"
        private const val KEY_TEST_ENCRYPTED = "test_encrypted" // Legacy verification
        
        // New Architecture Constants
        private const val KEY_KEK_SALT = "kek_salt"
        private const val KEY_WRAPPED_MASTER_KEY = "wrapped_master_key"
        
        // 扩展函数
        private fun ByteArray.toBase64(): String = 
            android.util.Base64.encodeToString(this, android.util.Base64.NO_WRAP)
        
        private fun String.fromBase64(): ByteArray = 
            android.util.Base64.decode(this, android.util.Base64.NO_WRAP)
    }
    
    /**
     * 检查是否已初始化（是否存在 KDF 参数）
     */
    fun isInitialized(): Boolean = lock.read {
        sharedPreferences.contains(KEY_INITIALIZED)
    }
    
    /**
     * 初始化（新版，支持助记词）
     * 
     * @param password 用户密码
     * @param importMnemonic 导入的助记词（可选）
     * @return 助记词（如果是生成的）或导入的助记词
     */
    fun initialize(password: String, importMnemonic: List<String>? = null): List<String> = lock.write {
        require(!isInitialized()) { "已经初始化过" }
        
        val mnemonic = importMnemonic ?: KeyDerivation.generateMnemonic()
        val masterKey = KeyDerivation.restoreMasterKey(mnemonic) // 32 bytes from mnemonic
        
        // Generate KEK Salt
        val kekSalt = KeyDerivation.generateSalt()
        val iterations = 100_000
        
        // Derive KEK
        val kek = KeyDerivation.deriveMasterKey(password, kekSalt, iterations)
        
        // Wrap Master Key
        val wrappedMk = CekManager.encryptCek(masterKey, kek)
        
        // Create Verification Value (Test Encryption of a random CEK)
        val testCek = CekManager.generateCek()
        val testEncrypted = CekManager.encryptCek(testCek, masterKey)
        
        // Save
        sharedPreferences.edit()
            .putString(KEY_KEK_SALT, kekSalt.toBase64())
            .putString(KEY_WRAPPED_MASTER_KEY, wrappedMk.toBase64())
            .putString(KEY_TEST_ENCRYPTED, testEncrypted.toBase64())
            .putInt(KEY_ITERATIONS, iterations)
            .putBoolean(KEY_INITIALIZED, true)
            .apply()
        
        masterKeyCache = masterKey
        _isUnlockedFlow.value = true
        
        return mnemonic
    }
    
    /**
     * 解锁
     */
    fun unlock(password: String): Boolean = lock.write {
        require(isInitialized()) { "尚未初始化" }
        
        val kekSalt = sharedPreferences.getString(KEY_KEK_SALT, null)?.fromBase64() ?: return false
        val iterations = sharedPreferences.getInt(KEY_ITERATIONS, 100_000)
        val wrappedMk = sharedPreferences.getString(KEY_WRAPPED_MASTER_KEY, null)?.fromBase64() ?: return false
        
        try {
            val kek = KeyDerivation.deriveMasterKey(password, kekSalt, iterations)
            val masterKey = CekManager.decryptCek(wrappedMk, kek)
            
            // Verify
            if (!verifyMasterKey(masterKey)) {
                return false
            }
            
            masterKeyCache = masterKey
            _isUnlockedFlow.value = true
            return true
        } catch (e: Exception) {
            return false
        }
    }
    
    private fun verifyMasterKey(masterKey: ByteArray): Boolean {
        if (sharedPreferences.contains(KEY_TEST_ENCRYPTED)) {
            val testEncrypted = sharedPreferences.getString(KEY_TEST_ENCRYPTED, null)?.fromBase64() ?: return false
            try {
                CekManager.decryptCek(testEncrypted, masterKey)
                return true
            } catch (e: Exception) {
                return false
            }
        }
        return true // Should not happen for initialized store
    }
    
    /**
     * 锁定
     */
    fun lock() = lock.write {
        masterKeyCache?.fill(0)
        masterKeyCache = null
        _isUnlockedFlow.value = false
    }
    
    /**
     * 检查是否已解锁
     */
    fun isUnlocked(): Boolean = lock.read {
        masterKeyCache != null
    }
    
    /**
     * 获取 Master Key（必须先解锁）
     */
    fun getMasterKey(): ByteArray = lock.read {
        masterKeyCache?.copyOf() ?: throw IllegalStateException("Master Key 未解锁")
    }
    
    /**
     * 修改密码
     */
    fun changePassword(oldPassword: String, newPassword: String): Boolean = lock.write {
        if (!isUnlocked()) {
            if (!unlock(oldPassword)) {
                return false
            }
        }
        
        val masterKey = masterKeyCache ?: return false
        val iterations = sharedPreferences.getInt(KEY_ITERATIONS, 100_000)
        
        // Re-wrap with new password
        val newKekSalt = KeyDerivation.generateSalt()
        val newKek = KeyDerivation.deriveMasterKey(newPassword, newKekSalt, iterations)
        val newWrappedMk = CekManager.encryptCek(masterKey, newKek)
        
        sharedPreferences.edit()
            .putString(KEY_KEK_SALT, newKekSalt.toBase64())
            .putString(KEY_WRAPPED_MASTER_KEY, newWrappedMk.toBase64())
            .apply()
            
        return true
    }
    
    /**
     * 导出恢复数据
     * Deprecated: New architecture uses Mnemonic recovery. 
     * This method will return null for new architecture.
     */
    fun exportRecoveryData(): RecoveryData? = lock.read {
        return null
    }
    
    /**
     * 导出助记词 (Requires Unlock)
     */
    fun exportMnemonic(): List<String> = lock.read {
        val mk = getMasterKey()
        return Bip39.toMnemonic(mk)
    }

    /**
     * 从助记词导入恢复 (Legacy method name, repurposed or deprecated)
     * Actually we have importFromMnemonic in SettingsRepository which calls initialize.
     * This legacy method signature (RecoveryData) is no longer useful.
     * But to keep interface clean we can remove it or throw exception.
     */
    fun importFromRecovery(recoveryData: RecoveryData, password: String): Unit = lock.write {
        throw UnsupportedOperationException("Legacy recovery not supported")
    }
    
    fun importFromMnemonic(mnemonic: String, password: String) {
        val words = mnemonic.trim().split("\\s+".toRegex())
        initialize(password, words)
    }

    data class RecoveryData(
        val salt: String,
        val iterations: Int
    )
}
