package com.wangchaozhi.wechatassistant.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import com.flyfishxu.kadb.shell.AdbShellPacket
import com.flyfishxu.kadb.shell.AdbShellStream
import com.wangchaozhi.wechatassistant.util.WifiAdbManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.Charset

/**
 * 通过 Wi-Fi ADB 启动 `getevent -lt`，解析多点触控协议得到每根手指完整的 down/move/up 序列，
 * 转成 [ServiceBus.RawTouch] 发到 [ServiceBus.recordedTap]。
 * 单实例，调用方负责生命周期。
 */
class WifiAdbTouchReader(private val context: Context) {

    private var job: Job? = null
    private var shell: WifiAdbManager.DedicatedShell? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private data class TouchscreenInfo(
        val devicePath: String,
        val maxX: Int,
        val maxY: Int,
    )

    fun start(scope: CoroutineScope, onError: (String) -> Unit = {}) {
        stop()
        job = scope.launch(Dispatchers.IO) {
            var devices = probeTouchscreens()
            if (devices.isEmpty()) {
                // 多半是上次录制后连接已失效（getevent -lp 跑在死连接上返回空），重连后再探一次
                Log.i(TAG, "probe empty, try reconnect then re-probe")
                if (WifiAdbManager.reconnect().isSuccess) {
                    devices = probeTouchscreens()
                }
            }
            if (devices.isEmpty()) {
                withContext(Dispatchers.Main) { onError("未找到可读取的触摸设备") }
                return@launch
            }
            Log.i(TAG, "start getevent devices=${devices.joinToString { it.devicePath }}")
            // 录制期间保持 Wi-Fi 无线和 CPU 不休眠，缓解后台被掐网导致的断连
            acquireLocks()
            try {
                var failures = 0
                while (isActive) {
                    // getevent 跑在独立连接上：关它会弄坏所在连接，但不影响主连接（探测/校验仍可用）
                    val ds = WifiAdbManager.openDedicatedShell("getevent -lt")
                    if (ds == null) {
                        if (!reconnectOrStop(onError, ++failures)) break
                        continue
                    }
                    shell = ds
                    val startedAt = SystemClock.uptimeMillis()
                    val dropped = try {
                        runReader(ds.stream, devices)
                        true // getevent 进程退出，也按断连处理，尝试恢复
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        // 连接断开时 kadb 的 stream.read() 会抛异常，不能让它崩掉整个 App
                        Log.w(TAG, "reader stopped", e)
                        true
                    } finally {
                        runCatching { ds.close() }
                        if (shell === ds) shell = null
                    }
                    if (!isActive || !dropped) break
                    // 连接稳定运行过一段时间则重置失败计数，避免长录制累积触顶
                    if (SystemClock.uptimeMillis() - startedAt > HEALTHY_RUN_MS) failures = 0
                    if (!reconnectOrStop(onError, ++failures)) break
                }
            } finally {
                releaseLocks()
            }
        }
    }

    /**
     * 断连后自动重连（无需重配对）。返回 false 表示放弃。
     * 进度只打日志，不走 onError——onError 会在「未录到点击时」中止录制，不能被中间态触发。
     */
    private suspend fun reconnectOrStop(onError: (String) -> Unit, failures: Int): Boolean {
        if (failures > MAX_RECONNECT) {
            withContext(Dispatchers.Main) { onError("Wi-Fi ADB 连接已断开，多次自动重连失败") }
            return false
        }
        Log.i(TAG, "connection dropped, auto reconnect attempt $failures/$MAX_RECONNECT")
        val result = WifiAdbManager.reconnect()
        if (result.isFailure) {
            Log.w(TAG, "reconnect failed: ${result.exceptionOrNull()?.message}")
            withContext(Dispatchers.Main) {
                onError("Wi-Fi ADB 连接已断开，自动重连失败：${result.exceptionOrNull()?.message ?: ""}")
            }
            return false
        }
        Log.i(TAG, "auto reconnect ok, restart getevent")
        return true
    }

