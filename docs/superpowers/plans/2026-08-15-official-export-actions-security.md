# ScanIt 1.3.0 Export Controls, Actions, and Security Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship ScanIt 1.3.0 with complete, crash-safe PDF/image controls in File details, a fullscreen Result preview, honest stable Actions, and a verified security/release gate while keeping Google ML Kit capture.

**Architecture:** Output changes use create/verify/metadata-commit/exact-cleanup transactions; no saved file is overwritten in place. Android platform codecs handle exact source copy, JPEG, and PNG. Compose keeps the approved compact Result hierarchy and adds dialogs/routes only where a picker or fullscreen surface is required.

**Tech Stack:** Kotlin 2.4.10, Android 13+ / API 33-36, Jetpack Compose Material 3, SAF, MediaStore, Android Bitmap codecs, Google ML Kit Document Scanner/Text Recognition/Barcode Scanning, Gradle 9.6.1, PowerShell release verifier.

## Global Constraints

- Official release version is `1.3.0`, version code `14`, package `com.majkeylab.scanit`, min SDK `33`, target SDK `36`.
- Keep Google ML Kit Document Scanner and the current Google review/editor workflow.
- Public Play/GitHub artifacts must have no app-owned `INTERNET`, Gemini, Ads, Billing, broad storage/media, camera, account, or advertising-ID permission/code.
- Image formats are exactly `Original`, `JPG`, and `PNG (lossless)`; never call JPEG lossless.
- Image presets are Original, 3840 px, 2560 px, 1600 px, or custom 320-6000 px, bounded by 12 MP.
- PDF targets remain Original, 5 MB, 10 MB, 20 MB, or Custom MB using decimal bytes.
- Destination changes target a user-selected persisted SAF tree; existing Downloads/Gallery defaults remain available.
- Replacement order is create -> verify -> metadata commit -> exact old-output cleanup. No filename search or raw-URI delete fallback.
- `Actions` is not labelled beta. Do not show placeholders or claim unimplemented semantic/AI/OMR functionality.
- Scanner v2 and ads/Premium beta are separate unpublished branches; this plan must not merge their dependencies or Internet permissions.
- Every code task is RED -> GREEN and ends with focused tests plus a hostile diff review.

---

### Task 1: Export policy and strict metadata v3

**Files:**
- Modify: `app/src/main/java/com/majkeylab/scanit/ScanModels.kt`
- Modify: `app/src/main/java/com/majkeylab/scanit/OutputMetadata.kt`
- Modify: `app/src/test/java/com/majkeylab/scanit/OutputMetadataTest.kt`
- Modify: `app/src/test/java/com/majkeylab/scanit/PureLogicTest.kt`

**Interfaces:**
- Produces: `ImageExportFormat`, `ImageSizePreset`, `ImageExportOptions`, `ResolvedImageExport`, `resolveImageExport(...)`.
- Produces: v3 `ImageOutputRef.treeUri/width/height/format`, rich `SavedImageOutput`, and bounded staged/retired output journals used by Task 3.
- Preserves: decoding of valid v1/v2 output metadata and strict rejection of malformed/oversized/unknown data.

- [ ] **Step 1: Write failing policy tests**

```kotlin
@Test fun imagePresetsResolveExactLongEdges() {
    assertEquals(null, resolveImageExport(ImageSizePreset.Original, null).maxDimension)
    assertEquals(3840, resolveImageExport(ImageSizePreset.High, null).maxDimension)
    assertEquals(2560, resolveImageExport(ImageSizePreset.Balanced, null).maxDimension)
    assertEquals(1600, resolveImageExport(ImageSizePreset.Small, null).maxDimension)
}

@Test fun customImageDimensionIsBounded() {
    assertFailsWith<IllegalArgumentException> {
        resolveImageExport(ImageSizePreset.Custom, 319)
    }
    assertEquals(6000, resolveImageExport(ImageSizePreset.Custom, 6000).maxDimension)
}
```

- [ ] **Step 2: Write failing v3 codec tests**

Cover v1/v2 compatibility, v3 image tree/format/dimensions, exact page order, SHA/length requirements for current and retired refs, maximum cleanup-list count, unknown keys/version, invalid URI, invalid MIME/format combinations, duplicate retired/current URI, and 64 KiB bound.

