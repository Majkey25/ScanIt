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

- The Google Play and GitHub public variants contain no public cloud-processing feature and declare no app-owned `INTERNET` permission.
- Google Play services supplies the ML Kit scanner UI and may download modules and process diagnostic telemetry under Google's terms.
- Scans are held in a bounded app-private cache and exposed for sharing only through scoped content URIs.
- Android backup and device-to-device transfer are disabled for ScanIt app data.
- Release signing keys and local signing properties are never stored in this repository.
- The frozen GitHub preview APK is a historical debug-signed artifact, not the Google Play build.

## Supply-chain controls

- Gradle resolves dependencies only from Google Maven, Maven Central, and the Gradle Plugin Portal.
- Dependency artifacts used by Windows development and Ubuntu CI are checked against the committed SHA-256 verification metadata. macOS builds are not currently claimed or tested.
- GitHub Actions are pinned to full commit SHAs and run the repository test, lint, build, and release-verification gate.
- Dependabot is configured for weekly Gradle and GitHub Actions update proposals.
