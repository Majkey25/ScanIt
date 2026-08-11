package com.majkeylab.scanit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.service.chooser.ChooserResult
import android.widget.Toast
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val EXTRA_SHARE_CACHE_ID = "com.majkeylab.scanit.extra.SHARE_CACHE_ID"
internal const val EXTRA_SHARE_ENTRY_ID = "com.majkeylab.scanit.extra.SHARE_ENTRY_ID"
internal const val EXTRA_SHARE_CLEANUP_KIND = "com.majkeylab.scanit.extra.SHARE_CLEANUP_KIND"
internal const val ACTION_SAVED_OUTPUTS_CHANGED =
    "com.majkeylab.scanit.action.SAVED_OUTPUTS_CHANGED"

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
        try {
            SettingsStore(context.applicationContext).savePendingShareCleanup(request)
        } catch (_: IOException) {
            Toast.makeText(context, R.string.shared_output_delete_failed, Toast.LENGTH_LONG).show()
            return
        } catch (_: RuntimeException) {
            Toast.makeText(context, R.string.shared_output_delete_failed, Toast.LENGTH_LONG).show()
            return
        }
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = retryPendingShareCleanup(context.applicationContext)
                if (result != null && shareCleanupCompletionPolicy(result).warn) {
                    showShareCleanupFailure(context.applicationContext)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

internal fun retryPendingShareCleanup(context: Context): OutputDeleteOperationResult? {
    val store = SettingsStore(context)
    val storage = ScanStorage(context)
    var reported: OutputDeleteOperationResult? = null
    var attempted = false
    var remaining = MAX_PENDING_SHARE_CLEANUPS
    while (remaining-- > 0) {
        val attempt =
            try {
                withStorageTransaction {
                    processPendingShareCleanup(
                        store = store,
                        delete = { request ->
                            storage.deleteDurableOutputs(
                                request =
                                    OutputDeleteRequest(
                                        cacheId = request.cacheId,
                                        entryId = request.entryId,
                                        target = request.kind.deleteTarget(),
                                    ),
                                deleteRecentCache = false,
                            )
                        },
                        afterDelete = {
                            try {
                                reconcilePdfTreeGrants(
                                    context = context,
                                    current = canonicalPdfTreeUri(store),
                                    live = storage.livePdfTreeUris(),
                                )
                            } catch (_: Exception) {
                                // Tree grant cleanup is independently retried on app start.
                            }
                        },
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                PendingShareCleanupAttempt(OutputDeleteOperationResult.Failed, false)
            } ?: break
        attempted = true
        if (reported == null || shareCleanupCompletionPolicy(attempt.result).warn) {
            reported = attempt.result
        }
        if (!attempt.queueAdvanced) break
    }
    if (attempted) {
        context.sendBroadcast(
            Intent(ACTION_SAVED_OUTPUTS_CHANGED).setPackage(context.packageName),
        )
    }
    return reported
}

internal data class PendingShareCleanupAttempt(
    val result: OutputDeleteOperationResult,
    val queueAdvanced: Boolean,
)

internal fun processPendingShareCleanup(
    store: SettingsStore,
    delete: (ShareCleanupRequest) -> OutputDeleteOperationResult,
    afterDelete: () -> Unit = {},
): PendingShareCleanupAttempt? {
    val request = store.pendingShareCleanup() ?: return null
    val result =
        try {
            delete(request)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            OutputDeleteOperationResult.Failed
        }
    afterDelete()
    var queueAdvanced = false
    if (shareCleanupCompletionPolicy(result).clear) {
        try {
            store.clearPendingShareCleanup(request)
            queueAdvanced = store.pendingShareCleanup() != request
        } catch (_: IOException) {
            // Keeping the exact request makes the terminal cleanup retryable.
        }
    }
    return PendingShareCleanupAttempt(result, queueAdvanced)
}

internal suspend fun showShareCleanupFailure(context: Context) {
    withContext(Dispatchers.Main) {
        Toast.makeText(
            context,
            R.string.shared_output_delete_failed,
            Toast.LENGTH_LONG,
        ).show()
    }
}

private fun ShareCleanupKind.deleteTarget(): RecentDeleteTarget =
    when (this) {
        ShareCleanupKind.Pdf -> RecentDeleteTarget.Pdf
        ShareCleanupKind.Images -> RecentDeleteTarget.Images
    }
