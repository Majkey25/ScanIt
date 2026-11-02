package com.majkeylab.scanit

import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class FileToolsUiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val screen = mutableStateOf<ScreenState>(ScreenState.Recent(emptyList()))
    private val viewport = mutableStateOf(DpSize(320.dp, 480.dp))
    private val pdfOpener = mutableStateOf<(() -> Unit)?>(null)
    private val openedActions = mutableListOf<DocumentImportAction>()
    private val scrollableList = hasScrollToIndexAction() and
        SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
    private var scans = 0
    private var openedPdfs = 0
    private val actions = listOf(
        R.string.open_pdf to DocumentImportAction.Open,
        R.string.pdf_to_images to DocumentImportAction.PdfToImages,
        R.string.images_to_pdf to DocumentImportAction.ImagesToPdf,
    )

    @Test
    fun recentToolsRouteAllActionsWithoutStartingScanner() {
        showApp()
        clickImportActions()
        compose.runOnIdle {
            assertEquals(actions.map { it.second }, openedActions)
            assertEquals(0, scans)
        }
    }

    @Test
    fun failureToolsRouteAllActionsWithoutRetryingScanner() {
        screen.value = ScreenState.Failure(UiMessage(R.string.file_import_failed))
        showApp()
        clickImportActions()
        compose.runOnIdle {
            assertEquals(actions.map { it.second }, openedActions)
            assertEquals(0, scans)
        }
        tool(R.string.try_again).performClick()
        compose.runOnIdle { assertEquals(1, scans) }
    }

    @Test
    fun deletionAndProcessingPreventImportUntilRecentIsReady() {
        screen.value = ScreenState.Recent(emptyList(), deletionInProgress = true)
        showApp()
        listOf(R.string.open_pdf, R.string.convert_documents).forEach { resource ->
            tool(resource).assertIsNotEnabled().performTouchInput { click() }
        }
        compose.runOnIdle {
            assertTrue(openedActions.isEmpty())
            screen.value = ScreenState.Processing(UiMessage(R.string.opening_file), canNavigateBack = false)
        }
        actions.forEach { (resource, _) ->
            compose.onNodeWithText(context.getString(resource)).assertDoesNotExist()
        }
        compose.runOnIdle {
            assertTrue(openedActions.isEmpty())
            screen.value = ScreenState.Recent(emptyList())
        }
        tool(R.string.open_pdf).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(DocumentImportAction.Open), openedActions) }
    }

    @Test
    fun toolsStayReachableAndUnclippedAtDoubleFontInSmallAndLandscapeLayouts() {
        showApp(fontScale = 2f)
        for (size in listOf(DpSize(320.dp, 480.dp), DpSize(640.dp, 320.dp))) {
            for (state in listOf(
                ScreenState.Recent(emptyList()),
                ScreenState.Failure(UiMessage(R.string.file_import_failed)),
            )) {
                compose.runOnIdle {
                    viewport.value = size
                    screen.value = state
                }
                val heading = tool(R.string.document_tools).fetchSemanticsNode()
                assertTrue(heading.config.contains(SemanticsProperties.Heading))
                val width = heading.boundsInRoot.width
                actions.forEach { (resource, _) ->
                    val button = tool(resource).assertIsEnabled().assertHeightIsAtLeast(48.dp)
                    val bounds = button.fetchSemanticsNode().boundsInRoot
                    assertTrue(bounds.width > 0f)
                    if (resource == R.string.open_pdf) assertTrue(bounds.width <= width)
                    val layouts = mutableListOf<TextLayoutResult>()
                    compose.onNodeWithText(context.getString(resource), useUnmergedTree = true)
                        .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
                    assertTrue(layouts.isNotEmpty())
                    assertTrue(
                        "${context.getString(resource)} overflows at $size",
                        layouts.none { it.hasVisualOverflow },
                    )
                    button.performClick()
                }
            }
        }
        compose.runOnIdle {
            assertEquals(List(4) { actions.map { it.second } }.flatten(), openedActions)
            assertEquals(0, scans)
        }
    }

    @Test
    fun openPdfRequiresCallbackAndWaitsForResultOperations() {
        val result = ScreenState.Result(
            scan = SavedScan(
                cached = CachedScan(
                    "FileToolsUi", listOf(File(context.cacheDir, "file-tools-unused.jpg")),
                    File(context.cacheDir, "file-tools-unused.pdf"),
                ),
                savedImages = emptyList(), savedPdf = null,
            ),
            thumbnail = null,
        )
        screen.value = result
        showApp()
        tool(R.string.open_pdf_in_viewer).assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { pdfOpener.value = { openedPdfs++ } }
        tool(R.string.open_pdf_in_viewer).assertIsEnabled().performClick()
        for (busy in listOf(
            result.copy(outputSaveInProgress = true),
            result.copy(outputChangeInProgress = true),
            result.copy(imageSharePreparationInProgress = true),
        )) {
            compose.runOnIdle { screen.value = busy }
            tool(R.string.open_pdf_in_viewer).assertIsNotEnabled().performTouchInput { click() }
        }
        compose.runOnIdle { screen.value = result }
        tool(R.string.open_pdf_in_viewer).assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(2, openedPdfs)
            assertTrue(openedActions.isEmpty())
            assertEquals(0, scans)
        }
    }

    private fun showApp(fontScale: Float = 1f) {
        check(context.packageName == "com.majkeylab.scanit.internal")
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(viewport.value)) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
                    ScanItApp(
                        state = screen.value, settings = AppSettings(), language = AppLanguage.System,
                        defaultEmailSubjects = emptySet(), onScan = { scans++ },
                        onSaveSettings = { _, complete -> complete(true) }, onLanguageChange = {},
                        onPdfFolderSelected = { _, _, _ -> }, onPdfFolderCleared = {},
                        onRecent = {}, onOpenRecent = {}, onShareRecentPdf = {}, onDeleteRecent = {},
                        onLoadThumbnail = { null }, onSelectResultPage = {}, onNavigateBack = {},
                        onOpenFiles = { openedActions += it }, onOpenPdf = pdfOpener.value,
                    )
                }
            }
        }
    }

    private fun clickImportActions() {
        actions.forEach { (resource, _) ->
            tool(resource)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .assertIsEnabled().performClick()
        }
    }

    private fun tool(@StringRes resource: Int): SemanticsNodeInteraction {
        val label = context.getString(resource)
        if (resource == R.string.pdf_to_images || resource == R.string.images_to_pdf) {
            tool(R.string.convert_documents).performClick()
            return compose.onNodeWithText(label).assertIsDisplayed()
        }
        compose.onNode(scrollableList).performScrollToNode(hasText(label))
        return compose.onNodeWithText(label).assertIsDisplayed()
    }
}
