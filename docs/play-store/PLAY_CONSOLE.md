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
| App name | `ScanIt` |
| Package | `com.majkeylab.scanit` |
| Version code | `15` |
| Version name | `1.3.1` |
| Default language | English (United States) |
| App or game | App |
| Category | Productivity |
| Free or paid | Free |
| Contact email | `majkeylab@gmail.com` |
| Website | `https://majkey25.github.io/ScanIt/` |
| Privacy Policy | `https://majkey25.github.io/ScanIt/privacy.html` |
| Ads | No |

Do not invent a legal developer name, address, phone number, organization, or
other account-holder detail here. Use only the verified values already held by
the Play developer account.

## English (United States) listing

### App name

> ScanIt

### Short description

71 characters including spaces:

> Scan documents to PDF and share in seconds. Simple, local, and ad-free.

### Full description

> ScanIt removes the file-management work from document scanning.
>
> Open the app and the scanner starts immediately. Capture one or more pages, review the automatic crop and enhancement, then save, share, or print the result.
>
> Features:
> - automatic document detection and capture
> - crop, perspective correction, rotation, filters, shadow removal, and cleanup
> - single-page and multi-page PDF, original-image, high-quality JPEG, and lossless PNG output
> - page thumbnails for browsing multi-page results
> - a full-screen zoomable preview opened from the Result image
> - Google review filters including Auto, Color, Grayscale, Black and white, and Shadows
> - measured Original, 5 MB, 10 MB, 20 MB, and custom 1–500 MB PDF size goals
> - per-document PDF size and folder changes from File details without changing saved defaults
> - per-document image size, format, and folder changes from File details
> - image size options for Original, 3840 px, 2560 px, 1600 px, or custom 320–6000 px
> - on-device Latin-script text extraction across all pages, explicit text export, and selected-page QR/barcode detection
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
> ScanIt has no ads, subscription, account, first-party analytics, cloud document library, or public cloud-processing feature. Scanned document content stays on the device unless you choose to share or print it.
>
> Visual marks are image annotations only. They are not digital or cryptographic signatures and do not verify identity or document integrity. PDF size limits are measured goals; if a readable file cannot meet the selected goal, ScanIt keeps the smallest readable result and shows its actual size.
>
> The scanner is powered by Google ML Kit Document Scanner and requires Google Play services. Google Play services may download the scanner or recognition modules before first use and process limited diagnostic and usage telemetry.

## Czech listing

### App name

> ScanIt

### Short description

70 characters including spaces:

> Naskenujte dokument do PDF a během pár sekund ho sdílejte. Bez reklam.

### Full description

> ScanIt usnadňuje skenování a odesílání dokumentů.
>
> Po otevření aplikace se skener spustí okamžitě. Naskenujte jednu nebo více stran, zkontrolujte automatický ořez a vylepšení a výsledek uložte, sdílejte nebo vytiskněte.
>
> Funkce:
> - automatická detekce a zachycení dokumentu
> - ořez, korekce perspektivy, otočení, filtry, odstranění stínů a vyčištění
> - jednostránkový i vícestránkový výstup do PDF, původních obrázků, kvalitního JPEG nebo bezeztrátového PNG
> - náhledy stránek pro procházení vícestránkových výsledků
> - celoobrazovkový náhled s přiblížením otevřený klepnutím na obrázek výsledku
> - filtry Google pro Auto, Barvy, Odstíny šedi, Černobíle a Stíny
> - měřené cíle velikosti PDF: Původní, 5 MB, 10 MB, 20 MB nebo vlastní cíl 1–500 MB
> - změna velikosti a složky konkrétního PDF v detailech souboru bez změny uložených výchozích nastavení
> - změna velikosti, formátu a složky obrázků v detailech souboru
> - velikost obrázku Původní, 3840 px, 2560 px, 1600 px nebo vlastní 320–6000 px
> - místní rozpoznání latinského textu ze všech stran, výslovný export textu a rozpoznání QR nebo čárových kódů na aktuální straně
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
> ScanIt neobsahuje reklamy, předplatné, účet, vlastní analytické nástroje, cloudovou knihovnu dokumentů ani veřejnou funkci cloudového zpracování. Obsah skenu zůstává v zařízení, dokud ho sami nesdílíte nebo nevytisknete.
>
> Vizuální značky jsou pouze obrázkové anotace. Nejde o digitální ani kryptografické podpisy a nepotvrzují totožnost ani neporušenost dokumentu. Limity velikosti PDF jsou měřené cíle; pokud je nelze dodržet při zachování čitelnosti, ScanIt ponechá nejmenší čitelný výsledek a zobrazí jeho skutečnou velikost.
>
> Skener používá Google ML Kit Document Scanner a vyžaduje služby Google Play. Služby Google Play mohou před prvním použitím stáhnout modul skeneru nebo rozpoznávání a zpracovávat omezené diagnostické a provozní údaje.

