package com.majkeylab.scanit

import java.util.UUID

@JvmInline
internal value class PageId private constructor(val value: String) {
    companion object {
        fun parse(raw: String): PageId {
            val parsed = runCatching { UUID.fromString(raw) }
                .getOrElse { throw IllegalArgumentException("Page id is invalid", it) }
            require(parsed.toString() == raw) { "Page id is not canonical" }
            return PageId(raw)
        }
    }
}

internal data class ScannerPage(val id: PageId)

internal enum class ScannerSessionStage {
    Capturing,
    Reviewing,
    Finishing,
    Cancelled,
}

@ConsistentCopyVisibility
internal data class ScannerSessionState internal constructor(
    val generation: Long,
    val pages: List<ScannerPage>,
    val selectedIndex: Int?,
    val stage: ScannerSessionStage,
    val pendingReplacementIndex: Int?,
) {
    init {
        require(generation > 0) { "Scanner session generation is invalid" }
        require(pages.size <= MAX_SCAN_PAGES) { "Scanner page limit exceeded" }
        require(pages.map { it.id }.toSet().size == pages.size) { "Scanner page ids are duplicated" }
        require(
            if (pages.isEmpty()) selectedIndex == null else selectedIndex in pages.indices,
        ) { "Selected page is invalid" }
        require(
            (stage != ScannerSessionStage.Reviewing && stage != ScannerSessionStage.Finishing) ||
                pages.isNotEmpty(),
        ) {
            "Scanner stage requires a page"
        }
        require(
            pendingReplacementIndex == null ||
                (stage == ScannerSessionStage.Capturing && pendingReplacementIndex in pages.indices),
        ) { "Pending replacement is invalid" }
    }

    companion object {
        fun restore(
            generation: Long,
            pages: List<ScannerPage>,
            selectedIndex: Int?,
            stage: ScannerSessionStage,
            pendingReplacementIndex: Int?,
        ): ScannerSessionState = ScannerSessionState(
            generation = generation,
            pages = pages.toList(),
            selectedIndex = selectedIndex,
            stage = stage,
            pendingReplacementIndex = pendingReplacementIndex,
        )
    }
}