- [ ] **Step 3: Run RED**

Run:

```powershell
.\gradlew.bat --no-daemon :app:testInternalDebugUnitTest --tests com.majkeylab.scanit.OutputMetadataTest --tests com.majkeylab.scanit.PureLogicTest
```

Expected: compilation fails only for the new export types/fields or new assertions fail against v2.

- [ ] **Step 4: Implement minimal models and codec**

```kotlin
internal enum class ImageExportFormat(val wireValue: String, val mimeType: String?) {
    Original("original", null),
    Jpeg("jpeg", "image/jpeg"),
    Png("png", "image/png"),
}

internal enum class ImageSizePreset(val maxDimension: Int?) {
    Original(null),
    High(3840),
    Balanced(2560),
    Small(1600),
    Custom(null),
}

internal data class ImageExportOptions(
    val format: ImageExportFormat,
    val sizePreset: ImageSizePreset,
    val customMaxDimension: Int? = null,
    val treeUri: String? = null,
)
```

Extend `ImageOutputRef` with strictly validated optional v3 fields and `OutputMetadata` with bounded `stagedPdf`, `stagedImages`, `retiredPdf`, and `retiredImages`. Replace URI-only `SavedScan.galleryPages` with rich saved-image records and a derived URI view so format, dimensions, tree, and fingerprint cannot diverge. Only a new export operation upgrades an entry to v3; untouched v1/v2 metadata remains byte-for-byte unchanged.

- [ ] **Step 5: Run GREEN + diff check**

Run the focused command above and `git diff --check`.

- [ ] **Step 6: Commit**

```text
feat: model configurable image exports
```

### Task 2: Bounded image export renderer

**Files:**
- Create: `app/src/main/java/com/majkeylab/scanit/ImageExportRenderer.kt`
- Create: `app/src/test/java/com/majkeylab/scanit/ImageExportRendererTest.kt`

**Interfaces:**
- Consumes: `ResolvedImageExport` from Task 1.
- Produces: `renderImageExport(source, destination, options, isCancelled): RenderedImageExport`.

- [ ] **Step 1: Write RED tests for pure geometry/format policy**

Cover exact-copy eligibility, no upscaling, 3840/2560/1600/custom scaling, 12 MP rejection/bounding, JPEG extension/MIME/quality, PNG extension/MIME, cancellation, invalid/empty source, and destination preservation on failure.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat --no-daemon :app:testInternalDebugUnitTest --tests com.majkeylab.scanit.ImageExportRendererTest
```

- [ ] **Step 3: Implement bounded renderer**

```kotlin
internal data class RenderedImageExport(
    val file: File,
    val mimeType: String,
    val extension: String,
    val width: Int,
    val height: Int,
    val exactSourceCopy: Boolean,
)

internal fun renderImageExport(
    source: File,
    destination: File,
    options: ResolvedImageExport,
    isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
): RenderedImageExport
```

Use exact `Files.copy` for Original/no-resize. Otherwise decode bounds first, enforce positive dimensions and 12 MP, calculate a sample, scale without upscaling, encode JPEG quality 95 or PNG quality ignored, verify the staged decode dimensions, then atomically publish inside the app-private staging directory. Recycle all bitmaps and delete temp files in `finally`.

- [ ] **Step 4: Run GREEN and platform smoke**

Run the focused test. Later Task 8 executes real JPEG/PNG rendering on emulator; do not claim JVM codec proof.

- [ ] **Step 5: Commit**

```text
feat: render bounded image exports
```

### Task 3: Crash-safe replace/move transactions

**Files:**
- Modify: `app/src/main/java/com/majkeylab/scanit/ScanStorage.kt`
- Modify: `app/src/main/java/com/majkeylab/scanit/DurableOutputDelete.kt`
- Modify: `app/src/main/java/com/majkeylab/scanit/OutputMetadata.kt`
- Create: `app/src/main/java/com/majkeylab/scanit/DurableOutputReplacement.kt`
- Modify: `app/src/main/java/com/majkeylab/scanit/SettingsStore.kt`
- Modify: `app/src/main/java/com/majkeylab/scanit/ScanShare.kt`
- Modify: `app/src/test/java/com/majkeylab/scanit/RecentScanTest.kt`
- Modify: `app/src/test/java/com/majkeylab/scanit/DurableOutputDeleteTest.kt`

**Interfaces:**
- Produces: `replacePdfOutput(cached, treeUri): OutputReplacementResult`.
- Produces: `replaceImageOutputs(cached, options): OutputReplacementResult`.
- Produces: `reconcileRetiredOutputs(cached): OutputReplacementResult`.

- [ ] **Step 1: Write RED transaction tests**

Test PDF and images for unsaved save, saved replacement, MediaStore -> SAF, SAF -> SAF, unchanged destination idempotence, partial image creation rollback, metadata-write failure, old-delete failure retained in journal, retry success, missing old output, stale `cacheId + entryId`, process-recovery reconciliation, cancellation, and provider identity mismatch.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat --no-daemon :app:testInternalDebugUnitTest --tests com.majkeylab.scanit.RecentScanTest --tests com.majkeylab.scanit.DurableOutputDeleteTest
```

