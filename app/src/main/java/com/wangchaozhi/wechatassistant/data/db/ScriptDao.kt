package com.wangchaozhi.wechatassistant.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.wangchaozhi.wechatassistant.data.model.Action
import com.wangchaozhi.wechatassistant.data.model.Edge
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

    // --- 节点图 ---

    @Query("SELECT * FROM edges WHERE scriptId = :scriptId")
    suspend fun getEdges(scriptId: Long): List<Edge>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertActionsReturningIds(actions: List<Action>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEdges(edges: List<Edge>)

    @Query("DELETE FROM edges WHERE scriptId = :scriptId")
    suspend fun deleteEdges(scriptId: Long)

    /**
     * 整图替换。先存节点拿到「按输入序返回」的新 id，再用旧 id→新 id 映射重写边。
     * 编辑器中新节点用负数临时 id，加载的节点用真实 id；此处一律按位置映射，不信任传入 id。
     */
    @Transaction
    suspend fun replaceGraph(script: Script, actions: List<Action>, edges: List<Edge>): Long {
        val sid = insertScript(script)
        deleteEdges(sid)
        deleteActions(sid)
        val clientIds = actions.map { it.id }
        val toInsert = actions.mapIndexed { i, a -> a.copy(id = 0, scriptId = sid, index = i) }
        val newIds = insertActionsReturningIds(toInsert)
        val idMap = clientIds.zip(newIds).toMap()
        val remapped = edges.mapNotNull { e ->
            val f = idMap[e.fromActionId]
            val t = idMap[e.toActionId]
            if (f == null || t == null) null
            else e.copy(id = 0, scriptId = sid, fromActionId = f, toActionId = t)
        }
        insertEdges(remapped)
        return sid
    }
}
