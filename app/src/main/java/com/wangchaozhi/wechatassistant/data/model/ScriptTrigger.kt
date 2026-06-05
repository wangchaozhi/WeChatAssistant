package com.wangchaozhi.wechatassistant.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// Room 用 ordinal 存储，新增类型只能追加到末尾。
enum class TriggerType {
    NOTIFICATION,  // 监听某 App 的通知，命中关键词时启动脚本。
    SCHEDULE,      // 到设定时间(可按星期重复)自动启动脚本。
}

/**
 * 一个「触发器」：满足条件时自动启动 [scriptId] 对应的脚本。
 * 同一个脚本可挂多个触发器（如既定时跑、又监听通知）。
 */
@Entity(
    tableName = "triggers",
    foreignKeys = [
        ForeignKey(
            entity = Script::class,
            parentColumns = ["id"],
            childColumns = ["scriptId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("scriptId")],
)
data class ScriptTrigger(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scriptId: Long,
    val type: TriggerType,
    val enabled: Boolean = true,

    // --- SCHEDULE 专用 ---
    // 触发时刻（24 小时制）。
    val hour: Int = 9,
    val minute: Int = 0,
    // 重复的星期掩码：bit0=周日, bit1=周一, ... bit6=周六。0 = 每天。
    val daysMask: Int = 0,

    // --- NOTIFICATION 专用 ---
    // 监听的 App 包名，默认微信。
    val packageName: String = "com.tencent.mm",
    // 命中关键词（出现在通知标题或正文即触发）。为空 = 任意通知都触发。
    val keyword: String? = null,
)
