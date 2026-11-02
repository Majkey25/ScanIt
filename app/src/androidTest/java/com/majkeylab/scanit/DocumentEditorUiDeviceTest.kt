package com.majkeylab.scanit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class DocumentEditorUiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val editor = mutableStateOf(VisualMarkEditorState(
        MarkEditorSource("Scan_EditorQA", "17ca792d-f61a-4d5e-82d8-2ae1d974c167", 0),
        pageAnnotations = emptyList(),
    ))
    private var saved = 0
    private var closed = 0
    private var signatures = 0

    @Test
    fun pageChangeKeepsAccessibilityZoomAndFitConnectedToTheVisibleCanvas() {
        showEditor(DpSize(390.dp, 780.dp), pageCount = 2)
        compose.onNodeWithText(context.getString(R.string.page_position_short, 1, 2)).assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.annotation_next_page))
            .assertIsEnabled().performClick()
        compose.onNodeWithText(context.getString(R.string.page_position_short, 2, 2)).assertIsDisplayed()

        val fitted = canvasPixels()
        val fittedBlue = fitted.count { it == Color.BLUE }
        assertTrue("The second page marker is not visible", fittedBlue > 100)
        val canvas = compose.onNodeWithContentDescription(label(R.string.annotation_canvas))
        val zoomIn = canvas.fetchSemanticsNode().config[SemanticsActions.CustomActions]
            .single { it.label == label(R.string.annotation_zoom_in) }
        compose.runOnIdle { assertTrue(zoomIn.action()) }
        assertTrue("Accessibility zoom did not enlarge the visible page marker",
            canvasPixels().count { it == Color.BLUE } > fittedBlue)

        click(R.string.annotation_fit)
        assertArrayEquals("Fit did not restore the visible page after a source change", fitted, canvasPixels())
    }

    @Test
    fun penTextUndoRedoAndSignatureAreReachableWithoutDiscardingPendingInk() {
        showEditor(DpSize(390.dp, 780.dp))
        val canvas = compose.onNodeWithContentDescription(label(R.string.annotation_canvas))
        canvas.performTouchInput { swipe(Offset(width * 0.3f, height * 0.4f), Offset(width * 0.65f, height * 0.55f), 250) }
        compose.runOnIdle { assertTrue(editor.value.pageAnnotations.orEmpty().isNotEmpty()) }
        compose.onNodeWithContentDescription(label(R.string.undo_drawing)).performClick()
        compose.runOnIdle { assertTrue(editor.value.pageAnnotations.orEmpty().isEmpty()) }
        compose.onNodeWithContentDescription(label(R.string.redo_drawing)).performClick()
        click(R.string.annotation_text)
        click(R.string.annotation_add_text)
        compose.onNodeWithText(label(R.string.annotation_text_hint)).performClick().performTextInput("Příliš žluťoučký kůň")
        capture("phone-keyboard.png", system = true)
        compose.onNodeWithText(label(R.string.save_visual_mark)).assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals("Příliš žluťoučký kůň", (editor.value.pageAnnotations!!.last() as PageAnnotation.Text).value)
        }
        capture("phone-text.png")
        click(R.string.annotation_sign)
        compose.runOnIdle {
            assertEquals(1, signatures)
            assertEquals(2, editor.value.pageAnnotations!!.size)
        }
        click(R.string.annotation_save_copy)
        compose.runOnIdle { assertEquals(1, saved) }
    }

    @Test
    fun dirtyBackRequiresExplicitDiscardAndOriginalWarningIsVisible() {
        editor.value = editor.value.copy(pageAnnotations = listOf(PageAnnotation.Text("Poznámka", 0.2f, 0.3f, 0.04f, Color.BLUE)))
        showEditor(DpSize(390.dp, 780.dp))
        compose.onNodeWithText(label(R.string.annotation_copy_hint)).assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.back)).performClick()
        compose.onNodeWithText(label(R.string.annotation_discard_title)).assertIsDisplayed()
        click(R.string.annotation_keep_editing)
        compose.runOnIdle { assertEquals(0, closed) }
        compose.onNodeWithContentDescription(label(R.string.back)).performClick()
        click(R.string.annotation_discard)
        compose.runOnIdle { assertEquals(1, closed) }
    }

    @Test
    fun tabletLayoutKeepsCanvasAndToolsVisibleAtLargeFont() {
        showEditor(DpSize(1000.dp, 760.dp), fontScale = 1.5f)
        compose.onNodeWithContentDescription(label(R.string.annotation_canvas)).assertIsDisplayed()
        listOf(R.string.annotation_pen, R.string.annotation_highlight, R.string.annotation_text,
            R.string.annotation_erase, R.string.annotation_sign).forEach { compose.onNodeWithText(label(it)).assertIsDisplayed() }
        click(R.string.annotation_text)
        click(R.string.annotation_add_text)
        compose.onNodeWithText(label(R.string.annotation_text_hint)).performTextInput("Tablet")
        compose.onNodeWithText(label(R.string.save_visual_mark)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, editor.value.pageAnnotations!!.size) }
        capture("tablet-text.png")
    }

    @Test
    fun smallPhoneKeepsCanvasAndTextControlsReachableAtDoubleFont() {
        showEditor(DpSize(320.dp, 600.dp), fontScale = 2f)
        compose.onNodeWithContentDescription(label(R.string.annotation_canvas)).assertIsDisplayed().assertHeightIsAtLeast(64.dp)
        compose.onNodeWithText(label(R.string.annotation_text)).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText(label(R.string.annotation_add_text)).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText(label(R.string.annotation_text_hint)).performClick().performTextInput("Malý telefon")
        capture("small-keyboard.png", system = true)
        compose.onNodeWithText(label(R.string.save_visual_mark)).assertIsDisplayed().performClick()
        capture("small-large-font.png")
    }

    @Test
    fun landscapePhoneKeepsThePageVisibleBesideControls() {
        showEditor(DpSize(640.dp, 320.dp))
        compose.onNodeWithContentDescription(label(R.string.annotation_canvas)).assertIsDisplayed().assertHeightIsAtLeast(120.dp)
        click(R.string.annotation_highlight)
        click(R.string.annotation_sign)
        compose.runOnIdle { assertEquals(1, signatures) }
        capture("landscape.png")
    }

    @Test
    fun pdfImportExplainsFlatteningAndDoesNothingUntilConfirmed() {
        var imports = 0
        var cancelled = 0
        compose.setContent {
            ScanItTheme(AppAppearanceSettings()) {
                DocumentImportDialog(DocumentImportAction.Open, { imports++ }, { cancelled++ })
            }
        }
        compose.onNodeWithText(label(R.string.pdf_import_body)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, imports) }
        click(R.string.cancel)
        compose.runOnIdle { assertEquals(1, cancelled); assertEquals(0, imports) }
        click(R.string.pdf_import_confirm)
        compose.runOnIdle { assertEquals(1, imports) }
    }

    private fun showEditor(size: DpSize, fontScale: Float = 1f, pageCount: Int = 1) {
        check(context.packageName == "com.majkeylab.scanit.internal")
        val pages = List(pageCount) { index ->
            Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.WHITE)
                if (index == 0) {
                    Canvas(this).drawRect(45f, 70f, 530f, 88f, Paint().apply { color = Color.BLACK })
                } else {
                    Canvas(this).drawRect(120f, 260f, 480f, 540f, Paint().apply { color = Color.BLUE })
                }
            }
        }
        val result = ScreenState.Result(
            SavedScan(CachedScan("Scan_EditorQA", List(pageCount) { File(context.cacheDir, "editor-qa-unused-$it.jpg") },
                File(context.cacheDir, "editor-qa-unused.pdf"), entryId = editor.value.source.entryId), emptyList(), null), pages.first(),
        )
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size)) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
                    ScanItTheme(AppAppearanceSettings()) {
                        val current = editor.value
                        DocumentEditorScreen(result.copy(thumbnail = pages[current.source.pageIndex],
                            selectedPageIndex = current.source.pageIndex), current, { closed++ }, { annotations ->
                            val old = editor.value
                            editor.value = old.copy(pageAnnotations = annotations,
                                annotationUndo = old.annotationUndo + listOf(old.pageAnnotations.orEmpty()), annotationRedo = emptyList())
                        }, {
                            val old = editor.value
                            editor.value = old.copy(pageAnnotations = old.annotationUndo.last(),
                                annotationUndo = old.annotationUndo.dropLast(1), annotationRedo = listOf(old.pageAnnotations.orEmpty()))
                        }, {
                            val old = editor.value
                            editor.value = old.copy(pageAnnotations = old.annotationRedo.last(),
                                annotationUndo = listOf(old.pageAnnotations.orEmpty()), annotationRedo = emptyList())
                        }, { signatures++ }, { saved++ }, { index ->
                            val old = editor.value
                            editor.value = old.copy(source = old.source.copy(pageIndex = index),
                                pageAnnotations = emptyList(), annotationUndo = emptyList(), annotationRedo = emptyList())
                        })
                    }
                }
            }
        }
    }

    private fun label(resource: Int) = context.getString(resource)
    private fun click(resource: Int) { compose.onNodeWithText(label(resource)).assertIsDisplayed().performClick() }
    private fun canvasPixels(): IntArray {
        compose.waitForIdle()
        val bitmap = compose.onNodeWithContentDescription(label(R.string.annotation_canvas))
            .captureToImage().asAndroidBitmap()
        try {
            return IntArray(bitmap.width * bitmap.height).also {
                bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            }
        } finally {
            bitmap.recycle()
        }
    }
    private fun capture(name: String, system: Boolean = false) {
        compose.waitForIdle()
        val directory = File(context.cacheDir, "document-editor-ui-qa").apply { mkdirs() }
        val bitmap = if (system) checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            else compose.onRoot().captureToImage().asAndroidBitmap()
        bitmap.let {
            File(directory, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
    }
}
