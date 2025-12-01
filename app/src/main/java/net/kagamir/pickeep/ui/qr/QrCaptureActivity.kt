package net.kagamir.pickeep.ui.qr

import android.os.Bundle
import android.view.View
import com.journeyapps.barcodescanner.CaptureActivity
import net.kagamir.pickeep.R

/**
 * 自定义扫码 Activity
 * - 使用 ZXing Embedded 内置 UI（布局为 zxing_capture.xml）
 * - 在 Manifest 中强制竖屏
 * - 顶部增加一个明显的「返回」按钮
 */
class QrCaptureActivity : CaptureActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 默认会加载 R.layout.zxing_capture（我们在项目中自定义了这个布局）
        // 这里给返回按钮绑定点击事件
        val backButton: View? = findViewById(R.id.zxing_back_button)
        backButton?.setOnClickListener {
            finish()
        }
    }
}



