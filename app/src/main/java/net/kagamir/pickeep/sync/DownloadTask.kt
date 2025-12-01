package net.kagamir.pickeep.sync

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.kagamir.pickeep.crypto.CekManager
import net.kagamir.pickeep.crypto.FileEncryptor
import net.kagamir.pickeep.crypto.MetadataEncryptor
import net.kagamir.pickeep.crypto.PhotoMetadata
import net.kagamir.pickeep.data.local.PicKeepDatabase
import net.kagamir.pickeep.data.local.entity.PhotoEntity
import net.kagamir.pickeep.storage.StorageClient
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream

/**
 * 下载步骤
 */
enum class DownloadStep {
    DOWNLOADING_FILE,      // 下载加密文件
    DOWNLOADING_METADATA,  // 下载元数据
    DECRYPTING_METADATA,   // 解密元数据
    GETTING_CEK,           // 获取 CEK
    DECRYPTING_FILE,       // 解密文件
    SAVING_FILE            // 保存文件
}

/**
 * 下载任务
 * 封装单个文件的下载、解密和保存流程
 */
class DownloadTask(
    private val context: Context,
    private val database: PicKeepDatabase,
    private val storageClient: StorageClient,
    private val masterKey: ByteArray
) {

    private val photoDao = database.photoDao()
    private val cekDao = database.cekDao()

    /**
     * 下载结果
     */
    data class DownloadResult(
        val success: Boolean,
        val localUri: Uri? = null,
        val errorMessage: String? = null
    )

    /**
     * 下载照片
     *
     * @param photo 照片实体（必须包含 remotePath 和 cekId）
     * @param onProgress 进度回调 (已下载字节, 总字节)
     * @param onStep 步骤回调 (步骤, 已处理字节, 总字节，总字节为null表示不确定)
     * @return 下载结果
     */
    suspend fun download(
        photo: PhotoEntity,
        onProgress: ((Long, Long) -> Unit)? = null,
        onStep: ((DownloadStep, Long, Long?) -> Unit)? = null
    ): Result<DownloadResult> = withContext(Dispatchers.IO) {
        var encryptedFileBytes: ByteArray? = null
        var metadataBytes: ByteArray? = null
        var decryptedFile: File? = null

        try {
            // 检查必要信息
            val remotePath = photo.remotePath
            if (remotePath.isNullOrBlank()) {
                throw Exception("远程路径为空")
            }

            // 1. 下载加密文件
            onStep?.invoke(DownloadStep.DOWNLOADING_FILE, 0, null)
            val fileDownloadResult = storageClient.downloadFile(remotePath) { downloaded, total ->
                onProgress?.invoke(downloaded, total)
                onStep?.invoke(DownloadStep.DOWNLOADING_FILE, downloaded, total)
            }

            if (fileDownloadResult.isFailure) {
                throw fileDownloadResult.exceptionOrNull() ?: Exception("下载加密文件失败")
            }

            encryptedFileBytes = fileDownloadResult.getOrNull()
            if (encryptedFileBytes == null || encryptedFileBytes.isEmpty()) {
                throw Exception("下载的文件为空")
            }

            // 2. 下载元数据
            onStep?.invoke(DownloadStep.DOWNLOADING_METADATA, 0, null)
            val remoteMetaPath = remotePath.replace(".enc", ".meta")
            val metaDownloadResult =
                storageClient.downloadFile(remoteMetaPath) { downloaded, total ->
                    onStep?.invoke(DownloadStep.DOWNLOADING_METADATA, downloaded, total)
                }

            if (metaDownloadResult.isFailure) {
                throw metaDownloadResult.exceptionOrNull() ?: Exception("下载元数据失败")
            }

            metadataBytes = metaDownloadResult.getOrNull()
            if (metadataBytes == null || metadataBytes.isEmpty()) {
                throw Exception("下载的元数据为空")
            }

            // 3. 解密元数据
            onStep?.invoke(DownloadStep.DECRYPTING_METADATA, 0, null)
            val metadata = decryptMetadata(metadataBytes, photo)

            // 4. 获取 CEK
            onStep?.invoke(DownloadStep.GETTING_CEK, 0, null)
            val cek = getCek(metadata.cekId)

            // 5. 解密文件
            onStep?.invoke(DownloadStep.DECRYPTING_FILE, 0, null)
            decryptedFile =
                File(context.cacheDir, "${System.currentTimeMillis()}_${metadata.originalName}")
            ByteArrayInputStream(encryptedFileBytes).use { input ->
                FileOutputStream(decryptedFile).use { output ->
                    FileEncryptor.decryptFile(input, output, cek)
                }
            }

            // 6. 保存文件到 MediaStore
            onStep?.invoke(DownloadStep.SAVING_FILE, 0, null)
            val savedUri = saveToMediaStore(decryptedFile, metadata)

            Result.success(DownloadResult(success = true, localUri = savedUri))

        } catch (e: Exception) {
            android.util.Log.e("DownloadTask", "下载失败", e)
            Result.failure(e)
        } finally {
            // 清理临时文件
            decryptedFile?.delete()
        }
    }

    /**
     * 仅下载并解密元数据（用于判断文件类型）
     *
     * @param photo 照片实体
     * @return 解密后的元数据
     */
    suspend fun downloadMetadataOnly(
        photo: PhotoEntity
    ): Result<PhotoMetadata> = withContext(Dispatchers.IO) {
        try {
            val remotePath = photo.remotePath
            if (remotePath.isNullOrBlank()) {
                throw Exception("远程路径为空")
            }

            // 下载元数据
            val remoteMetaPath = remotePath.replace(".enc", ".meta")
            val metaDownloadResult = storageClient.downloadFile(remoteMetaPath)
            if (metaDownloadResult.isFailure) {
                throw metaDownloadResult.exceptionOrNull() ?: Exception("下载元数据失败")
            }

            val metadataBytes = metaDownloadResult.getOrNull()
            if (metadataBytes == null || metadataBytes.isEmpty()) {
                throw Exception("下载的元数据为空")
            }

            // 解密元数据
            val metadata = decryptMetadata(metadataBytes, photo)
            Result.success(metadata)

        } catch (e: Exception) {
            android.util.Log.e("DownloadTask", "下载元数据失败", e)
            Result.failure(e)
        }
    }

    /**
     * 下载并解密到临时文件（用于预览）
     *
     * @param photo 照片实体
     * @return 临时文件路径
     */
    suspend fun downloadToTempFile(
        photo: PhotoEntity
    ): Result<String> = withContext(Dispatchers.IO) {
        var encryptedFileBytes: ByteArray? = null
        var metadataBytes: ByteArray? = null
        var decryptedFile: File? = null

        try {
            // 检查必要信息
            val remotePath = photo.remotePath
            if (remotePath.isNullOrBlank()) {
                throw Exception("远程路径为空")
            }

            // 1. 下载加密文件
            val fileDownloadResult = storageClient.downloadFile(remotePath)
            if (fileDownloadResult.isFailure) {
                throw fileDownloadResult.exceptionOrNull() ?: Exception("下载加密文件失败")
            }

            encryptedFileBytes = fileDownloadResult.getOrNull()
            if (encryptedFileBytes == null || encryptedFileBytes.isEmpty()) {
                throw Exception("下载的文件为空")
            }

            // 2. 下载元数据
            val remoteMetaPath = remotePath.replace(".enc", ".meta")
            val metaDownloadResult = storageClient.downloadFile(remoteMetaPath)
            if (metaDownloadResult.isFailure) {
                throw metaDownloadResult.exceptionOrNull() ?: Exception("下载元数据失败")
            }

            metadataBytes = metaDownloadResult.getOrNull()
            if (metadataBytes == null || metadataBytes.isEmpty()) {
                throw Exception("下载的元数据为空")
            }

            // 3. 解密元数据
            val metadata = decryptMetadata(metadataBytes, photo)

            // 4. 获取 CEK
            val cek = getCek(metadata.cekId)

            // 5. 解密文件到临时文件
            decryptedFile =
                File(context.cacheDir, "${System.currentTimeMillis()}_${metadata.originalName}")
            ByteArrayInputStream(encryptedFileBytes).use { input ->
                FileOutputStream(decryptedFile).use { output ->
                    FileEncryptor.decryptFile(input, output, cek)
                }
            }

            Result.success(decryptedFile.absolutePath)

        } catch (e: Exception) {
            android.util.Log.e("DownloadTask", "下载到临时文件失败", e)
            Result.failure(e)
        }
    }

    /**
     * 解密元数据
     */
    private suspend fun decryptMetadata(
        encryptedMetadata: ByteArray,
        photo: PhotoEntity
    ): PhotoMetadata {
        // 优先使用数据库中的 cekId
        val cekId = photo.cekId
        if (cekId.isNullOrBlank()) {
            throw Exception("CEK ID 为空，无法解密元数据")
        }

        // 获取并解密 CEK
        val cek = getCek(cekId)

        // 解密元数据
        return MetadataEncryptor.decryptMetadata(encryptedMetadata, cek)
    }

    /**
     * 获取并解密 CEK
     */
    private suspend fun getCek(cekId: String): ByteArray {
        val cekEntity = cekDao.getById(cekId)
        if (cekEntity == null) {
            throw Exception("找不到 CEK: $cekId")
        }

        return CekManager.decryptCek(cekEntity.encryptedKey, masterKey)
    }

    /**
     * 根据文件扩展名推断 MIME 类型
     */
    private fun inferMimeTypeFromExtension(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when (extension) {
            // 图片
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "heic", "heif" -> "image/heic"
            "bmp" -> "image/bmp"
            // 视频
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "3gp" -> "video/3gpp"
            "webm" -> "video/webm"
            else -> "application/octet-stream"
        }
    }

    /**
     * 获取有效的 MIME 类型（如果元数据中的类型无效，则根据扩展名推断）
     */
    private fun getValidMimeType(metadata: PhotoMetadata): String {
        val mimeType = metadata.mimeType
        // 如果 MIME 类型有效（是 image/ 或 video/），直接使用
        if (mimeType.startsWith("image/") || mimeType.startsWith("video/")) {
            return mimeType
        }
        // 否则根据文件扩展名推断
        return inferMimeTypeFromExtension(metadata.originalName)
    }

    /**
     * 保存文件到 MediaStore
     */
    private suspend fun saveToMediaStore(
        file: File,
        metadata: PhotoMetadata
    ): Uri = withContext(Dispatchers.IO) {
        val mimeType = getValidMimeType(metadata)
        val isImage = mimeType.startsWith("image/")
        val isVideo = mimeType.startsWith("video/")

        if (!isImage && !isVideo) {
            throw Exception("不支持的文件类型: $mimeType (文件: ${metadata.originalName})")
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, metadata.originalName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)  // 使用推断的 MIME 类型
            put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.MediaColumns.DATE_MODIFIED, metadata.timestamp / 1000)
            put(MediaStore.MediaColumns.SIZE, file.length())

            // 设置相对路径
            if (isImage) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PicKeep")
            } else {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/PicKeep")
            }
        }

        val collectionUri = if (isImage) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }

        // 检查文件是否已存在，如果存在则添加时间戳后缀
        var displayName = metadata.originalName
        val existingUri = findExistingFile(collectionUri, displayName, mimeType)

        if (existingUri != null) {
            // 文件已存在，添加时间戳后缀
            val nameWithoutExt = displayName.substringBeforeLast('.', displayName)
            val extension = if (displayName.contains('.')) {
                "." + displayName.substringAfterLast('.')
            } else {
                ""
            }
            val timestamp = System.currentTimeMillis()
            displayName = "${nameWithoutExt}_$timestamp$extension"

            // 更新 ContentValues 中的文件名
            contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        }

        // 始终创建新条目（即使文件已存在，我们也创建新的以避免权限问题）
        val uri = context.contentResolver.insert(collectionUri, contentValues)

        if (uri == null) {
            throw Exception("无法创建 MediaStore 条目")
        }

        // 写入文件内容
        // 注意：在 Android 10+ 上，通过 insert 创建的新 URI 应该可以直接写入
        context.contentResolver.openOutputStream(uri, "w")?.use { output ->
            file.inputStream().use { input ->
                input.copyTo(output)
            }
        } ?: throw Exception("无法打开输出流：URI = $uri")

        // 如果是图片，尝试保存 EXIF 数据（如果有地理位置信息）
        if (isImage && metadata.location != null) {
            try {
                saveExifData(uri, metadata)
            } catch (e: Exception) {
                android.util.Log.w("DownloadTask", "保存 EXIF 数据失败", e)
                // 不影响主流程，继续
            }
        }

        uri
    }

    /**
     * 查找已存在的文件
     */
    private fun findExistingFile(
        collectionUri: Uri,
        displayName: String,
        mimeType: String
    ): Uri? {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection =
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.MIME_TYPE} = ?"
        val selectionArgs = arrayOf(displayName, mimeType)

        context.contentResolver.query(
            collectionUri,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                return Uri.withAppendedPath(collectionUri, id.toString())
            }
        }

        return null
    }

    /**
     * 保存 EXIF 数据（地理位置）
     */
    private fun saveExifData(uri: Uri, metadata: PhotoMetadata) {
        val location = metadata.location ?: return

        // 使用 ExifInterface 保存地理位置
        // 注意：需要从 URI 获取文件路径，或者使用其他方法
        // 这里简化处理，实际可能需要更复杂的实现
        context.contentResolver.openInputStream(uri)?.use { input ->
            // 对于 Android 10+，可能需要使用不同的方法
            // 这里暂时跳过详细的 EXIF 写入
            android.util.Log.d("DownloadTask", "EXIF 数据保存需要文件路径，URI 方式暂不支持")
        }
    }
}

