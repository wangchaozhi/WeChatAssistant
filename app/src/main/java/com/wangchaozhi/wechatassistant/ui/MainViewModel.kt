package com.wangchaozhi.wechatassistant.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.wangchaozhi.wechatassistant.App
import com.wangchaozhi.wechatassistant.data.model.Action
import com.wangchaozhi.wechatassistant.data.model.ActionType
import com.wangchaozhi.wechatassistant.data.model.AiAnswer
import com.wangchaozhi.wechatassistant.data.model.Edge
import com.wangchaozhi.wechatassistant.data.model.Script
import com.wangchaozhi.wechatassistant.data.model.ScriptTrigger
import com.wangchaozhi.wechatassistant.data.repo.AiAnswerRepository
import com.wangchaozhi.wechatassistant.data.repo.ScriptRepository
import com.wangchaozhi.wechatassistant.data.repo.SettingsRepository
import com.wangchaozhi.wechatassistant.data.repo.TriggerRepository
import kotlinx.coroutines.flow.Flow
import com.wangchaozhi.wechatassistant.feature.ai.AiProvider
import com.wangchaozhi.wechatassistant.feature.ai.VisionAiRepository
import com.wangchaozhi.wechatassistant.service.ServiceBus
import com.wangchaozhi.wechatassistant.util.WifiAdbManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface Screen {
    data object Home : Screen
    data class Editor(val scriptId: Long?) : Screen
    data class Triggers(val scriptId: Long) : Screen
    data object History : Screen
    data class Settings(val openDebugLog: Boolean = false) : Screen
}