- [ ] **Step 3: Implement verified SAF image creation**

Create files only beneath a persisted writable tree. Validate canonical returned document URI, exact tree/authority/child relationship, returned document ID, MIME, provider display name, byte count, SHA-256, and decoded dimensions. Keep all provider calls behind existing storage locks and `Dispatchers.IO` callers.

- [ ] **Step 4: Implement replacement commit**

```kotlin
internal data class OutputReplacementResult(
    val scan: SavedScan,
    val warnings: List<UiMessage>,
)
```

Keep orchestration in the focused `DurableOutputReplacement.kt`, not the existing 2,600-line storage class. Stage and verify every new output, persist staged refs before MediaStore publication, reopen and verify after publication, then atomically write v3 metadata with new current refs plus old exact refs in retired lists. Run `ExactOutputDeleter`; remove only `Deleted`/`Absent` retired refs. Keep failed refs journaled and return a visible warning. Explicit Change location never falls back to Downloads/Gallery.

- [ ] **Step 5: Extend startup/open reconciliation**

Before exposing mutation controls, roll back bounded staged refs and retry retired-output cleanup. Generalize persisted-tree grant inventory to PDF and image trees. A corrupt/oversized v3 sidecar fails closed: core cache can open, but move/replace/delete actions stay unavailable. Sharing uses the active output MIME (`image/jpeg`, `image/png`, or `image/*` only for a mixed set).

- [ ] **Step 6: Run GREEN + nearby regression suites**

```powershell
.\gradlew.bat --no-daemon :app:testInternalDebugUnitTest --tests com.majkeylab.scanit.RecentScanTest --tests com.majkeylab.scanit.DurableOutputDeleteTest --tests com.majkeylab.scanit.OutputMetadataTest --tests com.majkeylab.scanit.OutputSavePolicyTest
```

- [ ] **Step 7: Commit**

```text
feat: replace saved outputs safely
```

### Task 4: ViewModel and Android picker orchestration

**Files:**
- Modify: `app/src/main/java/com/majkeylab/scanit/ScanModels.kt`
- Modify: `app/src/main/java/com/majkeylab/scanit/ScanViewModel.kt`
- Modify: `app/src/main/java/com/majkeylab/scanit/MainActivity.kt`
- Modify: `app/src/test/java/com/majkeylab/scanit/PureLogicTest.kt`

**Interfaces:**
- Produces generation-bound `OutputChangeRequest(cacheId, entryId, kind, generation)`.
- Exposes one-shot PDF/image tree picker requests and completion callbacks.
- Publishes refreshed `SavedScan` only for the exact current generation.

- [ ] **Step 1: Write RED state tests**

