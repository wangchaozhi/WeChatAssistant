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
    // 本次回答实际使用的供应商（AiProvider.name）与模型名，便于历史中追溯。
    val aiProvider: String? = null,
    val aiModel: String? = null,
)
