package com.wangchaozhi.wechatassistant.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.wangchaozhi.wechatassistant.data.model.ScriptTrigger
import com.wangchaozhi.wechatassistant.data.model.TriggerType
import kotlinx.coroutines.flow.Flow

@Dao
interface TriggerDao {

    @Query("SELECT * FROM triggers WHERE scriptId = :scriptId ORDER BY id ASC")
    fun triggersForScriptFlow(scriptId: Long): Flow<List<ScriptTrigger>>

    @Query("SELECT * FROM triggers WHERE enabled = 1 AND type = :type")
    suspend fun enabledByType(type: TriggerType): List<ScriptTrigger>

    @Query("SELECT * FROM triggers WHERE id = :id")
    suspend fun getTrigger(id: Long): ScriptTrigger?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(trigger: ScriptTrigger): Long

    @Update
    suspend fun update(trigger: ScriptTrigger)

    @Delete
    suspend fun delete(trigger: ScriptTrigger)
}
