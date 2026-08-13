# Settings Persistence and Visual Mark Gestures Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist every Settings change immediately and add direct move, resize, and rotation gestures to visual marks.

**Architecture:** Settings keeps a local Compose draft for responsive controls, but every mutation sends the complete draft through the existing `ScanViewModel.saveSettings` authority boundary. Visual mark gestures update the existing `MarkPlacement` state; preview and final JPEG rendering consume the same validated rotation-aware geometry.

**Tech Stack:** Kotlin, Jetpack Compose, Android Canvas, SharedPreferences, JUnit 4, Gradle.

## Global Constraints

- Existing explicit settings must survive app updates.
- Fresh installs default to delete saved PDF after sharing ON and images OFF.
- No new runtime dependency.
- Keep all sliders collapsed under `Manual position`.
- Ads and premium are excluded from this stable release.

---

### Task 1: Automatic Settings Persistence

**Files:**
- Modify: `app/src/main/java/com/majkeylab/scanit/ScanModels.kt`
- Modify: `app/src/main/java/com/majkeylab/scanit/AppUi.kt`
- Test: `app/src/test/java/com/majkeylab/scanit/PureLogicTest.kt`

**Interfaces:**
- Consumes: `SettingsStore.trySave(AppSettings, ActiveResultOwner)` through `ScanViewModel.saveSettings`.
- Produces: `AppSettings.deletePdfAfterShare = true` default and immediate full-draft saves.

- [ ] **Step 1: Write failing default and recreation tests**

Assert `AppSettings().deletePdfAfterShare` is true, images is false, malformed preferences use those defaults, and explicit false/true values survive a new `SettingsStore` instance.

- [ ] **Step 2: Run RED**

Run: `.\gradlew.bat :app:testInternalDebugUnitTest --tests com.majkeylab.scanit.PureLogicTest --no-daemon --console=plain`

Expected: the fresh and malformed PDF default assertions fail against the old false default.

- [ ] **Step 3: Implement immediate persistence**

Set only the model default:

```kotlin
val deletePdfAfterShare: Boolean = true
val deleteImagesAfterShare: Boolean = false
```

In `SettingsScreen`, build the current `AppSettings` draft in one local `persistSettings()` function. Every switch, text field, PDF target, and custom target mutation updates local state and then calls it. Keep folder and language persistence on their existing dedicated boundaries. Remove `Save settings` and `Cancel`.

- [ ] **Step 4: Run GREEN**

Run the focused test and `:app:compileInternalDebugKotlin`.

### Task 2: Rotation-Aware Mark Geometry

**Files:**
- Modify: `app/src/main/java/com/majkeylab/scanit/MarkModels.kt`
- Test: `app/src/test/java/com/majkeylab/scanit/MarkLogicTest.kt`

**Interfaces:**
- Produces: `MarkPlacement.rotationDegrees: Float` and `transformMarkPlacement(...): MarkPlacement`.

- [ ] **Step 1: Write failing geometry tests**

Cover a 2x zoom, minimum/maximum zoom clamp, pan, +45 degree rotation, wrap past 180 degrees, a rotated edge placement, and rejection of NaN/zero zoom.

- [ ] **Step 2: Run RED**

Run: `.\gradlew.bat :app:testInternalDebugUnitTest --tests com.majkeylab.scanit.MarkLogicTest --no-daemon --console=plain`

Expected: compilation fails because rotation and transform APIs do not exist.

- [ ] **Step 3: Implement validated geometry**

Add a finite normalized rotation to `MarkPlacement`. Compute the rotated bounding width/height from sine/cosine, clamp its center inside the page, and implement:

```kotlin
internal fun transformMarkPlacement(
    pageWidth: Float,
    pageHeight: Float,
    markWidth: Int,
    markHeight: Int,
    placement: MarkPlacement,
    panX: Float,
    panY: Float,
    zoom: Float,
    rotationDegrees: Float,
): MarkPlacement
```

Clamp width to `MIN_MARK_WIDTH_FRACTION..MAX_MARK_WIDTH_FRACTION`, normalize rotation, then apply the pan using the new rotated geometry.

- [ ] **Step 4: Run GREEN**

Run the focused MarkLogic test.

### Task 3: Direct Gestures and Collapsed Manual Controls

**Files:**
- Modify: `app/src/main/java/com/majkeylab/scanit/VisualMarkUi.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-cs/strings.xml`
- Modify: `app/src/main/res/values-de/strings.xml`
- Modify: `app/src/main/res/values-es/strings.xml`
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`

**Interfaces:**
- Consumes: `transformMarkPlacement` and `onPlacementChange`.
- Produces: direct pan/pinch/rotation input and a collapsed precise-control section.

- [ ] **Step 1: Replace drag-only input**

Use Compose `detectTransformGestures` on the enlarged preview. Send pan, zoom, and rotation through `transformMarkPlacement`. Disable input while busy or without a selected mark.

- [ ] **Step 2: Render preview rotation**

Save the native canvas, rotate around the resolved mark rectangle center, draw the bitmap, and restore the canvas.

- [ ] **Step 3: Collapse precise controls**

Add a `rememberSaveable` boolean and full-width `Manual position` button with `ic_expand_more`. Show X, Y, size, and rotation sliders only when expanded.

- [ ] **Step 4: Localize copy**

Add localized `manual_position`, `visual_mark_rotation`, and move/pinch/rotate hint strings for EN/CS/DE/ES/ZH.

### Task 4: Final Output Rotation

**Files:**
- Modify: `app/src/main/java/com/majkeylab/scanit/VisualMarkRendering.kt`

**Interfaces:**
- Consumes: `MarkPlacement.rotationDegrees`.
- Produces: a JPEG whose mark transform matches the preview.

- [ ] **Step 1: Apply rotation during render**

Rotate Android `Canvas` around `RectF.centerX()/centerY()`, draw the mark, and restore inside the existing `transformBitmap` callback.

- [ ] **Step 2: Run full host gate**

Run: `.\gradlew.bat clean :app:testInternalDebugUnitTest :app:lintInternalDebug :app:assembleInternalDebug --no-daemon --console=plain`

Expected: BUILD SUCCESSFUL, no test/lint errors.

### Task 5: Emulator, Release, and Play Alpha

**Files:**
- Modify: `app/build.gradle.kts`
- Modify release notes/version files already used by the repository.

- [ ] **Step 1: Upgrade QA**

Install the prior internal APK, set explicit delete choices, then update without uninstalling. Verify the explicit values remain. Clear app data and verify fresh PDF ON/images OFF. Toggle both, Back, reopen Settings, scan/cancel, force-stop/relaunch, and verify values each time.

- [ ] **Step 2: Gesture QA**

Create/select a visual mark; drag, pinch smaller/larger, rotate, use `Manual position`, Apply, reopen the derived result, and inspect the generated page. Verify busy/Back and no crash buffer entries.

- [ ] **Step 3: Release verification**

Bump to version code 11/name 1.2.3, build signed Play AAB and GitHub artifacts using `tools/build.ps1`, run the repository artifact verifier, and record SHA-256 values.

- [ ] **Step 4: Publish**

Commit, push, merge only after green CI, create GitHub `v1.2.3`, upload the exact Play AAB to Closed testing Alpha, and submit the change for Google review. Do not install a separate phone package because the user will update through Play Early Access.
