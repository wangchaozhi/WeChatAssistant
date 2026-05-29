package com.wangchaozhi.wechatassistant.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.wangchaozhi.wechatassistant.data.model.Action
import com.wangchaozhi.wechatassistant.data.model.Script
import kotlinx.coroutines.flow.Flow

@Dao
interface ScriptDao {

    @Query("SELECT * FROM scripts ORDER BY createdAt DESC")
    fun scriptsFlow(): Flow<List<Script>>

    @Query("SELECT * FROM scripts WHERE id = :id")
    suspend fun getScript(id: Long): Script?

    @Query("SELECT * FROM actions WHERE scriptId = :scriptId ORDER BY `index` ASC")
    suspend fun getActions(scriptId: Long): List<Action>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScript(script: Script): Long

    @Update
    suspend fun updateScript(script: Script)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertActions(actions: List<Action>)

    @Query("DELETE FROM scripts WHERE id = :id")
    suspend fun deleteScript(id: Long)

    @Query("DELETE FROM actions WHERE scriptId = :scriptId")
    suspend fun deleteActions(scriptId: Long)

    @Transaction
    suspend fun replaceScript(script: Script, actions: List<Action>): Long {
        val id = insertScript(script)
        deleteActions(id)
        val reindexed = actions.mapIndexed { i, a -> a.copy(id = 0, scriptId = id, index = i) }
        insertActions(reindexed)
        return id
    }
}
