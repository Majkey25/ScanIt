# Privacy

ScanIt is designed to work locally and does not operate an application server.

## Data ScanIt does not collect

ScanIt contains no first-party analytics, advertising SDK, account system, crash uploader, contact access, location access, user-visible document history, or document database.

## Local document processing

Scanned pages and PDFs are copied into an app-private cache. It retains at most eight temporary scan directories so Android can share or print recent results. Android may clear this cache.

By default, JPEG pages are saved to Gallery and PDFs to Downloads. Automatic PDF saving can be disabled in settings, and a different PDF folder can be selected through Android's Storage Access Framework.

Uninstalling ScanIt clears app-private settings, the encrypted API-key data, and temporary cache. It does not remove files already saved to Gallery, Downloads, or another selected folder.

## Google ML Kit Document Scanner

The scanner UI is provided by Google Play services. The device may connect to Google to download or update the scanner module. ScanIt does not send scanned pages or PDFs to its own server.

Google Play services and ML Kit may collect diagnostic information, usage metrics, device and app information, identifiers, and API configuration or settings. See [Google's ML Kit data disclosure](https://developers.google.com/ml-kit/android-data-disclosure) for the current details.

## Sharing and printing

Local Gallery or Downloads saving may happen automatically according to settings. A document is sent to a receiving application or print service only after the user explicitly taps a share or print action. That application or service handles the document under its own privacy policy.

## Optional Gemini AI cleanup

AI cleanup is experimental, disabled by default, and has not been live-verified with a Gemini API key. When enabled, ScanIt requires explicit consent and a user-provided key. Every page in the current scan is encoded as JPEG and sent over HTTPS to Google's Gemini service. Requests set `store=false`; ScanIt does not create a Gemini conversation history.

The API key is encrypted at rest with AES-GCM using a key stored in Android Keystore. A client-side API key cannot be treated as secret in a publicly distributed app, so this mode is intended for private builds using the device owner's own key. A public store release should use Firebase AI Logic with App Check/Play Integrity or a backend.

Google's terms and privacy policy apply to Gemini requests.

The user can delete the saved Gemini key in Advanced settings. Discarded AI previews remain only in the bounded app cache until Android, cache pruning, or uninstall removes them. Accepted `_AI` files saved to Gallery or Downloads remain until the user deletes them. ScanIt does not control Google's diagnostic or Gemini service retention.

## Backups

Android backup and device-to-device transfer are disabled for app data. API-key ciphertext and settings are excluded from backup.

## Questions

For a privacy problem, contact [majkeylab@gmail.com](mailto:majkeylab@gmail.com) or open a GitHub issue without attaching private documents, API keys, or other sensitive data. For a security vulnerability, use GitHub's private vulnerability reporting instead.

Last updated: 2026-08-07.
