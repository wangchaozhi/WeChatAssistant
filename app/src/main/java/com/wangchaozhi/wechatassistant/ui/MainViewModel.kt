package com.wangchaozhi.wechatassistant.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.wangchaozhi.wechatassistant.App
import com.wangchaozhi.wechatassistant.data.model.AiAnswer
import com.wangchaozhi.wechatassistant.data.model.Script
import com.wangchaozhi.wechatassistant.data.repo.AiAnswerRepository
import com.wangchaozhi.wechatassistant.data.repo.ScriptRepository
import com.wangchaozhi.wechatassistant.data.repo.SettingsRepository
import com.wangchaozhi.wechatassistant.service.ServiceBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Home : Screen
    data class Editor(val scriptId: Long) : Screen
    data object History : Screen
}

class MainViewModel(
    private val scriptRepo: ScriptRepository,
    private val historyRepo: AiAnswerRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    val scripts: StateFlow<List<Script>> = scriptRepo.observeScripts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val answers: StateFlow<List<AiAnswer>> = historyRepo.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val accessibilityReady: StateFlow<Boolean> = ServiceBus.accessibilityReady
    val captureReady: StateFlow<Boolean> = ServiceBus.captureReady
    val overlayReady: StateFlow<Boolean> = ServiceBus.overlayReady
    val lastAiAnswer: StateFlow<String?> = ServiceBus.lastAiAnswer
    val playerState: StateFlow<ServiceBus.PlayerState> = ServiceBus.playerState

    private val _screen = MutableStateFlow<Screen>(Screen.Home)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    fun navigate(target: Screen) { _screen.value = target }
    fun back() { _screen.value = Screen.Home }

    var apiKey: String
        get() = settings.qwenApiKey
        set(value) { settings.qwenApiKey = value }

    var defaultPrompt: String
        get() = settings.defaultPrompt
        set(value) { settings.defaultPrompt = value }

    fun play(scriptId: Long) {
        viewModelScope.launch { ServiceBus.playerCmd.emit(ServiceBus.PlayerCmd.Play(scriptId)) }
    }

    fun stop() {
        viewModelScope.launch { ServiceBus.playerCmd.emit(ServiceBus.PlayerCmd.Stop) }
    }

    fun delete(scriptId: Long) {
        viewModelScope.launch { scriptRepo.delete(scriptId) }
    }

    suspend fun loadScript(id: Long) = scriptRepo.load(id)

    fun saveScript(
        script: Script,
        actions: List<com.wangchaozhi.wechatassistant.data.model.Action>,
        onSaved: (Long) -> Unit = {},
    ) {
        viewModelScope.launch {
            val id = scriptRepo.save(script, actions)
            onSaved(id)
        }
    }

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
                MainViewModel(app.scriptRepo, app.aiAnswerRepo, app.settingsRepo) as T
        }
    }
}
