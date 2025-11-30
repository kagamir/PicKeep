package net.kagamir.pickeep.crypto

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import java.io.File
import java.security.MessageDigest
import kotlin.math.*

/**
 * 视频指纹计算器
 * 通过抽帧、图像指纹、时序指纹和运动指纹计算视频的唯一标识
 * 输出格式与SHA-256相同（64字符十六进制字符串）
 */
object VideoFingerprintCalculator {
    
    private const val FRAME_SIZE = 64  // 64x64
    private const val FRAME_RATE = 1.0  // 每秒1帧
    private const val DCT_DIMENSION = 32  // DCT压缩至32维
    private const val OPTICAL_FLOW_BINS = 12  // 光流方向直方图bins数量
    
    /**
     * 计算视频指纹
     * 
     * @param videoPath 视频文件路径
     * @return 64字符十六进制字符串（格式同SHA-256）
     */
    fun calculateFingerprint(videoPath: String): String {
        // 确保OpenCV已初始化
        if (!OpenCVLoader.initLocal()) {
            throw IllegalStateException("OpenCV未初始化")
        }
        
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(videoPath)
            
            // 获取视频时长（微秒）
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLongOrNull() ?: 0L
            val durationSeconds = durationMs / 1000.0
            
            if (durationSeconds <= 0) {
                throw IllegalArgumentException("无效的视频时长")
            }
            
            // 计算需要抽取的帧数
            val frameCount = maxOf(1, (durationSeconds * FRAME_RATE).toInt())
            
            // 1. 抽帧并计算图像指纹
            val frameHashes = mutableListOf<ByteArray>()
            val frames = mutableListOf<Mat>()
            
            for (i in 0 until frameCount) {
                val timeMs = (i / FRAME_RATE * 1000).toLong()
                val bitmap = retriever.getFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                
                if (bitmap != null) {
                    val frame = bitmapToGrayscaleMat(bitmap, FRAME_SIZE)
                    frames.add(frame)
                    
                    // 计算三种哈希
                    val pHash = calculatePHash(frame)
                    val dHash = calculateDHash(frame)
                    val aHash = calculateAHash(frame)
                    
                    // 拼接三种哈希（每个8字节，共24字节）
                    val combinedHash = ByteArray(24)
                    pHash.copyInto(combinedHash, 0)
                    dHash.copyInto(combinedHash, 8)
                    aHash.copyInto(combinedHash, 16)
                    frameHashes.add(combinedHash)
                    
                    bitmap.recycle()
                }
            }
            
            if (frames.isEmpty()) {
                throw IllegalArgumentException("无法从视频中提取帧")
            }
            
            // 2. 计算时序指纹（帧间差异能量序列 → DCT压缩至32维）
            val temporalFingerprint = calculateTemporalFingerprint(frames)
            
            // 3. 计算运动指纹（LK光流 → 方向直方图）
            val motionFingerprint = calculateMotionFingerprint(frames)
            
            // 4. 拼接所有指纹数据
            val totalSize = frameHashes.sumOf { it.size } + temporalFingerprint.size + motionFingerprint.size
            val fingerprintData = ByteArray(totalSize)
            var offset = 0
            
            // 哈希序列
            frameHashes.forEach { hash ->
                hash.copyInto(fingerprintData, offset)
                offset += hash.size
            }
            
            // 时序指纹
            temporalFingerprint.copyInto(fingerprintData, offset)
            offset += temporalFingerprint.size
            
            // 运动指纹
            motionFingerprint.copyInto(fingerprintData, offset)
            
            // 5. 计算SHA-256并返回64字符十六进制字符串
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(fingerprintData)
            val hashBytes = digest.digest()
            
            // 清理资源
            frames.forEach { it.release() }
            
            return hashBytes.joinToString("") { "%02x".format(it) }
            
        } finally {
            retriever.release()
        }
    }
    
    /**
     * 将Bitmap转换为64x64灰度Mat
     */
    private fun bitmapToGrayscaleMat(bitmap: Bitmap, size: Int): Mat {
        // 缩放并转换为灰度
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, size, size, true)
        val mat = Mat()
        Utils.bitmapToMat(scaledBitmap, mat)
        
        // 转换为灰度
        if (mat.channels() > 1) {
            val grayMat = Mat()
            Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_RGB2GRAY)
            mat.release()
            scaledBitmap.recycle()
            return grayMat
        }
        
        scaledBitmap.recycle()
        return mat
    }
    
    /**
     * 计算pHash（感知哈希）
     */
    private fun calculatePHash(mat: Mat): ByteArray {
        // 缩放至32x32
        val resized = Mat()
        Imgproc.resize(mat, resized, Size(32.0, 32.0))
        
        // DCT变换
        val dctMat = Mat()
        resized.convertTo(dctMat, CvType.CV_32F)
        Core.dct(dctMat, dctMat)
        
        // 取左上角8x8区域
        val roi = Mat(dctMat, Rect(0, 0, 8, 8))
        val mean = Core.mean(roi).`val`[0]
        
        // 生成64位哈希
        val hash = LongArray(1)
        var bitIndex = 0
        
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val value = dctMat.get(y, x)[0]
                if (value > mean) {
                    hash[0] = hash[0] or (1L shl bitIndex)
                }
                bitIndex++
            }
        }
        
        resized.release()
        dctMat.release()
        roi.release()
        
        // 转换为8字节
        return longToBytes(hash[0])
    }
    
    /**
     * 计算dHash（差异哈希）
     */
    private fun calculateDHash(mat: Mat): ByteArray {
        // 缩放至9x8（用于计算8x8的差异）
        val resized = Mat()
        Imgproc.resize(mat, resized, Size(9.0, 8.0))
        
        val hash = LongArray(1)
        var bitIndex = 0
        
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val left = resized.get(y, x)[0]
                val right = resized.get(y, x + 1)[0]
                if (left > right) {
                    hash[0] = hash[0] or (1L shl bitIndex)
                }
                bitIndex++
            }
        }
        
        resized.release()
        return longToBytes(hash[0])
    }
    
    /**
     * 计算aHash（平均哈希）
     */
    private fun calculateAHash(mat: Mat): ByteArray {
        // 缩放至8x8
        val resized = Mat()
        Imgproc.resize(mat, resized, Size(8.0, 8.0))
        
        // 计算平均值
        val mean = Core.mean(resized).`val`[0]
        
        val hash = LongArray(1)
        var bitIndex = 0
        
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val value = resized.get(y, x)[0]
                if (value > mean) {
                    hash[0] = hash[0] or (1L shl bitIndex)
                }
                bitIndex++
            }
        }
        
        resized.release()
        return longToBytes(hash[0])
    }
    
    /**
     * 计算时序指纹：帧间差异能量序列 → DCT压缩至32维
     */
    private fun calculateTemporalFingerprint(frames: List<Mat>): ByteArray {
        if (frames.size < 2) {
            // 如果只有一帧，返回零填充的32字节
            return ByteArray(DCT_DIMENSION)
        }
        
        // 计算帧间差异能量
        val differences = mutableListOf<Double>()
        for (i in 1 until frames.size) {
            val diff = Mat()
            Core.absdiff(frames[i - 1], frames[i], diff)
            val energy = Core.sumElems(diff).`val`[0] / (diff.rows() * diff.cols())
            differences.add(energy)
            diff.release()
        }
        
        // 如果差异序列长度小于32，进行填充或截断
        val sequence = if (differences.size < DCT_DIMENSION) {
            differences + List(DCT_DIMENSION - differences.size) { 0.0 }
        } else {
            differences.take(DCT_DIMENSION)
        }
        
        // 简化的DCT变换（使用FFT或直接计算）
        // 这里使用简化的DCT实现
        val dctCoeffs = DoubleArray(DCT_DIMENSION)
        val N = sequence.size
        
        for (k in 0 until DCT_DIMENSION) {
            var sum = 0.0
            for (n in 0 until N) {
                sum += sequence[n] * cos(PI * k * (2 * n + 1) / (2.0 * N))
            }
            dctCoeffs[k] = sum * (if (k == 0) sqrt(1.0 / N) else sqrt(2.0 / N))
        }
        
        // 转换为字节数组（32个double，每个8字节，共256字节）
        // 但为了保持合理大小，我们只取前32个系数的量化值
        val result = ByteArray(DCT_DIMENSION)
        val maxCoeff = dctCoeffs.maxOrNull()?.absoluteValue ?: 1.0
        val scale = if (maxCoeff > 0) 127.0 / maxCoeff else 1.0
        
        for (i in 0 until DCT_DIMENSION) {
            result[i] = (dctCoeffs[i] * scale).toInt().coerceIn(-128, 127).toByte()
        }
        
        return result
    }
    
    /**
     * 计算运动指纹：LK光流 → 方向直方图（12 bins）
     */
    private fun calculateMotionFingerprint(frames: List<Mat>): ByteArray {
        if (frames.size < 2) {
            // 如果只有一帧，返回零填充的12字节
            return ByteArray(OPTICAL_FLOW_BINS)
        }
        
        val histogram = IntArray(OPTICAL_FLOW_BINS)
        
        for (i in 1 until frames.size) {
            val prevFrame = frames[i - 1]
            val currFrame = frames[i]
            
            // 使用LK光流计算运动向量
            val prevPts = MatOfPoint2f()
            val currPts = MatOfPoint2f()
            val status = MatOfByte()
            val err = MatOfFloat()
            
            // 创建特征点网格（每8x8像素一个点）
            val points = mutableListOf<Point>()
            for (y in 8 until prevFrame.rows() step 8) {
                for (x in 8 until prevFrame.cols() step 8) {
                    points.add(Point(x.toDouble(), y.toDouble()))
                }
            }
            
            if (points.isNotEmpty()) {
                prevPts.fromList(points)
                
                // 计算光流
                val criteria = TermCriteria(
                    TermCriteria.EPS or TermCriteria.COUNT,
                    30,
                    0.01
                )
                Video.calcOpticalFlowPyrLK(
                    prevFrame, currFrame,
                    prevPts, currPts,
                    status, err,
                    Size(21.0, 21.0), 3,
                    criteria
                )
                
                // 统计方向直方图
                val statusArray = status.toArray()
                val prevArray = prevPts.toArray()
                val currArray = currPts.toArray()
                
                for (j in statusArray.indices) {
                    if (statusArray[j] == 1.toByte()) {
                        val dx = currArray[j].x - prevArray[j].x
                        val dy = currArray[j].y - prevArray[j].y
                        
                        if (abs(dx) > 0.1 || abs(dy) > 0.1) {
                            val angle = atan2(dy, dx)
                            // 将角度转换为0-2π范围，然后映射到bins
                            val normalizedAngle = (angle + PI) / (2 * PI)  // 0-1范围
                            val bin = (normalizedAngle * OPTICAL_FLOW_BINS).toInt().coerceIn(0, OPTICAL_FLOW_BINS - 1)
                            histogram[bin]++
                        }
                    }
                }
            }
            
            prevPts.release()
            currPts.release()
            status.release()
            err.release()
        }
        
        // 归一化直方图并转换为字节数组
        val maxCount = histogram.maxOrNull() ?: 1
        val result = ByteArray(OPTICAL_FLOW_BINS)
        for (i in histogram.indices) {
            result[i] = ((histogram[i] * 255.0 / maxCount).toInt().coerceIn(0, 255)).toByte()
        }
        
        return result
    }
    
    /**
     * 将Long转换为8字节数组
     */
    private fun longToBytes(value: Long): ByteArray {
        return byteArrayOf(
            ((value shr 56) and 0xFF).toByte(),
            ((value shr 48) and 0xFF).toByte(),
            ((value shr 40) and 0xFF).toByte(),
            ((value shr 32) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte()
        )
    }
}

