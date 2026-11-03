package dev.lenshift.app.activation

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import dev.lenshift.app.R
import dev.lenshift.app.assist.AssistTriggerLog
import dev.lenshift.app.data.settings.AppSettings
import dev.lenshift.app.ui.AssistLaunchActivity

/**
 * Quick Settings tile.
 *
 * This is the most reliable non-root activation surface on every Android build: it needs
 * no role, no accessibility grant, and no OEM cooperation. Tapping it opens the same
 * capture flow the assistant gesture uses.
 *
 * The tile is declared non-active (`ACTIVE_TILE=false`) so it behaves as a button rather
 * than a toggle.
 */
class LenshiftTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile)
        tile.subtitle = if (AssistantRole.isLenshiftCurrentAssistant(this)) {
            "Default assistant"
        } else {
            "Tap to capture"
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        AssistTriggerLog.recordActivation(this, AppSettings.ACTIVATION_TILE)
        Log.i(TAG, "Tile tapped")

        // Start the capture flow in the foreground; QS tiles may not launch activities
        // directly on newer versions without collapsing the shade first.
        val intent = Intent(this, AssistLaunchActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(AssistLaunchActivity.EXTRA_MECHANISM, AppSettings.ACTIVATION_TILE)

        runCatching {
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                        android.app.PendingIntent.FLAG_IMMUTABLE
                )
            )
        }.onFailure {
            Log.w(TAG, "startActivityAndCollapse failed, falling back", it)
            runCatching { startActivity(intent) }
        }
    }

    /**
     * Android 13+ long-press target; opens Lenshift settings so users can reach
     * configuration from the shade.
     */
    override fun onTileRemoved() {
        super.onTileRemoved()
    }

    companion object {
        private const val TAG = "Lenshift/Tile"

        /** Outcome of asking the system to add the tile, for the UI to explain. */
        enum class AddResult { ADDED, ALREADY_ADDED, NOT_ADDED, UNSUPPORTED }

        /**
         * Shows the system "Add tile to Quick Settings?" dialog (Android 13+).
         *
         * The previous implementation called `TileService.requestListeningState`, which
         * only asks an *already added* tile to refresh. It never adds anything and shows
         * no UI, so tapping "Add" appeared to do nothing at all.
         * `StatusBarManager.requestAddTileService` is the real API: it shows a system
         * prompt with Lenshift's icon and label, and reports the user's choice.
         */
        fun requestAdd(context: Context, onResult: (AddResult) -> Unit) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                onResult(AddResult.UNSUPPORTED)
                return
            }
            val statusBar = context.getSystemService(StatusBarManager::class.java)
            if (statusBar == null) {
                onResult(AddResult.UNSUPPORTED)
                return
            }
            runCatching {
                statusBar.requestAddTileService(
                    ComponentName(context, LenshiftTileService::class.java),
                    context.getString(R.string.tile_label),
                    Icon.createWithResource(context, R.drawable.ic_tile),
                    context.mainExecutor
                ) { code ->
                    val result = when (code) {
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> AddResult.ADDED
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ->
                            AddResult.ALREADY_ADDED
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED ->
                            AddResult.NOT_ADDED
                        else -> {
                            // Error codes: the request was refused (e.g. app not in the
                            // foreground, or another request is pending). The user can
                            // still add the tile by editing Quick Settings by hand.
                            Log.w(TAG, "requestAddTileService returned $code")
                            AddResult.UNSUPPORTED
                        }
                    }
                    onResult(result)
                }
            }.onFailure {
                Log.w(TAG, "requestAddTileService failed", it)
                onResult(AddResult.UNSUPPORTED)
            }
        }
    }
}
