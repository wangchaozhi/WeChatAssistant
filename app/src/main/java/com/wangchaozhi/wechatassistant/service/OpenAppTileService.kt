package com.wangchaozhi.wechatassistant.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.TileService
import android.widget.Toast
import com.wangchaozhi.wechatassistant.ui.CaptureRequestActivity
import com.wangchaozhi.wechatassistant.ui.MainActivity

/**
 * 快捷设置磁贴：下拉通知栏点一下，先弹屏幕共享授权框，授权后启动浮窗面板。
 *
 * - 没授予「悬浮窗」权限时无法显示面板，先打开主界面让用户去开。
 * - 其余情况走 [CaptureRequestActivity]：弹系统屏幕共享授权框，结束后顺带启动浮窗。
 *
 * 用户需先在「快捷设置」编辑面板里把本磁贴拖到可见区域（系统不允许应用自动添加）。
 */
class OpenAppTileService : TileService() {

    override fun onClick() {
        super.onClick()

        // 浮窗面板依赖「显示在其他应用上层」权限，没有就先引导到主界面开启。
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先在应用内开启「悬浮窗」权限", Toast.LENGTH_LONG).show()
            launchFromTile(Intent(this, MainActivity::class.java))
            return
        }

        // 弹屏幕共享授权框；授权流程结束后由该 Activity 顺带启动浮窗。
        val intent = Intent(this, CaptureRequestActivity::class.java)
            .putExtra(CaptureRequestActivity.EXTRA_START_OVERLAY, true)
            .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        launchFromTile(intent)
    }

    /** 从磁贴启动 Activity 并收起通知栏。Android 14+ 必须用 PendingIntent 版本。 */
    private fun launchFromTile(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
