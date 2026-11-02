package com.majkeylab.scanit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun DocumentTools(enabled: Boolean, onOpen: (DocumentImportAction) -> Unit) {
    var converting by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.document_tools), style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth().semantics { heading() })
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { onOpen(DocumentImportAction.Open) }, enabled = enabled,
                modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp)) {
                DocumentToolLabel(R.drawable.ic_pdf, R.string.open_pdf)
            }
            OutlinedButton(onClick = { converting = true }, enabled = enabled,
                modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp)) {
                DocumentToolLabel(R.drawable.ic_image, R.string.convert_documents)
            }
        }
        Text(stringResource(R.string.document_tools_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (converting) {
        ModalBottomSheet(onDismissRequest = { converting = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.convert_documents), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() })
                listOf(DocumentImportAction.PdfToImages to R.string.pdf_to_images,
                    DocumentImportAction.ImagesToPdf to R.string.images_to_pdf).forEach { (action, label) ->
                    OutlinedButton(onClick = { converting = false; onOpen(action) }, enabled = enabled,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        DocumentToolLabel(if (action == DocumentImportAction.PdfToImages) R.drawable.ic_image else R.drawable.ic_pdf, label)
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.DocumentToolLabel(icon: Int, label: Int) {
    Icon(painterResource(icon), null, Modifier.size(20.dp))
    Spacer(Modifier.width(8.dp))
    Text(stringResource(label), modifier = Modifier.weight(1f).padding(vertical = 8.dp), textAlign = TextAlign.Center)
}

@Composable
internal fun DocumentImportDialog(action: DocumentImportAction, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.pdf_import_title)) },
        text = {
            Column(Modifier.heightIn(max = dialogContentMaxHeight()).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.pdf_import_body))
                Text(stringResource(if (action == DocumentImportAction.Open) R.string.pdf_import_open_hint
                    else R.string.pdf_import_convert_hint))
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) {
            Text(stringResource(if (action == DocumentImportAction.Open) R.string.pdf_import_confirm else R.string.convert_documents))
        } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
internal fun DocumentEditorScreen(
    result: ScreenState.Result,
    editor: VisualMarkEditorState,
    onClose: () -> Unit,
    onChange: (List<PageAnnotation>) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSignature: () -> Unit,
    onApply: () -> Unit,
    onPageChange: (Int) -> Unit,
) {
    val annotations = editor.pageAnnotations.orEmpty()
    val source = editor.source
    var toolName by rememberSaveable(source) { mutableStateOf(AnnotationTool.Pen.name) }
    val tool = AnnotationTool.valueOf(toolName)
    var penColor by rememberSaveable(source) { mutableIntStateOf(0xff161616.toInt()) }
    var highlightColor by rememberSaveable(source) { mutableIntStateOf(0xffffd54f.toInt()) }
    var penWidth by rememberSaveable(source) { mutableFloatStateOf(0.004f) }
    var highlightWidth by rememberSaveable(source) { mutableFloatStateOf(0.035f) }
    var textSize by rememberSaveable(source) { mutableFloatStateOf(0.035f) }
    var fingerDrawing by rememberSaveable(source) { mutableStateOf(true) }
    var stylusSeen by rememberSaveable(source) { mutableStateOf(false) }
    var discard by rememberSaveable(source) { mutableStateOf(false) }
    var nextPage by rememberSaveable(source) { mutableIntStateOf(-1) }
    var textIndex by rememberSaveable(source) { mutableIntStateOf(-2) }
    var textX by rememberSaveable(source) { mutableFloatStateOf(0.15f) }
    var textY by rememberSaveable(source) { mutableFloatStateOf(0.15f) }
    var limit by remember(source) { mutableStateOf(false) }
    val enabled = !editor.busy && result.thumbnail != null
    val context = LocalContext.current
    val largeFont = LocalDensity.current.fontScale > 1.5f
    val canvas = remember(source) { DocumentEditorCanvas(context) }
    val colour = if (tool == AnnotationTool.Highlighter) highlightColor else penColor
    val width = if (tool == AnnotationTool.Highlighter) highlightWidth else penWidth
    val requestClose = { if (annotations.isEmpty()) onClose() else discard = true }
    val openText: (Int?, AnnotationPoint) -> Unit = { index, point ->
        textIndex = index ?: -1
        textX = point.x; textY = point.y
    }
    val requestPage: (Int) -> Unit = { page ->
        if (annotations.isEmpty()) onPageChange(page) else nextPage = page
    }
    BackHandler { if (!editor.busy) requestClose() }

    Scaffold(contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Surface {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(end = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = requestClose, enabled = !editor.busy, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                    }
                    if (largeFont) Spacer(Modifier.weight(1f)) else {
                        Text(stringResource(R.string.document_editor), style = MaterialTheme.typography.titleLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).semantics { heading() })
                    }
                    IconButton(onClick = onUndo, enabled = enabled && editor.annotationUndo.isNotEmpty(), modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(R.drawable.ic_restore), stringResource(R.string.undo_drawing))
                    }
                    IconButton(onClick = onRedo, enabled = enabled && editor.annotationRedo.isNotEmpty(), modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(R.drawable.ic_restore), stringResource(R.string.redo_drawing),
                            modifier = Modifier.graphicsLayer { scaleX = -1f })
                    }
                    Button(onClick = { if (annotations.isEmpty()) onClose() else onApply() },
                        enabled = enabled, contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(if (annotations.isEmpty()) R.string.annotation_done else R.string.annotation_save_copy))
                    }
                }
            }
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val wide = maxWidth >= 840.dp || (maxWidth >= 600.dp && maxWidth > maxHeight)
            val compact = maxHeight < 480.dp
            val controlsHeight = if (maxHeight < 600.dp) 140.dp else 220.dp
            val tools: @Composable (Modifier) -> Unit = { modifier ->
                EditorTools(tool, enabled, { toolName = it.name }, onSignature, wide, compact, modifier)
            }
            val controls: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (tool in listOf(AnnotationTool.Pen, AnnotationTool.Highlighter, AnnotationTool.Text)) {
                        EditorColours(colour, enabled, tool == AnnotationTool.Highlighter) {
                            if (tool == AnnotationTool.Highlighter) highlightColor = it else penColor = it
                        }
                        val sizeLabel = stringResource(if (tool == AnnotationTool.Text)
                            R.string.annotation_text_size else R.string.redaction_brush_thickness)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(sizeLabel,
                                style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(84.dp))
                            Slider(value = if (tool == AnnotationTool.Text) textSize else width,
                                onValueChange = { when (tool) {
                                    AnnotationTool.Text -> textSize = it
                                    AnnotationTool.Highlighter -> highlightWidth = it
                                    else -> penWidth = it
                                } }, enabled = enabled,
                                valueRange = when (tool) {
                                    AnnotationTool.Text -> 0.014f..0.08f
                                    AnnotationTool.Highlighter -> 0.012f..0.07f
                                    else -> 0.0015f..0.016f
                                }, modifier = Modifier.weight(1f).semantics {
                                    contentDescription = sizeLabel
                                })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (tool == AnnotationTool.Text) {
                            TextButton(onClick = { openText(null, AnnotationPoint(0.15f, 0.15f)) }, enabled = enabled,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                ActionButtonContent(R.drawable.ic_add, R.string.annotation_add_text)
                            }
                        } else {
                            Row(Modifier.weight(1f).heightIn(min = 48.dp).selectable(
                                selected = fingerDrawing, enabled = enabled, role = Role.Checkbox,
                                onClick = { fingerDrawing = !fingerDrawing }), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = fingerDrawing, onCheckedChange = null, enabled = enabled)
                                Text(stringResource(R.string.annotation_finger), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        TextButton(onClick = canvas::fitPage, enabled = enabled) { Text(stringResource(R.string.annotation_fit)) }
                    }
                    Text(stringResource(if (tool == AnnotationTool.Eraser) R.string.annotation_erase_hint else R.string.annotation_touch_hint),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (wide) Text(stringResource(R.string.annotation_copy_hint), style = MaterialTheme.typography.bodySmall)
                }
            }
            val page: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center) {
                        TextButton(onClick = { requestPage(source.pageIndex - 1) }, enabled = enabled && source.pageIndex > 0) {
                            Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.annotation_previous_page), Modifier.size(20.dp))
                        }
                        Text(stringResource(R.string.page_position_short, source.pageIndex + 1, result.scan.cached.pages.size),
                            style = MaterialTheme.typography.labelLarge)
                        TextButton(onClick = { requestPage(source.pageIndex + 1) },
                            enabled = enabled && source.pageIndex + 1 < result.scan.cached.pages.size) {
                            Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.annotation_next_page),
                                Modifier.size(20.dp).graphicsLayer { scaleX = -1f })
                        }
                    }
                    val canvasDescription = stringResource(R.string.annotation_canvas)
                    val zoomIn = stringResource(R.string.annotation_zoom_in)
                    val zoomOut = stringResource(R.string.annotation_zoom_out)
                    val fit = stringResource(R.string.annotation_fit)
                    val backdrop = MaterialTheme.colorScheme.surfaceContainerLow.toArgb()
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        key(source) {
                            AndroidView(factory = { canvas }, modifier = Modifier.fillMaxSize().clipToBounds().focusable().semantics {
                                contentDescription = canvasDescription
                                customActions = listOf(
                                    CustomAccessibilityAction(zoomIn) { canvas.zoomBy(1.25f); true },
                                    CustomAccessibilityAction(zoomOut) { canvas.zoomBy(0.8f); true },
                                    CustomAccessibilityAction(fit) { canvas.fitPage(); true },
                                )
                            }, update = { view ->
                                view.page = result.thumbnail
                                view.annotations = annotations
                                view.tool = tool; view.inkColor = colour; view.inkWidth = width
                                view.pageAspectRatio = editor.pageAspectRatio
                                view.fingerDrawing = fingerDrawing; view.isEnabled = enabled
                                view.canvasBackgroundColor = backdrop
                                view.onCommit = onChange; view.onText = openText
                                view.onLimit = { limit = true }
                                view.onStylus = { if (!stylusSeen) { stylusSeen = true; fingerDrawing = false } }
                                view.importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                            })
                        }
                        if (editor.busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
                        else if (result.thumbnail == null) {
                            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(stringResource(R.string.preview_unavailable))
                                TextButton(onClick = { onPageChange(source.pageIndex) }) { Text(stringResource(R.string.try_again)) }
                            }
                        }
                    }
                    editor.message?.let {
                        Text(it.resolve(), color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(8.dp).semantics { liveRegion = LiveRegionMode.Polite })
                    }
                }
            }
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    tools(Modifier.width(if (compact) 128.dp else 96.dp).verticalScroll(rememberScrollState()))
                    page(Modifier.weight(1f))
                    controls(Modifier.width(252.dp))
                }
            } else {
                Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                    page(Modifier.weight(1f))
                    Surface { Column {
                        tools(Modifier.fillMaxWidth())
                        controls(Modifier.fillMaxWidth().heightIn(max = controlsHeight))
                        Text(stringResource(R.string.annotation_copy_hint), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    } }
                }
            }
        }
    }
    if (textIndex >= -1) {
        val existing = annotations.getOrNull(textIndex) as? PageAnnotation.Text
        EditorTextDialog(existing, textSize, penColor, textX, textY,
            onClose = { textIndex = -2 },
            onDelete = if (existing == null) null else { { onChange(annotations.filterIndexed { index, _ -> index != textIndex }); textIndex = -2 } },
            onSave = { text ->
                val updated = annotations.toMutableList().apply {
                    if (existing == null) add(text) else set(textIndex, text)
                }
                try { validatePageAnnotations(updated); onChange(updated); textIndex = -2 }
                catch (_: IllegalArgumentException) { limit = true }
            })
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false },
        title = { Text(stringResource(R.string.annotation_discard_title)) },
        text = { Text(stringResource(R.string.annotation_discard_body)) },
        confirmButton = { TextButton(onClick = { discard = false; onClose() }) { Text(stringResource(R.string.annotation_discard)) } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(R.string.annotation_keep_editing)) } })
    if (nextPage >= 0) AlertDialog(onDismissRequest = { nextPage = -1 },
        title = { Text(stringResource(R.string.annotation_switch_title)) },
        text = { Text(stringResource(R.string.annotation_switch_body)) },
        confirmButton = { TextButton(onClick = { val target = nextPage; nextPage = -1; onPageChange(target) }) {
            Text(stringResource(R.string.annotation_save_continue))
        } },
        dismissButton = { TextButton(onClick = { nextPage = -1 }) { Text(stringResource(R.string.cancel)) } })
    if (limit) AlertDialog(onDismissRequest = { limit = false },
        text = { Text(stringResource(R.string.annotation_limit)) },
        confirmButton = { TextButton(onClick = { limit = false }) { Text(stringResource(R.string.annotation_done)) } })
}

