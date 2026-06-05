package com.wangchaozhi.wechatassistant.trigger

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wangchaozhi.wechatassistant.App
import com.wangchaozhi.wechatassistant.data.model.ScriptTrigger
import com.wangchaozhi.wechatassistant.data.model.TriggerType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * 「定时触发」：用 AlarmManager 在设定时刻(可按星期重复)唤醒，启动脚本。
 *
 * AlarmManager 的闹钟在设备重启 / 进程被杀后会丢失，因此：
 * - App 启动时（[App.onCreate]）会 [rescheduleAll]；
 * - 开机广播（[BootReceiver]）也会 [rescheduleAll]；
 * - 每次闹钟触发后（[ScheduleAlarmReceiver]）会把自己的下一次重新登记。
 *
 * 用 setAndAllowWhileIdle（非精确，但 doze 下也能近时触发），避免精确闹钟权限。
 */
object ScheduleTriggers {

    private const val ACTION_FIRE = "com.wangchaozhi.wechatassistant.SCHEDULE_FIRE"
    private const val EXTRA_TRIGGER_ID = "trigger_id"

    private fun alarmManager(context: Context): AlarmManager =
        context.getSystemService(AlarmManager::class.java)

    private fun pendingIntent(context: Context, triggerId: Long): PendingIntent {
        val intent = Intent(context, ScheduleAlarmReceiver::class.java)
            .setAction(ACTION_FIRE)
            .putExtra(EXTRA_TRIGGER_ID, triggerId)
        return PendingIntent.getBroadcast(
            context,
            triggerId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 取消某个触发器的闹钟（触发器被关闭/删除时调用）。 */
    fun cancel(context: Context, triggerId: Long) {
        runCatching { alarmManager(context).cancel(pendingIntent(context, triggerId)) }
        App.from(context).appendLog("ScheduleTrigger cancel trigger=$triggerId")
    }

    /** 为单个触发器登记「下一次」闹钟。 */
    fun scheduleNext(context: Context, trigger: ScriptTrigger) {
        if (!trigger.enabled || trigger.type != TriggerType.SCHEDULE) {
            cancel(context, trigger.id)
            return
        }
        val at = nextTriggerTime(trigger, System.currentTimeMillis())
        runCatching {
            alarmManager(context).setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                at,
                pendingIntent(context, trigger.id),
            )
        }
        App.from(context).appendLog(
            "ScheduleTrigger 登记 trigger=${trigger.id} script=${trigger.scriptId} at=$at " +
                "(${trigger.hour}:${"%02d".format(trigger.minute)} days=${trigger.daysMask})"
        )
    }

    /** 重新登记所有已启用的定时触发器。 */
    suspend fun rescheduleAll(context: Context) {
        val triggers = runCatching {
            App.from(context).triggerRepo.enabledByType(TriggerType.SCHEDULE)
        }.getOrDefault(emptyList())
        triggers.forEach { scheduleNext(context, it) }
        App.from(context).appendLog("ScheduleTrigger rescheduleAll count=${triggers.size}")
    }

    /**
     * 计算下一次触发的绝对毫秒时间。
     * daysMask==0 表示每天；否则按位匹配星期（bit0=周日 .. bit6=周六）。
     */
    fun nextTriggerTime(trigger: ScriptTrigger, now: Long): Long {
        val base = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, trigger.hour)
            set(Calendar.MINUTE, trigger.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (trigger.daysMask == 0) {
            if (base.timeInMillis <= now) base.add(Calendar.DAY_OF_YEAR, 1)
            return base.timeInMillis
        }
        // 从今天起最多向后查 7 天，找第一个命中星期且尚未过去的时刻。
        for (i in 0..7) {
            val c = (base.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, i) }
            if (c.timeInMillis <= now) continue
            val bit = c.get(Calendar.DAY_OF_WEEK) - 1 // DAY_OF_WEEK: 1=周日..7=周六
            if (trigger.daysMask and (1 shl bit) != 0) return c.timeInMillis
        }
        return base.timeInMillis + 24L * 60 * 60 * 1000
    }

    /** 闹钟回调用：取出本次触发的脚本 id 并启动，再登记下一次。 */
    internal fun extractTriggerId(intent: Intent?): Long? {
        if (intent?.action != ACTION_FIRE) return null
        val id = intent.getLongExtra(EXTRA_TRIGGER_ID, -1L)
        return id.takeIf { it >= 0 }
    }
}

/** 定时闹钟到点回调：启动脚本并登记下一次。 */
class ScheduleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val triggerId = ScheduleTriggers.extractTriggerId(intent) ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val app = App.from(context.applicationContext)
                val trigger = app.triggerRepo.get(triggerId)
                if (trigger != null && trigger.enabled) {
                    TriggerLauncher.launch(context.applicationContext, trigger.scriptId, "schedule")
                    // 重复闹钟：登记下一次。
                    ScheduleTriggers.scheduleNext(context.applicationContext, trigger)
                } else {
                    app.appendLog("ScheduleTrigger 回调：触发器已删除/关闭 trigger=$triggerId")
                }
            } finally {
                pending.finish()
            }
        }
    }
}

/** 开机后重新登记所有定时闹钟（重启会清空 AlarmManager 中的闹钟）。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                ScheduleTriggers.rescheduleAll(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }
}
