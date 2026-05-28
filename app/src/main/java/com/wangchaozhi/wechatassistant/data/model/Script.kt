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

enum class ActionType { TAP, SWIPE, LONG_PRESS, WAIT, SCREENSHOT_AI, AI_TAP }

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
)

data class ScriptWithActions(
    val script: Script,
    val actions: List<Action>,
)
