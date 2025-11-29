package net.kagamir.pickeep.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 内容加密密钥管理器
 * 负责 CEK 的生成、加密、解密
 */
object CekManager {
    
    private const val CEK_LENGTH_BYTES = 32  // 256-bit
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val TAG_LENGTH_BITS = 128
    private const val IV_LENGTH_BYTES = 12
    
    /**
     * 生成随机 CEK
     */
    fun generateCek(): ByteArray {
        val cek = ByteArray(CEK_LENGTH_BYTES)
        SecureRandom().nextBytes(cek)
        return cek
    }
    
    /**
     * 用 Master Key 加密 CEK
     * 
     * 格式: [IV (12 bytes)][Ciphertext][Tag (16 bytes)]
     * 
     * @param cek 待加密的 CEK
     * @param masterKey Master Key (256-bit)
     * @return 加密后的数据
     */
    fun encryptCek(cek: ByteArray, masterKey: ByteArray): ByteArray {
        require(cek.size == CEK_LENGTH_BYTES) { "CEK 长度必须为 $CEK_LENGTH_BYTES 字节" }
        require(masterKey.size == 32) { "Master Key 长度必须为 32 字节" }
        
        val iv = ByteArray(IV_LENGTH_BYTES)
        SecureRandom().nextBytes(iv)
        
        val cipher = Cipher.getInstance(ALGORITHM)
        val spec = GCMParameterSpec(TAG_LENGTH_BITS, iv)
        val secretKey = SecretKeySpec(masterKey, "AES")
        
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)
        val ciphertext = cipher.doFinal(cek)
        
        // 组合 IV 和密文
        return iv + ciphertext
    }
    
    /**
     * 用 Master Key 解密 CEK
     * 
     * @param encryptedCek 加密的 CEK（包含 IV）
     * @param masterKey Master Key (256-bit)
     * @return 解密后的 CEK
     */
    fun decryptCek(encryptedCek: ByteArray, masterKey: ByteArray): ByteArray {
        require(encryptedCek.size >= IV_LENGTH_BYTES + 16) { "加密数据长度不足" }
        require(masterKey.size == 32) { "Master Key 长度必须为 32 字节" }
        
        // 提取 IV 和密文
        val iv = encryptedCek.copyOfRange(0, IV_LENGTH_BYTES)
        val ciphertext = encryptedCek.copyOfRange(IV_LENGTH_BYTES, encryptedCek.size)
        
        val cipher = Cipher.getInstance(ALGORITHM)
        val spec = GCMParameterSpec(TAG_LENGTH_BITS, iv)
        val secretKey = SecretKeySpec(masterKey, "AES")
        
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        return cipher.doFinal(ciphertext)
    }
}

