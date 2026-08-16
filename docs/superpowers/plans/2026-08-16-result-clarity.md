# Result Clarity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship ScanIt 1.3.1 with an obvious Result action hierarchy, swipeable page preview, readable File details controls, and no misleading local Edit entry.

**Architecture:** Keep all scan/output authority unchanged. Add small pure UI policies in `PageBrowser.kt`, render them in the existing Compose `ResultScreen`, and route Rescan through the existing scanner callback. Preserve the recovery-only appearance flow but remove its public entry point.

**Tech Stack:** Kotlin 2.4.10, Jetpack Compose Material 3/Foundation, Android API 33-36, JUnit, Gradle 9.6.1.

## Global Constraints

- Google ML Kit remains the stable capture flow.
- Google ML Kit cannot reopen existing scan pages; stable UI says Rescan, not Edit.
- Current scan remains in Recent when Rescan starts or is cancelled.
- No storage, output replacement, sharing, signature, or Actions transaction changes.
- Ads/Premium and Scanner v2 remain unpublished separate work.
- Every visible control has a 48 dp minimum touch target and no ellipsized label.
- `1 of 1` remains visible.

---

### Task 1: Page and action layout policies

**Files:**
- Modify: `app/src/main/java/com/majkeylab/scanit/PageBrowser.kt`
- Modify: `app/src/test/java/com/majkeylab/scanit/PageBrowserTest.kt`

**Interfaces:**
- Produces: `resultPageStatus(currentIndex: Int, pageCount: Int): Pair<Int, Int>`.
- Produces: `stackResultActions(fontScale: Float, availableWidthDp: Int): Boolean`.
- Consumes: existing `resolvedPageIndex` bounds.

- [ ] **Step 1: Write failing tests**

Add literal assertions that `0/1 => 1 to 1`, `1/3 => 2 to 3`, invalid indices are clamped, normal 360 dp remains a row, and 320 dp at 1.3 font scale stacks.

- [ ] **Step 2: Verify RED**

Run:
`./gradlew :app:testInternalDebugUnitTest --tests com.majkeylab.scanit.PageBrowserTest`

Expected: test compilation fails only because the two policy functions do not exist.

- [ ] **Step 3: Implement minimal policies**

Return the one-based resolved page pair and a deterministic accessibility breakpoint. Do not add Compose types to the pure file.

- [ ] **Step 4: Verify GREEN**

Run the same focused command. Expected: all `PageBrowserTest` cases pass.

- [ ] **Step 5: Commit**

Commit message: `test: define result layout behavior`.

### Task 2: Swipeable Result preview and real action buttons

**Files:**
- Modify: `app/src/main/java/com/majkeylab/scanit/AppUi.kt`
- Modify: `app/src/main/java/com/majkeylab/scanit/MainActivity.kt`
- Modify: `app/src/main/java/com/majkeylab/scanit/ScanViewModel.kt`
- Modify: `app/src/main/java/com/majkeylab/scanit/ScanModels.kt`
- Modify: `app/src/test/java/com/majkeylab/scanit/PureLogicTest.kt`

**Interfaces:**
- Consumes: `resultPageStatus` and `stackResultActions` from Task 1.
- Produces: `ResultPrimaryAction.Rescan` as a pure route contract for the existing scanner callback.
- Consumes: existing `onScan`, `onSelectResultPage`, `ownedResultPreview`, and Result action gates.
- Removes: public `onOpenAppearanceEditor`, `openAppearanceEditor`, and `canEditAppearance` entry path.
- Preserves: recovery-only `appearanceReviewRequired`, close/apply, and checkpoint handling.

- [ ] **Step 1: Write failing behavior tests**

Change the existing appearance-entry test to prove no public appearance-edit gate remains and add a Rescan route policy assertion that maps the Result action to the existing scanner launch without mutating the current scan.

- [ ] **Step 2: Verify RED**

Run focused `PureLogicTest` + `PageBrowserTest`. Expected: missing Rescan route policy or obsolete Edit contract fails.

- [ ] **Step 3: Implement the main pager**

Use `HorizontalPager` with 12-24 dp horizontal content padding and 8 dp page spacing. Load bounded 1024 px previews through `ownedResultPreview`. Synchronize settled pager changes to `onSelectPage`; synchronize restored/external selected index back to the pager. Remove the duplicate main-screen thumbnail strip.

