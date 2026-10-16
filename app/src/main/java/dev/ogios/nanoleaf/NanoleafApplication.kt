package dev.ogios.nanoleaf

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.TileService
import java.util.concurrent.Executors

class NanoleafApplication : Application() {
    val repository by lazy { LightRepository(this) }
}

val Context.lightRepository: LightRepository
    get() = (applicationContext as NanoleafApplication).repository

class LightRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("nanoleaf_device", Context.MODE_PRIVATE)
    private val api = NanoleafApi()
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<() -> Unit>()
    var busy = false
        private set
    var message = ""
        private set
    val device: Device?
        get() = try {
            Device(Endpoint(prefs.getString("ip", "")!!, prefs.getInt("port", 16021)),
                prefs.getString("token", "")!!, prefs.getString("model", "未知型号")!!)
        } catch (_: IllegalArgumentException) { null }
    val lastState: Boolean? get() = when (prefs.getInt("state", -1)) { 0 -> false; 1 -> true; else -> null }
    val observedAt: Long get() = prefs.getLong("observed_at", 0)

    fun subscribe(listener: () -> Unit) { listeners.add(listener) }
    fun unsubscribe(listener: () -> Unit) { listeners.remove(listener) }

    private fun notifyChanged() {
        listeners.toList().forEach { it() }
        TileService.requestListeningState(context, ComponentName(context, LightTileService::class.java))
    }

    private fun saveDevice(device: Device) {
        val saved = prefs.edit().putString("ip", device.endpoint.ip).putInt("port", device.endpoint.port)
            .putString("token", device.token).putString("model", device.model)
            .putInt("state", -1).remove("observed_at").commit()
        if (!saved) throw ControlException("无法保存设备授权，请检查存储空间后重试")
    }

    // All commands from the Activity and tile share this gate and worker.
    // It prevents two concurrent read/invert/write sequences losing a toggle.
    private fun execute(label: String, action: () -> Boolean) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (busy) return
        busy = true
        message = label
        notifyChanged()
        worker.execute {
            val result = try { Result.success(action()) }
            catch (error: Exception) { Result.failure(error) }
            main.post {
                busy = false
                result.fold(onSuccess = { on ->
                    prefs.edit().putInt("state", if (on) 1 else 0)
                        .putLong("observed_at", System.currentTimeMillis()).apply()
                    message = "已确认灯带${if (on) "开启" else "关闭"}"
                }, onFailure = { error ->
                    prefs.edit().putInt("state", -1).remove("observed_at").apply()
                    message = (error as? ControlException)?.message ?: "操作失败，请检查网络后重试"
                })
                notifyChanged()
            }
        }
    }

    fun refresh() {
        val current = device ?: return
        execute("正在读取状态…") { api.read(current) }
    }

    fun power(on: Boolean? = null) {
        val current = device ?: return
        execute("正在控制灯带…") { if (on == null) api.toggle(current) else api.setPower(current, on) }
    }

    fun pair(endpoint: Endpoint, model: String) {
        execute("正在获取授权…") {
            val authorized = api.authorize(endpoint, model)
            // Save the new credential before optional follow-up reads can fail.
            saveDevice(authorized)
            val reportedModel = try { api.model(authorized) } catch (_: ControlException) { model }
            saveDevice(Device(endpoint, authorized.token, reportedModel))
            api.read(authorized)
        }
    }

    fun useToken(device: Device) {
        execute("正在验证连接…") {
            api.read(device).also { saveDevice(device) }
        }
    }

    fun forget() {
        if (busy) return
        prefs.edit().clear().apply()
        message = "已清除本机保存的连接信息"
        notifyChanged()
    }
}
