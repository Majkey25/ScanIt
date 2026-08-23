# Privacy Policy

The ScanIt GitHub Pages Privacy Policy target is
<https://majkey25.github.io/ScanIt/privacy.html>. GitHub Pages must be enabled
before that URL is used in Google Play. The full policy source is currently in
[`docs/privacy.html`](docs/privacy.html).

The public ScanIt app has no account system, advertising SDK, first-party
analytics, or maintainer-operated document server. Ordinary scanning, OCR,
barcode detection, redaction, and file creation run on-device. Google Play services and ML Kit process limited
diagnostic and usage telemetry as described in the canonical policy and
[Google's ML Kit disclosure](https://developers.google.com/ml-kit/android-data-disclosure).
Optional text extraction and QR/barcode detection use recognition models
delivered by Google Play services and run on-device; the model may download
before first use. ScanIt does not upload their input or results to a
maintainer-operated service. Text is
exported only to a document destination the user selects. Copying a recognition
result places it on Android's clipboard with the sensitive-content flag.

Smart cleanup and Manual cleanup run locally. Manual cleanup processes only the
bounded regions that the user draws and keeps the parent revision in the bounded
Recent cache. ScanIt does not send scanned pages to a maintainer-operated or
third-party generative-AI service.

Temporary scan working copies are kept in a bounded app-private cache. Durable
PDFs and images are stored only according to the user's settings and actions.
ScanIt can delete exact tracked outputs after explicit Recent-screen confirmation
or after a sharing app is selected when the matching option is enabled.
Sharing and printing send a selected document to an external app or service only
after a user action.

For privacy questions, email [majkeylab@gmail.com](mailto:majkeylab@gmail.com).
Do not send private documents or credentials.

Last updated: 2026-08-23.
