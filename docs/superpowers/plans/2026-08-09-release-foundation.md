# Release foundation implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` and `superpowers:test-driven-development`.

**Goal:** Produce verifiable Play/GitHub public builds, remove cloud AI from them, and fix restore/save/share correctness.

**Architecture:** Keep one app core. Add `play`, `github`, and `internal` distributions. Public variants contain no Gemini code or app-owned network permission; the private internal debug variant retains a small isolated BYOK harness. Persist only validated cache identifiers and durable content URIs.

**Tech stack:** Kotlin, Compose, Gradle/AGP, SavedStateHandle, MediaStore/SAF, PowerShell, bundletool.

---

## Task 1: Define build distributions and release version

**Files:**

- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/internal/AndroidManifest.xml`

- [ ] Add failing archive assertions to the release verifier for package, version, `INTERNET`, and Gemini residue.
- [ ] Set `versionCode = 5`, `versionName = "1.2.0-beta.1"`.
- [ ] Add `play` (`com.majkeylab.scanit`), `github` (`.github` suffix), and `internal` (`.internal` suffix) distribution flavors.
- [ ] Enable only `playRelease`, `githubRelease`, and `internalDebug`.
- [ ] Port alpha.2 hardening: R8 minification/resource shrinking, optimized default ProGuard rules, and `ndk.debugSymbolLevel = "FULL"`.
- [ ] Remove app-owned `INTERNET` from main; add it only to internal.
- [ ] Run `./gradlew.bat :app:assembleInternalDebug :app:assembleGithubRelease :app:bundlePlayRelease`.
- [ ] Commit `feat: define public release distributions`.

## Task 2: Compile Gemini out of public artifacts

**Files:**

- Move: `app/src/main/java/com/majkeylab/scanit/GeminiClient.kt` -> `app/src/internal/java/com/majkeylab/scanit/GeminiClient.kt`
- Create: `app/src/internal/java/com/majkeylab/scanit/InternalGeminiActivity.kt`
- Create: `app/src/internal/java/com/majkeylab/scanit/InternalGeminiViewModel.kt`
- Create: `app/src/internal/java/com/majkeylab/scanit/InternalGeminiKeyStore.kt`
- Move Gemini resources/tests into internal source sets.
- Modify: `ScanModels.kt`, `SettingsStore.kt`, `ScanViewModel.kt`, `AppUi.kt`, `MainActivity.kt`

- [ ] First prove the current public bundle fails the no-AI/no-network assertion.
- [ ] Remove AI state, key storage, callbacks, screens, and strings from main.
- [ ] Keep a bounded private harness: select one image, enter encrypted BYOK key, process, preview.
- [ ] Do not duplicate the public scanner/ViewModel/UI.
- [ ] Run internal tests and build both public bundles.
- [ ] Inspect the public archives: no Gemini endpoint/class/string and no `android.permission.INTERNET`.
- [ ] Commit `refactor: isolate Gemini to internal debug`.

## Task 3: Restore an active result safely

**Files:**

- Modify: `app/src/main/java/com/majkeylab/scanit/ScanModels.kt`
- Modify: `app/src/main/java/com/majkeylab/scanit/ScanStorage.kt`
- Modify: `app/src/main/java/com/majkeylab/scanit/ScanViewModel.kt`
- Modify: `app/src/test/java/com/majkeylab/scanit/PureLogicTest.kt`

- [ ] Add failing tests for descriptor round-trip, traversal IDs, missing files, and non-contiguous pages.
- [ ] Add `ActiveResultDescriptor(cacheId, savedPdfUri, galleryPageUris)` + a small `SavedStateHandle` store.
- [ ] Add `ScanStorage.openCachedScan(cacheId)` with canonical-parent, safe-name, non-empty PDF, and contiguous-page validation.
- [ ] Write the descriptor only after a complete result. Restore cache + readable `content://` URIs + thumbnail.
- [ ] Missing/evicted data -> clear descriptor and open Recent scans with one message.
- [ ] Run focused unit tests and a force-stop/reopen phone scenario.
- [ ] Commit `fix: restore active scan after process recreation`.

## Task 4: Verify every durable copy byte-for-byte

**Files:**

- Modify: `app/src/main/java/com/majkeylab/scanit/ScanStorage.kt`
- Modify: `app/src/test/java/com/majkeylab/scanit/PureLogicTest.kt`

- [ ] Add failing tests for exact, short, and unknown-size destinations.
- [ ] Add `destinationLengthMatches(expectedBytes, reportedBytes, countedBytes)`.
- [ ] Require stream copy count to equal source size.
- [ ] Require MediaStore/SAF reported size to match; if unknown, reopen and count up to `expected + 1`.
- [ ] Keep existing rollback: delete pending MediaStore row or SAF document on mismatch.
- [ ] Run unit tests + a negative short-write provider scenario.
- [ ] Commit `fix: verify saved output byte counts`.

## Task 5: Prefer durable share attachments

**Files:**

- Modify: `app/src/main/java/com/majkeylab/scanit/ScanShare.kt`
- Modify: `app/src/main/java/com/majkeylab/scanit/MainActivity.kt`
- Modify: `app/src/test/java/com/majkeylab/scanit/PureLogicTest.kt`

- [ ] Add failing tests for readable durable URIs, unreadable URIs, and page-count mismatch.
- [ ] Add `chooseShareItems(durable, fallback, isReadable)`; durable wins only when complete and readable.
- [ ] Make PDF/image share intents accept `SavedScan`.
- [ ] Prefer saved PDF and Gallery pages; keep FileProvider cache as all-or-nothing fallback.
- [ ] Verify an attachment remains readable after deleting its Recent scans cache entry.
- [ ] Commit `fix: prefer durable scan attachments`.

## Task 6: Add exact release verification

**Files:**

- Replace: `tools/verify-apk.ps1` -> `tools/verify-release.ps1`
- Modify: `tools/build.ps1`
- Modify: `.github/workflows/android-ci.yml`
- Modify: `app/build.gradle.kts`

- [ ] Resolve bundletool `1.18.3` as build tooling only.
- [ ] Verify exact artifact path/hash, package, version, min 35/target 36, non-debuggable, ZIP structure, APK alignment/signature, AAB signature, R8 mapping, and real symbols only when generated.
- [ ] Fail public artifacts containing Gemini or app-owned `INTERNET`.
- [ ] CI may pass `-AllowUnsigned`; the local release gate may not.
- [ ] Build script runs internal unit/lint + both public release lint/build/bundle tasks.
- [ ] CI uploads public artifacts/mappings/reports only; never the internal APK.
- [ ] Run the whole build plus both distribution verifiers.
- [ ] Commit `ci: verify public release artifacts`.

## Foundation gate

Run:

```powershell
./tools/build.ps1
./tools/verify-release.ps1 -Distribution play -ArtifactPath ./app/build/outputs/bundle/playRelease/app-play-release.aab -AllowUnsigned
./tools/verify-release.ps1 -Distribution github -ArtifactPath ./app/build/outputs/apk/github/release/app-github-release-unsigned.apk -AllowUnsigned
git diff --check
```

Expected: all checks pass. A deliberately wrong package, truncated destination, missing mapping, Gemini residue, or public `INTERNET` fails.
