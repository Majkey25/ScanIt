package com.majkeylab.scanit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test

class VisualMarkUiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var qaDirectory: File
    private lateinit var result: ScreenState.Result
    private lateinit var mark: Bitmap
    private val height = mutableStateOf(720.dp)
    private val editor = mutableStateOf(
        VisualMarkEditorState(
            source = MarkEditorSource("Scan_UI", "17ca792d-f61a-4d5e-82d8-2ae1d974c167", 0),
            templateIds = listOf("mark_1.png"),
            selectedTemplateId = "mark_1.png",
        ),
    )
    private var applies = 0
    private var appliedPlacement: MarkPlacement? = null
    private var placementChanges = 0

    @After
    fun restoreOwnActivityOrientation() {
        compose.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    @Before
    fun createPrivateFixtures() {
        check(context.packageName == "com.majkeylab.scanit.internal")
        compose.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(5_000) {
            compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        }
        qaDirectory = Files.createTempDirectory(context.cacheDir.toPath(), "mark-ui-qa-").toFile()
        val page = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
        page.eraseColor(Color.WHITE)
        Canvas(page).drawRect(40f, 60f, 500f, 80f, Paint().apply { color = Color.BLACK })
        val pageFile = File(qaDirectory, "page.jpg")
        pageFile.outputStream().use { assertTrue(page.compress(Bitmap.CompressFormat.JPEG, 92, it)) }
        mark = Bitmap.createBitmap(240, 80, Bitmap.Config.ARGB_8888)
        Canvas(mark).drawLine(10f, 60f, 230f, 20f, Paint().apply {
            color = Color.BLUE
            strokeWidth = 12f
        })
        result = ScreenState.Result(
            scan = SavedScan(
                cached = CachedScan("Scan_UI", listOf(pageFile), File(qaDirectory, "unused.pdf")),
                savedImages = emptyList(),
                savedPdf = null,
            ),
            thumbnail = page,
        )
        // Displayed fixture bitmaps remain GC-owned, like production Compose previews.
        println("MARK_UI_QA_DIR=${qaDirectory.absolutePath}")
    }

    @Test
    fun editorFingerGesturesClampResetAndPinnedApplyUseActualPreview() {
        showEditor()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("Physical test activity did not use portrait", root.height > root.width)
        val apply = compose.onNodeWithText(context.getString(R.string.apply_visual_mark, 1))
        apply.assertIsEnabled()
        val preview = compose.onNodeWithContentDescription(label(R.string.visual_mark_preview))
        val page = previewPageBounds(preview)
        capture("editor-initial.png")
        dragPreview(preview, Offset(-page.width * 0.15f, -page.height * 0.2f))
        compose.runOnIdle {
            assertEquals(0.35f, editor.value.placement.centerX, 0.015f)
            assertEquals(0.55f, editor.value.placement.centerY, 0.015f)
            assertEquals(0.35f, editor.value.placement.widthFraction, 0.001f)
            assertEquals(0f, editor.value.placement.rotationDegrees, 0.001f)
        }
        pinchRotatePreview(preview, zoom = 1.5f, rotationDegrees = 30f)
        compose.runOnIdle {
            assertEquals(0.525f, editor.value.placement.widthFraction, 0.02f)
            assertEquals(30f, editor.value.placement.rotationDegrees, 1f)
            assertEquals(0.35f, editor.value.placement.centerX, 0.015f)
            assertEquals(0.55f, editor.value.placement.centerY, 0.015f)
        }
        assertPlacementInsidePage()
        val transformed = compose.runOnIdle { editor.value.placement }
        dragPreview(preview, Offset(1f - page.width * transformed.centerX, 1f - page.height * transformed.centerY))
        compose.runOnIdle {
            assertTrue(editor.value.placement.centerX < transformed.centerX)
            assertTrue(editor.value.placement.centerY < transformed.centerY)
            assertEquals(transformed.widthFraction, editor.value.placement.widthFraction, 0.001f)
            assertEquals(transformed.rotationDegrees, editor.value.placement.rotationDegrees, 0.001f)
        }
        assertPlacementInsidePage()
        capture("editor-gestures.png")
        click(R.string.reset_visual_mark)
        compose.runOnIdle { assertEquals(MarkPlacement(), editor.value.placement) }
        dragPreview(preview, Offset(page.width * 0.1f, -page.height * 0.1f))
        val placementToApply = compose.runOnIdle {
            assertEquals(0.6f, editor.value.placement.centerX, 0.015f)
            assertEquals(0.65f, editor.value.placement.centerY, 0.015f)
            editor.value.placement
        }
        assertPlacementInsidePage()

        val before = apply.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val hint = compose.onNodeWithText(label(R.string.drag_visual_mark_hint))
        val hintBefore = hint.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText(label(R.string.visual_mark_disclaimer)).performScrollTo()
        assertEquals(before, apply.assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        assertEquals(hintBefore, hint.assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        apply.assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, applies)
            assertEquals(placementToApply, appliedPlacement)
        }
        capture("editor-controls.png")
    }

    @Test
    fun landscapeDarkEditorKeepsPreviewAndToolsReachable() {
        compose.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(5_000) {
            compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        showEditor(appearance = AppAppearanceSettings(mode = AppAppearanceMode.Dark))
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("Physical test activity did not rotate", root.width > root.height)
        val preview = compose.onNodeWithContentDescription(label(R.string.visual_mark_preview))
        preview.assertIsDisplayed()
        val canvas = preview.fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText(context.getString(R.string.apply_visual_mark, 1)).assertIsEnabled()
        val page = previewPageBounds(preview)
        dragPreview(preview, Offset(-page.width * 0.1f, -page.height * 0.15f))
        pinchRotatePreview(preview, zoom = 1.4f, rotationDegrees = 0f)
        compose.runOnIdle {
            assertEquals(0.4f, editor.value.placement.centerX, 0.015f)
            assertEquals(0.6f, editor.value.placement.centerY, 0.015f)
            assertEquals(0.49f, editor.value.placement.widthFraction, 0.02f)
            assertEquals(0f, editor.value.placement.rotationDegrees, 0.1f)
        }
        assertPlacementInsidePage()
        assertEquals(canvas, preview.fetchSemanticsNode().boundsInRoot)
        val pixels = preview.captureToImage().asAndroidBitmap()
        try {
            val scale = min(pixels.width / 600f, pixels.height / 800f)
            val frame = resolveMarkRect(600f * scale, 800f * scale, mark.width, mark.height, editor.value.placement)
            val x = ((pixels.width - 600f * scale) / 2f + (frame.left + frame.right) / 2f).roundToInt()
            val y = (pixels.height - 800f * scale) / 2f + frame.top
            val outer = pixels.getPixel(x, (y - compose.density.density).roundToInt())
            val inner = pixels.getPixel(x, y.roundToInt())
            assertTrue("Selection outline disappears on white paper in dark theme",
                Color.red(outer) < 32 && Color.green(outer) < 32 && Color.blue(outer) < 32)
            assertTrue("Selection outline needs a light edge on dark paper",
                Color.red(inner) > 224 && Color.green(inner) > 224 && Color.blue(inner) > 224)
        } finally {
            pixels.recycle()
        }
        compose.onNodeWithText(context.getString(R.string.apply_visual_mark, 1)).assertIsDisplayed()
        capture("editor-landscape-dark.png")
    }

    @Test
    fun previewAccessibilityActionsRepeatClampAndFeedFreshFingerGestures() {
        showEditor()
        compose.onNodeWithText(context.getString(R.string.apply_visual_mark, 1)).assertIsEnabled()
        val preview = compose.onNodeWithContentDescription(label(R.string.visual_mark_preview))
        val actions = preview.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        val actionResources = listOf(
            R.string.safe_share_move_left, R.string.safe_share_move_right,
            R.string.safe_share_move_up, R.string.safe_share_move_down,
            R.string.visual_mark_enlarge, R.string.visual_mark_reduce,
            R.string.visual_mark_rotate_left, R.string.visual_mark_rotate_right,
        )
        assertEquals(8, actions.size)
        assertEquals(actionResources.map(::label).toSet(), actions.map { it.label }.toSet())
        for (resource in listOf(R.string.visual_mark_size, R.string.visual_mark_rotation, R.string.manual_position)) {
            compose.onNodeWithText(label(resource)).assertDoesNotExist()
        }
        val normal = MarkPlacement()
        val cases = listOf(
            Triple(R.string.safe_share_move_left, normal, normal.copy(centerX = 0.44f)),
            Triple(R.string.safe_share_move_right, normal, normal.copy(centerX = 0.56f)),
            Triple(R.string.safe_share_move_up, normal, normal.copy(centerY = 0.69f)),
            Triple(R.string.safe_share_move_down, normal, normal.copy(centerY = 0.81f)),
            Triple(R.string.visual_mark_enlarge, normal, normal.copy(widthFraction = 0.41f)),
            Triple(R.string.visual_mark_reduce, normal, normal.copy(widthFraction = 0.29f)),
            Triple(R.string.visual_mark_rotate_left, normal, normal.copy(rotationDegrees = -15f)),
            Triple(R.string.visual_mark_rotate_right, normal, normal.copy(rotationDegrees = 15f)),
            Triple(R.string.safe_share_move_left, normal.copy(centerX = 0.18f), normal.copy(centerX = 0.175f)),
            Triple(R.string.safe_share_move_right, normal.copy(centerX = 0.82f), normal.copy(centerX = 0.825f)),
            Triple(R.string.safe_share_move_up, normal.copy(centerY = 0.05f), normal.copy(centerY = 0.04375f)),
            Triple(R.string.safe_share_move_down, normal.copy(centerY = 0.95f), normal.copy(centerY = 0.95625f)),
            Triple(R.string.visual_mark_enlarge, normal.copy(widthFraction = 0.79f), normal.copy(widthFraction = 0.8f)),
            Triple(R.string.visual_mark_reduce, normal.copy(widthFraction = 0.11f), normal.copy(widthFraction = 0.1f)),
            Triple(R.string.visual_mark_rotate_left, normal.copy(rotationDegrees = -179f), normal.copy(rotationDegrees = 166f)),
            Triple(R.string.visual_mark_rotate_right, normal.copy(rotationDegrees = 179f), normal.copy(rotationDegrees = -166f)),
        )
        for ((resource, initial, expected) in cases) {
            compose.runOnIdle { editor.value = editor.value.copy(placement = initial) }
            val action = actions.single { it.label == label(resource) }
            repeat(3) {
                // Keep the original callback across recompositions to catch stale placement captures.
                compose.runOnIdle { assertTrue("Accessibility action rejected: ${action.label}", action.action()) }
                assertPlacementInsidePage()
            }
            compose.runOnIdle {
                val actual = editor.value.placement
                assertEquals(action.label, expected.centerX, actual.centerX, 0.001f)
                assertEquals(action.label, expected.centerY, actual.centerY, 0.001f)
                assertEquals(action.label, expected.widthFraction, actual.widthFraction, 0.001f)
                assertEquals(action.label, expected.rotationDegrees, actual.rotationDegrees, 0.001f)
            }
        }
        click(R.string.reset_visual_mark)
        val enlarge = actions.single { it.label == label(R.string.visual_mark_enlarge) }
        val rotate = actions.single { it.label == label(R.string.visual_mark_rotate_right) }
        compose.runOnIdle { assertTrue(enlarge.action()) }
        compose.runOnIdle { assertTrue(rotate.action()) }
        val afterActions = compose.runOnIdle {
            assertEquals(0.37f, editor.value.placement.widthFraction, 0.001f)
            assertEquals(5f, editor.value.placement.rotationDegrees, 0.001f)
            editor.value.placement
        }
        val page = previewPageBounds(preview)
        dragPreview(preview, Offset(-page.width * 0.1f, -page.height * 0.1f))
        pinchRotatePreview(preview, zoom = 1.2f, rotationDegrees = 15f)
        compose.runOnIdle {
            assertEquals(afterActions.centerX - 0.1f, editor.value.placement.centerX, 0.015f)
            assertEquals(afterActions.centerY - 0.1f, editor.value.placement.centerY, 0.015f)
            assertEquals(afterActions.widthFraction * 1.2f, editor.value.placement.widthFraction, 0.02f)
            assertEquals(afterActions.rotationDegrees + 15f, editor.value.placement.rotationDegrees, 1f)
        }
        assertPlacementInsidePage()
    }

    @Test
    fun busyEditorIgnoresFingerGesturesUntilUnlocked() {
        showEditor()
        val apply = compose.onNodeWithText(context.getString(R.string.apply_visual_mark, 1))
        apply.assertIsEnabled()
        val preview = compose.onNodeWithContentDescription(label(R.string.visual_mark_preview))
        val page = previewPageBounds(preview)
        dragPreview(preview, Offset(-page.width * 0.05f, -page.height * 0.05f))
        val cachedActions = preview.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(8, cachedActions.size)
        val beforeBusy = compose.runOnIdle {
            assertTrue(placementChanges > 0)
            editor.value = editor.value.copy(busy = true, applying = true)
            editor.value.placement to placementChanges
        }
        apply.assertIsNotEnabled()
        compose.onNodeWithText(label(R.string.reset_visual_mark)).performScrollTo().assertIsNotEnabled()
        assertFalse(preview.fetchSemanticsNode().config.contains(SemanticsActions.CustomActions))
        for (action in cachedActions) {
            compose.runOnIdle { assertFalse("Busy action was accepted: ${action.label}", action.action()) }
        }
        dragPreview(preview, Offset(-page.width * 0.1f, -page.height * 0.1f))
        pinchRotatePreview(preview, zoom = 1.4f, rotationDegrees = 30f)
        compose.runOnIdle {
            assertEquals(beforeBusy.first, editor.value.placement)
            assertEquals(beforeBusy.second, placementChanges)
            assertEquals(0, applies)
            editor.value = editor.value.copy(busy = false, applying = false)
        }
        apply.assertIsEnabled()
        compose.onNodeWithText(label(R.string.reset_visual_mark)).assertIsEnabled()
        dragPreview(preview, Offset(-page.width * 0.05f, -page.height * 0.05f))
        compose.runOnIdle {
            assertTrue(editor.value.placement.centerX < beforeBusy.first.centerX)
            assertTrue(editor.value.placement.centerY < beforeBusy.first.centerY)
            assertTrue(placementChanges > beforeBusy.second)
        }
        assertPlacementInsidePage()
    }

    @Test
    fun addDropdownDispatchesEachActionWithoutStartingExternalActivities() {
        var draws = 0
        var imports = 0
        var scans = 0
        compose.setContent {
            TestSurface {
                VisualMarkCreationActions(
                    enabled = true,
                    onDraw = { draws++ }, onImport = { imports++ }, onScan = { scans++ },
                )
            }
        }
        for (action in listOf(R.string.draw_visual_mark, R.string.import_visual_mark, R.string.scan_visual_mark)) {
            compose.onNodeWithText(label(R.string.add_visual_mark)).performClick()
            compose.onNodeWithText(label(action)).assertIsDisplayed().performClick()
        }
        compose.runOnIdle {
            assertEquals(1, draws)
            assertEquals(1, imports)
            assertEquals(1, scans)
        }
    }

    @Test
    fun librarySelectsAndDeletesOnlyItsOwnSavedTemplate() {
        val isolated = object : ContextWrapper(compose.activity) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = qaDirectory
            override fun getCacheDir(): File = qaDirectory
        }
        val store = MarkTemplateStore(isolated)
        store.save(mark)
        store.save(mark)
        compose.setContent {
            TestSurface {
                CompositionLocalProvider(LocalContext provides isolated) {
                    VisualMarkLibraryScreen(onClose = {})
                }
            }
        }
        val second = context.getString(R.string.saved_visual_mark_number, 2)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription(second).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(second).performClick().assertIsSelected()
        capture("library-preview.png")
        compose.onNodeWithContentDescription(label(R.string.delete_visual_mark)).performClick()
        compose.onNodeWithText(label(R.string.delete_visual_mark_confirm)).performClick()
        compose.waitUntil(5_000) { store.list().size == 1 }
        compose.onNodeWithContentDescription(context.getString(R.string.saved_visual_mark_number, 1))
            .assertIsDisplayed().assertIsSelected()
    }

    @Test
    fun zeroHeightPreviewAtLargeFontRecoversWithoutGeometryFailure() {
        height.value = 1.dp
        showEditor(fontScale = 2f)
        compose.onNodeWithText(context.getString(R.string.apply_visual_mark, 1)).assertIsEnabled()
        val preview = compose.onNodeWithContentDescription(label(R.string.visual_mark_preview))
        assertEquals(0, preview.fetchSemanticsNode().size.height)
        compose.runOnIdle { height.value = 480.dp }
        preview.assertIsDisplayed()
        assertTrue(preview.fetchSemanticsNode().size.height > 0)
        compose.onNodeWithText(context.getString(R.string.apply_visual_mark, 1)).assertIsDisplayed()
        capture("editor-large-font.png")
    }

    @Test
    fun drawingDialogDeliversRealStrokesUndoClearAndSaveAtLargeFont() {
        var draft = emptyList<MarkStroke>()
        var saved = emptyList<MarkStroke>()
        compose.setContent {
            TestSurface(fontScale = 2f) {
                DrawVisualMarkDialog(
                    initialStrokes = emptyList(), message = null,
                    onDraftChange = { draft = it }, onDismiss = {}, onSave = { saved = it },
                )
            }
        }
        val drawing = compose.onNodeWithContentDescription(label(R.string.draw_visual_mark_accessibility))
        fun drawStroke() {
            drawing.performScrollTo()
            val size = drawing.fetchSemanticsNode().size
            drawing.performTouchInput {
                swipe(Offset(size.width * 0.2f, size.height * 0.5f),
                    Offset(size.width * 0.8f, size.height * 0.5f), durationMillis = 250)
            }
            compose.runOnIdle { assertTrue(draft.isNotEmpty()) }
        }
        drawStroke()
        click(R.string.undo_drawing)
        compose.runOnIdle { assertTrue(draft.isEmpty()) }
        compose.onNodeWithText(label(R.string.save_visual_mark)).assertIsNotEnabled()
        drawStroke()
        click(R.string.clear_drawing)
        compose.runOnIdle { assertTrue(draft.isEmpty()) }
        drawStroke()
        capture("drawing-large-font.png", compose.onNode(isDialog()))
        compose.onNodeWithText(label(R.string.save_visual_mark)).assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(draft, saved)
            assertTrue(saved.sumOf { it.points.size } >= 2)
            assertTrue(saved.all { stroke -> stroke.points.all { it.x in 0f..1f && it.y in 0f..1f } })
        }
    }

    private fun showEditor(fontScale: Float = 1f, appearance: AppAppearanceSettings = AppAppearanceSettings()) {
        compose.setContent {
            TestSurface(fontScale, appearance) {
                Box(Modifier.fillMaxWidth().height(height.value)) {
                    VisualMarkEditorScreen(
                        result = result, editor = editor.value,
                        onClose = {}, onSelectTemplate = {},
                        onPlacementChange = {
                            placementChanges++
                            editor.value = editor.value.copy(placement = it)
                        },
                        onBeginDrawing = {}, onUpdateDrawing = {}, onCancelDrawing = {},
                        onImport = {}, onSaveDrawing = {}, onDeleteTemplate = {},
                        onLoadTemplate = { _, _ -> mark }, onScan = {}, onApply = {
                            applies++
                            appliedPlacement = editor.value.placement
                        },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Composable
    private fun TestSurface(fontScale: Float = 1f, appearance: AppAppearanceSettings = AppAppearanceSettings(), content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            ScanItTheme(appearance, content = content)
        }
    }

    private fun label(@StringRes resource: Int): String = context.getString(resource)

    private fun click(@StringRes resource: Int) {
        compose.onNodeWithText(label(resource)).performScrollTo().performClick()
    }

    private fun previewPageBounds(preview: SemanticsNodeInteraction): Rect {
        val size = preview.assertIsDisplayed().fetchSemanticsNode().size
        val page = checkNotNull(result.thumbnail)
        val scale = min(size.width.toFloat() / page.width, size.height.toFloat() / page.height)
        val width = page.width * scale
        val height = page.height * scale
        val left = (size.width - width) / 2f
        val top = (size.height - height) / 2f
        assertTrue("Gesture preview has no usable page", width > 0f && height > 0f)
        return Rect(left, top, left + width, top + height)
    }

    private fun dragPreview(preview: SemanticsNodeInteraction, delta: Offset) {
        val page = previewPageBounds(preview)
        val placement = compose.runOnIdle { editor.value.placement }
        val start = Offset(page.left + page.width * placement.centerX, page.top + page.height * placement.centerY)
        preview.performTouchInput {
            down(0, start)
            for (step in 1..12) {
                moveTo(pointerId = 0, position = start + delta * (step / 12f), delayMillis = 16)
            }
            up(0)
        }
    }

    private fun pinchRotatePreview(preview: SemanticsNodeInteraction, zoom: Float, rotationDegrees: Float) {
        val page = previewPageBounds(preview)
        val placement = compose.runOnIdle { editor.value.placement }
        val center = Offset(page.left + page.width * placement.centerX, page.top + page.height * placement.centerY)
        val radius = min(page.width, page.height) * 0.12f
        preview.performTouchInput {
            down(0, center - Offset(radius, 0f))
            down(1, center + Offset(radius, 0f))
            for (step in 1..12) {
                val progress = step / 12f
                val angle = Math.toRadians((rotationDegrees * progress).toDouble())
                val distance = radius * (1f + (zoom - 1f) * progress)
                val vector = Offset((cos(angle) * distance).toFloat(), (sin(angle) * distance).toFloat())
                // Update both fingers before dispatching one move event, avoiding unintended pan.
                updatePointerTo(0, center - vector)
                moveTo(pointerId = 1, position = center + vector, delayMillis = 16)
            }
            up(0)
            up(1)
        }
    }

    private fun assertPlacementInsidePage() {
        compose.runOnIdle {
            val placement = editor.value.placement
            val page = checkNotNull(result.thumbnail)
            val width = page.width * placement.widthFraction
            val height = width * mark.height / mark.width
            val angle = Math.toRadians(placement.rotationDegrees.toDouble())
            val halfWidth = (abs(cos(angle)) * width + abs(sin(angle)) * height) / 2.0
            val halfHeight = (abs(sin(angle)) * width + abs(cos(angle)) * height) / 2.0
            val centerX = page.width * placement.centerX
            val centerY = page.height * placement.centerY
            assertTrue("Rotated mark crosses a horizontal page edge: $placement",
                centerX - halfWidth >= -0.5 && centerX + halfWidth <= page.width + 0.5)
            assertTrue("Rotated mark crosses a vertical page edge: $placement",
                centerY - halfHeight >= -0.5 && centerY + halfHeight <= page.height + 0.5)
        }
    }

    private fun capture(name: String, node: SemanticsNodeInteraction = compose.onRoot()) {
        val bitmap = node.captureToImage().asAndroidBitmap()
        try {
            val file = File(qaDirectory, name)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            assertTrue(file.length() > 0L)
        } finally {
            bitmap.recycle()
        }
    }
}