- [ ] **Step 4: Implement action hierarchy**

Render Rescan, Sign / stamp, and Actions as equal `OutlinedButton`s with icons and two-line labels. Stack them when `stackResultActions` is true. Route Rescan to existing `onScan`, remove the duplicate bottom New scan button, and keep Send PDF as the only filled primary action.

- [ ] **Step 5: Remove the misleading public Edit entry**

Remove only the public callback, ViewModel opener, gate, and obsolete test. Keep recovery-only appearance UI/state so interrupted older transactions remain recoverable.

- [ ] **Step 6: Verify GREEN**

Run focused tests plus `:app:compileInternalDebugKotlin`. Expected: pass with no obsolete Edit references.

- [ ] **Step 7: Commit**

Commit message: `fix: clarify result page actions`.

### Task 3: File details controls, copy, and PDF icon

**Files:**
- Modify: `app/src/main/java/com/majkeylab/scanit/AppUi.kt`
- Modify: `app/src/main/res/drawable/ic_pdf.xml`
- Delete: `app/src/main/res/drawable/ic_edit.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-cs/strings.xml`
- Modify: `app/src/main/res/values-de/strings.xml`
- Modify: `app/src/main/res/values-es/strings.xml`
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`
- Modify: `app/src/test/java/com/majkeylab/scanit/PureLogicTest.kt`

**Interfaces:**
- Consumes: unchanged `FileDetailControl` enablement.
- Produces: localized short page status and Rescan label.

- [ ] **Step 1: Add failing locale parity/page-copy tests**

Require all five locale key sets to include the new short page status and Rescan key and to remove the obsolete Edit label.

- [ ] **Step 2: Verify RED**

Run focused language and pure tests. Expected: missing keys fail.

- [ ] **Step 3: Replace File details text actions**

PDF uses two equal outlined buttons. Images uses Size and Format as equal outlined buttons, then a full-width Location outlined button. At stacked layout each control becomes full width. Keep all existing enablement predicates.

- [ ] **Step 4: Replace copy and icon**

Add localized `1 of N` copy and Rescan labels. Replace the PDF vector with a folded document outline and three horizontal rules; delete unused `ic_edit.xml`.

- [ ] **Step 5: Verify GREEN**

Run focused tests, compile, and lint. Expected: no missing/unused resource error and zero lint errors.

- [ ] **Step 6: Commit**

Commit message: `fix: make file controls unmistakable`.

### Task 4: Emulator, performance, release, and publication

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `CHANGELOG.md`
- Modify: `docs/play-store/PLAY_CONSOLE.md`

**Interfaces:**
- Produces: signed version code 15 / version name 1.3.1 candidates after QA.

- [ ] **Step 1: Run fresh host gates**

Run internal unit/lint/assemble first. Then run the repository signed release wrapper from a clean committed branch.

- [ ] **Step 2: Run emulator QA**

Verify one page, multiple pages, swipe/peek, restored selection, full-screen tap/back, Rescan cancellation preserving the original Recent entry, Sign/stamp, Actions, PDF/image File details buttons, 320 dp, font scale 1.3+, rotation, and process restart. Capture screenshots and UI trees.

- [ ] **Step 3: Run performance regression checks**

Compare startup, PSS after Result paging, rapid page swipes, and frame timing against the stored 1.3.0 evidence. Reject a proven app-owned regression; document emulator pressure separately.

- [ ] **Step 4: Prepare 1.3.1**

Only after QA passes, bump code/name, update changelog and Play release notes, build signed APK/AAB, verify package/version/signature/provenance/R8/permissions, and record hashes.

- [ ] **Step 5: Publish through protected workflow**

Push the feature branch, open PR, wait for required CI, merge without bypass, rebuild from clean final `main`, publish GitHub 1.3.1, then upload the exact Play AAB to Alpha when Play permits a new release. Verify the authoritative GitHub and Play states.

- [ ] **Step 6: Preserve separate follow-ups**

Rebase or merge the final stable changes into the existing unpublished ads/Premium beta only after stable merge. Start Scanner v2 in its isolated branch; do not publish either branch.