    private fun acquireLocks() {
        runCatching {
            val wm = context.applicationContext.getSystemService(WifiManager::class.java)
            val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm?.createWifiLock(lockMode, "wca:adb")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            val pm = context.applicationContext.getSystemService(PowerManager::class.java)
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wca:adb-record")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
        wakeLock = null
    }

    fun stop() {
        runCatching { shell?.close() }
        shell = null
        job?.cancel()
        job = null
        releaseLocks()
    }

    private fun runReader(stream: AdbShellStream, devices: List<TouchscreenInfo>) {
        val screenW: Int
        val screenH: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = context.getSystemService(WindowManager::class.java)
            val b = wm.currentWindowMetrics.bounds
            screenW = b.width(); screenH = b.height()
        } else {
            val m = context.resources.displayMetrics
            screenW = m.widthPixels; screenH = m.heightPixels
        }
        val deviceByPath = devices.associateBy { it.devicePath }
        val defaultDevice = devices.first()

        data class SlotState(
            var active: Boolean = false,
            var startX: Int = 0,
            var startY: Int = 0,
            var currentX: Int = 0,
            var currentY: Int = 0,
            var downAt: Long = 0L,
            var pendingX: Int? = null,
            var pendingY: Int? = null,
            var pendingDown: Boolean = false,
            var pendingUp: Boolean = false,
        )

        data class DeviceState(
            var currentSlot: Int = 0,
            val slots: MutableMap<Int, SlotState> = mutableMapOf(),
        )

        val states = mutableMapOf<String, DeviceState>()
        fun deviceState(path: String): DeviceState = states.getOrPut(path) { DeviceState() }
        fun slotState(path: String): SlotState {
            val state = deviceState(path)
            return state.slots.getOrPut(state.currentSlot) { SlotState() }
        }

        fun emitEndedTouch(device: TouchscreenInfo, state: SlotState) {
            if (!state.active) return
            val now = SystemClock.uptimeMillis()
            val dur = (now - state.downAt).coerceAtLeast(60L)
            val sx = if (device.maxX > 0) screenW.toFloat() / device.maxX else 1f
            val sy = if (device.maxY > 0) screenH.toFloat() / device.maxY else 1f
            val raw = ServiceBus.RawTouch(
                startX = state.startX * sx,
                startY = state.startY * sy,
                endX = state.currentX * sx,
                endY = state.currentY * sy,
                durationMs = dur,
                timestamp = state.downAt,
                source = ServiceBus.RawTouchSource.SHIZUKU,
            )
            Log.d(TAG, "touch ${device.devicePath} ${raw.startX},${raw.startY} -> ${raw.endX},${raw.endY} dur=${raw.durationMs}")
            ServiceBus.recordedTap.tryEmit(raw)
        }

        streamLines(stream) { line ->
            val parsed = parseLine(line, defaultDevice.devicePath)
            if (parsed != null) {
                val path = parsed.devicePath
                val device = deviceByPath[path]
                if (device != null) {
                when (val ev = parsed.event) {
                    is Event.Slot -> deviceState(path).currentSlot = ev.index.coerceAtLeast(0)
                    is Event.TrackingId -> {
                        val state = slotState(path)
                        if (ev.id == -1) state.pendingUp = true else state.pendingDown = true
                    }
                    is Event.X -> slotState(path).pendingX = ev.value
                    is Event.Y -> slotState(path).pendingY = ev.value
                    Event.Sync -> {
                        val frameTime = SystemClock.uptimeMillis()
                        deviceState(path).slots.values.forEach { state ->
                            state.pendingX?.let { state.currentX = it }
                            state.pendingY?.let { state.currentY = it }
                            if (state.pendingDown) {
                                state.active = true
                                state.startX = state.currentX
                                state.startY = state.currentY
                                state.downAt = frameTime
                            }
                            if (state.pendingUp) {
                                emitEndedTouch(device, state)
                                state.active = false
                            }
                            state.pendingX = null
                            state.pendingY = null
                            state.pendingDown = false
                            state.pendingUp = false
                        }
                    }
                }
                }
            }
        }
    }

    private inline fun streamLines(stream: AdbShellStream, onLine: (String) -> Unit) {
        val charset = Charset.defaultCharset()
        val pending = StringBuilder()
        while (true) {
            when (val packet = stream.read()) {
                is AdbShellPacket.StdOut -> {
                    pending.append(packet.payload.toString(charset))
                    while (true) {
                        val i = pending.indexOf("\n")
                        if (i < 0) break
                        val line = pending.substring(0, i).trimEnd('\r')
                        pending.delete(0, i + 1)
                        onLine(line)
                    }
                }
                is AdbShellPacket.Exit -> {
                    if (pending.isNotEmpty()) onLine(pending.toString())
                    return
                }
                is AdbShellPacket.StdError -> Unit
            }
        }
    }

