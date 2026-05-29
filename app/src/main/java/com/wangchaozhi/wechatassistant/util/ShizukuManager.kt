package com.wangchaozhi.wechatassistant.util

import android.content.pm.PackageManager
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import rikka.shizuku.Shizuku

object ShizukuManager {

    private const val REQUEST_CODE = 0xA110

    private val _state = MutableStateFlow(Status())
    val state = _state.asStateFlow()

    data class Status(
        val available: Boolean = false,
        val granted: Boolean = false,
    )

    private val permissionResults = MutableSharedFlow<Boolean>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener { refresh() }
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == REQUEST_CODE) {
            val granted = result == PackageManager.PERMISSION_GRANTED
            permissionResults.tryEmit(granted)
            refresh()
        }
    }

    fun install() {
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        refresh()
    }

    fun refresh() {
        val available = isBinderAlive()
        val granted = available && safeCheckPermission()
        _state.value = Status(available, granted)
    }

    private fun isBinderAlive(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }

    private fun safeCheckPermission(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }

    suspend fun requestPermission(): Boolean {
        if (!isBinderAlive()) return false
        if (safeCheckPermission()) return true
        try { Shizuku.requestPermission(REQUEST_CODE) } catch (_: Throwable) { return false }
        return permissionResults.first()
    }

    private val newProcessMethod by lazy {
        runCatching {
            Shizuku::class.java
                .getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java,
                )
                .apply { isAccessible = true }
        }.getOrNull()
    }

    fun newProcess(cmd: Array<String>): Process? {
        if (!isBinderAlive() || !safeCheckPermission()) return null
        val m = newProcessMethod ?: return null
        return try {
            m.invoke(null, cmd, null, null) as? Process
        } catch (_: Throwable) { null }
    }
}
