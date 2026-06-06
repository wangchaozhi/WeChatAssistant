package com.wangchaozhi.wechatassistant.trigger

import android.app.Notification
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.wangchaozhi.wechatassistant.App
import com.wangchaozhi.wechatassistant.data.model.TriggerType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 监听通知；命中已启用的「通知触发器」(包名 + 可选关键词)时启动对应脚本。
 *
 * 需要用户在「通知使用权」里授权本应用。授权后系统会绑定此服务并持续投递通知。
 */
class WeChatNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // 每个触发器最近一次触发时间(uptime ms)，做防抖：短时间内同一触发器只启动一次。
    private val lastFire = ConcurrentHashMap<Long, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notif = sbn ?: return
        val pkg = notif.packageName ?: return
        val extras = notif.notification?.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

        scope.launch {
            val app = App.from(this@WeChatNotificationListener)
            val triggers = runCatching {
                app.triggerRepo.enabledByType(TriggerType.NOTIFICATION)
            }.getOrDefault(emptyList())
            if (triggers.isEmpty()) return@launch

            val now = SystemClock.uptimeMillis()
            for (t in triggers) {
                if (t.packageName != pkg) continue
                val kw = t.keyword?.trim().orEmpty()
                if (kw.isNotEmpty() &&
                    !title.contains(kw, ignoreCase = true) &&
                    !text.contains(kw, ignoreCase = true)
                ) continue
                val last = lastFire[t.id] ?: 0L
                if (now - last < MIN_INTERVAL_MS) {
                    app.appendLog("NotifTrigger 防抖跳过 trigger=${t.id}")
                    continue
                }
                lastFire[t.id] = now
                app.appendLog("NotifTrigger 命中 trigger=${t.id} pkg=$pkg title=$title")
                TriggerLauncher.launch(applicationContext, t.scriptId, "notif")
                break
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        // 同一触发器最小重触发间隔，避免微信连发多条通知时反复启动脚本。
        private const val MIN_INTERVAL_MS = 8_000L
    }
}
