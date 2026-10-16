package dev.ogios.nanoleaf

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.util.ArrayDeque

data class DiscoveredDevice(val name: String, val endpoint: Endpoint, val model: String)

@Suppress("DEPRECATION") // resolveService supports Android 8–13 as well as newer devices.
class DeviceDiscovery(
    context: Context,
    private val onFound: (DiscoveredDevice) -> Unit,
    private val onFinished: (String) -> Unit,
) : NsdManager.DiscoveryListener {
    private val manager = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val lock = context.applicationContext.getSystemService(WifiManager::class.java)
        ?.createMulticastLock("nanoleaf-discovery")?.apply { setReferenceCounted(false) }
    private val pending = ArrayDeque<NsdServiceInfo>()
    private val seen = mutableSetOf<String>()
    private var resolving = false
    private var stopped = false
    private var started = false
    private val timeout = Runnable { stop() }

    fun start() {
        try {
            lock?.acquire()
            manager.discoverServices("_nanoleafapi._tcp.", NsdManager.PROTOCOL_DNS_SD, this)
            started = true
            main.postDelayed(timeout, 8000)
        } catch (_: RuntimeException) { finish("无法开始扫描，请检查 Wi-Fi 后重试") }
    }

    fun stop() {
        if (stopped) return
        finish("扫描结束；未找到时可手动填写 IP")
        if (started) {
            try { manager.stopServiceDiscovery(this) } catch (_: RuntimeException) { /* already stopped */ }
        }
    }

    private fun finish(message: String) {
        if (stopped) return
        stopped = true
        main.removeCallbacks(timeout)
        pending.clear()
        if (lock?.isHeld == true) lock.release()
        onFinished(message)
    }

    override fun onDiscoveryStarted(type: String) = Unit
    override fun onServiceLost(info: NsdServiceInfo) = Unit
    override fun onDiscoveryStopped(type: String) { main.post { finish("扫描结束") } }
    override fun onStartDiscoveryFailed(type: String, code: Int) {
        main.post { finish("扫描失败（$code），可手动填写 IP") }
    }
    override fun onStopDiscoveryFailed(type: String, code: Int) {
        main.post { finish("扫描已结束（$code）") }
    }

    override fun onServiceFound(info: NsdServiceInfo) {
        main.post {
            if (!stopped && seen.size < 32 && seen.add(info.serviceName)) {
                pending.add(info)
                resolveNext()
            }
        }
    }

    // Older NSD implementations allow only one outstanding resolve request.
    private fun resolveNext() {
        if (stopped || resolving || pending.isEmpty()) return
        resolving = true
        try {
            manager.resolveService(pending.removeFirst(), object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                    main.post { resolving = false; resolveNext() }
                }
                override fun onServiceResolved(info: NsdServiceInfo) {
                    main.post {
                        resolving = false
                        if (stopped) return@post
                        val addresses = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
                        val ip = addresses.filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress
                        if (ip != null) {
                            try {
                                val model = info.attributes["md"]?.toString(Charsets.UTF_8)?.take(80) ?: "未知型号"
                                onFound(DiscoveredDevice(info.serviceName.take(80), Endpoint(ip, info.port), model))
                            } catch (_: IllegalArgumentException) { /* Ignore non-local or invalid advertisements. */ }
                        }
                        resolveNext()
                    }
                }
            })
        } catch (_: RuntimeException) { resolving = false; resolveNext() }
    }
}
