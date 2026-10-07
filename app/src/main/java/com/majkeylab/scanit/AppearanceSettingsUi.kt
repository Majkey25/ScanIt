package com.majkeylab.scanit

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun AppearanceSettings(appearance: AppAppearanceSettings, onChange: (AppAppearanceSettings) -> Unit) {
    var customOpen by rememberSaveable { mutableStateOf(false) }
    val density = LocalDensity.current
    val window = LocalWindowInfo.current
    val columns = if (with(density) { window.containerSize.width.toDp() } < 360.dp ||
        density.fontScale * appearance.textScalePercent / 100f > 1.3f) 1 else 2
    val presets = AppThemePreset.entries.filter { it != AppThemePreset.MaterialYou || Build.VERSION.SDK_INT >= 31 }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(stringResource(R.string.app_appearance_mode), R.drawable.ic_light_mode)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(AppAppearanceMode.entries) { mode ->
                FilterChip(
                    selected = appearance.mode == mode,
                    onClick = { onChange(appearance.copy(mode = mode)) },
                    label = { Text(appearanceModeLabel(mode)) },
                    leadingIcon = {
                        Icon(painterResource(when (mode) {
                            AppAppearanceMode.System -> R.drawable.ic_settings
                            AppAppearanceMode.Light -> R.drawable.ic_light_mode
                            AppAppearanceMode.Dark -> R.drawable.ic_dark_mode
                        }), null, Modifier.size(18.dp))
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
        SectionTitle(stringResource(R.string.app_theme_presets), R.drawable.ic_palette)
        Text(stringResource(R.string.app_theme_pair_hint), style = MaterialTheme.typography.bodySmall)
        for (row in presets.chunked(columns)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (preset in row) {
                    Surface(
                        modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(themePresetLabel(preset), style = MaterialTheme.typography.titleSmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (dark in listOf(false, true)) {
                                    val preview = appearance.copy(lightTheme = preset, darkTheme = preset)
                                    val colors = appColorScheme(preview, dark)
                                    val selected = (if (dark) appearance.darkTheme else appearance.lightTheme) == preset
                                    val modeLabel = appearanceModeLabel(if (dark) AppAppearanceMode.Dark else AppAppearanceMode.Light)
                                    val description = stringResource(R.string.app_theme_choice, themePresetLabel(preset), modeLabel)
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                                        Surface(
                                            modifier = Modifier.size(56.dp).clip(CircleShape).selectable(
                                                selected = selected, role = Role.RadioButton,
                                                onClick = { onChange(if (dark) appearance.copy(darkTheme = preset) else appearance.copy(lightTheme = preset)) },
                                            ).semantics { contentDescription = description },
                                            shape = CircleShape,
                                            border = BorderStroke(if (selected) 3.dp else 1.dp,
                                                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                                            color = colors.background,
                                        ) {
                                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                                Box(Modifier.size(10.dp).background(colors.primary, CircleShape))
                                                Box(Modifier.fillMaxWidth().height(4.dp).background(colors.onSurface.copy(alpha = 0.5f)))
                                                Box(Modifier.fillMaxWidth().height(7.dp).background(colors.primary, CircleShape))
                                            }
                                        }
                                        Text(modeLabel, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        OutlinedButton(onClick = { customOpen = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            ActionButtonContent(R.drawable.ic_palette, R.string.app_theme_customize)
        }
        if (Build.VERSION.SDK_INT < 31) {
            Text(stringResource(R.string.app_material_you_requires), style = MaterialTheme.typography.bodySmall)
        }
        SettingsSwitch(stringResource(R.string.app_pure_black), appearance.pureBlackDark, R.drawable.ic_dark_mode) {
            onChange(appearance.copy(pureBlackDark = it))
        }
        SectionTitle(stringResource(R.string.app_font), R.drawable.ic_text_format)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(AppFont.entries) { font ->
                FilterChip(
                    selected = appearance.font == font,
                    onClick = { onChange(appearance.copy(font = font)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_text_format), null, Modifier.size(18.dp)) },
                    label = { Text(stringResource(when (font) {
                        AppFont.System -> R.string.app_font_system
                        AppFont.Serif -> R.string.app_font_serif
                        AppFont.Monospace -> R.string.app_font_monospace
                    })) }, modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
        Text(stringResource(R.string.app_text_sample), style = MaterialTheme.typography.bodyLarge)
        var textSize by rememberSaveable(appearance.textScalePercent) { mutableFloatStateOf(appearance.textScalePercent.toFloat()) }
        Text(stringResource(R.string.app_text_size_value, textSize.roundToInt()))
        val textSizeLabel = stringResource(R.string.app_text_size)
        Slider(
            value = textSize, onValueChange = { textSize = it }, valueRange = 80f..150f, steps = 13,
            onValueChangeFinished = {
                val requested = textSize.roundToInt()
                textSize = appearance.textScalePercent.toFloat()
                onChange(appearance.copy(textScalePercent = requested))
            },
            modifier = Modifier.semantics { contentDescription = textSizeLabel },
        )
        var corners by rememberSaveable(appearance.cornerRadiusDp) { mutableFloatStateOf(appearance.cornerRadiusDp.toFloat()) }
        Text(stringResource(R.string.app_corners_value, corners.roundToInt()))
        val cornersLabel = stringResource(R.string.app_corners)
        Slider(
            value = corners, onValueChange = { corners = it }, valueRange = 0f..32f, steps = 7,
            onValueChangeFinished = {
                val requested = corners.roundToInt()
                corners = appearance.cornerRadiusDp.toFloat()
                onChange(appearance.copy(cornerRadiusDp = requested))
            },
            modifier = Modifier.semantics { contentDescription = cornersLabel },
        )
        OutlinedButton(onClick = { onChange(AppAppearanceSettings()) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            ActionButtonContent(R.drawable.ic_restore, R.string.app_appearance_reset)
        }
    }
    if (customOpen) CustomThemeDialog(appearance, onDismiss = { customOpen = false }) { custom ->
        onChange(custom)
        customOpen = false
    }
}

@Composable
private fun CustomThemeDialog(appearance: AppAppearanceSettings, onDismiss: () -> Unit, onApply: (AppAppearanceSettings) -> Unit) {
    var accent by rememberSaveable { mutableStateOf(formatThemeColor(appearance.accentRgb)) }
    var light by rememberSaveable { mutableStateOf(formatThemeColor(appearance.lightBackgroundRgb)) }
    var dark by rememberSaveable { mutableStateOf(formatThemeColor(appearance.darkBackgroundRgb)) }
    val accentColor = parseThemeColor(accent)
    val lightColor = parseThemeColor(light)
    val darkColor = parseThemeColor(dark)
    val valid = accentColor != null && lightColor != null && darkColor != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.app_theme_customize)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.app_theme_color_hint), style = MaterialTheme.typography.bodySmall)
                ThemeColorField(stringResource(R.string.app_theme_accent), accent) { accent = it }
                ThemeColorField(stringResource(R.string.app_theme_light_background), light) { light = it }
                ThemeColorField(stringResource(R.string.app_theme_dark_background), dark) { dark = it }
                if (valid) {
                    val custom = appearance.copy(lightTheme = AppThemePreset.Custom, darkTheme = AppThemePreset.Custom,
                        accentRgb = requireNotNull(accentColor), lightBackgroundRgb = requireNotNull(lightColor), darkBackgroundRgb = requireNotNull(darkColor))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (isDark in listOf(false, true)) {
                            val colors = appColorScheme(custom, isDark)
                            Surface(color = colors.background, contentColor = colors.onBackground, modifier = Modifier.weight(1f),
                                shape = MaterialTheme.shapes.small, border = BorderStroke(1.dp, colors.outline)) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(appearanceModeLabel(if (isDark) AppAppearanceMode.Dark else AppAppearanceMode.Light))
                                    Box(Modifier.fillMaxWidth().height(12.dp).background(colors.primary, CircleShape))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onApply(appearance.copy(lightTheme = AppThemePreset.Custom, darkTheme = AppThemePreset.Custom,
                    accentRgb = requireNotNull(accentColor), lightBackgroundRgb = requireNotNull(lightColor), darkBackgroundRgb = requireNotNull(darkColor)))
            }) { ActionButtonContent(R.drawable.ic_check, R.string.apply_appearance) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { ActionButtonContent(R.drawable.ic_close, R.string.cancel) } },
    )
}

@Composable
private fun ThemeColorField(label: String, value: String, onChange: (String) -> Unit) {
    val invalid = parseThemeColor(value) == null
    OutlinedTextField(
        value = value, onValueChange = { onChange(it.removePrefix("#").take(32)) }, label = { Text(label) },
        prefix = { Text("#") }, isError = invalid, singleLine = true, modifier = Modifier.fillMaxWidth(),
        leadingIcon = { Icon(painterResource(R.drawable.ic_palette), null) },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
        supportingText = { if (invalid) Text(stringResource(R.string.app_theme_color_invalid)) },
    )
}

@Composable
private fun appearanceModeLabel(mode: AppAppearanceMode): String = stringResource(when (mode) {
    AppAppearanceMode.System -> R.string.app_mode_system
    AppAppearanceMode.Light -> R.string.app_mode_light
    AppAppearanceMode.Dark -> R.string.app_mode_dark
})

@Composable
private fun themePresetLabel(preset: AppThemePreset): String = stringResource(when (preset) {
    AppThemePreset.Classic -> R.string.app_theme_classic
    AppThemePreset.MaterialYou -> R.string.app_theme_material_you
    AppThemePreset.Forest -> R.string.app_theme_forest
    AppThemePreset.Ocean -> R.string.app_theme_ocean
    AppThemePreset.Amber -> R.string.app_theme_amber
    AppThemePreset.Lavender -> R.string.app_theme_lavender
    AppThemePreset.Paper -> R.string.app_theme_paper
    AppThemePreset.Custom -> R.string.app_theme_custom
})
