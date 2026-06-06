package com.wangchaozhi.wechatassistant.trigger

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.wangchaozhi.wechatassistant.App
import com.wangchaozhi.wechatassistant.R
import com.wangchaozhi.wechatassistant.service.ServiceBus
import com.wangchaozhi.wechatassistant.ui.MainActivity

/**
 * 触发器（通知监听 / 定时）统一的「启动脚本」入口。
 *
 * 真正的回放在无障碍服务里做。这里只把待播放脚本写进 [ServiceBus.pendingPlay]：
 * - 服务在线：StateFlow 立即把值推给正在收集的服务，开始回放；
 * - 进程刚被触发器冷启动：服务随后连接时，StateFlow 会重放最新值，照样能播。
 *
 * 若无障碍未开启则无法回放，发一条通知提示用户去开启。
 */
object TriggerLauncher {

    private const val NOTIF_ID = 0x7716

    fun launch(context: Context, scriptId: Long, reason: String) {
        val app = App.from(context)
        val accOn = ServiceBus.accessibilityReady.value
        val busy = ServiceBus.playerState.value !is ServiceBus.PlayerState.Idle
        app.appendLog("Trigger[$reason] launch scriptId=$scriptId acc=$accOn busy=$busy")

        if (busy) {
            app.appendLog("Trigger[$reason] 跳过：已有脚本在运行")
            return
        }
        if (!accOn) {
            notifyAccessibilityOff(context)
            // 仍然写入 pendingPlay：若用户随后开启无障碍、服务连接，会立即补播。
        }
        ServiceBus.pendingPlay.value = scriptId
    }

    private fun notifyAccessibilityOff(context: Context) {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, App.CHANNEL_TRIGGER)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.trigger_need_accessibility_title))
            .setContentText(context.getString(R.string.trigger_need_accessibility_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIF_ID, n) }
    }
}
