package com.wangchaozhi.wechatassistant.data.repo

import com.wangchaozhi.wechatassistant.data.db.ScriptDao
import com.wangchaozhi.wechatassistant.data.model.Action
import com.wangchaozhi.wechatassistant.data.model.Edge
import com.wangchaozhi.wechatassistant.data.model.Script
import com.wangchaozhi.wechatassistant.data.model.ScriptWithActions
import com.wangchaozhi.wechatassistant.data.model.ScriptWithGraph
import kotlinx.coroutines.flow.Flow

class ScriptRepository(private val dao: ScriptDao) {

    fun observeScripts(): Flow<List<Script>> = dao.scriptsFlow()

    suspend fun load(id: Long): ScriptWithActions? {
        val s = dao.getScript(id) ?: return null
        return ScriptWithActions(s, dao.getActions(id))
    }

    suspend fun save(script: Script, actions: List<Action>): Long =
        dao.replaceScript(script, actions)

    suspend fun loadGraph(id: Long): ScriptWithGraph? {
        val s = dao.getScript(id) ?: return null
        return ScriptWithGraph(s, dao.getActions(id), dao.getEdges(id))
    }

    suspend fun saveGraph(script: Script, actions: List<Action>, edges: List<Edge>): Long =
        dao.replaceGraph(script, actions, edges)

    suspend fun updateScript(script: Script) = dao.updateScript(script)

    suspend fun replaceActions(scriptId: Long, actions: List<Action>) {
        val reindexed = actions.mapIndexed { i, a ->
            a.copy(id = 0, scriptId = scriptId, index = i)
        }
        dao.deleteActions(scriptId)
        dao.insertActions(reindexed)
    }

    suspend fun delete(id: Long) = dao.deleteScript(id)
}
