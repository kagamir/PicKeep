package net.kagamir.pickeep.storage.webdav

import net.kagamir.pickeep.storage.StorageClient
import java.io.File
import java.io.FileInputStream

/**
 * 分片上传器
 * 用于大文件的分片上传
 */
class ChunkedUploader(
    private val storageClient: StorageClient,
    private val chunkSizeBytes: Long = 5 * 1024 * 1024  // 5MB
) {
    
    /**
     * 上传文件（支持分片上传）
     * 如果文件上传失败，下次会重新覆盖上传
     * 
     * @param file 本地文件
     * @param remotePath 远程路径
     * @param onProgress 进度回调 (已上传字节, 总字节)
     * @return Result<ETag>
     */
    suspend fun uploadFile(
        file: File,
        remotePath: String,
        onProgress: ((Long, Long) -> Unit)? = null
    ): Result<String> {
        val fileSize = file.length()
        
        // 小文件直接上传
        if (fileSize <= chunkSizeBytes) {
            return FileInputStream(file).use { inputStream ->
                storageClient.uploadFile(inputStream, remotePath, fileSize, onProgress)
            }
        }
        
        // 大文件分片上传
        return uploadInChunks(file, remotePath, onProgress)
    }
    
    /**
     * 分片上传
     * 由于 WebDAV 可能不支持 Range 上传，这里使用流式上传来减少内存占用
     * 通过分块读取文件并流式上传，避免将整个文件加载到内存
     */
    private suspend fun uploadInChunks(
        file: File,
        remotePath: String,
        onProgress: ((Long, Long) -> Unit)?
    ): Result<String> {
        val fileSize = file.length()
        
        return try {
            // 使用流式上传，分块读取以减少内存占用
            // WebDAV 虽然不支持真正的分片上传，但我们可以通过流式上传来减少内存压力
            FileInputStream(file).use { inputStream ->
                // 直接使用 storageClient 的流式上传功能
                // 它内部会分块读取，减少内存占用
                val result = storageClient.uploadFile(
                    inputStream,
                    remotePath,
                    fileSize,
                    onProgress
                )
                
                result
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
}

