package com.majkeylab.scanit

import android.app.Activity
import android.content.IntentSender
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

@Composable
internal fun VisualMarkLibraryScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val store = remember(context) { MarkTemplateStore(context) }
    val scope = rememberCoroutineScope()
    var ids by remember { mutableStateOf(emptyList<String>()) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var drawingOpen by rememberSaveable { mutableStateOf(false) }
    var drawing by rememberSaveable(stateSaver = markDrawingSaver) { mutableStateOf(emptyList<MarkStroke>()) }
    var message by remember { mutableStateOf<UiMessage?>(null) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }

    fun update(operation: suspend () -> String?) {
        busy = true
        val currentGeneration = ++generation
        message = null
        scope.launch {
            try {
                val (preferred, saved) = withContext(Dispatchers.IO) { operation() to store.list() }
                if (currentGeneration != generation) return@launch
                ids = saved
                selected = preferred ?: selected?.takeIf { it in saved } ?: saved.firstOrNull()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (currentGeneration == generation) message = UiMessage(R.string.visual_mark_template_failed)
            } finally {
                if (currentGeneration == generation) busy = false
            }
        }
    }

    LaunchedEffect(store) { if (!busy) update { null } }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { update { store.import(it) } }
    }
    val scanLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            busy = false
            if (result.resultCode == Activity.RESULT_OK) {
                val uri = runCatching {
                    GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pages?.singleOrNull()?.imageUri
                }.getOrNull()
                if (uri == null) message = UiMessage(R.string.visual_mark_template_failed)
                else update { store.import(uri) }
            } else if (result.resultCode != Activity.RESULT_CANCELED ||
                result.data?.hasExtra(ActivityResultContracts.StartIntentSenderForResult.EXTRA_SEND_INTENT_EXCEPTION) == true
            ) {
                message = UiMessage(R.string.visual_mark_template_failed)
            }
        }
    val load: suspend (String, Int) -> Bitmap? = remember(store) {
        { id, size -> withContext(Dispatchers.IO) { store.load(id, size) } }
    }
    val preview = ownedVisualMarkBitmap(selected, MARK_OUTPUT_MAX_SIDE, load)
    BackHandler(onBack = onClose)
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = { VisualMarkTopBar(true, onClose) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(stringResource(R.string.saved_visual_marks), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.visual_mark_library_hint), style = MaterialTheme.typography.bodyMedium)
            }
            item {
                Box(
                    Modifier.fillMaxWidth().height(180.dp).background(Color(0xffd0d0d0)),
                    contentAlignment = Alignment.Center,
                ) {
                    preview?.let {
                        Image(it.asImageBitmap(), stringResource(R.string.saved_visual_marks),
                            Modifier.fillMaxSize().padding(16.dp), contentScale = ContentScale.Fit)
                    }
                    if (busy) CircularProgressIndicator()
                }
            }
            item {
                if (ids.isEmpty()) Text(stringResource(R.string.no_saved_visual_marks))
                else VisualMarkTemplateRow(ids, selected, !busy, { selected = it }, load)
                if (selected != null) {
                    TextButton(onClick = { confirmDelete = true }, enabled = !busy) {
                        ActionButtonContent(R.drawable.ic_delete, R.string.delete_visual_mark)
                    }
                }
            }
            item {
                message?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error) }
                VisualMarkCreationActions(
                    enabled = !busy && ids.size < MAX_MARK_TEMPLATES,
                    onDraw = { drawing = emptyList(); drawingOpen = true },
                    onImport = { importLauncher.launch(arrayOf("image/*")) },
                    onScan = {
                        val host = activity
                        if (host == null) message = UiMessage(R.string.visual_mark_template_failed)
                        else {
                            busy = true
                            scope.launch {
                                try {
                                    val options = GmsDocumentScannerOptions.Builder()
                                        .setGalleryImportAllowed(false).setPageLimit(1)
                                        .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                                        .setScannerMode(scannerMode(ScannerPurpose.VisualMark)).build()
                                    val sender = suspendCancellableCoroutine<IntentSender> { continuation ->
                                        GmsDocumentScanning.getClient(options).getStartScanIntent(host)
                                            .addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
                                            .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
                                    }
                                    if (host.isFinishing || host.isDestroyed ||
                                        !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                                    ) {
                                        busy = false
                                        return@launch
                                    }
                                    scanLauncher.launch(IntentSenderRequest.Builder(sender).build())
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    busy = false
                                    message = UiMessage(R.string.visual_mark_template_failed)
                                }
                            }
                        }
                    },
                )
            }
        }
    }
    if (drawingOpen) {
        DrawVisualMarkDialog(drawing, message, { drawing = it }, { drawingOpen = false }) { strokes ->
            if (busy) return@DrawVisualMarkDialog
            drawingOpen = false
            update {
                val bitmap = renderDrawnMark(strokes)
                try { store.save(bitmap) } finally { bitmap.recycle() }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_visual_mark_title)) },
            text = { Text(stringResource(R.string.delete_visual_mark_body)) },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    if (busy) return@TextButton
                    confirmDelete = false
                    val id = selected
                    if (id != null) update { check(store.delete(id)); null }
                }) { ActionButtonContent(R.drawable.ic_delete, R.string.delete_visual_mark_confirm) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    ActionButtonContent(R.drawable.ic_close, R.string.cancel)
                }
            },
        )
    }
}

private val markDrawingSaver = listSaver<List<MarkStroke>, ArrayList<Float>>(
    save = { strokes ->
        strokes.map { stroke -> ArrayList(stroke.points.flatMap { listOf(it.x, it.y) }) }
    },
    restore = { saved ->
        runCatching {
            saved.map { coordinates ->
                require(coordinates.size % 2 == 0)
                MarkStroke(coordinates.chunked(2).map { MarkPoint(it[0], it[1]) })
            }.also { if (it.isNotEmpty()) validateNormalizedMarkStrokes(it) }
        }.getOrDefault(emptyList())
    },
)