Cover double tap, Back while active, rotation, stale picker callback, reused cache ID/different entry ID, process death before/after picker, replacement failure, exact success refresh, and File details busy-state gating.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat --no-daemon :app:testInternalDebugUnitTest --tests com.majkeylab.scanit.PureLogicTest
```

- [ ] **Step 3: Implement generation-bound operations**

Use existing route/output gates. Keep picker request state in `SavedStateHandle`; claim it once in `MainActivity`. Take persistable read/write grants only for a validated returned tree. All storage work runs on `Dispatchers.IO`; UI publication is exact-identity checked.

- [ ] **Step 4: Run GREEN**

Run the focused test and `:app:compileInternalDebugKotlin`.

- [ ] **Step 5: Commit**

```text
feat: orchestrate result output changes
```

### Task 5: File details controls and fullscreen preview

**Files:**
- Modify: `app/src/main/java/com/majkeylab/scanit/AppUi.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-cs/strings.xml`
- Modify: `app/src/main/res/values-de/strings.xml`
- Modify: `app/src/main/res/values-es/strings.xml`
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`
- Modify: `app/src/test/java/com/majkeylab/scanit/PureLogicTest.kt`

**Interfaces:**
- Consumes Task 4 callbacks and busy states.
- Adds no storage/provider work inside composables.

- [ ] **Step 1: Write RED UI-policy tests**

Cover available controls for unsaved/PDF-only/images-only/both/legacy-invalid states, custom dimension validation, dialog Back consumption, and fullscreen selected-page clamping.

- [ ] **Step 2: Implement File details UX**

Add `Spacer(Modifier.height(12.dp))` between expanded header and the first card. Use compact icon/text actions, not a new row of large primary buttons. Show actual format, dimensions, total bytes, status, and location. Dialogs expose exact options from Global Constraints and disable confirm for invalid custom values.

- [ ] **Step 3: Implement fullscreen viewer**

Make only the Result preview clickable. Open a fullscreen Compose `Dialog` with a display-bounded bitmap, transformable zoom/pan, double-tap reset, selected page label, close button, and multipage strip. Decode on IO and cancel on close/page change.

- [ ] **Step 4: Accessibility/i18n review**

Every icon-only control gets localized semantics; touch targets remain at least 48 dp; font scale 1.3 stacks controls; Czech keeps diacritics; all five locales have identical key sets.

- [ ] **Step 5: Run GREEN**

```powershell
.\gradlew.bat --no-daemon :app:testInternalDebugUnitTest --tests com.majkeylab.scanit.PureLogicTest :app:lintInternalDebug :app:assembleInternalDebug
```

- [ ] **Step 6: Commit**

```text
feat: complete result file controls
```

### Task 6: Honest stable Actions and security hardening

**Files:**
- Modify: `app/src/main/java/com/majkeylab/scanit/AppUi.kt`
- Modify: `app/src/main/java/com/majkeylab/scanit/DocumentActions.kt`
- Modify: `app/src/main/java/com/majkeylab/scanit/ScanModels.kt`
- Modify: `app/src/main/java/com/majkeylab/scanit/ScanViewModel.kt`
- Modify: `app/src/test/java/com/majkeylab/scanit/DocumentActionsTest.kt`
- Create: `.github/workflows/codeql.yml`
- Modify: `SECURITY.md`

**Interfaces:**
- Keeps `ExtractText` and `DetectCodes` as stable on-device actions.
- Adds text export through `ACTION_CREATE_DOCUMENT` without persisting extracted text.
- Keeps barcode payloads inert unless a typed value is validated.

- [ ] **Step 1: Write RED security/action tests**

Cover OCR 200,000-character bound, barcode 64-item bound, empty results, stale request cancellation, export filename sanitization, sensitive clipboard flag, non-UTF-8/raw barcode refusal, and no automatic external intent for untyped payloads.

- [ ] **Step 2: Make clipboard and copy contract safe**

Set `ClipDescription.EXTRA_IS_SENSITIVE = true` before placing OCR/barcode content on the clipboard. Label OCR accurately as Latin-script text in all locales.

- [ ] **Step 3: Add explicit text export**

Use Android's document creation contract with `text/plain`, a sanitized bounded filename, and exact generation/identity state. Write only after user-selected destination; no text is retained after the action closes.

- [ ] **Step 4: Keep Actions honest**

Do not add Safe Share, receipt, business-card image classification, forms, whiteboard semantics, or MusicXML/MIDI placeholders. Keep current QR/barcode output as readable inert text unless typed payload preservation is implemented and tested in this task.

