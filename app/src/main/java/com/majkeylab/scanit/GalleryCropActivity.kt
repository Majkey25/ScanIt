package com.majkeylab.scanit

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GalleryCropActivity : ComponentActivity() {
    private var session = UUID.randomUUID().toString()
    private var selectedUris by mutableStateOf<List<String>>(emptyList())
    private var pickerStarted = false
    private var cancelling = false
    private val cropWork by lazy { SupervisorJob(lifecycleScope.coroutineContext[Job]) }
    private val multiplePicker = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_SCAN_PAGES)) { acceptSelection(it) }
    private val singlePicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { acceptSelection(listOfNotNull(it)) }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(localizedLegacyContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        session = savedInstanceState?.getString(GALLERY_CROP_SESSION)?.takeIf(::isCanonicalUuid) ?: session
        selectedUris = savedInstanceState?.getStringArrayList("gallery_crop_uris").orEmpty()
        pickerStarted = savedInstanceState?.getBoolean("gallery_picker_started") == true
        val appearance = SettingsStore(this).load().appAppearance
        setContent {
            ScanItTheme(appearance) {
                GalleryCropScreen(selectedUris, session, cropWork, onCancel = ::cancelImport) { count ->
                    setResult(Activity.RESULT_OK, Intent().putExtra(GALLERY_CROP_SESSION, session).putExtra(GALLERY_CROP_PAGE_COUNT, count))
                    finish()
                }
            }
        }
        if (!pickerStarted && selectedUris.isEmpty()) {
            lifecycleScope.launch {
                try {
                    withContext(cropWork + Dispatchers.IO) { clearOldGalleryCrops(this@GalleryCropActivity, session) }
                    if (cancelling) return@launch
                    pickerStarted = true
                    if (intent.getIntExtra(GALLERY_CROP_PAGE_LIMIT, 1) == MAX_SCAN_PAGES) {
                        multiplePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    } else {
                        singlePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    failImport()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(GALLERY_CROP_SESSION, session)
        outState.putStringArrayList("gallery_crop_uris", ArrayList(selectedUris))
        outState.putBoolean("gallery_picker_started", pickerStarted)
        super.onSaveInstanceState(outState)
    }

    private fun acceptSelection(uris: List<Uri>) {
        if (uris.isEmpty()) {
            cancelImport()
        } else if (uris.size > intent.getIntExtra(GALLERY_CROP_PAGE_LIMIT, 1) ||
            !isAcceptedScanPageCount(uris.size) || uris.any { it.scheme != "content" }) {
            failImport()
        } else {
            selectedUris = uris.map(Uri::toString)
        }
    }

    private fun failImport() {
        Toast.makeText(this, R.string.gallery_crop_failed, Toast.LENGTH_LONG).show()
        cancelImport()
    }

    private fun cancelImport() {
        if (cancelling) return
        cancelling = true
        lifecycleScope.launch {
            cropWork.cancelAndJoin()
            setResult(Activity.RESULT_CANCELED, Intent().putExtra(GALLERY_CROP_SESSION, session))
            finish()
        }
    }
}

@Composable
private fun GalleryCropScreen(uris: List<String>, session: String, work: Job, onCancel: () -> Unit, onComplete: (Int) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by rememberSaveable { mutableIntStateOf(0) }
    var coordinates by rememberSaveable(page) { mutableStateOf(fullGalleryCrop().flatMap { listOf(it.x, it.y) }.toFloatArray()) }
    val corners = coordinates.toList().chunked(2).map { GalleryCropPoint(it[0], it[1]) }
    var directory by remember { mutableStateOf<File?>(null) }
    var bitmap by remember(page) { mutableStateOf<Bitmap?>(null) }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    BackHandler(onBack = onCancel)

    LaunchedEffect(uris, session, retry) {
        if (uris.isEmpty()) return@LaunchedEffect
        failed = false
        try {
            val coroutine = currentCoroutineContext()
            directory = withContext(work + Dispatchers.IO) {
                prepareGalleryCropSources(context, session, uris) { !coroutine.isActive || !work.isActive }
                galleryCropDirectory(context, session)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            failed = true
        }
    }
    LaunchedEffect(directory, page, retry) {
        val source = directory?.let { File(it, "source_$page") } ?: return@LaunchedEffect
        var decoded: Bitmap? = null
        failed = false
        try {
            withContext(work + Dispatchers.IO) { decoded = decodeGalleryCropBitmap(source, maxPixels = 2_000_000L, maxEdge = 1600) }
            bitmap = decoded
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            failed = true
        } finally {
            if (bitmap !== decoded) decoded?.recycle()
        }
    }
    DisposableEffect(bitmap) {
        val owned = bitmap
        onDispose { owned?.recycle() }
    }
    val preview = bitmap
    val valid = preview != null && galleryCropPlan(corners, preview.width, preview.height) != null
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.gallery_crop_title), style = MaterialTheme.typography.titleLarge)
            if (uris.isNotEmpty()) Text(stringResource(R.string.page_position, page + 1, uris.size))
            Text(stringResource(R.string.gallery_crop_hint))
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (preview != null) {
                    GalleryCropCanvas(preview, corners, enabled = !busy) { updated ->
                        coordinates = updated.flatMap { listOf(it.x, it.y) }.toFloatArray()
                    }
                } else if (!failed) {
                    CircularProgressIndicator()
                }
            }
            if (failed) Text(stringResource(R.string.gallery_crop_failed), color = MaterialTheme.colorScheme.error)
            else if (preview != null && !valid) Text(stringResource(R.string.gallery_crop_invalid), color = MaterialTheme.colorScheme.error)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.cancel)) }
                TextButton(onClick = { coordinates = fullGalleryCrop().flatMap { listOf(it.x, it.y) }.toFloatArray() }, enabled = !busy && !failed, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.gallery_crop_reset))
                }
            }
            if (failed) {
                Button(onClick = { retry++ }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.try_again)) }
            } else {
                    Button(enabled = valid && !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                        val destination = directory ?: return@Button
                        busy = true
                        scope.launch {
                            try {
                                val coroutine = currentCoroutineContext()
                                withContext(work + Dispatchers.IO) { renderGalleryCrop(destination, page, corners) { !coroutine.isActive || !work.isActive } }
                                currentCoroutineContext().ensureActive()
                                if (page + 1 == uris.size) onComplete(uris.size) else page++
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (_: Exception) {
                                failed = true
                            } finally {
                                busy = false
                            }
                        }
                    }) { Text(stringResource(if (busy) R.string.saving_document else R.string.apply_appearance)) }
            }
        }
    }
}

