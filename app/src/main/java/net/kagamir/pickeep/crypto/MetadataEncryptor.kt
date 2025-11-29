package net.kagamir.pickeep.crypto

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 元数据加密器
 * 使用 AES-256-GCM 加密照片元数据
 */
object MetadataEncryptor {
    
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val TAG_LENGTH_BITS = 128
    private const val IV_LENGTH_BYTES = 12
    
    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
    }
    
    /**
     * 加密元数据
     * 
     * 格式: [IV (12 bytes)][Ciphertext][Tag (16 bytes)]
     * 
     * @param metadata 照片元数据
     * @param cek 内容加密密钥
     * @return 加密后的字节数组
     */
    fun encryptMetadata(metadata: PhotoMetadata, cek: ByteArray): ByteArray {
        require(cek.size == 32) { "CEK 长度必须为 32 字节" }
        
        // 序列化为 JSON
        val jsonString = json.encodeToString(metadata)
        val plaintext = jsonString.toByteArray(Charsets.UTF_8)
        
        val iv = ByteArray(IV_LENGTH_BYTES)
        SecureRandom().nextBytes(iv)
        
        val cipher = Cipher.getInstance(ALGORITHM)
        val spec = GCMParameterSpec(TAG_LENGTH_BITS, iv)
        val secretKey = SecretKeySpec(cek, "AES")
        
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)
        val ciphertext = cipher.doFinal(plaintext)
        
        // 组合 IV 和密文
        return iv + ciphertext
    }
    
    /**
     * 解密元数据
     * 
     * @param encryptedData 加密的元数据（包含 IV）
     * @param cek 内容加密密钥
     * @return 解密后的元数据对象
     */
    fun decryptMetadata(encryptedData: ByteArray, cek: ByteArray): PhotoMetadata {
        require(cek.size == 32) { "CEK 长度必须为 32 字节" }
        require(encryptedData.size >= IV_LENGTH_BYTES + 16) { "加密数据长度不足" }
        
        // 提取 IV 和密文
        val iv = encryptedData.copyOfRange(0, IV_LENGTH_BYTES)
        val ciphertext = encryptedData.copyOfRange(IV_LENGTH_BYTES, encryptedData.size)
        
        val cipher = Cipher.getInstance(ALGORITHM)
        val spec = GCMParameterSpec(TAG_LENGTH_BITS, iv)
        val secretKey = SecretKeySpec(cek, "AES")
        
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        val plaintext = cipher.doFinal(ciphertext)
        
        // 反序列化 JSON
        val jsonString = String(plaintext, Charsets.UTF_8)
        return json.decodeFromString(jsonString)
    }
}

