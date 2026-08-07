# Google Play Console copy

Use this file for the internal alpha first. Recheck every declaration before closed or production release.

## App identity

- App name: `ScanIt`
- Package: `com.majkeylab.scanit`
- Default language: English (United States)
- App or game: App
- Free or paid: Free
- Category: Productivity
- Contact email: `majkeylab@gmail.com`
- Privacy policy: `https://github.com/Majkey25/ScanIt/blob/main/PRIVACY.md`
- Ads: No

## English listing

Short description:

> Scan documents to PDF and share them in seconds. Free, simple, no ads.

Full description:

> ScanIt removes the file-management work from document scanning.
>
> Open the app and the scanner starts immediately. Capture one or more pages, review the automatic crop and enhancement, then share the finished PDF or images through Android's standard share sheet.
>
> Features:
> - automatic document detection and capture
> - crop, perspective correction, rotation, filters, and cleanup
> - single-page and multi-page PDF/JPEG output
> - automatic PDF and Gallery saving, both configurable
> - selectable PDF folder
> - PDF/image sharing and system printing
> - monochrome light and dark interface
> - system, English, and Czech language selection
> - optional experimental Gemini cleanup behind Advanced settings
>
> ScanIt is free, open source, and contains no advertising, subscription, account, or first-party analytics.
>
> The scanner is powered by Google ML Kit Document Scanner and requires Google Play services. Experimental cloud AI is disabled by default and requires explicit consent plus the user's own API key.

## Czech listing

Short description:

> Naskenujte dokument do PDF a během pár sekund ho sdílejte. Zdarma, bez reklam.

Full description:

> ScanIt odstraňuje zbytečnou práci se soubory při skenování dokumentů.
>
> Po otevření aplikace se skener spustí okamžitě. Vyfoťte jednu nebo více stran, zkontrolujte automatický ořez a vylepšení a hotové PDF nebo obrázky sdílejte přes systémovou nabídku Androidu.
>
> Funkce:
> - automatická detekce a zachycení dokumentu
> - ořez, korekce perspektivy, otočení, filtry a vyčištění
> - jednostránkový i vícestránkový PDF/JPEG výstup
> - nastavitelné automatické ukládání PDF a obrázků
> - volitelná složka pro PDF
> - sdílení PDF/obrázků a systémový tisk
> - černobílé světlé i tmavé rozhraní
> - systémový, anglický a český jazyk
> - volitelné experimentální čištění Gemini skryté v pokročilém nastavení
>
> ScanIt je zdarma, open source a neobsahuje reklamy, předplatné, účet ani vlastní analytiku.
>
> Skener používá Google ML Kit Document Scanner a vyžaduje služby Google Play. Experimentální cloudová AI je ve výchozím stavu vypnutá a vyžaduje výslovný souhlas a vlastní API klíč uživatele.

## Internal-test reviewer notes

- No account or sign-in is required.
- Opening ScanIt immediately starts Google ML Kit Document Scanner.
- Finish or cancel the scanner to reach ScanIt, then use the gear icon for settings.
- Advanced Gemini cleanup is optional, disabled by default, and requires a user-owned key. It is not required for the scanner, saving, sharing, or printing flow.
- The internal alpha supports Android 15 and newer and requires Google Play services plus at least 1.7 GB total RAM.

## Data Safety draft

- Account data: none.
- Advertising: none.
- First-party analytics: none.
- Local scans: processed on-device and saved only according to user settings/actions.
- ML Kit: declare the device/app information, identifiers, performance, diagnostics, configuration, and usage events listed in Google's current ML Kit disclosure.
- Optional Gemini: every current scan page is sent as an encrypted-in-transit JPEG only after opt-in and a user-triggered cleanup action. Mark this collection optional and for app functionality. Confirm Google's current retention and the exact Play data-type mapping before closed or production testing.
- Deletion: the saved Gemini key can be deleted in Advanced settings; uninstall removes app-private settings/cache; users delete Gallery/Downloads files through Android.

## Required assets before public listing

- App icon: 512 x 512 PNG, at most 1 MB.
- Feature graphic: 1024 x 500 PNG or JPEG.
- Phone screenshots: at least two; prepare four accurate 1080 x 1920 captures.
- Do not use private documents, notifications, contact names, device identifiers, or misleading edited UI.

## Release gate

Internal testing may use the BYOK Gemini alpha. Do not move to closed or production until Gemini has been live-tested, reviewer access is resolved, the exact Data Safety form is confirmed, and the Play asset set is complete.
