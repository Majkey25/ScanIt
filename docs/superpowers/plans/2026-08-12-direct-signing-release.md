# Direct Signing Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add direct drag placement to the existing visual signature/stamp editor and ship verified release `1.2.0-beta.2`.

**Architecture:** Keep `MarkPlacement` as the single editor/rendering source of truth. Add one pure geometry helper in `MarkModels.kt`, call it from a hit-tested Compose drag gesture, and leave the existing storage/render/apply transaction unchanged. Release changes update the existing version/verifier/docs pipeline only.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Android Bitmap/PDF pipeline, JUnit 4, Gradle, PowerShell release verifier, GitHub CLI.

## Global Constraints

- Visual signature only; never claim certificate-backed, cryptographic, eIDAS, or identity-verified signing.
- Keep English and Czech with UTF-8 diacritics.
- No new dependency, permission, navigation layer, cloud service, or account.
- Version code `6`; version/tag `1.2.0-beta.2` / `v1.2.0-beta.2`.
- Final installation targets `com.majkeylab.scanit.internal`; never overwrite the stable public package during QA.

---

### Task 1: Pure drag geometry

**Files:**
- Modify: `app/src/main/java/com/majkeylab/scanit/MarkModels.kt`
- Test: `app/src/test/java/com/majkeylab/scanit/MarkLogicTest.kt`

**Interfaces:**
- Consumes: existing `MarkPlacement`, `resolveMarkRect(...)`.
- Produces: `dragMarkPlacement(pageWidth, pageHeight, markWidth, markHeight, placement, deltaX, deltaY): MarkPlacement`.

- [ ] **Step 1: Write failing tests**

Add tests proving a normal drag changes normalized centers, a drag clamps the rendered mark to all page edges without a dead zone, and non-finite/invalid geometry is rejected.

```kotlin
val moved = dragMarkPlacement(1_000f, 2_000f, 400, 200, MarkPlacement(), 100f, -200f)
assertEquals(0.6f, moved.centerX, 0.0001f)
assertEquals(0.65f, moved.centerY, 0.0001f)
```

- [ ] **Step 2: Run RED**

Run:

```powershell
.\gradlew.bat :app:testInternalDebugUnitTest --tests com.majkeylab.scanit.MarkLogicTest --no-daemon --console=plain
```

Expected: Kotlin test compilation fails only because `dragMarkPlacement` does not exist.

- [ ] **Step 3: Add minimal helper**

Use `resolveMarkRect` to obtain the actual on-page rectangle, move its center by the pixel delta, clamp that center by its half width/height, normalize, and return `placement.copy(centerX = ..., centerY = ...)`.

```kotlin
internal fun dragMarkPlacement(
    pageWidth: Float,
    pageHeight: Float,
    markWidth: Int,
    markHeight: Int,
    placement: MarkPlacement,
    deltaX: Float,
    deltaY: Float,
): MarkPlacement {
    require(deltaX.isFinite() && deltaY.isFinite()) { "Mark drag must be finite" }
    val rect = resolveMarkRect(pageWidth, pageHeight, markWidth, markHeight, placement)
    val centerX = ((rect.left + rect.right) / 2f + deltaX).coerceIn(rect.width / 2f, pageWidth - rect.width / 2f)
    val centerY = ((rect.top + rect.bottom) / 2f + deltaY).coerceIn(rect.height / 2f, pageHeight - rect.height / 2f)
    return placement.copy(centerX = centerX / pageWidth, centerY = centerY / pageHeight)
}
```

- [ ] **Step 4: Run GREEN**

Run the focused command from Step 2. Expected: all `MarkLogicTest` tests pass.

- [ ] **Step 5: Review diff**

Run `git diff --check` and confirm no duplicate geometry logic or UI dependency entered `MarkModels.kt`.

### Task 2: Direct placement UI and copy

**Files:**
- Modify: `app/src/main/java/com/majkeylab/scanit/VisualMarkUi.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-cs/strings.xml`
- Test: `app/src/test/java/com/majkeylab/scanit/MarkLogicTest.kt`

**Interfaces:**
- Consumes: `dragMarkPlacement(...)`, existing `onPlacementChange` callback.
- Produces: preview drag behavior; no new ViewModel state.

- [ ] **Step 1: Wire editor state into preview**

Change the preview call to pass `enabled = selectedBitmap != null && !editor.busy` and `onPlacementChange`.

- [ ] **Step 2: Add hit-tested gesture**

Track canvas size and use `detectDragGestures`. At drag start, compute the page rectangle and mark rectangle exactly as preview drawing does. Set an in-gesture Boolean only when the pointer is inside the displayed mark. Consume/move only active mark drags; use `dragMarkPlacement(...)` and the latest placement/callback state.

- [ ] **Step 3: Add concise guidance and signing copy**

Add:

```xml
<string name="drag_visual_mark_hint">Drag the signature to place it.</string>
<string name="add_signature_or_stamp">Sign or stamp document</string>
```

and Czech:

```xml
<string name="drag_visual_mark_hint">Přetažením podpis umístěte.</string>
<string name="add_signature_or_stamp">Podepsat nebo orazítkovat dokument</string>
```

