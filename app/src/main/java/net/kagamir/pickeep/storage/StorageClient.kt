package net.kagamir.pickeep.storage

import java.io.InputStream

/**
 * 存储客户端接口
 * 定义与远程存储交互的标准操作
 */
interface StorageClient {
    
    /**
     * 上传文件
     * 
     * @param inputStream 输入流
     * @param remotePath 远程路径
     * @param fileSize 文件大小（可选，用于显示进度）
     * @param onProgress 进度回调 (已上传字节, 总字节)
     * @return Result<ETag>
     */
    suspend fun uploadFile(
        inputStream: InputStream,
        remotePath: String,
        fileSize: Long? = null,
        onProgress: ((Long, Long) -> Unit)? = null
    ): Result<String>
    
    /**
     * 上传元数据
     * 
     * @param remotePath 远程路径
     * @param metadata 元数据字节数组
     * @return Result<Unit>
     */
    suspend fun uploadMetadata(
        remotePath: String,
        metadata: ByteArray
    ): Result<Unit>
    
    /**
     * 下载文件
     * 
     * @param remotePath 远程路径
     * @param onProgress 进度回调 (已下载字节, 总字节)
     * @return Result<ByteArray>
     */
    suspend fun downloadFile(
        remotePath: String,
        onProgress: ((Long, Long) -> Unit)? = null
    ): Result<ByteArray>
    
    /**
     * 列出文件
     * 
     * @param remotePath 远程目录路径
     * @return Result<List<RemoteFileInfo>>
     */
    suspend fun listFiles(remotePath: String): Result<List<RemoteFileInfo>>
    
    /**
     * 删除文件
     * 
     * @param remotePath 远程路径
     * @return Result<Unit>
     */
    suspend fun deleteFile(remotePath: String): Result<Unit>
    
    /**
     * 检查连接
     * 
     * @return Result<Boolean>
     */
    suspend fun checkConnection(): Result<Boolean>
    
    /**
     * 创建目录
     * 
     * @param remotePath 远程目录路径
     * @return Result<Unit>
     */
    suspend fun createDirectory(remotePath: String): Result<Unit>
}

