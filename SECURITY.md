# Security policy

## Supported versions

Security fixes are provided only for the latest published preview or release.

| Version | Supported |
|---|---|
| Latest published | Yes |
| All older versions | No |

## Report a vulnerability

Use [GitHub private vulnerability reporting](https://github.com/Majkey25/ScanIt/security/advisories/new).
Do not open a public issue for a vulnerability that could expose documents,
file URIs, signing material, or device data.

Include the affected version, Android version, reproduction steps, impact, and
the smallest safe proof of concept. Never attach real private documents or
credentials.

## Public-build boundaries

- Ordinary scanning, OCR, barcode detection, redaction, and file creation remain on-device.
- Smart cleanup and Manual cleanup remain on-device, preserve the parent revision, bound selection geometry and memory, and support cancellation.
- This stable GitHub edition declares no app-owned Internet permission and contains no generative-AI client, advertising SDK, billing SDK, first-party analytics SDK, broad storage permission, or maintainer-operated document backend.
- The separately maintained Google Play edition contains GMA Next-Gen, UMP, and Google Play Billing. UMP gates ad requests where consent is required. SeliaScan never passes document content to these SDKs.
- Google Play services supplies the ML Kit scanner UI and may download modules and process diagnostic telemetry under Google's terms.
- Scans are held in a bounded app-private cache and exposed for sharing only through scoped content URIs.
- Saved-output replacement verifies destination identity, size, dimensions, and SHA-256 before metadata activation and exact old-output cleanup.
- OCR and barcode payloads are bounded, stale requests are rejected, clipboard copies are marked sensitive, and detected codes do not open automatically.
- Android backup and device-to-device transfer are disabled for SeliaScan app data.
- Release signing keys and properties stay outside the repository and synchronized project directory with user-only filesystem access.
- Release verification enforces an exact permission allowlist and rejects unexpected permission growth.

## Supply-chain controls

- Gradle resolves dependencies only from Google Maven, Maven Central, and the Gradle Plugin Portal.
- Dependency artifacts used by Windows development and Ubuntu CI are checked against the committed SHA-256 verification metadata. macOS builds are not currently claimed or tested.
- The build includes an automated check that the Linux Android build-tools classifier required by Ubuntu CI remains covered by dependency verification metadata.
- GitHub Actions are pinned to full commit SHAs and run the repository test, lint, build, and release-verification gate.
- Dependabot is configured for weekly Gradle and GitHub Actions update proposals.
