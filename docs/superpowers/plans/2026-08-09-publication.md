# Publication surfaces implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development`.

**Goal:** Provide accurate proprietary/legal/repository/Play materials and real assets for the verified public build.

**Architecture:** Static GitHub Pages from `main/docs`, source-backed Play worksheets, local donation assets in README only, and remote publication actions gated on verified artifacts.

**Tech stack:** HTML/CSS, Markdown, PowerShell, GitHub Pages/CLI, Android phone, Play Console.

---

## Task 1: Establish the proprietary cutoff

**Files:** `LICENSE`, `README.md`, `PRIVACY.md`, `SECURITY.md`, `CHANGELOG.md`, `CONTRIBUTING.md`, `THIRD_PARTY_NOTICES.md`, `docs/licensing/HISTORICAL_MIT_RELEASES.md`, issue template, local BMC SVG/QR.

- [ ] Replace current MIT notice with all-rights-reserved/source-visible terms for first publication at `v1.2.0-beta.1`.
- [ ] Explicitly preserve every earlier MIT grant, including preview.1, alpha.1, and distributed alpha.2/versionCode4.
- [ ] Grant install/use of unmodified official binaries; preserve third-party/GitHub/legal rights.
- [ ] Generate exact dependency notices from the shipped public runtime graph.
- [ ] Issues accepted; code PRs currently not accepted.
- [ ] Remove current open-source/MIT/public-Gemini claims.
- [ ] README only: local BMC button + supplied exact QR. No CDN. No donation in app/Pages/Play/Reddit.
- [ ] Add and run `tools/verify-repository-policy.ps1`.
- [ ] Commit `docs: establish proprietary release terms`.

## Task 2: Add the static legal/support site

**Files:** `docs/index.html`, `docs/privacy.html`, `docs/terms.html`, `docs/assets/site.css`, `docs/.nojekyll`.

- [ ] Minimal responsive black/white pages: product, verified features, FAQ, support, privacy, terms.
- [ ] Disclose temporary Recent scans, durable local outputs, private marks, ML Kit diagnostics, support email, GitHub Pages hosting, retention/deletion.
- [ ] Explain visual mark is not a certificate/digital signature.
- [ ] No JavaScript, analytics, cookies, forms, webfonts, remote images, or donation.
- [ ] Update in-app policy URL to `https://majkey25.github.io/ScanIt/privacy.html`.
- [ ] Serve locally and test 360/768/1440 widths, keyboard focus, links, headings, and alt text.
- [ ] Commit `docs: add legal and support website`.

## Task 3: Build the Play Console pack

**Files:** replace `docs/play-store/PLAY_CONSOLE.md` with `LISTING_EN.md`, `LISTING_CS.md`, `APP_CONTENT.md`, `DATA_SAFETY.md`, `RELEASE_CHECKLIST.md`.

- [ ] Listing: direct scan/share, Recent scans, measured PDF targets, visual marks; no cloud AI/open-source/donation claim.
- [ ] App access: no account; Ads: No; Productivity; 18+ adult document workflow; government/financial/health: No; deletion: not applicable.
- [ ] Data Safety conservatively maps ML Kit device IDs, diagnostics, and app interactions as collected/required/not shared/not ephemeral/encrypted, analytics/diagnostics.
- [ ] Normal document pixels stay on-device and are not declared collected.
- [ ] Cite current official Play/ML Kit sources and require final dependency-graph comparison.
- [ ] Validate short descriptions <=80 characters and run repository policy checks.
- [ ] Commit `docs: prepare Google Play publication pack`.

## Task 4: Capture and validate real publication assets

**Prerequisite:** Final public APK is installed and verified.

**Files:** exact Play icon/feature graphic, five EN + five CS phone screenshots, repository screenshots/promo, `tools/verify-publication-assets.ps1`, CI.

- [ ] Use real phone UI/music-sheet scan. Status time 12:12; remove notifications/carrier/battery percentage; Wi-Fi may remain.
- [ ] Screenshots exactly 1080x1920: scan, result, Recent scans, Sign/stamp, Settings.
- [ ] Icon 512x512 <=1MB; feature graphic 1024x500 RGB/no alpha and no CTA/rank/store badge/device mockup.
- [ ] Remove obsolete AI/glossy/fabricated assets.
- [ ] Verifier checks exact paths/count/dimensions/format/ratio/size and missing duplicates.
- [ ] Inspect every asset at original resolution.
- [ ] Commit `docs: add verified publication assets`.

## Task 5: Triage Dependabot and finalize the draft PR

- [ ] Close #4/#5 with compileSdk37 evidence.
- [ ] Sequentially rebase/fresh-CI/merge #1, #2, #6, #3.
- [ ] Rebase #7 only after #3; merge only if fresh required CI is green, otherwise close with exact failure.
- [ ] Rebase feature branch on resulting `origin/main` and rerun every local gate.
- [ ] Verify no key/private document/release credential/generated binary is staged.
- [ ] Push `feat/publish-ready/09-08-2026` and open a draft PR with exact evidence and limitations.
- [ ] Wait for required GitHub CI. Do not mark ready or merge.

## Task 6: Post-merge publication gates

- [ ] Configure Pages from `main/docs` only after PR merge; verify root/privacy/terms HTTP 200.
- [ ] Remove `open-source` repository topic after proprietary terms merge.
- [ ] Complete Play drafts only after Pages and signed AAB are live/verified; final declaration/production submit remains an account-holder gate.
- [ ] Update Reddit only after a real release URL/site/device evidence exists. Titles cannot be edited: add a prominent correction, edit owner content, and reply to the compression/dashboard requesters.

## Publication gate

`git diff --check`, repository-policy verifier, asset verifier, local website/browser tests, final artifact verifier, physical-device scenarios, GitHub required CI. Pages/Play/Reddit are not claimed done before their explicit prerequisites.
