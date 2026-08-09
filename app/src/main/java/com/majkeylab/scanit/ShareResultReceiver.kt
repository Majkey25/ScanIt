package com.majkeylab.scanit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.service.chooser.ChooserResult
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val EXTRA_SHARE_CACHE_ID = "com.majkeylab.scanit.extra.SHARE_CACHE_ID"
internal const val EXTRA_SHARE_ENTRY_ID = "com.majkeylab.scanit.extra.SHARE_ENTRY_ID"
internal const val EXTRA_SHARE_CLEANUP_KIND = "com.majkeylab.scanit.extra.SHARE_CLEANUP_KIND"

class ShareResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val chooserResult =
            intent.getParcelableExtra(Intent.EXTRA_CHOOSER_RESULT, ChooserResult::class.java)
                ?: return
        if (
            !chooserResultAllowsCleanup(
                chooserResult.type,
                chooserResult.selectedComponent != null,
            )
        ) {
            return
        }
        val request =
            decodeShareCleanupRequest(
                intent.getStringExtra(EXTRA_SHARE_CACHE_ID),
                intent.getStringExtra(EXTRA_SHARE_ENTRY_ID),
                intent.getStringExtra(EXTRA_SHARE_CLEANUP_KIND),
            ) ?: return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                deleteSharedOutput(context.applicationContext, request)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

private suspend fun deleteSharedOutput(context: Context, request: ShareCleanupRequest) {
    val storage = ScanStorage(context)
    val result =
        try {
            storage.deleteDurableOutputs(
                request =
                    OutputDeleteRequest(
                        cacheId = request.cacheId,
                        entryId = request.entryId,
                        target =
                            when (request.kind) {
                                ShareCleanupKind.Pdf -> RecentDeleteTarget.Pdf
                                ShareCleanupKind.Images -> RecentDeleteTarget.Images
                            },
                    ),
                deleteRecentCache = false,
            )
        } catch (_: Exception) {
            OutputDeleteOperationResult.Failed
        }
    try {
        reconcilePdfTreeGrants(
            context = context,
            current = SettingsStore(context).load().pdfTreeUri,
            live = storage.livePdfTreeUris(),
        )
    } catch (_: Exception) {
        // A later app start or output action retries grant reconciliation.
    }
    if (result == OutputDeleteOperationResult.Failed) {
        withContext(Dispatchers.Main) {
            Toast.makeText(
                context,
                R.string.shared_output_delete_failed,
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}
