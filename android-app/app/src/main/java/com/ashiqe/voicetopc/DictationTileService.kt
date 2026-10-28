package com.ashiqe.voicetopc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile: one tap starts dictation from any screen, the next tap stops and sends.
 *
 * The tile never owns microphone state. It reads [DictationService.isActive] and asks the service
 * to start or stop; the service mirrors its real state back through [requestTileRefresh] whenever
 * a session begins or ends, so the tile cannot drift out of sync with the microphone.
 */
class DictationTileService : TileService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val settleRunnable = Runnable { applyState(DictationService.isActive()) }

    override fun onTileAdded() {
        super.onTileAdded()
        applyState(DictationService.isActive())
    }

    override fun onStartListening() {
        super.onStartListening()
        applyState(DictationService.isActive())
    }

    override fun onStopListening() {
        super.onStopListening()
        mainHandler.removeCallbacks(settleRunnable)
    }

    override fun onClick() {
        super.onClick()
        val wasActive = DictationService.isActive()
        if (wasActive) {
            DictationService.requestStop(this)
            applyState(false)
            scheduleSettle()
            return
        }
        if (!DictationService.hasMicPermission(this)) {
            // Nothing can record yet: explain, and let the tap open the app to grant access.
            DictationService.notifyHint(
                this,
                "Microphone permission is required. Tap to open Voice to PC and allow it.",
            )
            openApp()
            applyState(false)
            return
        }
        DictationService.requestStart(this)
        // Optimistic flip so the tile reacts instantly, then correct from the service's real state.
        applyState(true)
        scheduleSettle()
    }

    private fun scheduleSettle() {
        mainHandler.removeCallbacks(settleRunnable)
        mainHandler.postDelayed(settleRunnable, SETTLE_DELAY_MS)
    }

    @Suppress("DEPRECATION")
    private fun openApp() {
        try {
            val intent = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivityAndCollapse(intent)
        } catch (_: Exception) {
            // Some launchers refuse to collapse the shade; the notification hint still stands.
        }
    }

    private fun applyState(active: Boolean) {
        val tile = qsTile ?: return
        tile.label = "Dictate"
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        try {
            tile.updateTile()
        } catch (_: Exception) {
            // The tile host can disappear between reads; the next bind redraws it.
        }
    }

    companion object {
        private const val SETTLE_DELAY_MS = 750L

        /**
         * Asks the system to re-bind this tile so [onStartListening] can redraw it. Safe to call
         * when the tile is not pinned to Quick Settings; the system simply ignores it.
         */
        fun requestTileRefresh(context: Context) {
            try {
                TileService.requestListeningState(
                    context,
                    ComponentName(context, DictationTileService::class.java),
                )
            } catch (_: Exception) {
                // No tile host (or no permission to ask); nothing to refresh.
            }
        }
    }
}
