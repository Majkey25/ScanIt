# Google Play Console publication worksheet

Prepared for the public `playRelease` build only. Do not reuse these answers for
an internal developer build or after adding an SDK without re-auditing the
merged manifest and resolved runtime graph.

Google requires accurate app metadata, a Privacy Policy, and Data Safety answers
that include third-party SDK behavior. The developer remains responsible for the
submitted declarations:

- [Play Console requirements](https://support.google.com/googleplay/android-developer/answer/10788890)
- [Data Safety form guidance](https://support.google.com/googleplay/android-developer/answer/10787469)
- [Privacy Policy requirements](https://support.google.com/googleplay/android-developer/answer/17105854)
- [ML Kit Android disclosure](https://developers.google.com/ml-kit/android-data-disclosure)

## App identity

| Field | Value |
|---|---|
| App name | `SeliaScan` |
| Package | `com.majkeylab.scanit` |
| Version code | `26` |
| Version name | `1.5.0` |
| Default language | English (United States) |
| App or game | App |
| Category | Productivity |
| Free or paid | Free |
| Contact email | `majkeylab@gmail.com` |
| Website | `https://majkey25.github.io/ScanIt/` |
| Privacy Policy | `https://majkey25.github.io/ScanIt/privacy.html` |
| Ads | No in stable `playRelease`; yes in the version 35 Closed beta |

## Closed testing Premium setup

Version code 35 (`1.6.0-vip-ads.9`) is the ads and Premium candidate for Closed
testing. It must not replace the production or open-testing releases.

| Product | Type | Price | Benefit |
|---|---|---:|---|
| `seliascan_premium_monthly` | Auto-renewing subscription, base plan `monthly` | 49 CZK per month | Remove ads and unlock all Document Actions while subscribed |
| `seliascan_premium` | One-time buy option | 299 CZK | Remove ads and unlock all Document Actions forever |

Neither product uses a trial, introductory offer, discount, or prepaid plan.
Only license testers may use test payment methods. Regular Closed testers can
be charged real money.

Do not invent a legal developer name, address, phone number, organization, or
other account-holder detail here. Use only the verified values already held by
the Play developer account.

## English (United States) listing

### App name

> SeliaScan

### Short description

70 characters including spaces:

> Scan documents to PDF and share in seconds. Simple and stored locally.

### Full description

> SeliaScan removes the file-management work from document scanning.
>
> Open the app and the scanner starts immediately. Capture one or more pages, review the automatic crop and enhancement, then save, share, or print the result.
>
> Features:
> - automatic document detection and capture
> - automatic orientation correction from text-line angles, with a portrait fallback for textless landscape scans
> - crop, perspective correction, rotation, filters, shadow removal, and cleanup
> - single-page and multi-page PDF, original-image, high-quality JPEG, and lossless PNG output
> - page thumbnails for browsing multi-page results
> - a full-screen zoomable preview opened from the Result image
> - Google review filters including Auto, Color, Grayscale, Black and white, and Shadows
> - measured Original, 200 KB, 500 KB, 1 MB, 5 MB, 10 MB, 20 MB, and custom 1 KB–500 MB PDF size goals
> - per-document PDF size and folder changes from File details without changing saved defaults
> - per-document image size, format, and folder changes from File details
> - image size options for Original, 3840 px, 2560 px, 1600 px, or custom 320–6000 px
> - on-device Latin text recognition, including Czech, or Chinese text recognition across all pages
> - configurable Read all pages language with Auto, Czech, English, German, Spanish, and Chinese choices
> - permanent freehand black redaction with adjustable brush thickness, undo, redo, and clear-page controls
> - local Smart cleanup and lasso-based Manual cleanup with a preserved parent revision
> - Recent scans with page previews and saved-output deletion
> - automatic or manual saving of PDFs and Gallery images
> - PDF saving to Downloads or a folder you choose
> - PDF/image sharing with an optional subject and message
> - optional deletion of saved PDFs or images after sharing
> - reusable drawn, imported, or scanned signatures and stamps dragged into place on a selected page
> - Android system printing
> - monochrome light and dark interface
> - system, English, Czech, German, Spanish, and Simplified Chinese language selection
>
> The free version includes ads. Optional monthly or lifetime Premium removes ads and unlocks all Document Actions. SeliaScan has no app account, first-party analytics, cloud document library, or generative-AI client. Scanning and cleanup stay on-device.
>
> Visual marks are image annotations only. They are not digital or cryptographic signatures and do not verify identity or document integrity. PDF size limits are measured goals; if a readable file cannot meet the selected goal, SeliaScan keeps the smallest readable result and shows its actual size.
>
> The scanner is powered by Google ML Kit Document Scanner and requires Google Play services. Google Play services may download the scanner or recognition modules before first use and process limited diagnostic and usage telemetry.

## Czech listing

### App name

> SeliaScan

### Short description

65 characters including spaces:

> Skenujte do PDF a sdílejte během pár sekund. Jednoduše a lokálně.

### Full description

> SeliaScan usnadňuje skenování a odesílání dokumentů.
>
> Po otevření aplikace se skener spustí okamžitě. Naskenujte jednu nebo více stran, zkontrolujte automatický ořez a vylepšení a výsledek uložte, sdílejte nebo vytiskněte.
>
> Funkce:
> - automatická detekce a zachycení dokumentu
> - automatická oprava orientace podle úhlů textových řádků a portrétní fallback pro landscape skeny bez textu
> - ořez, korekce perspektivy, otočení, filtry, odstranění stínů a vyčištění
> - jednostránkový i vícestránkový výstup do PDF, původních obrázků, kvalitního JPEG nebo bezeztrátového PNG
> - náhledy stránek pro procházení vícestránkových výsledků
> - celoobrazovkový náhled s přiblížením otevřený klepnutím na obrázek výsledku
> - filtry Google pro Auto, Barvy, Odstíny šedi, Černobíle a Stíny
> - měřené cíle velikosti PDF: Původní, 200 KB, 500 KB, 1 MB, 5 MB, 10 MB, 20 MB nebo vlastní cíl 1 KB–500 MB
> - změna velikosti a složky konkrétního PDF v detailech souboru bez změny uložených výchozích nastavení
> - změna velikosti, formátu a složky obrázků v detailech souboru
> - velikost obrázku Původní, 3840 px, 2560 px, 1600 px nebo vlastní 320–6000 px
> - místní rozpoznání latinky včetně češtiny nebo čínského textu ze všech stran
> - nastavitelný jazyk Čtení všech stránek: Auto, čeština, angličtina, němčina, španělština nebo čínština
> - trvalé ruční začernění černým štětcem s nastavitelnou tloušťkou, krokem zpět, znovu a vymazáním stránky
> - místní Chytré vyčištění a Ruční čistič s obtažením skvrn a zachovanou původní revizí
> - Nedávné skeny s náhledy a mazáním uložených výstupů
> - automatické nebo ruční ukládání PDF a obrázků do Galerie
> - ukládání PDF do Stažených souborů nebo zvolené složky
> - sdílení PDF nebo obrázků s volitelným předmětem a zprávou
> - volitelné smazání uložených PDF nebo obrázků po sdílení
> - opakovaně použitelné kreslené, importované nebo skenované podpisy a razítka přetažené na vybranou stránku
> - systémový tisk Androidu
> - černobílé světlé i tmavé rozhraní
> - systémový, anglický, český, německý, španělský a zjednodušený čínský jazyk
>
> Bezplatná verze obsahuje reklamy. Volitelné měsíční nebo doživotní Premium reklamy odstraní a odemkne všechny Akce dokumentu. SeliaScan nemá vlastní uživatelský účet, vlastní analytické nástroje, cloudovou knihovnu dokumentů ani generativní AI. Skenování i vyčištění zůstávají v zařízení.
>
> Vizuální značky jsou pouze obrázkové anotace. Nejde o digitální ani kryptografické podpisy a nepotvrzují totožnost ani neporušenost dokumentu. Limity velikosti PDF jsou měřené cíle; pokud je nelze dodržet při zachování čitelnosti, SeliaScan ponechá nejmenší čitelný výsledek a zobrazí jeho skutečnou velikost.
>
> Skener používá Google ML Kit Document Scanner a vyžaduje služby Google Play. Služby Google Play mohou před prvním použitím stáhnout modul skeneru nebo rozpoznávání a zpracovávat omezené diagnostické a provozní údaje.

## Release notes — version 26 / 1.5.0

Each block is below Google Play's 500-character per-language limit.

### English (United States)

> Added straight-line or brush redaction, Latin/Chinese text recognition, selectable read-all-pages languages, and collapsible Settings categories. Redactions remain permanently rasterized.

### Čeština

> Přidána rovná čára nebo štětec pro redakci, rozpoznání latinky/čínštiny, jazyky Čtení všech stránek a skládací kategorie Nastavení. Redakce zůstává trvalá.

### Deutsch

> Neu: gerade Linie oder Pinsel für Schwärzungen, lateinische/chinesische Texterkennung, wählbare Vorlesesprache und einklappbare Einstellungen.

### Español

> Se añadió censura con línea recta o pincel, reconocimiento latino/chino, idioma seleccionable para leer todas las páginas y ajustes plegables.

### 简体中文

> 新增直线或画笔遮盖、拉丁/中文识别、可选的全页朗读语言以及可折叠设置分类。遮盖仍会永久写入派生版本。

The listing describes current implemented public behavior only. It does not
claim certificate-backed signatures, guaranteed PDF compression, cloud
processing, or any unfinished feature. The existing support link is optional
and does not unlock app features.

## App Content worksheet

### Privacy Policy

- URL: `https://majkey25.github.io/ScanIt/privacy.html`
- Publicly accessible without login: **Yes; verified HTTP 200 on 2026-08-09.**
- Names SeliaScan and provides privacy contact: Yes.
- Covers SDK data, retention/deletion, sharing, and security: Yes.
- GitHub Pages is enabled and the URL returns the policy without login.
- The in-app link points to this same URL.

### App access

- Is all functionality available without special access? **Yes**.
- Account or sign-in required? **No**.
- Reviewer credentials, QR code, membership, location, or special instructions required? **No**.

Reviewer note:

> SeliaScan requires no account. Opening it starts the Google ML Kit document scanner. Finish a scan to reach Result, or cancel to reach Recent scans. The camera and editing flow is supplied by Google Play services. Settings is available from the gear icon. The app supports Android 10 and newer, requires Google Play services, and ML Kit requires at least 1.7 GB total device RAM.

### Ads

- Does the app contain ads? **Yes for the version 35 Closed beta; no for stable `playRelease`.**
- Advertising SDK present? **Yes in the Closed beta: GMA Next-Gen SDK and UMP.**
- Donation or payment prompt in the app or Play listing? **Yes: Settings contains optional monthly and lifetime Premium purchases plus an external Buy Me a Coffee donation link. The donation does not unlock features or remove ads.**

### Target audience and content

- Selected age group: **18 and over**.
- Designed for children: **No**.
- Families program: **No**.
- Social features or communication between users: **No**.
- User-generated content published or exchanged inside SeliaScan: **No**.
- Content rating: answer the current IARC questionnaire from the shipped app; the maintainer provides no violence, sexual content, gambling, controlled substances, profanity, or user communication.

The app can scan user-owned documents, but it does not publish or distribute
that content itself. Reassess the audience and rating if marketing or app
features change.

### Required declarations

| Declaration | Answer |
|---|---|
| News or magazine app | No |
| Government app | No |
| Financial features | No |
| Health features | No |
| COVID-19 contact tracing or status app | No |
| Account creation | No |
| Account deletion requirement | Not applicable; no accounts |

### Permissions and sensitive APIs

The public manifest requests no app-owned Internet, camera, broad storage,
contacts, location, accounts, notifications, or advertising ID permission.
The user explicitly chooses imported images through Android system pickers.
Verify the final merged manifest again before submission.

## Data Safety worksheet

### Top-level answers

| Question | Answer |
|---|---|
| Does the app collect or share required user data types? | Collects: Yes, because Google Play services and ML Kit process diagnostic and usage telemetry. |
| Is all transmitted data encrypted in transit? | Yes. Google states that ML Kit telemetry uses HTTPS. |
| Can users request account deletion? | Not applicable; SeliaScan has no accounts. |
| Does the app provide an independent deletion-request mechanism for ML Kit telemetry? | No. Google processes that telemetry under Google's Privacy Policy. |
| Does the app follow the Families policy? | Recheck against the selected target audience; SeliaScan is not designed specifically for children. |

### Data types to declare

Conservative mapping for the resolved ML Kit runtime dependencies:

- `com.google.android.gms:play-services-mlkit-document-scanner:16.0.0`
- `com.google.android.gms:play-services-mlkit-text-recognition:19.0.1`
- `com.google.android.gms:play-services-mlkit-barcode-scanning:18.3.1`

| Play data type | Collected | Shared | Required | Ephemeral | Purpose |
|---|---:|---:|---:|---:|---|
| Device or other IDs | Yes | No | Yes | No | Analytics |
| App activity → App interactions | Yes | No | Yes | No | Analytics |
| App info and performance → Diagnostics | Yes | No | Yes | No | Analytics |

Google's disclosure says ML Kit may process device and application information,
device or per-installation identifiers, performance metrics, API configuration,
input/output size, feature version, event type, and error codes for diagnostics
and usage analytics. Google states that these metrics are encrypted in transit
and are not transferred to third parties. “Required” and “not ephemeral” are the
conservative choices because SeliaScan provides no SDK telemetry toggle and Google
does not state that all metrics are processed only in memory.

### Data types not declared as collected by SeliaScan

- Scanned pages, imported document images, generated PDFs, file names, and extracted text or code results: processed and stored on-device; explicit user-directed export or sharing to another app is not developer collection.
- Email subject/message defaults and folder choices: local app settings only.
- Contacts, location, payment information, health data, financial information, messages, audio, calendar, and browsing history: not accessed by SeliaScan.

### Local retention and deletion

- App-private working copies: at most eight scan directories; Android may clear them; removing a Recent item removes its temporary working copy; uninstall removes the remaining app-private cache.
- App settings: retained locally until changed, cleared, or the app is uninstalled; Android backup and transfer are disabled.
- Gallery, Downloads, and selected-folder files: retained until the user deletes them through SeliaScan or the corresponding storage provider. On a fresh install, PDF deletion after choosing a sharing app is enabled and image deletion is disabled; both settings are configurable. Uninstall does not delete saved files outside app-private storage.
- External share/print destination: retention is controlled by the app or service selected by the user.
- Google ML Kit telemetry: retention and deletion are controlled by Google under Google's policies, not by SeliaScan.

### Final Data Safety gate

Before submission, compare this worksheet with:

1. the exact signed `playRelease` AAB;
2. the merged release manifest;
3. `playReleaseRuntimeClasspath`;
4. the current ML Kit disclosure and Play form wording.

Stop submission if the artifact contains an undeclared data-processing SDK or
endpoint, advertising, billing, first-party analytics, app-owned Internet access,
or broad permissions.

## Store assets and localization

Use the current official [Google Play preview-asset requirements](https://support.google.com/googleplay/android-developer/answer/9866151):

- App icon: 512 × 512, 32-bit PNG with alpha, at most 1,024 KB.
- Feature graphic: 1024 × 500, JPEG or 24-bit PNG without alpha.
- Phone screenshots: prepare eight accurate English and eight accurate Czech 1080 × 1920 images; Google Play requires at least two and recommends at least four 1080px 9:16 images for app promotion surfaces.
- Screenshots must show the real current UI, use `12:12`, omit notifications, carrier identity, and battery percentage, and contain no private documents or third-party copyrighted sheet music.
- Provide English and Czech alt text for every uploaded graphic.

### Asset manifest

The files below use real current UI captures from the final artifact. They were
recaptured after the Result and Recent UI changes and use only the original QA
practice sheet.

- `assets/icon.png`: 512 × 512 RGBA PNG, no text or badge.
- `assets/feature-graphic.png`: 1024 × 500 RGB PNG, no alpha.
- `assets/en-US/phone/01-capture.png` through `08-pdf-size.png`: eight 1080 × 1920 RGB PNG screenshots.
- `assets/cs-CZ/phone/01-capture.png` through `08-pdf-size.png`: eight 1080 × 1920 RGB PNG screenshots.

Upload screenshots in this exact order: capture, review, result, File details,
Recent scans, Sign / stamp, Actions, PDF size.

English alt text:

1. `SeliaScan detects the edges of an original document before capture.`
2. `Review, crop, enhance, and filter scanned pages before saving.`
3. `SeliaScan result with rescan, signature, document actions, sharing, printing, and file details.`
4. `Expanded PDF and image details with size, format, and location controls.`
5. `Recent scans with document previews, page counts, file sizes, and one-tap opening.`
6. `Signature and stamp editor with direct placement controls.`
7. `On-device text extraction and QR or barcode actions.`
8. `PDF size choices including original, common limits, and a custom target.`

Czech alt text:

1. `SeliaScan rozpoznává okraje původního dokumentu před pořízením skenu.`
2. `Kontrola, ořez, vylepšení a filtrování naskenovaných stran před uložením.`
3. `Výsledek v SeliaScan s novým skenem, podpisem, akcemi, sdílením, tiskem a podrobnostmi souborů.`
4. `Rozbalené podrobnosti PDF a obrázků s ovládáním velikosti, formátu a umístění.`
5. `Nedávné skeny s náhledy, počtem stran, velikostí a otevřením jedním klepnutím.`
6. `Editor podpisu a razítka s přímým ovládáním umístění.`
7. `Rozpoznání textu a QR nebo čárových kódů přímo v zařízení.`
8. `Volba velikosti PDF včetně původní, běžných limitů a vlastního cíle.`

Do not show a Play badge, price/free claim, ranking, award, download call to
action, donation, unfinished feature, or device mockup in the feature graphic.

## Submission checklist

- [ ] Signed AAB is exactly `com.majkeylab.scanit`, version code 26, version `1.5.0`; verify after the final release build.
- [x] R8 mapping from the exact build is retained. The only native library is a prebuilt dependency without a separate symbol payload; no fake symbols are uploaded.
- [ ] Public artifact contains no Gemini/cloud-cleanup code or app-owned `INTERNET` permission; verify before Play submission.
- [x] Complete third-party license text and applicable notices are packaged with the APK/AAB distribution and verified in the exact artifact.
- [x] In-app Privacy Policy link opens the Pages URL above.
- [x] Pages root, Privacy, and Terms URLs return successfully over HTTPS.
- [ ] Listing text and refreshed screenshots match the final device-tested feature set.
- [x] English and Czech short descriptions remain within 80 characters.
- [x] Data Safety answers are rechecked against the exact runtime graph and current official guidance.
- [x] Developer identity and public contact fields use verified Play account data.
- [x] App icon, feature graphic, screenshots, and alt text pass the current asset rules.
- [x] No account credentials are entered in App access because none are required.
- [ ] Closed/production submission happens only after the signed artifact and all declarations receive final account-holder review.
