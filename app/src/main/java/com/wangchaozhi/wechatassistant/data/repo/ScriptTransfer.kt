package com.wangchaozhi.wechatassistant.data.repo

import android.content.Context
import android.util.Base64
import com.wangchaozhi.wechatassistant.data.model.Action
import com.wangchaozhi.wechatassistant.data.model.ActionType
import com.wangchaozhi.wechatassistant.data.model.Edge
import com.wangchaozhi.wechatassistant.data.model.Script
import com.wangchaozhi.wechatassistant.data.model.ScriptWithGraph
import com.wangchaozhi.wechatassistant.feature.match.TemplateMatchUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

class ScriptTransfer(
    private val context: Context,
    private val scriptRepo: ScriptRepository,
) {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    suspend fun exportScripts(ids: Collection<Long>): String = withContext(Dispatchers.IO) {
        val graphs = ids.distinct().mapNotNull { scriptRepo.loadGraph(it) }
        val templates = LinkedHashMap<String, String>()
        graphs.forEach { graph ->
            graph.actions
                .flatMap { TemplateMatchUseCase.splitTemplatePaths(it.templatePath) }
                .distinct()
                .forEach { path ->
                    val file = File(path)
                    if (file.isFile && path !in templates) {
                        templates[path] = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                    }
                }
        }
        json.encodeToString(
            ScriptExportPackage.serializer(),
            ScriptExportPackage(
                scripts = graphs.map { it.toDto() },
                templates = templates.map { (path, data) ->
                    ExportedTemplate(path = path, fileName = File(path).name, base64Png = data)
                },
            ),
        )
    }

    suspend fun importScripts(raw: String): Int = withContext(Dispatchers.IO) {
        val pkg = json.decodeFromString(ScriptExportPackage.serializer(), raw)
        require(pkg.format == FORMAT) { "不支持的脚本导入格式：${pkg.format}" }
        val templatePathMap = importTemplates(pkg.templates)
        val graphs = pkg.scripts.map { dto ->
            dto.toGraph(templatePathMap)
        }
        scriptRepo.importGraphs(graphs).size
    }

    private fun importTemplates(templates: List<ExportedTemplate>): Map<String, String> {
        val dir = File(context.filesDir, "templates").apply { if (!exists()) mkdirs() }
        return templates.associate { tpl ->
            val ext = tpl.fileName.substringAfterLast('.', "png").ifBlank { "png" }
            val file = File(dir, "import_tpl_${System.currentTimeMillis()}_${safeName(tpl.fileName)}.$ext")
            file.writeBytes(Base64.decode(tpl.base64Png, Base64.DEFAULT))
            tpl.path to file.absolutePath
        }
    }

    private fun safeName(name: String): String =
        name.substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .take(32)
            .ifBlank { "template" }

    private fun ScriptWithGraph.toDto(): ExportedScript =
        ExportedScript(
            script = script.toDto(),
            actions = actions.map { it.toDto() },
            edges = edges.map { it.toDto() },
        )

    private fun ExportedScript.toGraph(templatePathMap: Map<String, String>): ScriptWithGraph =
        ScriptWithGraph(
            script = script.toModel(),
            actions = actions.map { it.toModel(templatePathMap) },
            edges = edges.map { it.toModel(script.id) },
        )

    private fun Script.toDto(): ExportedScriptMeta =
        ExportedScriptMeta(id, name, createdAt, loopCount, speed)

    private fun ExportedScriptMeta.toModel(): Script =
        Script(id = id, name = name, createdAt = createdAt, loopCount = loopCount, speed = speed)

    private fun Action.toDto(): ExportedAction =
        ExportedAction(
            id = id,
            index = index,
            type = type.name,
            startX = startX,
            startY = startY,
            endX = endX,
            endY = endY,
            durationMs = durationMs,
            delayBeforeMs = delayBeforeMs,
            randomExtraMs = randomExtraMs,
            aiPrompt = aiPrompt,
            templatePath = templatePath,
            matchThreshold = matchThreshold,
            retryCount = retryCount,
            upFallbackPx = upFallbackPx,
            repeatPrevSteps = repeatPrevSteps,
            posX = posX,
            posY = posY,
            alias = alias,
            aiProvider = aiProvider,
            aiModel = aiModel,
            aiFastRegionCapture = aiFastRegionCapture,
            aiStreamOutput = aiStreamOutput,
            pasteText = pasteText,
            callScriptId = callScriptId,
        )

    private fun ExportedAction.toModel(templatePathMap: Map<String, String>): Action =
        Action(
            id = id,
            scriptId = 0,
            index = index,
            type = ActionType.valueOf(type),
            startX = startX,
            startY = startY,
            endX = endX,
            endY = endY,
            durationMs = durationMs,
            delayBeforeMs = delayBeforeMs,
            randomExtraMs = randomExtraMs,
            aiPrompt = aiPrompt,
            templatePath = remapTemplatePath(templatePath, templatePathMap),
            matchThreshold = matchThreshold,
            retryCount = retryCount,
            upFallbackPx = upFallbackPx,
            repeatPrevSteps = repeatPrevSteps,
            posX = posX,
            posY = posY,
            alias = alias,
            aiProvider = aiProvider,
            aiModel = aiModel,
            aiFastRegionCapture = aiFastRegionCapture,
            aiStreamOutput = aiStreamOutput,
            pasteText = pasteText,
            callScriptId = callScriptId,
        )

    private fun remapTemplatePath(raw: String?, templatePathMap: Map<String, String>): String? {
        val paths = TemplateMatchUseCase.splitTemplatePaths(raw)
        if (paths.isEmpty()) return raw
        return paths.joinToString("\n") { templatePathMap[it] ?: it }
    }

    private fun Edge.toDto(): ExportedEdge =
        ExportedEdge(id, fromActionId, toActionId, fromPort)

    private fun ExportedEdge.toModel(scriptId: Long): Edge =
        Edge(id = id, scriptId = scriptId, fromActionId = fromActionId, toActionId = toActionId, fromPort = fromPort)

    companion object {
        const val MIME_TYPE = "application/json"
        const val FORMAT = "wechat-assistant-scripts"
    }
}

@Serializable
data class ScriptExportPackage(
    val format: String = ScriptTransfer.FORMAT,
    val version: Int = 1,
    val scripts: List<ExportedScript>,
    val templates: List<ExportedTemplate> = emptyList(),
)

@Serializable
data class ExportedScript(
    val script: ExportedScriptMeta,
    val actions: List<ExportedAction>,
    val edges: List<ExportedEdge>,
)

@Serializable
data class ExportedScriptMeta(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val loopCount: Int,
    val speed: Float,
)

@Serializable
data class ExportedAction(
    val id: Long,
    val index: Int,
    val type: String,
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val durationMs: Long,
    val delayBeforeMs: Long,
    val randomExtraMs: Long,
    val aiPrompt: String?,
    val templatePath: String?,
    val matchThreshold: Float,
    val retryCount: Int,
    val upFallbackPx: Int,
    val repeatPrevSteps: Int,
    val posX: Float,
    val posY: Float,
    val alias: String?,
    val aiProvider: String?,
    val aiModel: String?,
    val aiFastRegionCapture: Boolean = true,
    val aiStreamOutput: Boolean = false,
    val pasteText: String?,
    val callScriptId: Long?,
)

@Serializable
data class ExportedEdge(
    val id: Long,
    val fromActionId: Long,
    val toActionId: Long,
    val fromPort: Int,
)

@Serializable
data class ExportedTemplate(
    val path: String,
    val fileName: String,
    val base64Png: String,
)
