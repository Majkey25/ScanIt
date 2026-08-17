# README Screenshot Refresh Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace outdated README graphics with one wide overview and all eight current, accurate ScanIt screenshots.

**Architecture:** Treat the existing English Play Store screenshots as immutable UI truth. Compose a README-only landscape overview from four of those PNGs, then reference all eight source screenshots directly from README so there is no duplicated gallery asset set.

**Tech Stack:** Git, GitHub-flavored Markdown, FFmpeg, PNG, GitHub Markdown API.

## Global Constraints

- Preserve the existing sheet-music scan in every screenshot.
- Do not generate or invent application UI.
- Do not change application code, Play Store assets, release artifacts, or Reddit content.
- Keep all published screenshots English and use version-neutral README asset names.

---

### Task 1: Create the wide current-UI overview

**Files:**
- Create: `docs/images/scanit-current-overview.png`
- Consume: `docs/play-store/assets/en-US/phone/01-capture.png`
- Consume: `docs/play-store/assets/en-US/phone/03-result.png`
- Consume: `docs/play-store/assets/en-US/phone/04-file-details.png`
- Consume: `docs/play-store/assets/en-US/phone/06-sign-stamp.png`

**Interfaces:**
- Consumes four authoritative 1080 x 1920 PNG screenshots.
- Produces one 1920 x 1080 RGB PNG overview for README.

- [ ] **Step 1: Verify the four source files**

Run `ffprobe` for codec, dimensions, and pixel format. Expected: four readable PNG files, each 1080 x 1920.

- [ ] **Step 2: Compose the overview**

Use FFmpeg to scale each screenshot to 480 x 853, pad it vertically on the existing off-white background to 480 x 1080, then join the four panels horizontally in workflow order: capture, result, file details, signature.

```powershell
ffmpeg -hide_banner -loglevel error -y `
  -i docs/play-store/assets/en-US/phone/01-capture.png `
  -i docs/play-store/assets/en-US/phone/03-result.png `
  -i docs/play-store/assets/en-US/phone/04-file-details.png `
  -i docs/play-store/assets/en-US/phone/06-sign-stamp.png `
  -filter_complex "[0:v]scale=480:853,pad=480:1080:0:113:color=0xf8f7f3[a];[1:v]scale=480:853,pad=480:1080:0:113:color=0xf8f7f3[b];[2:v]scale=480:853,pad=480:1080:0:113:color=0xf8f7f3[c];[3:v]scale=480:853,pad=480:1080:0:113:color=0xf8f7f3[d];[a][b][c][d]hstack=inputs=4,format=rgb24[out]" `
  -map "[out]" -frames:v 1 docs/images/scanit-current-overview.png
```

- [ ] **Step 3: Verify output**

Run `ffprobe` and visually inspect `docs/images/scanit-current-overview.png`. Expected: PNG, 1920 x 1080, RGB, no clipped panels, no invented UI.

### Task 2: Replace README graphics

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes `docs/images/scanit-current-overview.png` and all eight existing English Play Store screenshots.
- Produces valid GitHub-flavored Markdown with one overview and a centered 4 + 4 gallery.

- [ ] **Step 1: Replace the obsolete top image**

Change the top README image from `docs/images/scanit-v1.1-update.png` to `docs/images/scanit-current-overview.png` and update its alt text to describe the four current screens.

- [ ] **Step 2: Replace the old composite**

Remove the `docs/images/scanit-v1.1-phones.png` block. Add two centered rows containing `01-capture.png` through `08-pdf-size.png`, each at 23% width, with accurate per-screen alt text.

- [ ] **Step 3: Remove the duplicate four-image gallery**

Remove the later four-image subset so each screenshot appears once in README.

### Task 3: Validate and publish

**Files:**
- Verify: `README.md`
- Verify: `docs/images/scanit-current-overview.png`

**Interfaces:**
- Produces one reviewed Git commit and a protected-branch PR.

- [ ] **Step 1: Validate assets and links**

Verify all nine referenced PNG paths exist, are readable, and meet the expected dimensions. Search README to confirm no `scanit-v1.1-` references remain.

- [ ] **Step 2: Validate GitHub rendering**

Render README through `gh api markdown -F mode=gfm -F context=Majkey25/ScanIt -F text=@README.md`. Confirm the returned HTML contains the new overview and all eight screenshot paths.

- [ ] **Step 3: Review and commit**

Run `git diff --check`, inspect the full diff, scan staged content for secrets, and commit with `docs: refresh current ScanIt screenshots`.

- [ ] **Step 4: Publish safely**

Push `feat/readme-screenshots/17-08-2026`, open a PR, wait for required CI, merge through branch protection, and verify GitHub `main` renders the new README assets.
