package com.majkeylab.scanit

internal enum class ScannerV2Filter(
    val wireValue: String,
    val colorMode: ScanColorMode,
) {
    Original("original", ScanColorMode.Natural),
    Natural("natural", ScanColorMode.Natural),
    Color("color", ScanColorMode.Color),
    LightText("light_text", ScanColorMode.LightText),
    Grayscale("grayscale", ScanColorMode.Grayscale),
    Drawing("drawing", ScanColorMode.Grayscale),
    BlackWhite("black_white", ScanColorMode.BlackWhite),
    Whiteboard("whiteboard", ScanColorMode.Whiteboard),
    ;

    companion object {
        fun parse(raw: String): ScannerV2Filter? = entries.firstOrNull { it.wireValue == raw }
    }
}

internal data class ScannerV2Appearance(
    val filter: ScannerV2Filter,
    val intensity: Int,
    val shadows: Int,
) {
    init {
        require(intensity in 0..100) { "Scanner filter intensity is invalid" }
        require(shadows in 0..100) { "Scanner shadow strength is invalid" }
        require(filter != ScannerV2Filter.Original || (intensity == 0 && shadows == 0)) {
            "Original scanner appearance cannot modify pixels"
        }
        require(filter != ScannerV2Filter.Drawing || shadows == 0) {
            "Drawing scanner appearance cannot remove shadows"
        }
    }

    fun asScanAppearance(): ScanAppearance = ScanAppearance(
        colorMode = filter.colorMode,
        intensity = intensity,
        shadows = shadows,
    )

    companion object {
        fun original(): ScannerV2Appearance = ScannerV2Appearance(
            filter = ScannerV2Filter.Original,
            intensity = 0,
            shadows = 0,
        )

        fun defaultFor(filter: ScannerV2Filter): ScannerV2Appearance =
            if (filter == ScannerV2Filter.Original) {
                original()
            } else {
                ScannerV2Appearance(filter, intensity = 100, shadows = 0)
            }
    }
}
