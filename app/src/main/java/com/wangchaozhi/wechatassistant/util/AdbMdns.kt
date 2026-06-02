package com.wangchaozhi.wechatassistant.util

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 通过系统 mDNS（NsdManager）发现无线调试广播的服务。
 *
 * Android 10+ 下 `/proc/net/tcp` 被 SELinux 限制，普通应用读不到 adbd 监听的端口，
 * 只能依赖系统无线调试通过 DNS-SD 广播的以下服务来拿到端口：
 *  - [TLS_PAIRING] 配对端口（配对弹窗里随机生成的那个）
 *  - [TLS_CONNECT] 连接端口
 *
 * 坑点：
 *  1. 旧 [NsdManager.resolveService] 会返回缓存里**过期**的 SRV 记录，Android 14+ 用
 *     [NsdManager.registerServiceInfoCallback] 拿实时端口。
 *  2. 网络上可能同时存在**多个**同类型实例（旧 adbd 残留的僵尸端口 + 当前真实端口），
 *     所以不能拿到第一个就返回，要收集一个时间窗内的**全部**实例，挨个去连。
 */
object AdbMdns {
    const val TLS_PAIRING = "_adb-tls-pairing._tcp"
    const val TLS_CONNECT = "_adb-tls-connect._tcp"

    data class Service(val host: String, val port: Int)

    /** 发现指定类型的第一个服务，优先 IPv4。给配对用（配对端口一般唯一）。 */
    suspend fun discover(context: Context, serviceType: String, timeoutMs: Long = 6000): Service? =
        discoverAll(context, serviceType, gatherMs = timeoutMs, stopOnFirst = true).firstOrNull()

    /**
     * 收集 [gatherMs] 时间窗内发现到的全部服务实例（去重）。IPv4 排在前面。
     * @param stopOnFirst 为 true 时拿到首个 IPv4 即提前返回（配对场景）。
     */
    suspend fun discoverAll(
        context: Context,
        serviceType: String,
        gatherMs: Long = 2500,
        stopOnFirst: Boolean = false,
    ): List<Service> {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return emptyList()
        val results = CopyOnWriteArrayList<Service>()

        withTimeoutOrNull(gatherMs) {
            suspendCancellableCoroutine<Unit> { cont ->
                val cleanups = CopyOnWriteArrayList<() -> Unit>()
                val resumed = AtomicBoolean(false)
                lateinit var listener: NsdManager.DiscoveryListener

                fun stop() {
                    cleanups.forEach { runCatching { it() } }
                    runCatching { nsd.stopServiceDiscovery(listener) }
                }

                fun resumeOnce() {
                    if (resumed.compareAndSet(false, true)) {
                        stop()
                        cont.resumeWith(Result.success(Unit))
                    }
                }

                fun add(addresses: List<InetAddress>, port: Int) {
                    if (port <= 0) return
                    val addr = addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull() ?: return
                    val service = Service(addr.hostAddress ?: addr.hostName, port)
                    if (results.none { it.host == service.host && it.port == service.port }) {
                        results.add(service)
                        Log.i(TAG, "found $serviceType -> $service")
                    }
                    if (stopOnFirst && addr is Inet4Address) {
                        resumeOnce()
                    }
                }

                // Android 14+：实时解析，绕开 resolveService 缓存
                fun resolveModern(found: NsdServiceInfo) {
                    val callback = object : NsdManager.ServiceInfoCallback {
                        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                            Log.w(TAG, "info callback reg failed $serviceType code=$errorCode")
                        }

                        override fun onServiceUpdated(info: NsdServiceInfo) {
                            @Suppress("NewApi")
                            add(info.hostAddresses, info.port)
                        }

                        override fun onServiceLost() {}
                        override fun onServiceInfoCallbackUnregistered() {}
                    }
                    runCatching {
                        nsd.registerServiceInfoCallback(found, Executor { it.run() }, callback)
                        cleanups.add { runCatching { nsd.unregisterServiceInfoCallback(callback) } }
                    }.onFailure { Log.w(TAG, "registerServiceInfoCallback threw", it) }
                }

                // Android 13 及以下：串行 resolveService
                val resolving = AtomicBoolean(false)
                val pending = ConcurrentLinkedQueue<NsdServiceInfo>()
                fun resolveLegacy() {
                    if (!cont.isActive) return
                    if (!resolving.compareAndSet(false, true)) return
                    val info = pending.poll() ?: run { resolving.set(false); return }
                    @Suppress("DEPRECATION")
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                            resolving.set(false); resolveLegacy()
                        }

                        @Suppress("DEPRECATION")
                        override fun onServiceResolved(si: NsdServiceInfo) {
                            resolving.set(false)
                            si.host?.let { add(listOf(it), si.port) }
                            resolveLegacy()
                        }
                    })
                }

                listener = object : NsdManager.DiscoveryListener {
                    override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
                        Log.w(TAG, "start discovery failed $type code=$errorCode")
                        resumeOnce()
                    }

                    override fun onStopDiscoveryFailed(type: String, errorCode: Int) {}
                    override fun onDiscoveryStarted(type: String) {}
                    override fun onDiscoveryStopped(type: String) {}

                    override fun onServiceFound(si: NsdServiceInfo) {
                        if (!si.serviceType.trimEnd('.').endsWith(serviceType.trimEnd('.'))) return
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            resolveModern(si)
                        } else {
                            pending.add(si); resolveLegacy()
                        }
                    }

                    override fun onServiceLost(si: NsdServiceInfo) {}
                }

                cont.invokeOnCancellation { stop() }

                runCatching {
                    nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
                }.onFailure {
                    Log.w(TAG, "discoverServices threw for $serviceType", it)
                    resumeOnce()
                }
            }
        }

        // IPv4 排前面
        return results.sortedBy { it.host.contains(':') }
    }

    private const val TAG = "AdbMdns"
}