@Composable
private fun GalleryCropCanvas(bitmap: Bitmap, corners: List<GalleryCropPoint>, enabled: Boolean, onChange: (List<GalleryCropPoint>) -> Unit) {
    val currentCorners by rememberUpdatedState(corners)
    val update by rememberUpdatedState(onChange)
    val color = MaterialTheme.colorScheme.primary
    val description = stringResource(R.string.gallery_crop_hint)
    val names = listOf(R.string.gallery_crop_top_left, R.string.gallery_crop_top_right, R.string.gallery_crop_bottom_right, R.string.gallery_crop_bottom_left).map { stringResource(it) }
    val directions = listOf(R.string.gallery_crop_left, R.string.gallery_crop_right, R.string.gallery_crop_up, R.string.gallery_crop_down).map { stringResource(it) }
    val moveLabel = stringResource(R.string.gallery_crop_move)
    val actions = if (!enabled) emptyList() else corners.indices.flatMap { index ->
        listOf(GalleryCropPoint(-0.01f, 0f), GalleryCropPoint(0.01f, 0f), GalleryCropPoint(0f, -0.01f), GalleryCropPoint(0f, 0.01f)).mapIndexed { direction, delta ->
            CustomAccessibilityAction(moveLabel.format(names[index], directions[direction])) {
                val point = currentCorners[index]
                update(currentCorners.mapIndexed { i, value -> if (i == index) GalleryCropPoint((point.x + delta.x).coerceIn(0f, 1f), (point.y + delta.y).coerceIn(0f, 1f)) else value })
                true
            }
        }
    }
    val paint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }
    Canvas(Modifier.fillMaxSize().padding(24.dp).semantics { contentDescription = description; customActions = actions }
        .pointerInput(bitmap, enabled) {
            if (!enabled) return@pointerInput
            var active = -1
            fun frame(): GalleryCropFrame? =
                if (size.width <= 0 || size.height <= 0) null
                else galleryCropFrame(bitmap.width, bitmap.height, size.width.toFloat(), size.height.toFloat())
            detectDragGestures(
                onDragStart = { position ->
                    val frame = frame() ?: return@detectDragGestures
                    active = currentCorners.indices.minByOrNull { index ->
                        val point = currentCorners[index]
                        (Offset(frame.x + point.x * frame.width, frame.y + point.y * frame.height) - position).getDistance()
                    } ?: -1
                    if (active >= 0) {
                        val point = currentCorners[active]
                        if ((Offset(frame.x + point.x * frame.width, frame.y + point.y * frame.height) - position).getDistance() > 48.dp.toPx()) active = -1
                    }
                },
                onDragEnd = { active = -1 },
                onDragCancel = { active = -1 },
            ) { change, _ ->
                if (active >= 0) {
                    change.consume()
                    val frame = frame() ?: return@detectDragGestures
                    val point = frame.imagePoint(change.position.x, change.position.y)
                    update(currentCorners.mapIndexed { index, value -> if (index == active) point else value })
                }
            }
        }) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val frame = galleryCropFrame(bitmap.width, bitmap.height, size.width, size.height)
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawBitmap(bitmap, null, RectF(frame.x, frame.y, frame.x + frame.width, frame.y + frame.height), paint)
        }
        val points = corners.map { Offset(frame.x + it.x * frame.width, frame.y + it.y * frame.height) }
        points.forEachIndexed { index, point ->
            drawLine(color, point, points[(index + 1) % 4], strokeWidth = 3.dp.toPx())
            drawCircle(Color.White, radius = 10.dp.toPx(), center = point)
            drawCircle(color, radius = 7.dp.toPx(), center = point)
        }
    }
}