- [ ] **Step 5: Add supply-chain/security automation**

Keep existing Dependabot. Generate Gradle verification metadata for resolved release dependencies and review it for local/file artifacts before committing. Add SHA-pinned GitHub CodeQL for Java/Kotlin only if a trial workflow successfully extracts Kotlin 2.4.10; otherwise record the incompatibility and use an equivalent current scanner rather than committing a permanently failing security workflow.

- [ ] **Step 6: Run security gates**

Run focused tests, lint, dependency verification, secret scan over tracked files, merged-manifest permission checks, and current OSV/GitHub advisory checks. Record exact tool/version/output; do not claim checks that could not run.

- [ ] **Step 7: Commit**

```text
fix: harden document actions and dependencies
```

### Task 7: Release metadata, docs, and signed artifacts

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `tools/verify-release.ps1`
- Modify: `README.md`
- Modify: `CHANGELOG.md`
- Modify: `PRIVACY.md` only if behavior/data flow changed
- Modify: `THIRD_PARTY_NOTICES.md` only if dependencies changed
- Modify: `docs/play-store/PLAY_CONSOLE.md`

**Interfaces:**
- Produces signed Play AAB and GitHub APK/AAB for version code 14 / version 1.3.0.

- [ ] **Step 1: Update exact release constants/copy**

Set Gradle and verifier to code 14/name 1.3.0. Document only shipped behavior. Keep advanced Scanner v2/monetization claims out of public copy.

- [ ] **Step 2: Run the complete signed release gate**

```powershell
.\tools\build.ps1
```

Expected: tests + internal lint/APK + Play lint/AAB + GitHub lint/APK/AAB all pass and the verifier confirms package/version/SDK/signature/R8/no public Internet/no Gemini.

- [ ] **Step 3: Inspect artifacts**

Record SHA-256, size, signing certificate, merged permissions, mapping presence, ZIP path safety, and absence of Ads/Billing/Gemini/public Internet. Render and parse one generated PDF and decode each image format.

- [ ] **Step 4: Commit**

```text
chore: prepare ScanIt 1.3.0
```

### Task 8: Emulator, phone, GitHub, and Play publication

**Files:**
- Create release evidence only under ignored `.reference/` paths.
- Update tracked screenshots/listing assets only when final UI capture differs materially and every asset passes Play dimensions/format checks.

**Interfaces:**
- Consumes exact Task 7 signed artifacts.

- [ ] **Step 1: Emulator QA on API 33 and API 36**

Test one-page and multipage scan/import, PDF every target including Custom, PDF destination change, images Original/JPG/PNG at every preset including custom, Gallery/SAF moves, fullscreen zoom/page changes, OCR/text export, barcode, signature/stamp, share/print/delete-after-share, rotation, process death, low-memory/oversized negative paths, and update-preserved settings.

- [ ] **Step 2: Physical Samsung QA**

Install only the verified internal/test package first. Confirm Google scanner module/camera path, then repeat the highest-risk output replacement and share/delete flows. Preserve the stable package until release artifacts are final.

- [ ] **Step 3: Final hostile review**

Review the complete branch diff for correctness, crash consistency, URI/provider races, security, bloat, i18n, accessibility, and release-claim accuracy. Fix all Critical/Important findings and rerun affected plus full gates.

- [ ] **Step 4: Merge and push through protected-main workflow**

Push the feature branch, open a PR, wait for required CI, merge only when green, and verify `origin/main` contains the exact reviewed commits.

- [ ] **Step 5: Publish GitHub release**

Create tag `v1.3.0`, upload the verified signed GitHub APK plus source-generated artifacts required by repository policy, publish concise release notes, and verify the live release asset hashes.

- [ ] **Step 6: Upload and submit Google Play**

Use the exact verified Play AAB. Upload to the existing closed Alpha track, add accurate release notes/localizations, inspect App Bundle Explorer/pre-launch warnings, submit for review, and verify the Console status. Do not alter Data Safety or permissions declarations unless the audited artifact requires it.

- [ ] **Step 7: Record completion**

Store final commit, CI links, GitHub release URL, AAB/APK hashes, Play release/version/status, emulator/device matrix, and every known limitation.
