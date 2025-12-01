package net.kagamir.pickeep.util

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 二维码辅助类
 * 用于生成和解析恢复二维码
 */
object QrCodeHelper {
    
    /**
     * 恢复数据
     */
    @Serializable
    data class RecoveryQrData(
        val mnemonic: List<String>
    )
    
    /**
     * 生成二维码 Bitmap
     * @param data 要编码的数据
     * @param size 二维码尺寸（像素）
     * @return Bitmap 图像
     */
    fun generateQrCodeBitmap(data: String, size: Int = 512): Bitmap {
        val hints = hashMapOf<EncodeHintType, Any>().apply {
            put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M)
            put(EncodeHintType.MARGIN, 1)
            put(EncodeHintType.CHARACTER_SET, "UTF-8")
        }
        
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(data, BarcodeFormat.QR_CODE, size, size, hints)
        
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        
        return bitmap
    }
    
    /**
     * 生成恢复二维码
     * @param mnemonic 助记词列表
     * @param size 二维码尺寸（像素）
     * @return ImageBitmap
     */
    fun generateRecoveryQrCode(mnemonic: List<String>, size: Int = 512): ImageBitmap {
        val data = RecoveryQrData(mnemonic = mnemonic)
        val json = Json.encodeToString(RecoveryQrData.serializer(), data)
        val bitmap = generateQrCodeBitmap(json, size)
        return bitmap.asImageBitmap()
    }
    
    /**
     * 解析恢复二维码数据
     * @param json 二维码内容（JSON 字符串）
     * @return 助记词列表，如果解析失败返回 null
     */
    fun parseRecoveryQrData(json: String): List<String>? {
        return try {
            val data = Json.decodeFromString<RecoveryQrData>(json)
            data.mnemonic
        } catch (e: Exception) {
            null
        }
    }
}

