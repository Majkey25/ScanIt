# Recent scans and PDF size implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` and `superpowers:test-driven-development`.

**Goal:** Add a small Recent scans dashboard and measured PDF-size targets without changing the scan-first workflow.

**Architecture:** Reuse the existing bounded `cacheDir/share` folders as temporary history. One complete folder equals one entry. Rebuild PDFs from existing ordered JPEG pages with native `PdfDocument`; measure completed files instead of adding FFmpeg or a PDF SDK.

**Tech stack:** Kotlin, Compose, coroutines, Android Bitmap/PdfDocument, existing ML Kit scanner.

---

## Task 1: Define Recent scans rules

**Files:**

- Create: `app/src/main/java/com/majkeylab/scanit/RecentScan.kt`
- Modify: `app/src/main/java/com/majkeylab/scanit/ScanStorage.kt`
- Create: `app/src/test/java/com/majkeylab/scanit/RecentScanTest.kt`

- [ ] Add failing tests for folder parsing, malformed names, contiguous page order, newest-first order, derived names, traversal IDs, and protected pruning.
- [ ] Add `RecentScan(cacheId, displayName, createdAt, pageCount, pdfBytes, firstPage)`.
- [ ] Add `listRecentScans()`, `openCachedScan(cacheId)`, `deleteRecentScan(cacheId)`, and atomic `publishCacheEntry(workDir, finalDir)`.
- [ ] Skip malformed/evicted entries; never touch Downloads, Gallery, or SAF.
- [ ] Keep at most eight visible folders. Protect the active source/open entry during pruning.
- [ ] Run focused tests.
- [ ] Commit `feat: index recent scans`.

## Task 2: Add the minimal dashboard/navigation

**Files:**

- Modify: `app/src/main/java/com/majkeylab/scanit/ScanModels.kt`
- Modify: `app/src/main/java/com/majkeylab/scanit/ScanViewModel.kt`
- Modify: `app/src/main/java/com/majkeylab/scanit/AppUi.kt`
- Modify: `app/src/main/java/com/majkeylab/scanit/MainActivity.kt`
- Modify: English/Czech `strings.xml`

- [ ] Add `ScreenState.Recent` and route state in `SavedStateHandle`.
- [ ] Refresh Recent scans on start, after a completed/derived scan, and after delete.
- [ ] Add a Recent scans icon directly before Settings. Keep fresh launch -> scanner.
- [ ] Cancel scanner -> Recent scans. Dashboard has one primary `New scan` action.
- [ ] Rows show name/date/page count/PDF size + Open/Share/Delete overflow. No folders/tags/rename/database.
- [ ] Back behavior: dashboard from result -> result; dashboard from scanner cancel -> exit; Settings -> previous surface.
- [ ] Empty/evicted state is honest and stable across rotation/process restore.
- [ ] Add a lazy first-page preview only for visible rows.
- [ ] Run lint/unit tests and verify navigation on phone.
- [ ] Commit `feat: add recent scans dashboard`.

## Task 3: Add result page selection

**Files:**

- Modify: `app/src/main/java/com/majkeylab/scanit/AppUi.kt`
- Modify: `app/src/main/java/com/majkeylab/scanit/ScanViewModel.kt`
- Modify: `app/src/main/java/com/majkeylab/scanit/ScanStorage.kt`

- [ ] Add selected page index to state and restoration.
- [ ] Add a small horizontal thumbnail strip only when multiple pages exist.
- [ ] Decode bounded visible thumbnails on `Dispatchers.IO`; cancel obsolete work.
- [ ] Selecting a thumbnail updates the large preview and later mark-page default.
- [ ] Missing page -> refresh/fallback, never crash.
- [ ] Commit `feat: browse scanned pages`.

## Task 4: Define PDF size targets

**Files:**

- Modify: `app/src/main/java/com/majkeylab/scanit/ScanModels.kt`
- Modify: `app/src/main/java/com/majkeylab/scanit/SettingsStore.kt`
- Modify: `app/src/main/java/com/majkeylab/scanit/AppUi.kt`
- Modify: English/Czech `strings.xml`
- Create: `app/src/test/java/com/majkeylab/scanit/PdfSizePolicyTest.kt`

- [ ] Add failing tests for preference parsing, 1,000,000-byte MB values, profile order, under-target identity, impossible target, and malformed stored values.
- [ ] Add `PdfSizeTarget`: Original, 5 MB, 10 MB, 20 MB.
- [ ] Add `PdfSizeResult(file, targetBytes, actualBytes, wasRebuilt, targetMet, profileLongEdge)`.
- [ ] Default Original. Settings label says PDF only; Gallery/image share remain original.
- [ ] Commit `feat: configure PDF size targets`.

## Task 5: Implement measured native PDF rebuilding

**Files:**

- Modify: `app/src/main/java/com/majkeylab/scanit/ScanStorage.kt`
- Modify: `app/src/main/java/com/majkeylab/scanit/ScanViewModel.kt`
- Modify: `app/src/test/java/com/majkeylab/scanit/PdfSizePolicyTest.kt`

- [ ] First add integration tests with generated page bitmaps: under-target hash unchanged, over-target page count preserved, impossible target returns smallest complete candidate, corrupt page preserves source.
- [ ] Add `buildPdfForTarget(pages, sourcePdf, output, target, edited)`.
- [ ] Untouched Original/under-target -> byte-identical source.
- [ ] Edited Original -> highest-quality rebuilt PDF.
- [ ] Otherwise try long edges 2480, 1754, 1240, 877; measure every completed candidate.
- [ ] Atomically select first candidate at/below target, otherwise smallest readable complete candidate.
- [ ] Never replace the source before verification; clean every temp on failure/cancel.
- [ ] Apply this once after scanning/all visual edits and before durable save.
- [ ] Result message shows target + actual when target is impossible.
- [ ] Verify saved/share PDF is the same chosen cache PDF.
- [ ] Commit `feat: build PDFs to measured size targets`.

## Core gate

Run:

```powershell
./gradlew.bat :app:testInternalDebugUnitTest :app:lintInternalDebug :app:assembleInternalDebug
git diff --check
```

Phone scenarios: under-target unchanged; compress a multi-page scan for each target; impossible target warns; corrupt/missing page preserves readable source; Recent scans open/share/delete; ordinary scan/share/print still work.
