# Security policy

## Supported versions

Security fixes are provided only for the latest published preview or release.

| Version | Supported |
|---|---|
| Latest published | Yes |
| All older versions | No |

## Report a vulnerability

Use [GitHub private vulnerability reporting](https://github.com/Majkey25/ScanIt/security/advisories/new). Do not open a public issue for a vulnerability that could expose documents, API keys, file URIs, or device data.

Include the affected version, Android version, reproduction steps, impact, and the smallest safe proof of concept. Never attach real private documents or credentials.

## Security boundaries

- The repository never contains signing keys or Gemini API keys.
- Preview APKs are debug-signed and are not production Google Play artifacts.
- Client-side Gemini credentials are suitable only for private, user-owned builds.
- The experimental AI workflow sends every page in the current scan to a third-party cloud service only after explicit opt-in.
