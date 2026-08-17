package com.majkeylab.scanit

import android.content.Context
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal const val MAX_SCANNER_V2_CUSTOM_FILTERS = 8
internal const val MAX_SCANNER_V2_CUSTOM_FILTER_BYTES = 8 * 1024
internal const val MAX_SCANNER_V2_CUSTOM_FILTER_NAME_LENGTH = 24
private const val CUSTOM_FILTER_VERSION = 1
private const val CUSTOM_FILTER_PREFERENCES = "scanner_v2_custom_filters"
private const val CUSTOM_FILTER_VALUE = "presets"
private val CUSTOM_FILTER_ROOT_KEYS = setOf("version", "presets")
private val CUSTOM_FILTER_KEYS = setOf("id", "name", "filterId", "intensity", "shadows")

internal enum class ScannerV2CustomFilterSaveError {
    InvalidName,
    CapacityReached,
    UnsupportedAppearance,
}

internal sealed interface ScannerV2CustomFilterLoadResult {
    data class Loaded(val presets: List<ScannerV2CustomFilter>) : ScannerV2CustomFilterLoadResult
    data object Unreadable : ScannerV2CustomFilterLoadResult
}

internal data class ScannerV2CustomFilter(
    val id: String,
    val name: String,
    val appearance: ScannerV2Appearance,
) {
    init {
        require(UUID.fromString(id).toString() == id) { "Custom filter ID is invalid" }
        require(normalizeScannerV2CustomFilterName(name) == name) { "Custom filter name is invalid" }
        require(appearance.filter != ScannerV2Filter.Original) { "Original cannot be a custom filter" }
    }
}

internal fun scannerV2CustomFilterSaveError(
    current: List<ScannerV2CustomFilter>,
    rawName: String,
    appearance: ScannerV2Appearance,
): ScannerV2CustomFilterSaveError? {
    validateScannerV2CustomFilters(current)
    if (appearance.filter == ScannerV2Filter.Original) {
        return ScannerV2CustomFilterSaveError.UnsupportedAppearance
    }
    val name = normalizeScannerV2CustomFilterName(rawName)
        ?: return ScannerV2CustomFilterSaveError.InvalidName
    val existing = current.any { it.name.equals(name, ignoreCase = true) }
    return if (!existing && current.size >= MAX_SCANNER_V2_CUSTOM_FILTERS) {
        ScannerV2CustomFilterSaveError.CapacityReached
    } else {
        null
    }
}

internal fun upsertScannerV2CustomFilter(
    current: List<ScannerV2CustomFilter>,
    rawName: String,
    appearance: ScannerV2Appearance,
    newId: String,
): List<ScannerV2CustomFilter> {
    require(scannerV2CustomFilterSaveError(current, rawName, appearance) == null) {
        "Custom filter cannot be saved"
    }
    val name = requireNotNull(normalizeScannerV2CustomFilterName(rawName))
    val existingIndex = current.indexOfFirst { it.name.equals(name, ignoreCase = true) }
    val replacement = if (existingIndex >= 0) {
        ScannerV2CustomFilter(current[existingIndex].id, name, appearance)
    } else {
        ScannerV2CustomFilter(newId, name, appearance)
    }
    return if (existingIndex >= 0) {
        current.toMutableList().apply { this[existingIndex] = replacement }
    } else {
        current + replacement
    }
}

internal fun scannerV2CustomFilterReplacementName(
    current: List<ScannerV2CustomFilter>,
    rawName: String,
): String? {
    validateScannerV2CustomFilters(current)
    val name = normalizeScannerV2CustomFilterName(rawName) ?: return null
    return current.firstOrNull { it.name.equals(name, ignoreCase = true) }?.name
}

internal fun deleteScannerV2CustomFilter(
    current: List<ScannerV2CustomFilter>,
    id: String,
): List<ScannerV2CustomFilter> {
    validateScannerV2CustomFilters(current)
    return current.filterNot { it.id == id }
}

