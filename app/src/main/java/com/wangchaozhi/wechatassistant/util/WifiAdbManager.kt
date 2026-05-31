package com.wangchaozhi.wechatassistant.util

import android.content.Context
import android.util.Log
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.KadbCertPolicy
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore
import com.wangchaozhi.wechatassistant.data.repo.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import java.io.File

object WifiAdbManager {

    private var settings: SettingsRepository? = null
    private var appContext: Context? = null
    private var client: Kadb? = null

    private val _state = MutableStateFlow(Status())
    val state = _state.asStateFlow()

    data class Status(
        val host: String = SettingsRepository.DEFAULT_ADB_HOST,
        val pairingPort: Int = 0,
        val connectPort: Int = 0,
        val connected: Boolean = false,
        val busy: Boolean = false,
        val message: String = "",
    )

    fun install(context: Context, settingsRepository: SettingsRepository) {
        settings = settingsRepository
        appContext = context.applicationContext
        val keyPath = context.filesDir.resolve("kadb_private_key.pem").absolutePath.toPath()
        KadbCert.configure(
            store = OkioFilePrivateKeyStore(keyPath, FileSystem.SYSTEM),
            policy = KadbCertPolicy(),
        )
        refresh()
    }

    fun refresh() {
        val s = settings ?: return
        val connected = runCatching { client?.connectionCheck() == true }.getOrDefault(false)
        if (!connected) {
            runCatching { client?.close() }
            client = null
        }
        _state.value = _state.value.copy(
            host = s.adbHost.ifBlank { SettingsRepository.DEFAULT_ADB_HOST },
            pairingPort = s.adbPairingPort,
            connectPort = s.adbConnectPort,
            connected = connected,
            busy = false,
        )
    }

    fun saveConfig(host: String, pairingPort: Int, connectPort: Int) {
        val s = settings ?: return
        s.adbHost = host.ifBlank { SettingsRepository.DEFAULT_ADB_HOST }
        s.adbPairingPort = pairingPort.coerceAtLeast(0)
        s.adbConnectPort = connectPort.coerceAtLeast(0)
        refresh()
    }

    suspend fun pair(pairingCode: String): Result<Unit> = withContext(Dispatchers.IO) {
        val s = settings ?: return@withContext Result.failure(IllegalStateException("Wi-Fi ADB 未初始化"))
        val host = s.adbHost.ifBlank { SettingsRepository.DEFAULT_ADB_HOST }
        val port = s.adbPairingPort
        if (port <= 0) return@withContext Result.failure(IllegalArgumentException("请填写配对端口"))
        if (pairingCode.isBlank()) return@withContext Result.failure(IllegalArgumentException("请填写配对码"))
        setBusy("正在配对...")
        runCatching {
            KadbCert.ensureReady()
            Kadb.pair(host, port, pairingCode.trim())
            Log.i(TAG, "pair success host=$host port=$port")
            Unit
        }.also { result ->
            result.exceptionOrNull()?.let { Log.w(TAG, "pair failed host=$host port=$port", it) }
            _state.value = _state.value.copy(
                busy = false,
                message = result.fold({ "配对成功，请填写连接端口后连接" }, { "配对失败：${it.message ?: it.javaClass.simpleName}" }),
            )
        }
    }

    suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        val s = settings ?: return@withContext Result.failure(IllegalStateException("Wi-Fi ADB 未初始化"))
        val host = s.adbHost.ifBlank { SettingsRepository.DEFAULT_ADB_HOST }
        val port = s.adbConnectPort
        if (port <= 0) return@withContext Result.failure(IllegalArgumentException("请填写连接端口"))
        setBusy("正在连接...")
        runCatching {
            client?.close()
            client = Kadb.create(host, port)
            val response = client!!.shell("echo wca_adb_ok")
            check(response.exitCode == 0 && response.output.contains("wca_adb_ok")) {
                response.errorOutput.ifBlank { response.allOutput.ifBlank { "shell 检查失败" } }
            }
            Log.i(TAG, "connect success host=$host port=$port")
            Unit
        }.also { result ->
            result.exceptionOrNull()?.let { Log.w(TAG, "connect failed host=$host port=$port", it) }
            _state.value = _state.value.copy(
                connected = result.isSuccess,
                busy = false,
                message = result.fold({ "已连接 Wi-Fi ADB" }, { "连接失败：${it.message ?: it.javaClass.simpleName}" }),
            )
        }
    }

    suspend fun pairAndConnectAuto(pairingCode: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (pairingCode.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("请填写配对码"))
        }
        val s = settings ?: return@withContext Result.failure(IllegalStateException("Wi-Fi ADB 未初始化"))
        val ctx = appContext ?: return@withContext Result.failure(IllegalStateException("Wi-Fi ADB 未初始化"))
        val fallbackHost = s.adbHost.ifBlank { SettingsRepository.DEFAULT_ADB_HOST }

        // 优先用系统无线调试通过 mDNS 广播的配对服务定位端口；
        // /proc/net/tcp 在 Android 10+ 被 SELinux 限制读不到，只作旧设备兜底。
        setBusy("正在查找无线调试配对端口...")
        val pairingService = AdbMdns.discover(ctx, AdbMdns.TLS_PAIRING)
        val pairCandidates = buildList {
            if (pairingService != null) add(pairingService.host to pairingService.port)
            listenPorts().filter { it in 30000..49999 }.forEach { add(fallbackHost to it) }
        }.distinct()
        if (pairCandidates.isEmpty()) {
            _state.value = _state.value.copy(busy = false)
            return@withContext Result.failure(
                IllegalStateException("未找到无线调试配对端口，请确认「无线调试」已开启并停留在配对弹窗"),
            )
        }

        setBusy("正在配对...")
        var pairHost = ""
        var pairPort = 0
        var lastError: Throwable? = null
        for ((host, port) in pairCandidates) {
            val ok = runCatching {
                KadbCert.ensureReady()
                Kadb.pair(host, port, pairingCode.trim())
            }.onFailure { lastError = it }.isSuccess
            if (ok) {
                pairHost = host
                pairPort = port
                Log.i(TAG, "auto pair success host=$host port=$port")
                break
            }
        }
        if (pairPort <= 0) {
            _state.value = _state.value.copy(busy = false)
            return@withContext Result.failure(lastError ?: IllegalStateException("没有端口接受该配对码"))
        }

        // 配对端口是一次性的，连接端口要重新发现。adbd 配对完后可能要一会儿才在连接端口起监听，
        // mDNS 也可能先返回旧端口，所以带延迟重试，每轮重新发现，并始终保留回环兜底。
        setBusy("配对成功，正在连接...")
        repeat(CONNECT_ATTEMPTS) { round ->
            // 网络上可能有多个 _adb-tls-connect 实例（僵尸旧端口 + 真实端口），全部收集逐个试
            val services = AdbMdns.discoverAll(ctx, AdbMdns.TLS_CONNECT, gatherMs = 2500)
            val ports = (services.map { it.port } +
                listenPorts().filter { it in 30000..49999 && it != pairPort }).distinct()
            val hosts = (services.map { it.host } + LOOPBACK + pairHost).distinct()
            // 自己连自己优先走回环，避开 Wi-Fi 局域网 IP 的 hairpin（更稳，部分 ROM 会掐）
            val candidates = ports.flatMap { port -> hosts.map { host -> host to port } }
                .sortedBy { it.first != LOOPBACK }
                .distinct()
            Log.i(TAG, "connect round=$round services=$services candidates=$candidates")
            for ((host, port) in candidates) {
                val ok = runCatching {
                    client?.close()
                    client = Kadb.create(host, port)
                    val response = client!!.shell("echo wca_adb_ok")
                    check(response.exitCode == 0 && response.output.contains("wca_adb_ok")) {
                        response.errorOutput.ifBlank { response.allOutput.ifBlank { "shell 检查失败" } }
                    }
                }.onFailure { lastError = it; Log.w(TAG, "connect attempt failed host=$host port=$port: ${it.message}") }.isSuccess
                if (ok) {
                    s.adbHost = host
                    s.adbPairingPort = pairPort
                    s.adbConnectPort = port
                    _state.value = _state.value.copy(
                        host = host,
                        pairingPort = pairPort,
                        connectPort = port,
                        connected = true,
                        busy = false,
                        message = "已自动连接 Wi-Fi ADB",
                    )
                    Log.i(TAG, "auto connect success host=$host port=$port")
                    return@withContext Result.success(Unit)
                }
            }
            if (round < CONNECT_ATTEMPTS - 1) delay(800)
        }
        _state.value = _state.value.copy(connected = false, busy = false)
        Result.failure(lastError ?: IllegalStateException("配对成功，但未找到可用连接端口"))
    }

    fun disconnect() {
        runCatching { client?.close() }
        client = null
        _state.value = _state.value.copy(connected = false, busy = false, message = "已断开")
    }

    suspend fun shell(command: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val adb = requireClient()
            val response = adb.shell(command)
            if (response.exitCode != 0) {
                error(response.errorOutput.ifBlank { "shell exit ${response.exitCode}" })
            }
            response.output
        }
    }

    fun openShell(command: String) = runCatching { requireClient().openShell(command) }

    private fun requireClient(): Kadb {
        val adb = client ?: error("请先在设置中连接 Wi-Fi ADB")
        check(runCatching { adb.connectionCheck() }.getOrDefault(false)) { "Wi-Fi ADB 连接已断开" }
        return adb
    }

    private fun setBusy(message: String) {
        _state.value = _state.value.copy(busy = true, message = message)
    }

    private fun listenPorts(): List<Int> =
        (parseProcNetTcp("/proc/net/tcp") + parseProcNetTcp("/proc/net/tcp6"))
            .distinct()
            .sorted()

    private fun parseProcNetTcp(path: String): List<Int> =
        runCatching {
            File(path).readLines().drop(1).mapNotNull { line ->
                val cols = line.trim().split(Regex("\\s+"))
                if (cols.size < 4 || cols[3] != "0A") return@mapNotNull null
                val local = cols[1]
                val portHex = local.substringAfterLast(":", "")
                portHex.toIntOrNull(16)
            }
        }.getOrDefault(emptyList())

    private const val TAG = "WifiAdbManager"
    private const val LOOPBACK = "127.0.0.1"
    private const val CONNECT_ATTEMPTS = 5
}
