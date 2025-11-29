package net.kagamir.pickeep.crypto

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 文件加密器
 * 使用 AES-256-GCM 流式加密文件
 */
object FileEncryptor {
    
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val TAG_LENGTH_BITS = 128
    private const val IV_LENGTH_BYTES = 12
    private const val CHUNK_SIZE = 64 * 1024  // 64KB
    private const val VERSION = 1.toByte()
    
    /**
     * 加密文件
     * 
     * 格式: [Version (1 byte)][IV (12 bytes)][Ciphertext...][Tag (16 bytes)]
     * 
     * @param inputStream 输入流（原始文件）
     * @param outputStream 输出流（加密文件）
     * @param cek 内容加密密钥
     * @return 加密前文件的 SHA-256 哈希
     */
    fun encryptFile(
        inputStream: InputStream,
        outputStream: OutputStream,
        cek: ByteArray
    ): String {
        require(cek.size == 32) { "CEK 长度必须为 32 字节" }
        
        val iv = ByteArray(IV_LENGTH_BYTES)
        SecureRandom().nextBytes(iv)
        
        val cipher = Cipher.getInstance(ALGORITHM)
        val spec = GCMParameterSpec(TAG_LENGTH_BITS, iv)
        val secretKey = SecretKeySpec(cek, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)
        
        // 写入版本号和 IV
        outputStream.write(VERSION.toInt())
        outputStream.write(iv)
        
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(CHUNK_SIZE)
        var bytesRead: Int
        
        inputStream.use { input ->
            outputStream.use { output ->
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    // 计算原始数据的哈希
                    digest.update(buffer, 0, bytesRead)
                    
                    // 加密数据块
                    val encrypted = cipher.update(buffer, 0, bytesRead)
                    if (encrypted != null && encrypted.isNotEmpty()) {
                        output.write(encrypted)
                    }
                }
                
                // 写入最后的数据块和认证标签
                val finalBytes = cipher.doFinal()
                if (finalBytes.isNotEmpty()) {
                    output.write(finalBytes)
                }
            }
        }
        
        // 返回 SHA-256 哈希（十六进制）
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    
    /**
     * 解密文件
     * 
     * @param inputStream 输入流（加密文件）
     * @param outputStream 输出流（解密文件）
     * @param cek 内容加密密钥
     */
    fun decryptFile(
        inputStream: InputStream,
        outputStream: OutputStream,
        cek: ByteArray
    ) {
        require(cek.size == 32) { "CEK 长度必须为 32 字节" }
        
        inputStream.use { input ->
            // 读取版本号
            val version = input.read().toByte()
            require(version == VERSION) { "不支持的文件版本: $version" }
            
            // 读取 IV
            val iv = ByteArray(IV_LENGTH_BYTES)
            val ivRead = input.read(iv)
            require(ivRead == IV_LENGTH_BYTES) { "无法读取 IV" }
            
            val cipher = Cipher.getInstance(ALGORITHM)
            val spec = GCMParameterSpec(TAG_LENGTH_BITS, iv)
            val secretKey = SecretKeySpec(cek, "AES")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            
            val buffer = ByteArray(CHUNK_SIZE)
            var bytesRead: Int
            
            outputStream.use { output ->
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    val decrypted = cipher.update(buffer, 0, bytesRead)
                    if (decrypted != null && decrypted.isNotEmpty()) {
                        output.write(decrypted)
                    }
                }
                
                // 处理最后的数据块并验证认证标签
                val finalBytes = cipher.doFinal()
                if (finalBytes.isNotEmpty()) {
                    output.write(finalBytes)
                }
            }
        }
    }
    
    /**
     * 计算文件的 SHA-256 哈希
     */
    fun calculateHash(inputStream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(CHUNK_SIZE)
        var bytesRead: Int
        
        inputStream.use { input ->
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

