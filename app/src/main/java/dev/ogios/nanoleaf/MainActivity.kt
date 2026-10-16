package dev.ogios.nanoleaf

import android.app.Activity
import android.app.AlertDialog
import android.app.StatusBarManager
import android.content.ComponentName
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity() {
    private val repository get() = lightRepository
    private val green = Color.rgb(37, 116, 83)
    private lateinit var status: TextView
    private lateinit var deviceLabel: TextView
    private lateinit var message: TextView
    private lateinit var timestamp: TextView
    private lateinit var power: Button
    private lateinit var scan: Button
    private lateinit var results: LinearLayout
    private lateinit var scanMessage: TextView
    private val controls = mutableListOf<Button>()
    private var discovery: DeviceDiscovery? = null
    private val found = mutableSetOf<Endpoint>()
    private var editorDialog: AlertDialog? = null
    private val listener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val page = column().apply { setPadding(dp(24), dp(16), dp(24), dp(28)) }
        scroll.addView(page)
        setContentView(scroll)
        // Target 35 uses edge-to-edge: keep every control clear of system bars and keyboard.
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout() or android.view.WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        scroll.requestApplyInsets()
        page.addView(text("NANOLEAF  /  局域网控制", 12, green))
        page.addView(text("灯带", 36).apply { setTypeface(typeface, Typeface.BOLD) })
        page.addView(text("让光，随手可控。", 16, Color.DKGRAY))

        val card = column().apply {
            setPadding(dp(20), dp(24), dp(20), dp(20))
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(24).toFloat() }
        }
        page.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24); bottomMargin = dp(24) })
        deviceLabel = text("尚未连接设备", 14, Color.DKGRAY).also { card.addView(it) }
        status = text("等待连接", 30).also { card.addView(it) }
        timestamp = text("", 12, Color.DKGRAY).also { card.addView(it) }
        power = button("切换开关") { repository.power() }.apply {
            textSize = 22f
            minHeight = dp(88)
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(green)
        }.also { card.addView(it, LinearLayout.LayoutParams(-1, -2)) }
        val actions = LinearLayout(this)
        actions.addView(button("开启") { repository.power(true) }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(button("关闭") { repository.power(false) }, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(actions)
        card.addView(button("刷新状态") { repository.refresh() })
        message = text("", 14).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        card.addView(message)

        page.addView(button("添加到下拉快捷面板") { addTile() }.apply {
            controls.remove(this)
        })
        page.addView(text("快捷磁贴可直接开关灯带；长按可打开应用。", 13, Color.DKGRAY))
        page.addView(text("连接设备", 24).apply { setPadding(0, dp(24), 0, dp(8)) })
        page.addView(text("手机与灯带需在同一局域网。先扫描，再选择你的灯带。", 15))
        scan = button("扫描设备（8 秒）") { startScan() }.also { page.addView(it) }
        scanMessage = text("仅在点击扫描时搜索设备", 13, Color.DKGRAY).also { page.addView(it) }
        results = column().also { page.addView(it) }
        page.addView(button("手动连接 / 输入已有令牌") { showManualEditor() })
        page.addView(button("清除连接信息") {
            AlertDialog.Builder(this).setTitle("清除连接信息？")
                .setMessage("清除本应用保存的设备和令牌。再次控制时需重新连接。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清除") { _, _ -> repository.forget() }.show()
        })
        page.addView(text("只在打开页面或操作时连接设备，无后台轮询。令牌仅保存在本应用内，不参与系统备份。", 12, Color.DKGRAY))
        render()
    }

    override fun onStart() {
        super.onStart()
        repository.subscribe(listener)
        render()
    }

    override fun onResume() {
        super.onResume()
        repository.refresh()
    }

    override fun onStop() {
        discovery?.stop()
        discovery = null
        repository.unsubscribe(listener)
        super.onStop()
    }

    override fun onDestroy() {
        editorDialog?.dismiss()
        super.onDestroy()
    }

    private fun render() {
        val device = repository.device
        val state = repository.lastState
        deviceLabel.text = device?.let { "${it.model} · ${it.endpoint.ip}:${it.endpoint.port}" } ?: "尚未连接设备"
        status.text = when {
            repository.busy -> "正在连接…"
            device == null -> "等待连接"
            state == true -> "灯带已开启"
            state == false -> "灯带已关闭"
            else -> "状态未知"
        }
        status.setTextColor(if (state == true) green else Color.rgb(42, 51, 45))
        timestamp.text = if (repository.observedAt > 0) "上次确认 ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(repository.observedAt))}" else ""
        power.text = when (state) { true -> "关闭灯带"; false -> "开启灯带"; null -> "读取并切换" }
        message.text = repository.message.ifBlank { "连接后即可控制灯带" }
        controls.forEach { it.isEnabled = !repository.busy }
        // The first four controls belong to the power card.
        controls.take(4).forEach { it.isEnabled = device != null && !repository.busy }
        scan.isEnabled = !repository.busy && discovery == null
        for (index in 0 until results.childCount) results.getChildAt(index).isEnabled = !repository.busy
    }

    private fun startScan() {
        found.clear()
        results.removeAllViews()
        scanMessage.text = "正在搜索局域网中的 Nanoleaf…"
        val scanner = DeviceDiscovery(this, onFound = { device ->
            if (found.add(device.endpoint)) {
                results.addView(Button(this).apply {
                    text = "${device.name}\n${device.model} · ${device.endpoint.ip}:${device.endpoint.port}"
                    isAllCaps = false
                    isEnabled = !repository.busy
                    setOnClickListener { confirmPair(device) }
                })
            }
        }, onFinished = { result ->
            discovery = null
            scanMessage.text = if (found.isEmpty()) result else "找到 ${found.size} 台设备，点击连接"
            render()
        })
        discovery = scanner
        render()
        scanner.start()
    }

    private fun confirmPair(device: DiscoveredDevice) {
        AlertDialog.Builder(this).setTitle("连接 ${device.name}")
            .setMessage("在 Nanoleaf App 中打开这台设备的设置，点击 Connect to API，然后在 30 秒内点击下方“获取授权”。")
            .setNegativeButton("取消", null)
            .setPositiveButton("获取授权") { _, _ ->
                discovery?.stop()
                repository.pair(device.endpoint, device.model)
            }.show()
    }

    private fun showManualEditor() {
        val form = column().apply { setPadding(dp(24), dp(8), dp(24), 0) }
        val current = repository.device
        fun field(label: String, value: String, input: Int): EditText {
            form.addView(text(label, 14))
            return EditText(this).apply {
                inputType = input
                isSingleLine = true
                isSaveEnabled = false
                setText(value)
                form.addView(this)
            }
        }
        val ip = field("局域网 IPv4 地址", current?.endpoint?.ip ?: "192.168.0.106",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        val port = field("端口", (current?.endpoint?.port ?: 16021).toString(), InputType.TYPE_CLASS_NUMBER)
        val token = field("已有令牌（选填，留空则申请授权）", "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        form.addView(text("申请授权前，请先在 Nanoleaf App 的设备设置中点击 Connect to API。", 13))
        val error = text("", 13, Color.rgb(170, 40, 40)).also { form.addView(it) }
        val dialog = AlertDialog.Builder(this).setTitle("手动连接")
            .setView(ScrollView(this).apply { addView(form) })
            .setNegativeButton("取消", null).setPositiveButton("连接", null).create()
        editorDialog = dialog
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val endpoint = Endpoint(ip.text.toString().trim(), port.text.toString().toIntOrNull() ?: 0)
                    val credential = token.text.toString().trim()
                    val manualDevice = if (credential.isEmpty()) null else Device(endpoint, credential)
                    discovery?.stop()
                    if (manualDevice == null) repository.pair(endpoint, current?.model ?: "未知型号")
                    else repository.useToken(manualDevice)
                    token.text.clear()
                    dialog.dismiss()
                } catch (invalid: IllegalArgumentException) { error.text = invalid.message }
            }
        }
        dialog.show()
    }

    private fun addTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(this, LightTileService::class.java), getString(R.string.tile_name),
                Icon.createWithResource(this, R.drawable.ic_light), mainExecutor,
            ) { result ->
                val message = when (result) {
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "已添加灯带开关"
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "灯带开关已在快捷面板中"
                    else -> "可下拉快捷面板，点击编辑并添加“灯带开关”"
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        } else {
            AlertDialog.Builder(this).setTitle("添加快捷开关")
                .setMessage("下拉系统快捷面板，点击编辑（铅笔图标），将“灯带开关”拖入面板。")
                .setPositiveButton("知道了", null).show()
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun text(value: String, size: Int, color: Int = Color.rgb(42, 51, 45)) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        setPadding(0, dp(4), 0, dp(4))
    }
    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        minHeight = dp(48)
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnClickListener { action() }
        controls.add(this)
    }
}
