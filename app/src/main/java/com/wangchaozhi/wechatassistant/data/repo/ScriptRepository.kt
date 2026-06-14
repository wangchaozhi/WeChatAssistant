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

    suspend fun importGraphs(graphs: List<ScriptWithGraph>): Map<Long, Long> {
        val scriptIdMap = LinkedHashMap<Long, Long>()
        graphs.forEach { graph ->
            val imported = graph.script.copy(
                id = 0,
                name = uniqueImportName(graph.script.name),
                createdAt = System.currentTimeMillis(),
            )
            scriptIdMap[graph.script.id] = dao.insertScript(imported)
        }
        graphs.forEach { graph ->
            val newScriptId = scriptIdMap[graph.script.id] ?: return@forEach
            dao.deleteEdges(newScriptId)
            dao.deleteActions(newScriptId)
            val clientIds = graph.actions.map { it.id }
            val toInsert = graph.actions.mapIndexed { i, action ->
                action.copy(
                    id = 0,
                    scriptId = newScriptId,
                    index = i,
                    callScriptId = action.callScriptId?.let { scriptIdMap[it] ?: it },
                )
            }
            val newActionIds = dao.insertActionsReturningIds(toInsert)
            val actionIdMap = clientIds.zip(newActionIds).toMap()
            val remappedEdges = graph.edges.mapNotNull { edge ->
                val from = actionIdMap[edge.fromActionId]
                val to = actionIdMap[edge.toActionId]
                if (from == null || to == null) null
                else edge.copy(id = 0, scriptId = newScriptId, fromActionId = from, toActionId = to)
            }
            dao.insertEdges(remappedEdges)
        }
        return scriptIdMap
    }

    suspend fun updateScript(script: Script) = dao.updateScript(script)

    suspend fun replaceActions(scriptId: Long, actions: List<Action>) {
        val reindexed = actions.mapIndexed { i, a ->
            a.copy(id = 0, scriptId = scriptId, index = i)
        }
        dao.deleteActions(scriptId)
        dao.insertActions(reindexed)
    }

    suspend fun delete(id: Long) = dao.deleteScript(id)

    private fun uniqueImportName(name: String): String =
        if (name.endsWith("（导入）")) name else "$name（导入）"
}
