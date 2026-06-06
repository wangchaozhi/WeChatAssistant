package com.wangchaozhi.wechatassistant.data.repo

import com.wangchaozhi.wechatassistant.data.db.TriggerDao
import com.wangchaozhi.wechatassistant.data.model.ScriptTrigger
import com.wangchaozhi.wechatassistant.data.model.TriggerType
import kotlinx.coroutines.flow.Flow

class TriggerRepository(private val dao: TriggerDao) {

    fun observeForScript(scriptId: Long): Flow<List<ScriptTrigger>> =
        dao.triggersForScriptFlow(scriptId)

    suspend fun enabledByType(type: TriggerType): List<ScriptTrigger> = dao.enabledByType(type)

    suspend fun get(id: Long): ScriptTrigger? = dao.getTrigger(id)

    suspend fun upsert(trigger: ScriptTrigger): Long =
        if (trigger.id == 0L) dao.insert(trigger) else { dao.update(trigger); trigger.id }

    suspend fun delete(trigger: ScriptTrigger) = dao.delete(trigger)
}
