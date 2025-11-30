package net.kagamir.pickeep.storage.webdav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.kagamir.pickeep.storage.RemoteFileInfo
import net.kagamir.pickeep.storage.StorageClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import okio.source
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * WebDAV 客户端实现
 */
class WebDavClient(
    private val baseUrl: String,
    private val username: String,
    private val password: String,
    private val timeout: Long = 60  // 秒
) : StorageClient {
    
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(timeout, TimeUnit.SECONDS)
        .readTimeout(timeout, TimeUnit.SECONDS)
        .writeTimeout(timeout, TimeUnit.SECONDS)
        .authenticator { _, response ->
            if (response.request.header("Authorization") != null) {
                return@authenticator null // 已经尝试过认证，放弃
            }
            val credential = okhttp3.Credentials.basic(username, password)
            response.request.newBuilder()
                .header("Authorization", credential)
                .build()
        }
        .build()
    
    private val credential = okhttp3.Credentials.basic(username, password)
    
    override suspend fun uploadFile(
        inputStream: InputStream,
        remotePath: String,
        fileSize: Long?,
        onProgress: ((Long, Long) -> Unit)?
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val url = normalizeUrl(remotePath)
            
            val requestBody = object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                
                override fun contentLength(): Long = fileSize ?: -1L
                
                override fun writeTo(sink: BufferedSink) {
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytesWritten = 0L
                    val total = fileSize ?: 0L
                    
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        sink.write(buffer, 0, bytesRead)
                        totalBytesWritten += bytesRead
                        onProgress?.invoke(totalBytesWritten, total)
                    }
                }
            }
            
            val request = Request.Builder()
                .url(url)
                .put(requestBody)
                .header("Authorization", credential)
                .build()
            
            val response = client.newCall(request).execute()
            response.use {
                if (it.isSuccessful || it.code == 201 || it.code == 204) {
                    val etag = it.header("ETag") ?: ""
                    Result.success(etag)
                } else {
                    Result.failure(IOException("上传失败: ${it.code} ${it.message}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun uploadMetadata(
        remotePath: String,
        metadata: ByteArray
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val url = normalizeUrl(remotePath)
            
            val requestBody = RequestBody.create(
                "application/octet-stream".toMediaType(),
                metadata
            )
            
            val request = Request.Builder()
                .url(url)
                .put(requestBody)
                .header("Authorization", credential)
                .build()
            
            val response = client.newCall(request).execute()
            response.use {
                if (it.isSuccessful || it.code == 201 || it.code == 204) {
                    Result.success(Unit)
                } else {
                    Result.failure(IOException("上传元数据失败: ${it.code} ${it.message}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun downloadFile(
        remotePath: String,
        onProgress: ((Long, Long) -> Unit)?
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val url = normalizeUrl(remotePath)
            
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Authorization", credential)
                .build()
            
            val response = client.newCall(request).execute()
            response.use {
                if (it.isSuccessful) {
                    val body = it.body ?: return@withContext Result.failure(
                        IOException("响应体为空")
                    )
                    val bytes = body.bytes()
                    Result.success(bytes)
                } else {
                    Result.failure(IOException("下载失败: ${it.code} ${it.message}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun listFiles(remotePath: String): Result<List<RemoteFileInfo>> =
        withContext(Dispatchers.IO) {
            try {
                val url = normalizeUrl(remotePath)
                
                val propfindBody = """
                    <?xml version="1.0" encoding="utf-8" ?>
                    <D:propfind xmlns:D="DAV:">
                        <D:prop>
                            <D:getcontentlength/>
                            <D:getlastmodified/>
                            <D:getetag/>
                            <D:resourcetype/>
                        </D:prop>
                    </D:propfind>
                """.trimIndent()
                
                val requestBody = RequestBody.create(
                    "application/xml; charset=utf-8".toMediaType(),
                    propfindBody
                )
                
                val request = Request.Builder()
                    .url(url)
                    .method("PROPFIND", requestBody)
                    .header("Authorization", credential)
                    .header("Depth", "1")
                    .build()
                
                val response = client.newCall(request).execute()
                response.use {
                    if (it.isSuccessful || it.code == 207) {
                        val body = it.body?.string() ?: ""
                        val files = parseWebDavResponse(body)
                        Result.success(files)
                    } else {
                        Result.failure(IOException("列出文件失败: ${it.code} ${it.message}"))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    
    override suspend fun deleteFile(remotePath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val url = normalizeUrl(remotePath)
                
                val request = Request.Builder()
                    .url(url)
                    .delete()
                    .header("Authorization", credential)
                    .build()
                
                val response = client.newCall(request).execute()
                response.use {
                    if (it.isSuccessful || it.code == 204) {
                        Result.success(Unit)
                    } else {
                        Result.failure(IOException("删除失败: ${it.code} ${it.message}"))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    
    override suspend fun checkConnection(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(baseUrl)
                .method("PROPFIND", RequestBody.create(null, ByteArray(0)))
                .header("Authorization", credential)
                .header("Depth", "0")
                .build()
            
            val response = client.newCall(request).execute()
            response.use {
                Result.success(it.isSuccessful || it.code == 207)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun createDirectory(remotePath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val url = normalizeUrl(remotePath)
                
                val request = Request.Builder()
                    .url(url)
                    .method("MKCOL", null)
                    .header("Authorization", credential)
                    .build()
                
                val response = client.newCall(request).execute()
                response.use {
                    if (it.isSuccessful || it.code == 201 || it.code == 405) {
                        // 405 表示目录已存在
                        Result.success(Unit)
                    } else {
                        Result.failure(IOException("创建目录失败: ${it.code} ${it.message}"))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    
    /**
     * 规范化 URL
     */
    private fun normalizeUrl(path: String): String {
        val cleanBase = baseUrl.trimEnd('/')
        val cleanPath = path.trimStart('/')
        return "$cleanBase/$cleanPath"
    }
    
    /**
     * 解析 WebDAV PROPFIND 响应
     * 简化版本，仅提取基本信息
     */
    private fun parseWebDavResponse(xml: String): List<RemoteFileInfo> {
        val files = mutableListOf<RemoteFileInfo>()
        
        // 简化的 XML 解析（生产环境应使用 XML 解析器）
        val responsePattern = """<D:response[^>]*>(.*?)</D:response>""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val hrefPattern = """<D:href[^>]*>(.*?)</D:href>""".toRegex()
        val sizePattern = """<D:getcontentlength[^>]*>(.*?)</D:getcontentlength>""".toRegex()
        val etagPattern = """<D:getetag[^>]*>(.*?)</D:getetag>""".toRegex()
        val modifiedPattern = """<D:getlastmodified[^>]*>(.*?)</D:getlastmodified>""".toRegex()
        val collectionPattern = """<D:collection\s*/>""".toRegex()
        
        responsePattern.findAll(xml).forEach { match ->
            val responseXml = match.groupValues[1]
            
            val href = hrefPattern.find(responseXml)?.groupValues?.get(1)?.trim() ?: return@forEach
            val size = sizePattern.find(responseXml)?.groupValues?.get(1)?.trim()?.toLongOrNull() ?: 0L
            val etag = etagPattern.find(responseXml)?.groupValues?.get(1)?.trim()?.removeSurrounding("\"")
            val modified = modifiedPattern.find(responseXml)?.groupValues?.get(1)?.trim() ?: ""
            val isDirectory = collectionPattern.containsMatchIn(responseXml)
            
            files.add(
                RemoteFileInfo(
                    path = href,
                    size = size,
                    etag = etag,
                    lastModified = parseHttpDate(modified),
                    isDirectory = isDirectory
                )
            )
        }
        
        return files
    }
    
    /**
     * 解析 HTTP 日期格式
     */
    private fun parseHttpDate(dateString: String): Long {
        return try {
            val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            format.parse(dateString)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}

