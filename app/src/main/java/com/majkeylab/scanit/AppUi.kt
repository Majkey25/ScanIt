package com.majkeylab.scanit

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.io.IOException

internal enum class AppLanguage(val languageTag: String?) {
    System(null),
    English("en"),
    Czech("cs"),
}

private const val PRIVACY_POLICY_URL =
    "https://github.com/Majkey25/ScanIt/blob/main/PRIVACY.md"

private val LightColorScheme =
    lightColorScheme(
        primary = Color.Black,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFE5E5E5),
        onPrimaryContainer = Color.Black,
        secondary = Color(0xFF444444),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFE5E5E5),
        onSecondaryContainer = Color.Black,
        tertiary = Color(0xFF666666),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFE5E5E5),
        onTertiaryContainer = Color.Black,
        inversePrimary = Color.White,
    )

private val DarkColorScheme =
    darkColorScheme(
        primary = Color.White,
        onPrimary = Color.Black,
        primaryContainer = Color(0xFF303030),
        onPrimaryContainer = Color.White,
        secondary = Color(0xFFD0D0D0),
        onSecondary = Color.Black,
        secondaryContainer = Color(0xFF303030),
        onSecondaryContainer = Color.White,
        tertiary = Color(0xFFB0B0B0),
        onTertiary = Color.Black,
        tertiaryContainer = Color(0xFF303030),
        onTertiaryContainer = Color.White,
        inversePrimary = Color.Black,
    )

@Composable
internal fun ScanItApp(
    state: ScreenState,
    settings: AppSettings,
    language: AppLanguage,
    defaultEmailSubjects: Set<String>,
    onScan: () -> Unit,
    onSaveSettings: (AppSettings) -> Unit,
    onLanguageChange: (AppLanguage) -> Unit,
    onPdfFolderSelected: (Uri, Int) -> UiMessage?,
    onPdfFolderCleared: () -> UiMessage?,
    onSharePdf: (() -> Unit)? = null,
    onShareImages: (() -> Unit)? = null,
    onPrint: (() -> Unit)? = null,
) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    BackHandler(showSettings) { showSettings = false }
    val settingsOnly = state === ScreenState.Ready

    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColorScheme else LightColorScheme,
    ) {
        if (showSettings || settingsOnly) {
            SettingsScreen(
                settings = settings,
                language = language,
                defaultEmailSubjects = defaultEmailSubjects,
                onClose = {
                    showSettings = false
                    if (settingsOnly) {
                        onScan()
                    }
                },
                onSave = onSaveSettings,
                onLanguageChange = onLanguageChange,
                onPdfFolderSelected = onPdfFolderSelected,
                onPdfFolderCleared = onPdfFolderCleared,
            )
        } else {
            when (state) {
                ScreenState.Ready -> ProcessingScreen(stringResource(R.string.opening_scanner))
                is ScreenState.Processing -> ProcessingScreen(state.message.resolve())
                is ScreenState.Failure ->
                    FailureScreen(
                        message = state.message.resolve(),
                        onRetry = onScan,
                        onSettings = { showSettings = true },
                    )
                is ScreenState.Result ->
                    ResultScreen(
                        result = state,
                        onNewScan = onScan,
                        onSettings = { showSettings = true },
                        onSharePdf = onSharePdf,
                        onShareImages = onShareImages,
                        onPrint = onPrint,
                    )
            }
        }
    }
}

