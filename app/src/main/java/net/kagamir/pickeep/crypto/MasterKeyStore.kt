package net.kagamir.pickeep.crypto

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
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
    
    init {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        sharedPreferences = EncryptedSharedPreferences.create(
            "pickeep_secure_prefs",
            masterKeyAlias,
            context.applicationContext,
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
        private const val KEY_SALT = "kdf_salt"
        private const val KEY_ITERATIONS = "kdf_iterations"
        private const val KEY_INITIALIZED = "initialized"
        private const val KEY_TEST_ENCRYPTED = "test_encrypted"
        
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
        sharedPreferences.contains(KEY_SALT)
    }
    
    /**
     * 初始化（首次设置密码）
     * 
     * @param password 用户密码
     * @param iterations KDF 迭代次数
     */
    fun initialize(password: String, iterations: Int = 100_000) = lock.write {
        require(!isInitialized()) { "已经初始化过" }
        
        val salt = KeyDerivation.generateSalt()
        val masterKey = KeyDerivation.deriveMasterKey(password, salt, iterations)
        
        // 保存 KDF 参数（不保存 master key）
        sharedPreferences.edit()
            .putString(KEY_SALT, salt.toBase64())
            .putInt(KEY_ITERATIONS, iterations)
            .putBoolean(KEY_INITIALIZED, true)
            .apply()
        
        // 缓存到内存
        masterKeyCache = masterKey
    }
    
    /**
     * 解锁（用密码派生 Master Key）
     * 
     * @param password 用户密码
     * @return 是否解锁成功
     */
    fun unlock(password: String): Boolean = lock.write {
        require(isInitialized()) { "尚未初始化" }
        
        val salt = sharedPreferences.getString(KEY_SALT, null)
            ?.fromBase64() ?: return false
        val iterations = sharedPreferences.getInt(KEY_ITERATIONS, 100_000)
        
        try {
            val masterKey = KeyDerivation.deriveMasterKey(password, salt, iterations)
            
            // 验证密码（通过尝试解密一个测试值）
            if (sharedPreferences.contains(KEY_TEST_ENCRYPTED)) {
                val testEncrypted = sharedPreferences.getString(KEY_TEST_ENCRYPTED, null)
                    ?.fromBase64() ?: return false
                try {
                    CekManager.decryptCek(testEncrypted, masterKey)
                } catch (e: Exception) {
                    // 解密失败，密码错误
                    return false
                }
            } else {
                // 首次解锁，创建测试值
                val testCek = CekManager.generateCek()
                val testEncrypted = CekManager.encryptCek(testCek, masterKey)
                sharedPreferences.edit()
                    .putString(KEY_TEST_ENCRYPTED, testEncrypted.toBase64())
                    .apply()
            }
            
            masterKeyCache = masterKey
            return true
        } catch (e: Exception) {
            return false
        }
    }
    
    /**
     * 锁定（清除内存中的 Master Key）
     */
    fun lock() = lock.write {
        masterKeyCache?.fill(0)
        masterKeyCache = null
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
     * 
     * @param oldPassword 旧密码
     * @param newPassword 新密码
     * @return 是否成功
     */
    fun changePassword(oldPassword: String, newPassword: String): Boolean = lock.write {
        if (!unlock(oldPassword)) {
            return false
        }
        
        val salt = KeyDerivation.generateSalt()
        val iterations = sharedPreferences.getInt(KEY_ITERATIONS, 100_000)
        val newMasterKey = KeyDerivation.deriveMasterKey(newPassword, salt, iterations)
        
        // 重新加密测试值
        val testCek = CekManager.generateCek()
        val testEncrypted = CekManager.encryptCek(testCek, newMasterKey)
        
        sharedPreferences.edit()
            .putString(KEY_SALT, salt.toBase64())
            .putString(KEY_TEST_ENCRYPTED, testEncrypted.toBase64())
            .apply()
        
        masterKeyCache = newMasterKey
        return true
    }
    
    /**
     * 导出恢复码（用于备份/多设备）
     * 返回 salt 和 iterations，用户需要记住密码
     */
    fun exportRecoveryData(): RecoveryData? = lock.read {
        if (!isInitialized()) return null
        
        val salt = sharedPreferences.getString(KEY_SALT, null) ?: return null
        val iterations = sharedPreferences.getInt(KEY_ITERATIONS, 100_000)
        
        return RecoveryData(salt, iterations)
    }
    
    /**
     * 从恢复数据导入
     */
    fun importFromRecovery(recoveryData: RecoveryData, password: String) = lock.write {
        val salt = recoveryData.salt.fromBase64()
        val masterKey = KeyDerivation.deriveMasterKey(password, salt, recoveryData.iterations)
        
        sharedPreferences.edit()
            .putString(KEY_SALT, recoveryData.salt)
            .putInt(KEY_ITERATIONS, recoveryData.iterations)
            .putBoolean(KEY_INITIALIZED, true)
            .apply()
        
        // 创建测试值
        val testCek = CekManager.generateCek()
        val testEncrypted = CekManager.encryptCek(testCek, masterKey)
        sharedPreferences.edit()
            .putString(KEY_TEST_ENCRYPTED, testEncrypted.toBase64())
            .apply()
        
        masterKeyCache = masterKey
    }
    
    data class RecoveryData(
        val salt: String,  // Base64 编码
        val iterations: Int
    )
}

