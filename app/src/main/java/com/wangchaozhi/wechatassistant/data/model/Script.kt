package com.wangchaozhi.wechatassistant.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "scripts")
data class Script(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val loopCount: Int = 1,
    val speed: Float = 1.0f,
)

// 注意：Room 用 ordinal 存储 ActionType，新增类型只能追加到末尾，不能插入中间。
enum class ActionType { TAP, SWIPE, LONG_PRESS, WAIT, SCREENSHOT_AI, AI_TAP, PASTE, ENTER, IMAGE_MATCH }

@Entity(
    tableName = "actions",
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
data class Action(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scriptId: Long,
    val index: Int,
    val type: ActionType,
    val startX: Float,
    val startY: Float,
    val endX: Float = startX,
    val endY: Float = startY,
    val durationMs: Long = 80L,
    val delayBeforeMs: Long = 0L,
    val aiPrompt: String? = null,
    // IMAGE_MATCH 专用：模板图片在内部存储的绝对路径，以及匹配置信度阈值 (0~1)。
    val templatePath: String? = null,
    val matchThreshold: Float = 0.85f,
)

data class ScriptWithActions(
    val script: Script,
    val actions: List<Action>,
)
