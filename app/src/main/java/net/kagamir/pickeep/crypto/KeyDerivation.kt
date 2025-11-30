package net.kagamir.pickeep.crypto

import java.io.File
import java.io.FileInputStream
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 密钥派生工具
 * 使用 PBKDF2-HMAC-SHA256 从用户密码派生 Master Key
 */
object KeyDerivation {
    
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val KEY_LENGTH_BITS = 256
    private const val DEFAULT_ITERATIONS = 100_000  // OWASP 推荐值
    private const val SALT_LENGTH_BYTES = 32
    
    /**
     * 生成随机盐值
     */
    fun generateSalt(): ByteArray {
        val salt = ByteArray(SALT_LENGTH_BYTES)
        SecureRandom().nextBytes(salt)
        return salt
    }
    
    /**
     * 从密码派生 Master Key
     * 
     * @param password 用户密码
     * @param salt 盐值
     * @param iterations 迭代次数（默认 100,000）
     * @return 256-bit Master Key
     */
    fun deriveMasterKey(
        password: String,
        salt: ByteArray,
        iterations: Int = DEFAULT_ITERATIONS
    ): ByteArray {
        require(password.isNotEmpty()) { "密码不能为空" }
        require(salt.size >= 16) { "盐值长度至少 16 字节" }
        require(iterations > 0) { "迭代次数必须大于 0" }
        
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance(ALGORITHM)
        return factory.generateSecret(spec).encoded
    }
    
    /**
     * 验证密码强度
     * 
     * @return 密码强度描述（弱、中、强）或 null（合格）
     */
    fun validatePasswordStrength(password: String): String? {
        // 已移除密码强度要求，允许用户使用任意密码
        // 仅检查密码不为空
        return if (password.isEmpty()) {
            "密码不能为空"
        } else {
            null // 合格
        }
    }
    
    /**
     * 生成文件名哈希盐
     * 从 Master Key 派生一个专用的盐
     */
    fun deriveFilenameSalt(masterKey: ByteArray): ByteArray {
        val hmac = javax.crypto.Mac.getInstance("HmacSHA256")
        val secretKey = javax.crypto.spec.SecretKeySpec(masterKey, "HmacSHA256")
        hmac.init(secretKey)
        return hmac.doFinal("filename_salt".toByteArray())
    }

    /**
     * 计算文件内容的哈希
     * HMAC-SHA256(Content, Salt)
     */
    fun calculateFileHash(file: java.io.File, salt: ByteArray): String {
        val hmac = javax.crypto.Mac.getInstance("HmacSHA256")
        val secretKey = javax.crypto.spec.SecretKeySpec(salt, "HmacSHA256")
        hmac.init(secretKey)
        
        val buffer = ByteArray(8192)
        java.io.FileInputStream(file).use { input ->
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                hmac.update(buffer, 0, bytesRead)
            }
        }
        
        val hash = hmac.doFinal()
        return hash.joinToString("") { "%02x".format(it) }
    }
    
    /**
     * 生成助记词（使用 BIP39）
     */
    fun generateMnemonic(): List<String> {
        val entropy = ByteArray(16) // 128 bits -> 12 words
        SecureRandom().nextBytes(entropy)
        return Bip39.toMnemonic(entropy)
    }

    /**
     * 从助记词恢复 Master Key
     */
    fun restoreMasterKey(mnemonic: List<String>): ByteArray {
        // BIP39 seed (512 bits) -> Take first 256 bits as Master Key
        val seed = Bip39.toSeed(mnemonic)
        return seed.copyOf(32)
    }

    /**
     * 生成助记词（简化版本，实际可用 BIP39）
     * 这里使用 Base32 编码的随机字节作为恢复码
     */
    fun generateRecoveryCode(): String {
        val bytes = ByteArray(20)  // 160 bits
        SecureRandom().nextBytes(bytes)
        return encodeBase32(bytes)
    }
    
    /**
     * Base32 编码（简化版，仅用于恢复码）
     */
    private fun encodeBase32(data: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val result = StringBuilder()
        var buffer = 0
        var bitsLeft = 0
        
        for (byte in data) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                result.append(alphabet[(buffer shr (bitsLeft - 5)) and 0x1F])
                bitsLeft -= 5
            }
        }
        
        if (bitsLeft > 0) {
            result.append(alphabet[(buffer shl (5 - bitsLeft)) and 0x1F])
        }
        
        // 格式化为分组形式（每 4 个字符一组）
        return result.chunked(4).joinToString("-")
    }
}