    private sealed interface Event {
        data class Slot(val index: Int) : Event
        data class TrackingId(val id: Int) : Event
        data class X(val value: Int) : Event
        data class Y(val value: Int) : Event
        data object Sync : Event
    }

    private data class ParsedEvent(val devicePath: String, val event: Event)

    private fun parseLine(line: String, defaultDevicePath: String): ParsedEvent? {
        // 例：[  12345.678] EV_ABS       ABS_MT_POSITION_X    000003e8
        // 或：[  12345.678] /dev/input/event2: EV_ABS ABS_MT_POSITION_X 000003e8
        val afterTs = line.substringAfterLast("] ", line)
        val path = Regex("""(/dev/input/event\d+):""").find(afterTs)?.groupValues?.get(1)
            ?: defaultDevicePath
        val afterPath = afterTs.substringAfter(": ", afterTs).trim()
        val parts = afterPath.split(Regex("\\s+"))
        if (parts.size < 3) return null
        val type = parts[0]
        val code = parts[1]
        val rawVal = parts.last()
        val v = runCatching { rawVal.toLong(16) }.getOrNull() ?: return null
        val event = when {
            type == "EV_ABS" && code == "ABS_MT_SLOT" -> Event.Slot(v.toInt())
            type == "EV_ABS" && code == "ABS_MT_TRACKING_ID" -> {
                val id = if (v == 0xffffffffL || v.toInt() == -1) -1 else v.toInt()
                Event.TrackingId(id)
            }
            type == "EV_ABS" && code == "ABS_MT_POSITION_X" -> Event.X(v.toInt())
            type == "EV_ABS" && code == "ABS_MT_POSITION_Y" -> Event.Y(v.toInt())
            type == "EV_SYN" && code == "SYN_REPORT" -> Event.Sync
            else -> null
        }
        return event?.let { ParsedEvent(path, it) }
    }

    private suspend fun probeTouchscreens(): List<TouchscreenInfo> = withContext(Dispatchers.IO) {
        val text = WifiAdbManager.shell("getevent -lp").getOrNull() ?: return@withContext null
        parseProbe(text)
    }.orEmpty()

    private fun parseProbe(text: String): List<TouchscreenInfo> {
        val devices = mutableListOf<TouchscreenInfo>()
        var path: String? = null
        var maxX = 0
        var maxY = 0
        var hasMt = false
        var direct = false
        fun commit() {
            val p = path
            if (p != null && hasMt && direct && maxX > 0 && maxY > 0) {
                devices += TouchscreenInfo(p, maxX, maxY)
            }
        }
        text.lineSequence().forEach { line ->
            val devMatch = Regex("""add device \d+: (/dev/input/event\d+)""").find(line)
            if (devMatch != null) {
                commit()
                path = devMatch.groupValues[1]
                maxX = 0; maxY = 0; hasMt = false; direct = false
                return@forEach
            }
            if (line.contains("INPUT_PROP_DIRECT")) { direct = true; return@forEach }
            val xMatch = Regex("""ABS_MT_POSITION_X\s*:.*?max\s+(\d+)""").find(line)
            if (xMatch != null) { maxX = xMatch.groupValues[1].toInt(); hasMt = true; return@forEach }
            val yMatch = Regex("""ABS_MT_POSITION_Y\s*:.*?max\s+(\d+)""").find(line)
            if (yMatch != null) { maxY = yMatch.groupValues[1].toInt(); hasMt = true }
        }
        commit()
        val largestArea = devices.maxOfOrNull { it.maxX.toLong() * it.maxY.toLong() } ?: return emptyList()
        return devices.filter { it.maxX.toLong() * it.maxY.toLong() == largestArea }
    }

    companion object {
        private const val TAG = "WifiAdbTouchReader"
        private const val MAX_RECONNECT = 4
        private const val HEALTHY_RUN_MS = 20_000L
    }
}