internal fun encodeScannerV2CustomFilters(presets: List<ScannerV2CustomFilter>): String {
    validateScannerV2CustomFilters(presets)
    val array = JSONArray()
    presets.forEach { preset ->
        array.put(
            JSONObject()
                .put("id", preset.id)
                .put("name", preset.name)
                .put("filterId", preset.appearance.filter.wireValue)
                .put("intensity", preset.appearance.intensity)
                .put("shadows", preset.appearance.shadows),
        )
    }
    val encoded = JSONObject().put("version", CUSTOM_FILTER_VERSION).put("presets", array).toString()
    require(encoded.toByteArray(StandardCharsets.UTF_8).size <= MAX_SCANNER_V2_CUSTOM_FILTER_BYTES) {
        "Custom filter data is too large"
    }
    return encoded
}

internal fun decodeScannerV2CustomFilters(raw: String): List<ScannerV2CustomFilter>? {
    if (
        raw.length > MAX_SCANNER_V2_CUSTOM_FILTER_BYTES ||
        raw.toByteArray(StandardCharsets.UTF_8).size > MAX_SCANNER_V2_CUSTOM_FILTER_BYTES
    ) return null
    return try {
        val root = JSONObject(raw)
        if (root.keys().asSequence().toSet() != CUSTOM_FILTER_ROOT_KEYS) return null
        if (root.strictInt("version") != CUSTOM_FILTER_VERSION) return null
        val array = root.optJSONArray("presets") ?: return null
        if (array.length() > MAX_SCANNER_V2_CUSTOM_FILTERS) return null
        val presets = buildList(array.length()) {
            repeat(array.length()) { index ->
                val value = array.optJSONObject(index) ?: return null
                if (value.keys().asSequence().toSet() != CUSTOM_FILTER_KEYS) return null
                val filter = ScannerV2Filter.parse(value.strictString("filterId")) ?: return null
                add(
                    ScannerV2CustomFilter(
                        id = value.strictString("id"),
                        name = value.strictString("name"),
                        appearance = ScannerV2Appearance(
                            filter = filter,
                            intensity = value.strictInt("intensity"),
                            shadows = value.strictInt("shadows"),
                        ),
                    ),
                )
            }
        }
        validateScannerV2CustomFilters(presets)
        presets
    } catch (_: JSONException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

internal fun scannerV2CustomFilterLoadResult(raw: Any?): ScannerV2CustomFilterLoadResult =
    if (raw == null) {
        ScannerV2CustomFilterLoadResult.Loaded(emptyList())
    } else if (raw !is String) {
        ScannerV2CustomFilterLoadResult.Unreadable
    } else {
        decodeScannerV2CustomFilters(raw)
            ?.let(ScannerV2CustomFilterLoadResult::Loaded)
            ?: ScannerV2CustomFilterLoadResult.Unreadable
    }

internal class ScannerV2CustomFilterStore(context: Context) {
    private val preferences = context.getSharedPreferences(CUSTOM_FILTER_PREFERENCES, Context.MODE_PRIVATE)

    fun load(): ScannerV2CustomFilterLoadResult = scannerV2CustomFilterLoadResult(
        preferences.all[CUSTOM_FILTER_VALUE],
    )

    fun save(presets: List<ScannerV2CustomFilter>) {
        preferences.edit().putString(CUSTOM_FILTER_VALUE, encodeScannerV2CustomFilters(presets)).apply()
    }

    fun reset() {
        preferences.edit().remove(CUSTOM_FILTER_VALUE).apply()
    }
}

private fun normalizeScannerV2CustomFilterName(raw: String): String? {
    val name = raw.trim()
    if (name.length !in 1..MAX_SCANNER_V2_CUSTOM_FILTER_NAME_LENGTH) return null
    return name.takeIf { value -> value.none(Char::isISOControl) }
}

private fun validateScannerV2CustomFilters(presets: List<ScannerV2CustomFilter>) {
    require(presets.size <= MAX_SCANNER_V2_CUSTOM_FILTERS) { "Too many custom filters" }
    require(presets.map { it.id }.toSet().size == presets.size) { "Custom filter IDs are duplicated" }
    require(
        presets.map { it.name.lowercase(Locale.ROOT) }.toSet().size == presets.size,
    ) { "Custom filter names are duplicated" }
}

private fun JSONObject.strictString(key: String): String = get(key).let { value ->
    require(value is String) { "$key must be a string" }
    value
}

private fun JSONObject.strictInt(key: String): Int = get(key).let { value ->
    require(value is Int) { "$key must be an integer" }
    value
}
