package com.wangchaozhi.wechatassistant.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ai_answers")
data class AiAnswer(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val prompt: String,
    val answer: String,
    val thumbnailPath: String? = null,
    val scriptId: Long? = null,
)