Render the hint below the preview only when a mark is selected. Preserve the disclaimer and the three accessible sliders.

- [ ] **Step 4: Compile and run focused tests**

```powershell
.\gradlew.bat :app:testInternalDebugUnitTest --tests com.majkeylab.scanit.MarkLogicTest --tests com.majkeylab.scanit.VisualMarkApplyTest :app:lintInternalDebug --no-daemon --console=plain
```

Expected: focused tests and lint pass.

- [ ] **Step 5: Hostile self-review**

Check pointer cancellation, scroll outside the mark, stale placement capture, busy-state disabling, page/mark null handling, TalkBack slider fallback, and preview/final placement agreement.

### Task 3: Release metadata `1.2.0-beta.2`

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `tools/verify-release.ps1`
- Modify: `CHANGELOG.md`
- Modify: `README.md`
- Modify: `docs/play-store/PLAY_CONSOLE.md`
- Modify: `docs/third-party-notices.txt`
- Modify: `app/src/main/assets/legal/THIRD_PARTY_NOTICES.md`

**Interfaces:**
- Consumes: repository release gate and current artifact matrix.
- Produces: version code `6`, version `1.2.0-beta.2`, matching verifier/docs.

- [ ] **Step 1: Update build and verifier**

Set `versionCode = 6`, `versionName = "1.2.0-beta.2"`, and update exact verifier expectations for internal/play/github variants.

- [ ] **Step 2: Update release copy**

Add a changelog section dated `2026-08-12` for direct signature placement and the already-shipped safe visual signing flow. Update current-version references in README, Play worksheet, and both identical third-party notice copies. Do not add certificate-signing claims.

- [ ] **Step 3: Verify notice parity and diff**

```powershell
Get-FileHash docs\third-party-notices.txt, app\src\main\assets\legal\THIRD_PARTY_NOTICES.md -Algorithm SHA256
git diff --check
```

Expected: both notice hashes match; diff check passes.

### Task 4: Full gate and live QA

**Files:**
- No production file additions.
- Temporary QA artifacts only under `.reference/tmp`, removed before commit.

**Interfaces:**
- Consumes: final source and local ignored signing configuration.
- Produces: verified internal APK, signed Play AAB, signed GitHub APK/AAB.

- [ ] **Step 1: Run full release gate**

```powershell
.\tools\build.ps1
```

Expected: clean internal tests/lint/build, Play/GitHub lint/build, and all four artifact verifications pass.

- [ ] **Step 2: Install internal APK**

```powershell
adb -s <physical-device-serial> install -r app\build\outputs\apk\internal\debug\app-internal-debug.apk
adb -s <physical-device-serial> shell am start -n com.majkeylab.scanit.internal/com.majkeylab.scanit.MainActivity
```

Use the physical serial returned by `adb devices -l`. Never substitute the emulator and call it a phone.

- [ ] **Step 3: Exercise live scenarios**

Verify: draw/sign/drag/apply happy path; clamp at an edge; cancel/back negative path; Recent open/share nearby regression; English/Czech copy; no new `FATAL EXCEPTION`, ANR, or OOM. If the phone is unavailable, run the same bounded checks on `emulator-5554` and retain the phone install as an explicit blocker.

- [ ] **Step 4: Record hashes**

Hash internal APK, Play AAB, GitHub APK, GitHub AAB, and `mapping.txt` with SHA-256 for release evidence.

### Task 5: Merge and publish

**Files:**
- Create: GitHub PR/release records only.

**Interfaces:**
- Consumes: green local gate, artifact hashes, protected-branch workflow.
- Produces: merged `main`, tag/release `v1.2.0-beta.2`, downloadable artifacts.

- [ ] **Step 1: Commit minimal code/release changes**

Stage exact files, run staged `git diff --check`, and commit with Conventional Commits.

- [ ] **Step 2: Push branch and open PR**

Push `feat/direct-signing-release/12-08-2026`, open a PR to `main`, and include exact test/build evidence.

- [ ] **Step 3: Wait for required checks and merge**

Do not bypass protection. Merge only after `Test, lint, and build` succeeds. Pull/verify resulting `main` SHA.

- [ ] **Step 4: Rebuild from merged main**

Run `tools/build.ps1` again from exact merged `main`. This post-merge build is release authority.

- [ ] **Step 5: Publish prerelease**

Create annotated tag `v1.2.0-beta.2`, push it, and create a GitHub prerelease with verified GitHub APK/AAB, Play AAB, `mapping.txt`, checksums, and concise notes. Confirm the release page/tag/assets through `gh release view`.

- [ ] **Step 6: Install exact post-merge internal build**

Install the post-merge internal APK on the physical phone and confirm package/version through `dumpsys package`.

## Self-review

- Spec coverage: direct drag, hit test, accessible fallback, legal boundary, release metadata, signed artifacts, protected merge, GitHub release, and physical install all have tasks.
- Placeholder scan: no TBD/TODO/generic error-handling steps.
- Type consistency: Task 2 consumes the exact helper signature from Task 1; release version/code match every task.
