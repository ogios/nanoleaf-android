package dev.ogios.nanoleaf

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class LightTileService : TileService() {
    private val repository get() = lightRepository
    private val listener: () -> Unit = { render() }

    override fun onStartListening() {
        super.onStartListening()
        repository.subscribe(listener)
        render()
    }

    override fun onStopListening() {
        repository.unsubscribe(listener)
        super.onStopListening()
    }

    override fun onDestroy() {
        repository.unsubscribe(listener)
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { act() } else act()
    }

    private fun act() {
        if (repository.device == null) {
            val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            } else {
                openSettingsBeforeAndroid14(intent)
            }
        } else repository.power()
    }

    // This overload is the platform API on Android 8–13. The caller checks SDK < 34.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun openSettingsBeforeAndroid14(intent: Intent) {
        startActivityAndCollapse(intent)
    }

    private fun render() {
        val tile = qsTile ?: return
        val on = repository.lastState
        val label = when {
            repository.device == null -> "灯带 · 请先设置"
            repository.busy -> "灯带 · 连接中…"
            on == null -> "灯带 · 状态未知"
            on -> "灯带 · 上次开"
            else -> "灯带 · 上次关"
        }
        tile.label = label
        tile.contentDescription = label
        tile.state = when {
            repository.busy -> Tile.STATE_UNAVAILABLE
            on == true && repository.device != null -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = if (repository.busy) "请稍候" else "点击读取并切换"
        }
        tile.updateTile()
    }
}
