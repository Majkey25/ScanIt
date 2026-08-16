package com.majkeylab.scanit

internal object ScannerSessionGate {
    fun start(): ScannerSessionState = state(
        generation = 1,
        pages = emptyList(),
        selectedIndex = null,
        stage = ScannerSessionStage.Capturing,
    )

    fun beginCapture(current: ScannerSessionState): ScannerSessionState {
        requireReviewing(current)
        check(current.pages.size < MAX_SCAN_PAGES) { "Scanner page limit exceeded" }
        return state(
            generation = nextGeneration(current.generation),
            pages = current.pages,
            selectedIndex = current.selectedIndex,
            stage = ScannerSessionStage.Capturing,
        )
    }

    fun beginRetake(current: ScannerSessionState): ScannerSessionState {
        requireReviewing(current)
        val selected = checkNotNull(current.selectedIndex) { "Selected page is missing" }
        return state(
            generation = nextGeneration(current.generation),
            pages = current.pages,
            selectedIndex = selected,
            stage = ScannerSessionStage.Capturing,
            pendingReplacementIndex = selected,
        )
    }

    fun completeCapture(
        current: ScannerSessionState,
        callbackGeneration: Long,
        page: ScannerPage,
    ): ScannerSessionState {
        if (current.stage != ScannerSessionStage.Capturing || callbackGeneration != current.generation) {
            return current
        }
        val replacementIndex = current.pendingReplacementIndex
        require(
            current.pages.none { it.id == page.id } ||
                (replacementIndex != null && current.pages[replacementIndex].id == page.id),
        ) { "Scanner page id is duplicated" }

        val pages = if (replacementIndex == null) {
            check(current.pages.size < MAX_SCAN_PAGES) { "Scanner page limit exceeded" }
            current.pages + page
        } else {
            current.pages.toMutableList().apply { this[replacementIndex] = page }
        }
        val selected = replacementIndex ?: pages.lastIndex
        return state(
            generation = current.generation,
            pages = pages,
            selectedIndex = selected,
            stage = ScannerSessionStage.Reviewing,
        )
    }

    fun cancelCapture(
        current: ScannerSessionState,
        callbackGeneration: Long,
    ): ScannerSessionState {
        if (current.stage != ScannerSessionStage.Capturing || callbackGeneration != current.generation) {
            return current
        }
        return state(
            generation = current.generation,
            pages = current.pages,
            selectedIndex = current.selectedIndex,
            stage = if (current.pages.isEmpty()) {
                ScannerSessionStage.Cancelled
            } else {
                ScannerSessionStage.Reviewing
            },
        )
    }

    fun select(current: ScannerSessionState, index: Int): ScannerSessionState {
        requireReviewing(current)
        require(index in current.pages.indices) { "Selected page is invalid" }
        return state(
            generation = current.generation,
            pages = current.pages,
            selectedIndex = index,
            stage = current.stage,
        )
    }

    fun reorder(current: ScannerSessionState, fromIndex: Int, toIndex: Int): ScannerSessionState {
        requireReviewing(current)
        require(fromIndex in current.pages.indices && toIndex in current.pages.indices) {
            "Page reorder is invalid"
        }
        if (fromIndex == toIndex) return current

        val selectedId = current.pages[checkNotNull(current.selectedIndex)].id
        val pages = current.pages.toMutableList()
        pages.add(toIndex, pages.removeAt(fromIndex))
        return state(
            generation = current.generation,
            pages = pages,
            selectedIndex = pages.indexOfFirst { it.id == selectedId },
            stage = current.stage,
        )
    }

    fun delete(current: ScannerSessionState, index: Int): ScannerSessionState {
        requireReviewing(current)
        require(index in current.pages.indices) { "Page deletion is invalid" }
        val selectedId = current.pages[checkNotNull(current.selectedIndex)].id
        val pages = current.pages.toMutableList().apply { removeAt(index) }
        if (pages.isEmpty()) {
            return state(
                generation = nextGeneration(current.generation),
                pages = pages,
                selectedIndex = null,
                stage = ScannerSessionStage.Capturing,
            )
        }
        val retainedSelection = pages.indexOfFirst { it.id == selectedId }
        return state(
            generation = current.generation,
            pages = pages,
            selectedIndex = if (retainedSelection >= 0) retainedSelection else minOf(index, pages.lastIndex),
            stage = current.stage,
        )
    }

    fun finish(current: ScannerSessionState): ScannerSessionState {
        requireReviewing(current)
        return state(
            generation = current.generation,
            pages = current.pages,
            selectedIndex = current.selectedIndex,
            stage = ScannerSessionStage.Finishing,
        )
    }

    private fun requireReviewing(state: ScannerSessionState) {
        check(state.stage == ScannerSessionStage.Reviewing) { "Scanner session is not reviewable" }
    }

    private fun nextGeneration(generation: Long): Long {
        check(generation < Long.MAX_VALUE) { "Scanner session generation exhausted" }
        return generation + 1
    }

    private fun state(
        generation: Long,
        pages: List<ScannerPage>,
        selectedIndex: Int?,
        stage: ScannerSessionStage,
        pendingReplacementIndex: Int? = null,
    ): ScannerSessionState = ScannerSessionState.restore(
        generation = generation,
        pages = pages,
        selectedIndex = selectedIndex,
        stage = stage,
        pendingReplacementIndex = pendingReplacementIndex,
    )
}
