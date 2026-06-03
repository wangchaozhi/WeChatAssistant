package com.wangchaozhi.wechatassistant.ui

import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.wangchaozhi.wechatassistant.service.CaptureForegroundService
import com.wangchaozhi.wechatassistant.service.ServiceBus

/**
 * 无界面的中转 Activity：只负责弹系统「屏幕共享」授权框，拿到结果就启动截图服务并立即结束，
 * 不显示主界面。供悬浮窗「共享」按钮调用——授权弹窗盖在用户当前所在的 App 上，授权后直接
 * 回到原处，不会跳回本应用主界面。
 */
class CaptureRequestActivity : ComponentActivity() {

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            CaptureForegroundService.start(this, result.resultCode, data)
        } else {
            Toast.makeText(this, "已取消屏幕共享", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ServiceBus.captureReady.value) {
            finish()
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        if (mpm == null) {
            Toast.makeText(this, "此设备不支持屏幕共享", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        launcher.launch(mpm.createScreenCaptureIntent())
    }

    override fun finish() {
        super.finish()
        // 去掉结束动画，避免一闪。
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
