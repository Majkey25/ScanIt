# Visual marks implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` and `superpowers:test-driven-development`.

**Goal:** Add private reusable visual signature/stamp templates and apply one mark to one selected page.

**Architecture:** Native Android only: Compose Canvas, ImageDecoder, Bitmap/Canvas, and the existing ML Kit scanner. Templates live in `noBackupFilesDir/marks`; applying one creates a verified derived scan and never mutates the source.

**Tech stack:** Kotlin, Compose, ImageDecoder, Canvas, PdfDocument, coroutines.

---

## Task 1: Define mark geometry and names

**Files:**

- Create: `app/src/main/java/com/majkeylab/scanit/MarkModels.kt`
- Create: `app/src/test/java/com/majkeylab/scanit/MarkLogicTest.kt`

- [ ] Add failing tests for finite/clamped placement, aspect preservation, all edges, collision-safe signed names, visible bounds, and alpha conversion.
- [ ] Add `MarkPoint`, `MarkStroke`, `MarkPlacement`, `MarkRect`, and `PixelBounds`.
- [ ] Add one `resolveMarkRect(...)` used by preview and final render.
- [ ] Add `signedBaseName(source, existingNames)` -> `_Signed`, `_Signed_2`, etc.
- [ ] Run focused tests.
- [ ] Commit `feat: define visual mark rules`.

## Task 2: Store normalized private templates

**Files:**

- Create: `app/src/main/java/com/majkeylab/scanit/MarkStorage.kt`
- Modify: `app/src/test/java/com/majkeylab/scanit/MarkLogicTest.kt`

- [ ] Add constants: 12 templates, 20,000,000 input bytes, 2048 decode side, 1024 output side.
- [ ] Implement list/prepare/save drawing/load/delete under `noBackupFilesDir/marks`.
- [ ] Validate file name + canonical parent every time; keep FileProvider unchanged.
- [ ] Bounded-copy imported/scanned URI, decode software bitmap with EXIF applied, reject partial/malformed/blank/dust-only input.
- [ ] Fade near-white pixels to transparent while preserving dark/colored ink; crop visible bounds; downscale to 1024.
- [ ] Render drawings on transparent 1024x512, including single-point dots.
- [ ] Write temp + fsync + verify decode + atomic same-directory move; clean failures.
- [ ] Test traversal, overflow, white/colored input, 12/13 limit, deterministic order.
- [ ] Commit `feat: store private visual marks`.

## Task 3: Add creation/review/editor UI

**Files:**

- Create: `app/src/main/java/com/majkeylab/scanit/MarkUi.kt`
- Modify: `ScanModels.kt`, `ScanViewModel.kt`, `MainActivity.kt`, `AppUi.kt`
- Modify: English/Czech `strings.xml`

- [ ] Add editor/drawing/review screen states and validated SavedStateHandle restoration.
- [ ] Result button `Sign / stamp`; templates appear as a small newest-first strip.
- [ ] Creation actions: Draw, Import image, Scan mark.
- [ ] Drawing: Undo/Clear/Cancel/Save; no-stroke Save disabled.
- [ ] Import/scan review: Crop and Transparent previews + explicit Use template.
- [ ] Editor: choose template/page + horizontal/vertical/size sliders + one Apply button.
- [ ] Show: visual mark only; not certificate-backed/digital signing.
- [ ] Use `OpenDocument("image/*")`, copy immediately, no persistent URI grant.
- [ ] Add separate ML Kit one-page/JPEG/no-gallery launcher; cancel leaves editor unchanged.
- [ ] File/bitmap work stays on `Dispatchers.IO`.
- [ ] Commit `feat: add visual mark editor`.

## Task 4: Create atomic signed scans

**Files:**

- Modify: `app/src/main/java/com/majkeylab/scanit/ScanStorage.kt`
- Modify: `app/src/main/java/com/majkeylab/scanit/ScanViewModel.kt`
- Modify: `app/src/test/java/com/majkeylab/scanit/MarkLogicTest.kt`

- [ ] Add `createMarkedScan(source, pageIndex, mark, placement, target, protectedIds)`.
- [ ] Validate source/page/paths, then build in hidden `.mark-work-*` directory.
- [ ] Copy untouched pages byte-for-byte; render only selected page using `resolveMarkRect`.
- [ ] Call shared `buildPdfForTarget(..., edited = true)` once after overlay.
- [ ] Verify pages + PDF, then atomically publish the whole folder.
- [ ] Auto-save derived PDF/images according to settings. Original durable files stay untouched.
- [ ] Any failure rolls back derived cache/durable outputs and restores the source result.
- [ ] On success open derived result so Share/Print use it.
- [ ] Commit `feat: apply marks to derived scans`.

## Marks gate

Run unit/lint/build, then device scenarios: drawing persistence; EXIF rotated colored stamp; blank/corrupt/oversized rejection; 12-template bound; page-2 apply; edge placement; all size targets; process recreation; forced save failure; ordinary scan/share/print regression.
