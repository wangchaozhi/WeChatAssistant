package com.wangchaozhi.wechatassistant.service

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import com.wangchaozhi.wechatassistant.util.ShizukuManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 通过 Shizuku 启动 `getevent -lt`，解析多点触控协议得到每根手指完整的 down/move/up 序列，
 * 转成 [ServiceBus.RawTouch] 发到 [ServiceBus.recordedTap]。
 * 单实例，调用方负责生命周期。
 */
class ShizukuTouchReader(private val context: Context) {

    private var job: Job? = null
    private var process: Process? = null

    private data class TouchscreenInfo(
        val devicePath: String,
        val maxX: Int,
        val maxY: Int,
    )

    fun start(scope: CoroutineScope, onError: (String) -> Unit = {}) {
        stop()
        job = scope.launch(Dispatchers.IO) {
            val devices = probeTouchscreens()
            if (devices.isEmpty()) {
                withContext(Dispatchers.Main) { onError("未找到可读取的触摸设备") }
                return@launch
            }
            Log.i(TAG, "start getevent devices=${devices.joinToString { it.devicePath }}")
            val cmd = arrayOf("sh", "-c", "exec getevent -lt")
            val proc = ShizukuManager.newProcess(cmd)
            if (proc == null) {
                withContext(Dispatchers.Main) { onError("Shizuku 无法启动 getevent") }
                return@launch
            }
            process = proc
            try {
                runReader(proc, devices)
                withContext(Dispatchers.Main) { onError("Shizuku 录制进程已退出") }
            } finally {
                runCatching { proc.destroy() }
                if (process === proc) process = null
            }
        }
    }

    fun stop() {
        runCatching { process?.destroy() }
        process = null
        job?.cancel()
        job = null
    }

    private fun runReader(proc: Process, devices: List<TouchscreenInfo>) {
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

        proc.inputStream.bufferedReader().useLines { lines ->
            for (line in lines) {
                val parsed = parseLine(line, defaultDevice.devicePath) ?: continue
                val path = parsed.devicePath
                val device = deviceByPath[path] ?: continue
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
        val proc = ShizukuManager.newProcess(arrayOf("sh", "-c", "getevent -lp")) ?: return@withContext null
        val text = try { proc.inputStream.bufferedReader().readText() } finally { runCatching { proc.destroy() } }
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
        private const val TAG = "ShizukuTouchReader"
    }
}
