# Google Play edition boundary

This public checkout builds the fully unlocked, no-ads SeliaScan GitHub edition:

- package: `com.majkeylab.scanit.github`
- version: `1.8.0` (code 40)
- license: MIT
- advertising, consent, Billing, Premium, paywalls, and feature locks: absent

Do not upload this checkout's AAB to the existing Google Play listing for
`com.majkeylab.scanit`. That separately maintained edition contains the reviewed
Google Mobile Ads, User Messaging Platform, Google Play Billing, Premium, and
free-mode logic. Its current Play Console worksheet, release notes, Data Safety
mapping, and exact signed AAB live with that local source so the public edition
cannot accidentally be submitted as the monetized app.

Public links shared by both editions:

- website: `https://majkey25.github.io/ScanIt/`
- privacy policy: `https://majkey25.github.io/ScanIt/privacy.html`
- support: `majkeylab@gmail.com`

Before any Play submission, verify the exact signed artifact, merged manifest,
runtime dependency graph, package ID, version code, R8 mapping, privacy policy,
store listing, App Content answers, and Data Safety declarations.
