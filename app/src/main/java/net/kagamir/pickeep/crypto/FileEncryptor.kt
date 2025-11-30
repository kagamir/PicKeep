package net.kagamir.pickeep.crypto

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import org.bouncycastle.jce.provider.BouncyCastleProvider
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
    private val BC_PROVIDER by lazy { BouncyCastleProvider() }
    
    /**
     * 加密文件（使用文件路径，自动检测视频文件）
     * 
     * 格式: [Version (1 byte)][IV (12 bytes)][Ciphertext...][Tag (16 bytes)]
     * 
     * @param filePath 文件路径
     * @param outputStream 输出流（加密文件）
     * @param cek 内容加密密钥
     * @return 加密前文件的哈希（视频使用指纹，其他使用SHA-256）
     */
    fun encryptFile(
        filePath: String,
        outputStream: OutputStream,
        cek: ByteArray
    ): String {
        val file = File(filePath)
        if (!file.exists()) {
            throw IllegalArgumentException("文件不存在: $filePath")
        }
        
        // 检测是否为视频文件
        val isVideo = isVideoFile(filePath)
        
        // 如果是视频文件，使用视频指纹计算
        val hash = if (isVideo) {
            try {
                VideoFingerprintCalculator.calculateFingerprint(filePath)
            } catch (e: Exception) {
                // 如果视频指纹计算失败，回退到SHA-256
                FileInputStream(file).use { input ->
                    calculateHash(input)
                }
            }
        } else {
            // 非视频文件使用SHA-256
            FileInputStream(file).use { input ->
                calculateHash(input)
            }
        }
        
        // 加密文件
        FileInputStream(file).use { input ->
            encryptFileStream(input, outputStream, cek)
        }
        
        return hash
    }
    
    /**
     * 加密文件（使用输入流）
     * 
     * 格式: [Version (1 byte)][IV (12 bytes)][Ciphertext...][Tag (16 bytes)]
     * 
     * 注意：此方法会同时计算哈希和加密，需要读取输入流两次。
     * 如果输入流不支持reset，建议使用文件路径版本的encryptFile方法。
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
        
        val cipher = Cipher.getInstance(ALGORITHM, BC_PROVIDER)
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
     * 加密文件流（内部方法，不计算哈希）
     */
    private fun encryptFileStream(
        inputStream: InputStream,
        outputStream: OutputStream,
        cek: ByteArray
    ) {
        require(cek.size == 32) { "CEK 长度必须为 32 字节" }
        
        val iv = ByteArray(IV_LENGTH_BYTES)
        SecureRandom().nextBytes(iv)
        
        val cipher = Cipher.getInstance(ALGORITHM, BC_PROVIDER)
        val spec = GCMParameterSpec(TAG_LENGTH_BITS, iv)
        val secretKey = SecretKeySpec(cek, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)
        
        // 写入版本号和 IV
        outputStream.write(VERSION.toInt())
        outputStream.write(iv)
        
        val buffer = ByteArray(CHUNK_SIZE)
        val outputBuffer = ByteArray(cipher.getOutputSize(CHUNK_SIZE))
        var bytesRead: Int
        
        inputStream.use { input ->
            outputStream.use { output ->
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    // 加密数据块（写入可复用的输出缓冲区）
                    val outLen = cipher.update(buffer, 0, bytesRead, outputBuffer, 0)
                    if (outLen > 0) {
                        output.write(outputBuffer, 0, outLen)
                    }
                }
                
                // 写入最后的数据块和认证标签
                val finalBytes = cipher.doFinal()
                if (finalBytes.isNotEmpty()) {
                    output.write(finalBytes)
                }
            }
        }
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
            
            val cipher = Cipher.getInstance(ALGORITHM, BC_PROVIDER)
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
    
    /**
     * 计算视频文件的指纹（使用文件路径）
     */
    fun calculateVideoFingerprint(filePath: String): String {
        return VideoFingerprintCalculator.calculateFingerprint(filePath)
    }
    
    /**
     * 检测文件是否为视频文件
     */
    private fun isVideoFile(filePath: String): Boolean {
        val extension = filePath.substringAfterLast('.', "").lowercase()
        return extension in setOf("mp4", "mkv", "avi", "mov", "3gp", "webm", "flv", "wmv", "m4v")
    }
}

