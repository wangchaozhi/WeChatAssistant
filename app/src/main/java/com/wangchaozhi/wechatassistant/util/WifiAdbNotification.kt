package com.wangchaozhi.wechatassistant.util

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.wangchaozhi.wechatassistant.App
import com.wangchaozhi.wechatassistant.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object WifiAdbNotification {
    private const val NOTIF_ID = 0xADB1
    private const val ACTION_REPLY = "com.wangchaozhi.wechatassistant.WIFI_ADB_REPLY"
    private const val KEY_REPLY = "wifi_adb_reply"

    fun show(context: Context, status: String = "回复配对码即可", showInput: Boolean = true) {
        val builder = NotificationCompat.Builder(context, App.CHANNEL_ADB)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.notif_adb_title))
            .setContentText(status)
            .setOngoing(false)
            .setAutoCancel(false)

        // 已连接时不再需要输入框，只保留一条状态提示
        if (showInput) {
            val replyIntent = Intent(context, WifiAdbNotificationReceiver::class.java).setAction(ACTION_REPLY)
            val replyPending = PendingIntent.getBroadcast(
                context,
                1,
                replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            val replyInput = RemoteInput.Builder(KEY_REPLY)
                .setLabel("在此输入 6 位配对码")
                .build()
            val replyAction = NotificationCompat.Action.Builder(
                R.mipmap.ic_launcher,
                "输入配对码",
                replyPending,
            ).addRemoteInput(replyInput).build()
            builder.addAction(replyAction)
        }
        runCatching { NotificationManagerCompat.from(context).notify(NOTIF_ID, builder.build()) }
    }

    fun hide(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.cancel(NOTIF_ID)
    }
}

class WifiAdbNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.wangchaozhi.wechatassistant.WIFI_ADB_REPLY") return
        val pending = goAsync()
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence("wifi_adb_reply")
            ?.toString()
            .orEmpty()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                handleReply(context.applicationContext, text)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handleReply(context: Context, text: String) {
        val parts = Regex("""\d+""").findAll(text).map { it.value }.toList()
        if (parts.size != 1) {
            WifiAdbNotification.show(context, "只需要回复配对码，例如：288601")
            return
        }
        val app = App.from(context)
        WifiAdbManager.install(app, app.settingsRepo)
        val code = parts[0]
        WifiAdbNotification.show(context, "正在自动查找端口并配对...")
        val result = WifiAdbManager.pairAndConnectAuto(code)
        result.fold(
            { WifiAdbNotification.show(context, "已连接 Wi-Fi ADB，可以回 App 开始录制", showInput = false) },
            { WifiAdbNotification.show(context, "自动连接失败：${it.message ?: it.javaClass.simpleName}") },
        )
    }
}
