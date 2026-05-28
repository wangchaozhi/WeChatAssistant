package com.wangchaozhi.wechatassistant.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.wangchaozhi.wechatassistant.data.model.AiAnswer
import kotlinx.coroutines.flow.Flow

@Dao
interface AiAnswerDao {

    @Query("SELECT * FROM ai_answers ORDER BY createdAt DESC LIMIT 200")
    fun answersFlow(): Flow<List<AiAnswer>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(answer: AiAnswer): Long

    @Query("DELETE FROM ai_answers WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM ai_answers")
    suspend fun clear()
}