## Release notes — version 15 / 1.3.1

Each block is below Google Play's 500-character per-language limit.

### English (United States)

> Result pages now swipe with a visible next-page preview. Rescan, Sign / stamp, and Actions are clear buttons. File details groups PDF Size and Location plus image Size, Format, and Location into compact controls. The previous scan stays in Recent when a rescan is canceled.

### Čeština

> Stránky výsledku lze posouvat s viditelným náhledem další strany. Znovu skenovat, Podpis / razítko a Akce jsou jasná tlačítka. Detaily souboru přehledně seskupují velikost a umístění PDF i velikost, formát a umístění obrázků. Při zrušení nového skenu zůstane původní sken v Nedávných.

### Deutsch

> Ergebnisseiten lassen sich wischen und zeigen einen Teil der nächsten Seite. Neu scannen, Signieren / Stempel und Aktionen sind klare Schaltflächen. Dateidetails gruppiert PDF-Größe und Speicherort sowie Bildgröße, Format und Speicherort. Beim Abbruch eines neuen Scans bleibt der bisherige Scan unter Zuletzt erhalten.

### Español

> Las páginas del resultado se deslizan y muestran parte de la siguiente. Volver a escanear, Firmar / sello y Acciones son botones claros. Detalles del archivo agrupa Tamaño y Ubicación del PDF y Tamaño, Formato y Ubicación de imágenes. Al cancelar un nuevo escaneo, el anterior permanece en Recientes.

### 简体中文

> 结果页面现在可横向滑动，并显示下一页的一部分。重新扫描、签名/印章和操作均为清晰按钮。文件详情将 PDF 大小与位置，以及图片大小、格式与位置紧凑分组。取消重新扫描时，原扫描仍保留在最近记录中。

The listing describes current implemented public behavior only. It does not
claim certificate-backed signatures, guaranteed PDF compression, cloud
processing, or any unfinished feature. The existing support link is optional
and does not unlock app features.

## App Content worksheet

### Privacy Policy

- URL: `https://majkey25.github.io/ScanIt/privacy.html`
- Publicly accessible without login: **Yes; verified HTTP 200 on 2026-08-09.**
- Names ScanIt and provides privacy contact: Yes.
- Covers SDK data, retention/deletion, sharing, and security: Yes.
- GitHub Pages is enabled and the URL returns the policy without login.
- The in-app link points to this same URL.

### App access

- Is all functionality available without special access? **Yes**.
- Account or sign-in required? **No**.
- Reviewer credentials, QR code, membership, location, or special instructions required? **No**.

Reviewer note:

> ScanIt requires no account. Opening it starts the Google ML Kit document scanner. Finish a scan to reach Result, or cancel to reach Recent scans. The camera and editing flow is supplied by Google Play services. Settings is available from the gear icon. The app supports Android 13 and newer, requires Google Play services, and ML Kit requires at least 1.7 GB total device RAM.

### Ads

- Does the app contain ads? **No**.
- Advertising SDK present? **No**.
- Donation or payment prompt in the app or Play listing? **Yes: Settings contains an optional external Buy Me a Coffee support link. It does not unlock digital content or app features.**

### Target audience and content

- Selected age group: **18 and over**.
- Designed for children: **No**.
- Families program: **No**.
- Social features or communication between users: **No**.
- User-generated content published or exchanged inside ScanIt: **No**.
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

The public manifest does not request ScanIt's own camera, broad storage,
contacts, location, accounts, notifications, advertising ID, or app-owned
internet permission. The user explicitly chooses any imported image and custom
PDF folder through Android system pickers. Verify the final merged manifest
again before submission.