@Composable
private fun EditorTools(tool: AnnotationTool, enabled: Boolean, onSelect: (AnnotationTool) -> Unit,
    onSign: () -> Unit, wide: Boolean, compact: Boolean, modifier: Modifier) {
    val largeFont = LocalDensity.current.fontScale > 1.5f
    val tools = listOf<Triple<AnnotationTool?, Int, Int>>(
        Triple(AnnotationTool.Navigate, R.drawable.ic_pan, R.string.annotation_move),
        Triple(AnnotationTool.Pen, R.drawable.ic_edit, R.string.annotation_pen),
        Triple(AnnotationTool.Highlighter, R.drawable.ic_highlight, R.string.annotation_highlight),
        Triple(AnnotationTool.Text, R.drawable.ic_text_format, R.string.annotation_text),
        Triple(AnnotationTool.Eraser, R.drawable.ic_eraser, R.string.annotation_erase),
        Triple(null, R.drawable.ic_signature, R.string.annotation_sign),
    )
    val item: @Composable (Triple<AnnotationTool?, Int, Int>, Modifier) -> Unit = { (value, icon, label), itemModifier ->
            Column(itemModifier.selectable(selected = value == tool, enabled = enabled,
                role = if (value == null) Role.Button else Role.RadioButton,
                onClick = { if (value == null) onSign() else onSelect(value) })
                .background(if (value == tool) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                .heightIn(min = 64.dp).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(painterResource(icon), null, Modifier.size(24.dp))
                Text(stringResource(label), style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center, maxLines = 3)
            }
    }
    if (wide && compact) Column(modifier) {
        tools.chunked(2).forEach { pair -> Row { pair.forEach { item(it, Modifier.weight(1f)) } } }
    } else if (wide) Column(modifier) { tools.forEach { item(it, Modifier.fillMaxWidth()) } }
    else if (largeFont) Row(modifier.horizontalScroll(rememberScrollState())) {
        tools.forEach { item(it, Modifier.width(IntrinsicSize.Max).widthIn(min = 72.dp).padding(horizontal = 12.dp)) }
    } else Row(modifier) { tools.forEach { item(it, Modifier.weight(1f)) } }
}

@Composable
private fun EditorColours(selected: Int, enabled: Boolean, highlighter: Boolean, onSelect: (Int) -> Unit) {
    val colours = if (highlighter) listOf(0xffffd54f.toInt() to R.string.annotation_yellow,
        0xff66bb6a.toInt() to R.string.annotation_green, 0xff42a5f5.toInt() to R.string.annotation_blue,
        0xffffa726.toInt() to R.string.annotation_orange)
    else listOf(0xff161616.toInt() to R.string.annotation_black, 0xffb3261e.toInt() to R.string.annotation_red,
        0xff1558b0.toInt() to R.string.annotation_blue, 0xff146b3a.toInt() to R.string.annotation_green)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        colours.forEach { (value, label) ->
            val name = stringResource(label)
            Surface(onClick = { onSelect(value) }, enabled = enabled, shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(if (value == selected) 3.dp else 1.dp,
                    if (value == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                modifier = Modifier.size(48.dp).semantics {
                    contentDescription = name
                    this.selected = value == selected
                }) {
                Box(Modifier.padding(10.dp).background(Color(value), CircleShape))
            }
        }
    }
}

@Composable
private fun EditorTextDialog(existing: PageAnnotation.Text?, size: Float, color: Int, x: Float, y: Float,
    onClose: () -> Unit, onDelete: (() -> Unit)?, onSave: (PageAnnotation.Text) -> Unit) {
    var value by rememberSaveable { mutableStateOf(existing?.value.orEmpty()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(onDismissRequest = onClose, modifier = Modifier.imePadding(),
        title = { Text(stringResource(if (existing == null) R.string.annotation_add_text else R.string.annotation_edit_text)) },
        text = {
            OutlinedTextField(value = value, onValueChange = { if (it.length <= MAX_ANNOTATION_TEXT_LENGTH) value = it },
                label = { Text(stringResource(R.string.annotation_text_hint)) }, minLines = 2, maxLines = 5,
                modifier = Modifier.fillMaxWidth().focusRequester(focus))
        },
        confirmButton = { TextButton(onClick = {
            onSave(PageAnnotation.Text(value, existing?.x ?: x, existing?.y ?: y,
                existing?.sizeFraction ?: size, existing?.color ?: color))
        }, enabled = value.isNotBlank()) { Text(stringResource(R.string.save_visual_mark)) } },
        dismissButton = { Row {
            onDelete?.let { TextButton(onClick = it) { Text(stringResource(R.string.delete_visual_mark_confirm)) } }
            TextButton(onClick = onClose) { Text(stringResource(R.string.cancel)) }
        } },
    )
}
