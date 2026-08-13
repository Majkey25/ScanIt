# Public Support Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a policy-safe optional Buy Me a Coffee button to public ScanIt Settings and publish verified `v1.2.1` artifacts.

**Architecture:** Keep the external support link in the existing Settings footer. Add localized resources and no new runtime dependency, permission, state, or abstraction.

**Tech Stack:** Kotlin, Jetpack Compose, Android resources, Gradle release variants, existing PowerShell artifact verifier.

---

### Task 1: Localized support link

**Files:**
- Modify: `app/src/main/java/com/majkeylab/scanit/AppUi.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-cs/strings.xml`
- Modify: `app/src/main/res/values-de/strings.xml`
- Modify: `app/src/main/res/values-es/strings.xml`
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`
- Test: `app/src/test/java/com/majkeylab/scanit/PureLogicTest.kt`

- [ ] Add a failing test requiring the exact HTTPS support URL and no-entitlement explanatory copy.
- [ ] Run `./gradlew.bat :app:testInternalDebugUnitTest --tests com.majkeylab.scanit.PureLogicTest --no-daemon --console=plain` and verify the new assertion fails because the support contract does not exist.
- [ ] Add `SUPPORT_URL`, a full-width Settings `TextButton`, and localized button/hint strings. The hint must say that support is optional and unlocks nothing.
- [ ] Re-run the focused test and verify it passes.

### Task 2: Public release verification

**Files:**
- Verify: `app/build.gradle.kts`
- Verify: `app/src/play/AndroidManifest.xml`
- Verify: `app/src/github/AndroidManifest.xml`

- [ ] Run `./gradlew.bat clean :app:testInternalDebugUnitTest :app:lintInternalDebug :app:assembleInternalDebug :app:bundlePlayRelease :app:assembleGithubRelease :app:bundleGithubRelease --no-daemon --console=plain`.
- [ ] Run `./tools/verify-release.ps1 play ./app/build/outputs/bundle/playRelease/app-play-release.aab`.
- [ ] Run `./tools/verify-release.ps1 github ./app/build/outputs/apk/github/release/app-github-release.apk` and verify the GitHub AAB too.
- [ ] Install only the internal APK on the emulator, open Settings, activate Support ScanIt, and verify the browser receives the expected Buy Me a Coffee HTTPS URL.
- [ ] Run `git diff --check` and inspect the complete diff.
- [ ] Commit with `feat: add optional ScanIt support link`, push the feature branch, open a PR into protected `main`, wait for required checks, merge, then create the signed `v1.2.1` GitHub release only from merged `main` artifacts.