class MainViewModel(
    private val scriptRepo: ScriptRepository,
    private val historyRepo: AiAnswerRepository,
    private val settings: SettingsRepository,
    private val visionAi: VisionAiRepository,
    private val triggerRepo: TriggerRepository,
) : ViewModel() {

    var settingsModelsFetchedThisRun: Boolean = false

    val scripts: StateFlow<List<Script>> = scriptRepo.observeScripts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val answers: StateFlow<List<AiAnswer>> = historyRepo.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val accessibilityReady: StateFlow<Boolean> = ServiceBus.accessibilityReady
    val captureReady: StateFlow<Boolean> = ServiceBus.captureReady
    val overlayReady: StateFlow<Boolean> = ServiceBus.overlayReady
    val lastAiAnswer: StateFlow<String?> = ServiceBus.lastAiAnswer
    val playerState: StateFlow<ServiceBus.PlayerState> = ServiceBus.playerState

    private val _selectedScriptId = MutableStateFlow(settings.selectedScriptId)
    val selectedScriptId: StateFlow<Long> = _selectedScriptId.asStateFlow()

    private val _recordEngine = MutableStateFlow(settings.recordEngine)
    val recordEngineState: StateFlow<String> = _recordEngine.asStateFlow()

    private val _themeMode = MutableStateFlow(settings.themeMode)
    val themeModeState: StateFlow<String> = _themeMode.asStateFlow()

    private val _screen = MutableStateFlow<Screen>(Screen.Home)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    init {
        viewModelScope.launch {
            ServiceBus.selectedScriptChanged.collect { id ->
                _selectedScriptId.value = id
            }
        }
    }

    fun navigate(target: Screen) { _screen.value = target }
    fun back() { _screen.value = Screen.Home }

    var apiKey: String
        get() = settings.qwenApiKey
        set(value) { settings.qwenApiKey = value }

    var defaultPrompt: String
        get() = settings.defaultPrompt
        set(value) { settings.defaultPrompt = value }

    var qwenModel: String
        get() = settings.qwenModel
        set(value) { settings.qwenModel = value }

    var modelScopeApiKey: String
        get() = settings.modelScopeApiKey
        set(value) { settings.modelScopeApiKey = value }

    var modelScopeModel: String
        get() = settings.modelScopeModel
        set(value) { settings.modelScopeModel = value }

    var defaultAiProvider: String
        get() = settings.defaultAiProvider
        set(value) { settings.defaultAiProvider = value }

    var aiReasoningEffort: String
        get() = settings.aiReasoningEffort
        set(value) { settings.aiReasoningEffort = value }

    fun cachedModels(provider: AiProvider): List<String> =
        settings.cachedModels(provider.name)

    /** 手动拉取某供应商官方可用模型列表，成功后持久缓存。 */
    suspend fun fetchModels(provider: AiProvider): Result<List<String>> =
        visionAi.listModels(provider).onSuccess { models ->
            settings.setCachedModels(provider.name, models)
        }

    var thumbnailMaxSide: Int
        get() = settings.thumbnailMaxSide
        set(value) { settings.thumbnailMaxSide = value }

    var saveAiHistory: Boolean
        get() = settings.saveAiHistory
        set(value) { settings.saveAiHistory = value }

    var aiImageMaxSide: Int
        get() = settings.aiImageMaxSide
        set(value) { settings.aiImageMaxSide = value }

    var recordEngine: String
        get() = _recordEngine.value
        set(value) {
            settings.recordEngine = value
            _recordEngine.value = settings.recordEngine
        }

    var showPlaybackMarker: Boolean
        get() = settings.showPlaybackMarker
        set(value) { settings.showPlaybackMarker = value }

    var themeMode: String
        get() = _themeMode.value
        set(value) {
            settings.themeMode = value
            _themeMode.value = settings.themeMode
        }

    val wifiAdbState: StateFlow<WifiAdbManager.Status> = WifiAdbManager.state

    fun saveWifiAdbConfig(host: String, pairingPort: Int, connectPort: Int) {
        WifiAdbManager.saveConfig(host, pairingPort, connectPort)
    }

    fun pairWifiAdb(pairingCode: String) {
        viewModelScope.launch { WifiAdbManager.pair(pairingCode) }
    }

    fun connectWifiAdb() {
        viewModelScope.launch { WifiAdbManager.connect() }
    }

    fun pairAndConnectWifiAdb(pairingCode: String) {
        viewModelScope.launch { WifiAdbManager.pairAndConnectAuto(pairingCode) }
    }

    fun disconnectWifiAdb() { WifiAdbManager.disconnect() }

    fun refreshWifiAdb() { WifiAdbManager.refresh() }

    /** 未连接时用已保存的密钥重连（免重配对），已连接时仅核验。 */
    fun reconnectWifiAdb() {
        viewModelScope.launch {
            WifiAdbManager.refresh()
            if (!WifiAdbManager.state.value.connected) {
                WifiAdbManager.reconnect()
            }
        }
    }

    fun play(scriptId: Long) {
        viewModelScope.launch { ServiceBus.playerCmd.emit(ServiceBus.PlayerCmd.Play(scriptId)) }
    }

    fun stop() {
        viewModelScope.launch { ServiceBus.playerCmd.emit(ServiceBus.PlayerCmd.Stop) }
    }

    fun delete(scriptId: Long) {
        viewModelScope.launch {
            scriptRepo.delete(scriptId)
            if (settings.selectedScriptId == scriptId) {
                settings.selectedScriptId = -1L
                _selectedScriptId.value = -1L
                ServiceBus.selectedScriptChanged.tryEmit(-1L)
            }
        }
    }

    fun selectScript(scriptId: Long) {
        settings.selectedScriptId = scriptId
        _selectedScriptId.value = scriptId
        ServiceBus.selectedScriptChanged.tryEmit(scriptId)
    }

    fun createEmptyScript(onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val name = "脚本_" + SimpleDateFormat("MMdd_HHmm", Locale.getDefault()).format(Date())
            // 新脚本种一个 START 节点作为图入口。
            val start = Action(
                id = -1L, scriptId = 0, index = 0, type = ActionType.START,
                startX = 0f, startY = 0f, posX = 120f, posY = 120f,
            )
            val id = scriptRepo.saveGraph(Script(name = name), listOf(start), emptyList())
            onCreated(id)
        }
    }

    suspend fun loadScript(id: Long) = scriptRepo.load(id)

    suspend fun loadGraphScript(id: Long) = scriptRepo.loadGraph(id)

    fun saveScript(
        script: Script,
        actions: List<Action>,
        onSaved: (Long) -> Unit = {},
    ) {
        viewModelScope.launch {
            val id = scriptRepo.save(script, actions)
            onSaved(id)
        }
    }

    fun saveGraph(
        script: Script,
        nodes: List<Action>,
        edges: List<Edge>,
        onSaved: (Long) -> Unit = {},
    ) {
        viewModelScope.launch {
            val id = scriptRepo.saveGraph(script, nodes, edges)
            onSaved(id)
        }
    }

    fun observeTriggers(scriptId: Long): Flow<List<ScriptTrigger>> =
        triggerRepo.observeForScript(scriptId)

    suspend fun upsertTrigger(trigger: ScriptTrigger): Long = triggerRepo.upsert(trigger)

    suspend fun deleteTrigger(trigger: ScriptTrigger) = triggerRepo.delete(trigger)

    fun scriptName(scriptId: Long): String? = scripts.value.firstOrNull { it.id == scriptId }?.name

    fun deleteAnswer(answer: AiAnswer) {
        viewModelScope.launch { historyRepo.delete(answer.id, answer.thumbnailPath) }
    }

    fun clearHistory() {
        viewModelScope.launch { historyRepo.clear() }
    }

    companion object {
        fun factory(app: App) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MainViewModel(
                    app.scriptRepo,
                    app.aiAnswerRepo,
                    app.settingsRepo,
                    app.visionAi,
                    app.triggerRepo,
                ) as T
        }
    }
}