## Data Safety worksheet

### Top-level answers

| Question | Answer |
|---|---|
| Does the app collect or share required user data types? | Collects: Yes, because the ML Kit SDK transmits telemetry. Shares: No. |
| Is all transmitted data encrypted in transit? | Yes, Google states ML Kit uses HTTPS. |
| Can users request account deletion? | Not applicable; ScanIt has no accounts. |
| Does the app provide an independent deletion-request mechanism for ML Kit telemetry? | No. Google processes that telemetry under Google's Privacy Policy. |
| Does the app follow the Families policy? | Not applicable; target audience is 18+. |

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
conservative choices because ScanIt provides no SDK telemetry toggle and Google
does not state that all metrics are processed only in memory.

### Data types not declared as collected by ScanIt

- Scanned pages, imported document images, generated PDFs, file names, and extracted text or code results: processed and stored on-device; explicit user-directed export or sharing to another app is not developer collection.
- Email subject/message defaults and folder choices: local app settings only.
- Contacts, location, payment information, health data, financial information, messages, audio, calendar, and browsing history: not accessed by ScanIt.

### Local retention and deletion

- App-private working copies: at most eight scan directories; Android may clear them; removing a Recent item removes its temporary working copy; uninstall removes the remaining app-private cache.
- App settings: retained locally until changed, cleared, or the app is uninstalled; Android backup and transfer are disabled.
- Gallery, Downloads, and selected-folder files: retained until the user deletes them through ScanIt or the corresponding storage provider. On a fresh install, PDF deletion after choosing a sharing app is enabled and image deletion is disabled; both settings are configurable. Uninstall does not delete saved files outside app-private storage.
- External share/print destination: retention is controlled by the app or service selected by the user.
- Google ML Kit telemetry: retention and deletion are controlled by Google under Google's policies, not by ScanIt.

### Final Data Safety gate

Before submission, compare this worksheet with:

1. the exact signed `playRelease` AAB;
2. the merged release manifest;
3. `playReleaseRuntimeClasspath`;
4. the current ML Kit disclosure and Play form wording.

Stop submission if the artifact contains another data-processing SDK, a public
cloud endpoint, advertising, analytics, or an app-owned `INTERNET` permission.

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

1. `ScanIt detects the edges of an original document before capture.`
2. `Review, crop, enhance, and filter scanned pages before saving.`
3. `ScanIt result with rescan, signature, document actions, sharing, printing, and file details.`
4. `Expanded PDF and image details with size, format, and location controls.`
5. `Recent scans with document previews, page counts, file sizes, and one-tap opening.`
6. `Signature and stamp editor with direct placement controls.`
7. `On-device text extraction and QR or barcode actions.`
8. `PDF size choices including original, common limits, and a custom target.`

Czech alt text:

1. `ScanIt rozpoznává okraje původního dokumentu před pořízením skenu.`
2. `Kontrola, ořez, vylepšení a filtrování naskenovaných stran před uložením.`
3. `Výsledek ve ScanIt s novým skenem, podpisem, akcemi, sdílením, tiskem a podrobnostmi souborů.`
4. `Rozbalené podrobnosti PDF a obrázků s ovládáním velikosti, formátu a umístění.`
5. `Nedávné skeny s náhledy, počtem stran, velikostí a otevřením jedním klepnutím.`
6. `Editor podpisu a razítka s přímým ovládáním umístění.`
7. `Rozpoznání textu a QR nebo čárových kódů přímo v zařízení.`
8. `Volba velikosti PDF včetně původní, běžných limitů a vlastního cíle.`

Do not show a Play badge, price/free claim, ranking, award, download call to
action, donation, unfinished feature, or device mockup in the feature graphic.

## Submission checklist

- [ ] Signed AAB is exactly `com.majkeylab.scanit`, version code 15, version `1.3.1`; verify after the final release build.
- [x] R8 mapping from the exact build is retained. The only native library is a prebuilt dependency without a separate symbol payload; no fake symbols are uploaded.
- [x] Public artifact contains no public cloud-processing code or app-owned `INTERNET` permission.
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
