package com.majkeylab.scanit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal const val ACTION_SAVED_OUTPUTS_CHANGED =
    "com.majkeylab.scanit.action.SAVED_OUTPUTS_CHANGED"

// Retained for pending chooser callbacks created by older installations.
class ShareResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                retryPendingShareCleanup(context.applicationContext)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

internal fun retryPendingShareCleanup(context: Context) {
    try {
        discardPendingShareCleanups(SettingsStore(context))
    } catch (_: IOException) {
        // Failed migration can retry on next start. Saved files are never deleted.
    }
}

internal fun discardPendingShareCleanups(store: SettingsStore) {
    repeat(MAX_PENDING_SHARE_CLEANUPS) {
        val request = store.pendingShareCleanup() ?: return
        store.clearPendingShareCleanup(request)
    }
}