@Composable
private fun ProcessingScreen(message: String) {
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(20.dp))
            Text(message, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun FailureScreen(
    message: String,
    onRetry: () -> Unit,
    onSettings: () -> Unit,
) {
    MainScaffold(onSettings) { modifier ->
        Column(
            modifier = modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(message, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(stringResource(R.string.try_again))
            }
        }
    }
}

@Composable
private fun ResultScreen(
    result: ScreenState.Result,
    onNewScan: () -> Unit,
    onSettings: () -> Unit,
    onSharePdf: (() -> Unit)?,
    onShareImages: (() -> Unit)?,
    onPrint: (() -> Unit)?,
) {
    val scan = result.scan
    val pageCount = scan.cached.pages.size
    MainScaffold(onSettings) { modifier ->
        LazyColumn(
            modifier = modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                if (result.thumbnail == null) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(stringResource(R.string.preview_unavailable))
                    }
                } else {
                    Image(
                        bitmap = result.thumbnail.asImageBitmap(),
                        contentDescription = stringResource(R.string.scan_preview),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 360.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
            item {
                Text(
                    pluralStringResource(R.plurals.page_count, pageCount, pageCount),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            item {
                val savedPdf = scan.savedPdf
                Text(
                    when {
                        savedPdf == null -> stringResource(R.string.pdf_temporary)
                        savedPdf.authority == MediaStore.AUTHORITY ->
                            stringResource(
                                R.string.pdf_saved_downloads,
                                scan.cached.pdf.name,
                            )
                        else ->
                            stringResource(
                                R.string.pdf_saved_selected_folder,
                                scan.cached.pdf.name,
                            )
                    },
                )
                Text(
                    if (scan.galleryPages.isEmpty()) {
                        stringResource(R.string.images_temporary)
                    } else {
                        stringResource(
                            R.string.images_saved_gallery,
                            scan.cached.pages.first().name,
                        )
                    },
                )
                scan.warnings.forEach {
                    Text(it.resolve(), color = MaterialTheme.colorScheme.error)
                }
            }
            item {
                Button(
                    onClick = { onSharePdf?.invoke() },
                    enabled = onSharePdf != null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) {
                    Text(stringResource(R.string.send_pdf))
                }
            }
            item {
                OutlinedButton(
                    onClick = { onShareImages?.invoke() },
                    enabled = onShareImages != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.send_images))
                }
            }
            item {
                OutlinedButton(
                    onClick = { onPrint?.invoke() },
                    enabled = onPrint != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.print_document))
                }
            }
            item {
                TextButton(onClick = onNewScan, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.new_scan))
                }
            }
            item { Spacer(Modifier.height(4.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold(
    onSettings: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    val settingsDescription = stringResource(R.string.open_settings)
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(
                        onClick = onSettings,
                        modifier = Modifier.semantics {
                            contentDescription = settingsDescription
                        },
                    ) {
                        Text(
                            stringResource(R.string.settings_symbol),
                            modifier = Modifier.clearAndSetSemantics {},
                        )
                    }
                },
            )
        },
    ) { padding -> content(Modifier.fillMaxSize().padding(padding)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    settings: AppSettings,
    language: AppLanguage,
    defaultEmailSubjects: Set<String>,
    onClose: () -> Unit,
    onSave: (AppSettings) -> Unit,
    onLanguageChange: (AppLanguage) -> Unit,
    onPdfFolderSelected: (Uri, Int) -> UiMessage?,
    onPdfFolderCleared: () -> UiMessage?,
) {
    val folderPermissionMissing = UiMessage(R.string.folder_permission_missing)
    val folderPermissionFailed = UiMessage(R.string.folder_permission_failed)
    val folderClearFailed = UiMessage(R.string.folder_clear_failed)
    val settingsSaveFailed = UiMessage(R.string.settings_save_failed)
    var savePdf by rememberSaveable { mutableStateOf(settings.savePdf) }
    var saveImages by rememberSaveable { mutableStateOf(settings.saveImages) }
    var albumName by rememberSaveable { mutableStateOf(settings.albumName) }
    var multipage by rememberSaveable { mutableStateOf(settings.multipage) }
    var allowGallery by rememberSaveable { mutableStateOf(settings.allowGallery) }
    var emailSubject by rememberSaveable { mutableStateOf(settings.emailSubject) }
    var emailBody by rememberSaveable { mutableStateOf(settings.emailBody) }
    var pdfTreeUri by rememberSaveable { mutableStateOf(settings.pdfTreeUri) }
    var folderError by remember { mutableStateOf<UiMessage?>(null) }
    var settingsError by remember { mutableStateOf<UiMessage?>(null) }
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(settings.emailSubject, defaultEmailSubjects) {
        emailSubject =
            localizedDefaultEmailSubject(
                current = emailSubject,
                targetDefault = settings.emailSubject,
                supportedDefaults = defaultEmailSubjects,
            )
    }

    val folderLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data
                val uri = data?.data
                val grantedFlags = (data?.flags ?: 0) and PDF_TREE_FLAGS
                if (uri == null || grantedFlags != PDF_TREE_FLAGS) {
                    folderError = folderPermissionMissing
                } else {
                    try {
                        folderError = onPdfFolderSelected(uri, grantedFlags)
                        pdfTreeUri = uri.toString()
                    } catch (_: SecurityException) {
                        folderError = folderPermissionFailed
                    } catch (_: IOException) {
                        folderError = folderPermissionFailed
                    } catch (_: RuntimeException) {
                        folderError = folderPermissionFailed
                    }
                }
            }
        }

    val backDescription = stringResource(R.string.back)
    Scaffold(
        modifier = Modifier.imePadding(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.semantics {
                            contentDescription = backDescription
                        },
                    ) {
                        Text(
                            stringResource(R.string.back_symbol),
                            modifier = Modifier.clearAndSetSemantics {},
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { SectionTitle(stringResource(R.string.language)) }
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    AppLanguage.entries.forEach { option ->
                        FilterChip(
                            selected = language == option,
                            onClick = { onLanguageChange(option) },
                            label = {
                                Text(
                                    stringResource(
                                        when (option) {
                                            AppLanguage.System -> R.string.language_system
                                            AppLanguage.English -> R.string.language_english
                                            AppLanguage.Czech -> R.string.language_czech
                                        },
                                    ),
                                )
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        )
                    }
                }
            }
            item { HorizontalDivider() }
            item { SectionTitle(stringResource(R.string.saving)) }
            item {
                SettingsSwitch(
                    label = stringResource(R.string.save_pdf),
                    checked = savePdf,
                    onCheckedChange = { savePdf = it },
                )
            }
            item {
                SettingsSwitch(
                    label = stringResource(R.string.save_images),
                    checked = saveImages,
                    onCheckedChange = { saveImages = it },
                )
            }
            item {
                OutlinedTextField(
                    value = albumName,
                    onValueChange = { albumName = it },
                    label = { Text(stringResource(R.string.album_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Text(
                    if (pdfTreeUri == null) {
                        stringResource(R.string.default_pdf_folder)
                    } else {
                        stringResource(R.string.custom_pdf_folder)
                    },
                )
                Text(
                    stringResource(R.string.folder_saved_immediately),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            folderError = null
                            folderLauncher.launch(
                                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                                    PDF_TREE_FLAGS or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
                                ),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.choose_folder))
                    }
                    OutlinedButton(
                        onClick = {
                            try {
                                folderError = onPdfFolderCleared()
                                pdfTreeUri = null
                            } catch (_: SecurityException) {
                                folderError = folderClearFailed
                            } catch (_: IOException) {
                                folderError = folderClearFailed
                            } catch (_: RuntimeException) {
                                folderError = folderClearFailed
                            }
                        },
                        enabled = pdfTreeUri != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.clear_folder))
                    }
                }
                folderError?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error) }
            }
            item { SectionTitle(stringResource(R.string.scanning)) }
            item {
                SettingsSwitch(
                    label = stringResource(R.string.multiple_pages),
                    checked = multipage,
                    onCheckedChange = { multipage = it },
                )
            }
            item {
                SettingsSwitch(
                    label = stringResource(R.string.allow_gallery),
                    checked = allowGallery,
                    onCheckedChange = { allowGallery = it },
                )
            }
            item { SectionTitle(stringResource(R.string.sharing)) }
            item {
                OutlinedTextField(
                    value = emailSubject,
                    onValueChange = { emailSubject = it },
                    label = { Text(stringResource(R.string.email_subject)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = emailBody,
                    onValueChange = { emailBody = it },
                    label = { Text(stringResource(R.string.email_body)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                settingsError?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error) }
                Button(
                    onClick = {
                        try {
                            onSave(
                                AppSettings(
                                    savePdf = savePdf,
                                    saveImages = saveImages,
                                    albumName = albumName,
                                    multipage = multipage,
                                    allowGallery = allowGallery,
                                    emailSubject = emailSubject,
                                    emailBody = emailBody,
                                    pdfTreeUri = pdfTreeUri,
                                ),
                            )
                            onClose()
                        } catch (_: RuntimeException) {
                            settingsError = settingsSaveFailed
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) {
                    Text(stringResource(R.string.save_settings))
                }
                TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.cancel))
                }
                TextButton(
                    onClick = { uriHandler.openUri(PRIVACY_POLICY_URL) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.privacy_policy))
                }
            }
            item { Spacer(Modifier.height(4.dp)) }
        }
    }
}

@Composable
private fun SettingsSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun UiMessage.resolve(): String =
    stringResource(resourceId, *formatArgs.toTypedArray())
