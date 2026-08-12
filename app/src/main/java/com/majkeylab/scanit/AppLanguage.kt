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
