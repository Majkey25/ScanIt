package com.majkeylab.scanit

internal enum class AppLanguage(val languageTag: String?) {
    System(null),
    English("en"),
    Czech("cs"),
    German("de"),
    Spanish("es"),
    SimplifiedChinese("zh-Hans"),
}

internal fun appLanguageForTag(languageTag: String?): AppLanguage =
    AppLanguage.entries.firstOrNull { it.languageTag == languageTag } ?: AppLanguage.System

internal enum class OcrScript(val wireValue: String) {
    Auto("auto"),
    Latin("latin"),
    Chinese("chinese"),
}

internal fun ocrScriptForWireValue(value: String?): OcrScript =
    OcrScript.entries.firstOrNull { it.wireValue == value } ?: OcrScript.Auto

internal fun resolveOcrScript(setting: OcrScript, appLanguageTag: String?): OcrScript =
    if (setting != OcrScript.Auto) {
        setting
    } else if (appLanguageTag?.startsWith("zh", ignoreCase = true) == true) {
        OcrScript.Chinese
    } else {
        OcrScript.Latin
    }

internal enum class ReadAloudLanguage(
    val wireValue: String,
    val languageTag: String?,
) {
    Auto("auto", null),
    English("english", "en"),
    Czech("czech", "cs"),
    German("german", "de"),
    Spanish("spanish", "es"),
    SimplifiedChinese("simplified_chinese", "zh-CN"),
}

internal fun readAloudLanguageForWireValue(value: String?): ReadAloudLanguage =
    ReadAloudLanguage.entries.firstOrNull { it.wireValue == value } ?: ReadAloudLanguage.Auto

internal fun resolveReadAloudLanguageTag(
    setting: ReadAloudLanguage,
    appLanguageTag: String?,
    systemLanguageTag: String,
): String = setting.languageTag ?: appLanguageTag?.takeIf(String::isNotBlank) ?: systemLanguageTag
