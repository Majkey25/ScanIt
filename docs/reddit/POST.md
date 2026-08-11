# Reddit update package

Use this after the closed-test review completes. Replace an existing post or
comment with the same facts; do not describe ScanIt as a production release.

## Title

I built a minimal, local Android document scanner — ScanIt closed beta

## Body

I wanted document scanning to be one short flow: open the app, scan, review,
then save or share.

ScanIt now supports multi-page scans, Recent scans, page previews, PDF and JPEG
sharing, printing, manual or automatic local saving, and exact deletion of saved
outputs that ScanIt can verify it created. You can also adjust
color/grayscale/black-and-white intensity and
shadows, choose a measured PDF size goal, and add a reusable visual mark to one
page.

The visual mark is only an image annotation. It is not a digital or
cryptographic signature and does not verify identity or document integrity.
PDF size options are measured goals, not a lossless-compression promise; if a
readable result cannot meet the selected size, the app shows the actual size.

The public build has no ads, account, subscription, first-party analytics,
cloud document library, or app-owned Internet permission. Scanned content stays
on the device unless you choose to share or print it. The scanner uses Google
ML Kit Document Scanner through Google Play services.

The source is visible for review, but current material is proprietary rather
than open source. Historical MIT releases keep their original MIT license.

Closed test (available after Google completes the current review):
https://play.google.com/apps/testing/com.majkeylab.scanit

Feedback is useful, especially around multi-page scans, PDF size goals, and
device-specific sharing or printing behavior. Please do not send private
documents in bug reports.

## Gallery order

1. `../play-store/assets/en-US/phone/01-capture.png`
2. `../play-store/assets/en-US/phone/02-review.png`
3. `../play-store/assets/en-US/phone/03-result.png`
4. `../play-store/assets/en-US/phone/04-recent.png`
5. `../play-store/assets/en-US/phone/05-visual-mark.png`
6. `../play-store/assets/en-US/phone/06-settings.png`

All gallery images are real UI captures. Do not add a generated device frame,
private document, personal signature, price claim, ranking, or Google branding.
