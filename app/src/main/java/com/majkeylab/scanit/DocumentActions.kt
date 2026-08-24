package com.majkeylab.scanit

import android.content.ClipDescription
import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.URI
import java.net.URISyntaxException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Currency
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal const val MAX_DOCUMENT_TEXT_CHARACTERS = 200_000
internal const val MAX_DOCUMENT_ENTITY_CANDIDATES_PER_PAGE = 256
internal const val MAX_DETECTED_CODES = 64
internal const val MAX_DETECTED_CODE_CHARACTERS = 4_096
internal const val MAX_FIND_QUERY_CHARACTERS = 256
internal const val MAX_TEXT_MATCHES = 1_000
internal const val MAX_TTS_CHARACTERS = 4_000
internal const val MAX_TEXT_EXPORT_FILE_NAME_LENGTH = 96
private const val MAX_DETECTED_CODE_TOTAL_CHARACTERS = 65_536
private const val MAX_DETECTED_CODE_BYTES = MAX_DETECTED_CODE_CHARACTERS * 4
private const val MAX_TYPED_CODE_SHORT_CHARACTERS = 256
private const val MAX_TYPED_CODE_LIST_ITEMS = 16
private const val MAX_DOCUMENT_ACTION_IMAGE_BYTES = 64L * 1024L * 1024L
private const val MAX_OPENABLE_HTTP_URL_CHARACTERS = 4_096
private const val TEXT_EXPORT_SUFFIX = "_text.txt"
private const val MAX_ENTITY_SOURCE_CHARACTERS = MAX_DOCUMENT_TEXT_CHARACTERS
private const val MONEY_AMOUNT_PATTERN =
    "\\d(?:[\\d ,.']{0,62}\\d)?"
private val EMAIL_PATTERN =
    Regex(
        """(?<![A-Z0-9.!#$%&'*+/=?^_`{|}~-])[A-Z0-9.!#$%&'*+/=?^_`{|}~-]{1,64}@[A-Z0-9-]+(?:\.[A-Z0-9-]+)+(?![A-Z0-9-])""",
        RegexOption.IGNORE_CASE,
    )
private val URL_PATTERN =
    Regex("""\bhttps?://[^\s<>"']{1,4096}""", RegexOption.IGNORE_CASE)
private val PHONE_PATTERN =
    Regex("""(?<![\p{L}\p{N}])\+?(?:\(\d{1,4}\)|\d)[\d ()-]{5,24}\d(?![\p{L}\p{N}])""")
private val IBAN_PATTERN =
    Regex(
        """(?<![A-Z0-9])[A-Z]{2}\d{2}(?: ?[A-Z0-9]){11,30}(?![A-Z0-9])""",
        RegexOption.IGNORE_CASE,
    )
private val PAYMENT_CARD_PATTERN = Regex("""(?<!\d)(?:\d[ -]?){12,18}\d(?!\d)""")
private val MONEY_PATTERN =
    Regex(
        """(?<![\p{L}\p{N}])(?:\p{Sc}\s?$MONEY_AMOUNT_PATTERN|[A-Z]{3}\s+$MONEY_AMOUNT_PATTERN|$MONEY_AMOUNT_PATTERN\s?(?:\p{Sc}|[A-Z]{3}))(?![\p{L}\p{N}])""",
    )
private val MONEY_AMOUNT_FORMAT_PATTERN =
    Regex("""(?:\d{1,3}(?:[ ,.']\d{3})+|\d+)(?:[.,]\d{1,2})?""")
private val DATE_PATTERN =
    Regex("""(?<!\d)(?:\d{4}([./-])\d{1,2}\1\d{1,2}|\d{1,2}([./-])\d{1,2}\2\d{4})(?!\d)""")
private val CURRENCY_CODE_PATTERN = Regex("""\b[A-Z]{3}\b""")

internal enum class DocumentActionFailureKind {
    ModelUnavailable,
    Failed,
}

internal fun documentActionFailureKind(failure: Throwable): DocumentActionFailureKind =
    if (failure is SafeShareModelUnavailableException) {
        DocumentActionFailureKind.ModelUnavailable
    } else {
        documentActionFailureKindForMlKitError((failure as? MlKitException)?.errorCode)
    }

internal fun documentActionFailureKindForMlKitError(errorCode: Int?): DocumentActionFailureKind =
    if (errorCode == MlKitException.UNAVAILABLE) {
        DocumentActionFailureKind.ModelUnavailable
    } else {
        DocumentActionFailureKind.Failed
    }

internal enum class DetectedCodeKind {
    QrCode,
    Barcode,
}

internal data class DetectedCode(
    val kind: DetectedCodeKind,
    val value: String,
    val bounds: NormalizedRect?,
    val action: DetectedCodeAction?,
) {
    constructor(
        kind: DetectedCodeKind,
        value: String,
        openableHttpUrl: String?,
    ) : this(
        kind = kind,
        value = value,
        bounds = null,
        action = openableHttpUrl?.let(DetectedCodeAction::OpenUrl),
    )

    val openableHttpUrl: String?
        get() = (action as? DetectedCodeAction.OpenUrl)?.url
}

internal data class SensitiveClipboardExtra(
    val key: String,
    val value: Boolean,
)

internal fun documentClipboardSensitiveExtra(): SensitiveClipboardExtra =
    SensitiveClipboardExtra(ClipDescription.EXTRA_IS_SENSITIVE, true)

internal fun buildDocumentText(
    pageTexts: List<String>,
    maxCharacters: Int = MAX_DOCUMENT_TEXT_CHARACTERS,
    pageLabel: (Int) -> String = { page -> "Page $page" },
): DocumentActionOutput.Text {
    val accumulator = DocumentTextAccumulator(maxCharacters, pageLabel)
    for ((index, pageText) in pageTexts.withIndex()) {
        if (!accumulator.appendPage(index + 1, pageText)) break
    }
    return accumulator.result()
}

internal fun findText(
    snapshot: DocumentOcrSnapshot,
    query: String,
): List<TextMatch> {
    require(query.length in 1..MAX_FIND_QUERY_CHARACTERS && strictUtf8Bytes(query) != null) {
        "Find query is invalid"
    }
    val matches = ArrayList<TextMatch>()
    for ((page, text) in snapshot.pageTexts.withIndex()) {
        var start = 0
        while (start <= text.length - query.length && matches.size < MAX_TEXT_MATCHES) {
            val match = text.indexOf(query, start, ignoreCase = true)
            if (match < 0) break
            matches += TextMatch(page, match, match + query.length)
            start = match + 1
        }
        if (matches.size == MAX_TEXT_MATCHES) break
    }
    return matches
}

internal fun validatedSpeechText(value: String): String? {
    val text = value.trim()
    if (
        text.isEmpty() ||
            text.length > MAX_DOCUMENT_TEXT_CHARACTERS ||
            strictUtf8Bytes(text) == null
    ) {
        return null
    }
    return boundedTextPrefix(text, MAX_TTS_CHARACTERS)
}

private class DocumentTextAccumulator(
    private val maxCharacters: Int,
    private val pageLabel: (Int) -> String,
) {
    private val output: StringBuilder
    private var truncated = false

    init {
        require(maxCharacters in 1..MAX_DOCUMENT_TEXT_CHARACTERS) {
            "Document text limit is invalid"
        }
        output = StringBuilder(minOf(maxCharacters, 8_192))
    }

    fun appendPage(pageNumber: Int, pageText: String): Boolean {
        if (truncated) return false
        val text = pageText.trim()
        if (text.isEmpty()) return true
        if (output.isNotEmpty()) appendBounded("\n\n")
        if (!truncated) appendBounded(pageLabel(pageNumber))
        if (!truncated) appendBounded("\n")
        if (!truncated) appendBounded(text)
        return !truncated
    }

    fun result(): DocumentActionOutput.Text =
        DocumentActionOutput.Text(output.toString(), truncated)

    private fun appendBounded(value: String) {
        val remaining = maxCharacters - output.length
        if (value.length <= remaining) {
            output.append(value)
        } else {
            output.append(value, 0, remaining)
            truncated = true
        }
    }
}

internal fun validatedDetectedCode(
    rawValue: String?,
    displayValue: String?,
    rawBytes: ByteArray?,
    isQrCode: Boolean,
    typedUrl: String?,
    bounds: NormalizedRect? = null,
    typedAction: DetectedCodeAction? = null,
): DetectedCode? {
    val decodedBytes =
        rawBytes?.let { bytes ->
            if (bytes.isEmpty() || bytes.size > MAX_DETECTED_CODE_BYTES) return null
            decodeStrictUtf8(bytes) ?: return null
        }
    val value = (rawValue ?: decodedBytes ?: displayValue)?.trim()?.takeIf(String::isNotEmpty)
        ?: return null
    if (value.length > MAX_DETECTED_CODE_CHARACTERS || strictUtf8Bytes(value) == null) return null
    if (decodedBytes != null && decodedBytes.trim() != value) return null
    return DetectedCode(
        kind = if (isQrCode) DetectedCodeKind.QrCode else DetectedCodeKind.Barcode,
        value = value,
        bounds = bounds,
        action =
            (typedAction ?: typedUrl?.let(::validatedUrlAction))
                ?.let(::validatedSystemAction),
    )
}

internal fun buildDetectedCodes(
    candidates: Sequence<DetectedCode>,
    maxCodes: Int = MAX_DETECTED_CODES,
    maxCharacters: Int = MAX_DETECTED_CODE_TOTAL_CHARACTERS,
): DocumentActionOutput.Codes {
    require(maxCodes in 1..MAX_DETECTED_CODES) { "Detected-code limit is invalid" }
    require(maxCharacters in 1..MAX_DETECTED_CODE_TOTAL_CHARACTERS) {
        "Detected-code character limit is invalid"
    }
    val values = ArrayList<DetectedCode>(maxCodes)
    val seen = HashSet<Pair<DetectedCodeKind, String>>(maxCodes)
    var characters = 0
    val iterator = candidates.iterator()
    while (values.size < maxCodes && iterator.hasNext()) {
        val candidate = iterator.next()
        if (
            candidate.value.isEmpty() ||
                candidate.value.length > MAX_DETECTED_CODE_CHARACTERS ||
                strictUtf8Bytes(candidate.value) == null ||
                !seen.add(candidate.kind to candidate.value)
        ) {
            continue
        }
        if (candidate.value.length > maxCharacters - characters) break
        values += candidate.copy(action = candidate.action?.let(::validatedSystemAction))
        characters += candidate.value.length
    }
    return DocumentActionOutput.Codes(values)
}

internal fun isValidIban(raw: String): Boolean {
    val value = raw.trim()
    if (value.length !in 15..64) return false
    val compact = StringBuilder(34)
    for (character in value) {
        when (character) {
            ' ' -> Unit
            in '0'..'9', in 'A'..'Z', in 'a'..'z' -> compact.append(character.uppercaseChar())
            else -> return false
        }
    }
    if (
        compact.length !in 15..34 ||
            compact[0] !in 'A'..'Z' ||
            compact[1] !in 'A'..'Z' ||
            compact[2] !in '0'..'9' ||
            compact[3] !in '0'..'9'
    ) {
        return false
    }
    var remainder = 0
    for (index in compact.indices) {
        val character = compact[(index + 4) % compact.length]
        remainder =
            if (character in '0'..'9') {
                (remainder * 10 + character.digitToInt()) % 97
            } else {
                (remainder * 100 + character.code - 'A'.code + 10) % 97
            }
    }
    return remainder == 1
}

internal fun isLuhnValid(raw: String): Boolean {
    if (raw.length !in 13..64) return false
    val digits = raw.filter { character ->
        when (character) {
            ' ', '-' -> false
            in '0'..'9' -> true
            else -> return false
        }
    }
    if (digits.length !in 13..19) return false
    var sum = 0
    var double = false
    for (index in digits.indices.reversed()) {
        var digit = digits[index].digitToInt()
        if (double) {
            digit *= 2
            if (digit > 9) digit -= 9
        }
        sum += digit
        double = !double
    }
    return sum % 10 == 0
}

internal fun buildDocumentEntityCandidates(
    elements: Iterable<OcrElement>,
): List<DocumentEntityCandidate> {
    val values = ArrayList<DocumentEntityCandidate>()
    val counts = IntArray(MAX_SCAN_PAGES)
    val seen = HashSet<DocumentEntityCandidate>()
    var characters = 0
    for (element in elements) {
        if (element.value.length > MAX_ENTITY_SOURCE_CHARACTERS - characters) break
        characters += element.value.length
        if (counts[element.page] >= MAX_DOCUMENT_ENTITY_CANDIDATES_PER_PAGE) continue
        for (kind in DocumentEntityKind.entries) {
            for (value in entityValues(kind, element.value)) {
                if (counts[element.page] >= MAX_DOCUMENT_ENTITY_CANDIDATES_PER_PAGE) break
                val candidate = DocumentEntityCandidate(element.page, kind, value, element.bounds)
                if (seen.add(candidate)) {
                    values += candidate
                    counts[element.page] += 1
                }
            }
        }
    }
    return values
}

private fun entityValues(kind: DocumentEntityKind, text: String): Sequence<String> =
    when (kind) {
        DocumentEntityKind.Email ->
            EMAIL_PATTERN.findAll(text).map(MatchResult::value).filter(::isValidEmail)
        DocumentEntityKind.Phone ->
            if (isValidIban(text)) {
                emptySequence()
            } else {
                PHONE_PATTERN.findAll(text).map { it.value.trim() }.filter(::isValidPhone)
            }
        DocumentEntityKind.Url ->
            URL_PATTERN.findAll(text)
                .map { it.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}') }
                .filter { validatedHttpUrl(it) != null }
        DocumentEntityKind.Iban ->
            IBAN_PATTERN.findAll(text).map(MatchResult::value).filter(::isValidIban)
        DocumentEntityKind.PaymentCard ->
            if (isValidIban(text)) {
                emptySequence()
            } else {
                PAYMENT_CARD_PATTERN.findAll(text).map { it.value.trim() }.filter(::isLuhnValid)
            }
        DocumentEntityKind.Money -> moneyEntityValues(text)
        DocumentEntityKind.Date ->
            DATE_PATTERN.findAll(text).map(MatchResult::value).filter(::isValidDate)
    }

internal fun moneyEntityValues(text: String): Sequence<String> =
    MONEY_PATTERN.findAll(text).map { it.value.trim() }.filter(::isValidMoney)

private fun isValidEmail(value: String): Boolean {
    if (value.length !in 3..254) return false
    val at = value.lastIndexOf('@')
    if (at !in 1..64) return false
    val local = value.substring(0, at)
    if (
        local.any { character ->
            character !in 'a'..'z' &&
                character !in 'A'..'Z' &&
                character !in '0'..'9' &&
                character !in ".!#$%&'*+/=?^_`{|}~-"
        } ||
            local.startsWith('.') ||
            local.endsWith('.') ||
            ".." in local
    ) {
        return false
    }
    val domain = value.substring(at + 1)
    val labels = domain.split('.')
    return domain.length <= 253 &&
        labels.size >= 2 &&
        labels.all { label ->
            label.length in 1..63 &&
                label.first().isLetterOrDigit() &&
                label.last().isLetterOrDigit() &&
                label.all { it.isLetterOrDigit() || it == '-' }
        }
}

private fun isValidPhone(raw: String): Boolean {
    val value = raw.trim()
    if (
        value.length !in 7..32 ||
            value.any { it !in "0123456789+()- " } ||
            value.drop(1).contains('+') ||
            value.count { it == '(' } != value.count { it == ')' } ||
            value.count { it == '(' } > 1 ||
            (value.first() != '+' && value.none { it == ' ' || it == '-' || it == '(' })
    ) {
        return false
    }
    val digits = value.count(Char::isDigit)
    return digits in 7..15 && !isValidDate(value) && !isLuhnValid(value)
}

private fun isValidMoney(value: String): Boolean {
    if (value.length !in 2..64) return false
    val firstDigit = value.indexOfFirst(Char::isDigit)
    val lastDigit = value.indexOfLast(Char::isDigit)
    if (
        firstDigit < 0 ||
            !MONEY_AMOUNT_FORMAT_PATTERN.matches(value.substring(firstDigit, lastDigit + 1))
    ) {
        return false
    }
    if (value.any { Character.getType(it) == Character.CURRENCY_SYMBOL.toInt() }) return true
    val code = CURRENCY_CODE_PATTERN.find(value)?.value ?: return false
    return try {
        Currency.getInstance(code)
        true
    } catch (_: IllegalArgumentException) {
        false
    }
}

private fun isValidDate(value: String): Boolean {
    val separators = value.filterNot(Char::isDigit)
    if (separators.length != 2 || separators[0] != separators[1]) return false
    val parts = value.split(separators[0])
    if (parts.size != 3) return false
    val numbers = parts.map { it.toIntOrNull() ?: return false }
    return try {
        if (parts[0].length == 4) {
            LocalDate.of(numbers[0], numbers[1], numbers[2])
            true
        } else if (parts[2].length == 4) {
            runCatching { LocalDate.of(numbers[2], numbers[1], numbers[0]) }.isSuccess ||
                runCatching { LocalDate.of(numbers[2], numbers[0], numbers[1]) }.isSuccess
        } else {
            false
        }
    } catch (_: DateTimeException) {
        false
    }
}

internal fun validatedHttpUrl(raw: String): String? {
    val value = raw.trim()
    if (
        value.isEmpty() ||
            value.length > MAX_OPENABLE_HTTP_URL_CHARACTERS ||
            strictUtf8Bytes(value) == null ||
            value.any(Char::isUnsafeUriDisplayCharacter)
    ) {
        return null
    }
    val uri =
        try {
            URI(value)
        } catch (_: URISyntaxException) {
            return null
        }
    val scheme = uri.scheme?.lowercase() ?: return null
    if (
        (scheme != "http" && scheme != "https") ||
            uri.isOpaque ||
            uri.host.isNullOrBlank() ||
            uri.rawUserInfo != null ||
            uri.port > 65_535
    ) {
        return null
    }
    return value
}

internal fun validatedUrlAction(raw: String?): DetectedCodeAction.OpenUrl? =
    raw?.let(::validatedHttpUrl)?.let(DetectedCodeAction::OpenUrl)

internal fun validatedDialAction(phone: String?): DetectedCodeAction.Dial? =
    requiredPayload(phone, MAX_DETECTED_CODE_CHARACTERS)
        ?.takeIf(::isValidTypedPhone)
        ?.let(DetectedCodeAction::Dial)

internal fun validatedEmailAction(
    address: String?,
    subject: String?,
    body: String?,
): DetectedCodeAction.ComposeEmail? {
    val validAddress = requiredPayload(address, MAX_DETECTED_CODE_CHARACTERS) ?: return null
    if (
        !isValidEmail(validAddress) ||
        !isValidOptionalPayload(subject, MAX_TYPED_CODE_SHORT_CHARACTERS) ||
            !isValidOptionalPayload(body, MAX_DETECTED_CODE_CHARACTERS)
    ) {
        return null
    }
    return DetectedCodeAction.ComposeEmail(
        validAddress,
        subject?.takeUnless(String::isBlank),
        body?.takeUnless(String::isBlank),
    )
}

internal fun validatedSmsAction(
    phone: String?,
    message: String?,
): DetectedCodeAction.ComposeSms? {
    val validPhone = requiredPayload(phone, MAX_DETECTED_CODE_CHARACTERS) ?: return null
    if (
        !isValidTypedPhone(validPhone) ||
            !isValidOptionalPayload(message, MAX_DETECTED_CODE_CHARACTERS)
    ) {
        return null
    }
    return DetectedCodeAction.ComposeSms(validPhone, message?.takeUnless(String::isBlank))
}

internal fun validatedContactAction(
    name: String?,
    phones: List<String>,
    emails: List<String>,
): DetectedCodeAction.CreateContact? {
    if (
        phones.size > MAX_TYPED_CODE_LIST_ITEMS ||
            emails.size > MAX_TYPED_CODE_LIST_ITEMS ||
            !isValidOptionalPayload(name, MAX_TYPED_CODE_SHORT_CHARACTERS)
    ) {
        return null
    }
    val validPhones =
        phones.map {
            requiredPayload(it, MAX_DETECTED_CODE_CHARACTERS)
                ?.takeIf(::isValidTypedPhone)
                ?: return null
        }
    val validEmails =
        emails.map {
            requiredPayload(it, MAX_DETECTED_CODE_CHARACTERS)
                ?.takeIf(::isValidEmail)
                ?: return null
        }
    val validName = name?.takeUnless(String::isBlank)
    if (validName == null && validPhones.isEmpty() && validEmails.isEmpty()) return null
    return DetectedCodeAction.CreateContact(validName, validPhones, validEmails)
}

internal fun validatedCalendarAction(
    title: String?,
    start: Barcode.CalendarDateTime?,
    end: Barcode.CalendarDateTime?,
): DetectedCodeAction.CreateCalendarEvent? {
    val startMillis = start?.let(::calendarDateTimeMillis)
    val endMillis = end?.let(::calendarDateTimeMillis)
    if ((start != null && startMillis == null) || (end != null && endMillis == null)) return null
    return validatedCalendarEventAction(title, startMillis, endMillis)
}

internal fun validatedCalendarEventAction(
    title: String?,
    startMillis: Long?,
    endMillis: Long?,
): DetectedCodeAction.CreateCalendarEvent? {
    val validTitle = requiredPayload(title, MAX_DETECTED_CODE_CHARACTERS) ?: return null
    if (startMillis != null && endMillis != null && endMillis < startMillis) return null
    return DetectedCodeAction.CreateCalendarEvent(validTitle, startMillis, endMillis)
}

internal fun validatedGeoAction(
    latitude: Double,
    longitude: Double,
): DetectedCodeAction.OpenGeo? =
    if (
        latitude.isFinite() &&
            longitude.isFinite() &&
            latitude in -90.0..90.0 &&
            longitude in -180.0..180.0
    ) {
        DetectedCodeAction.OpenGeo(latitude, longitude)
    } else {
        null
    }

internal fun validatedWifiAction(
    ssid: String?,
    password: String?,
): DetectedCodeAction.OpenWifiSettings? {
    val validSsid = requiredPayload(ssid, MAX_TYPED_CODE_SHORT_CHARACTERS) ?: return null
    if (!isValidOptionalPayload(password, MAX_DETECTED_CODE_CHARACTERS)) return null
    return DetectedCodeAction.OpenWifiSettings(validSsid, password?.takeUnless(String::isBlank))
}

private fun requiredPayload(value: String?, maxCharacters: Int): String? =
    value?.takeIf {
        it.isNotBlank() && it.length <= maxCharacters && strictUtf8Bytes(it) != null
    }

private fun isValidOptionalPayload(value: String?, maxCharacters: Int): Boolean =
    value == null ||
        value.isBlank() ||
        (value.length <= maxCharacters && strictUtf8Bytes(value) != null)

internal fun validatedSystemAction(action: DetectedCodeAction): DetectedCodeAction? =
    when (action) {
        is DetectedCodeAction.OpenUrl -> validatedUrlAction(action.url)
        is DetectedCodeAction.Dial -> validatedDialAction(action.phone)
        is DetectedCodeAction.ComposeEmail ->
            validatedEmailAction(action.address, action.subject, action.body)
        is DetectedCodeAction.ComposeSms -> validatedSmsAction(action.phone, action.message)
        is DetectedCodeAction.CreateContact ->
            if (action.phones.size <= 3 && action.emails.size <= 3) {
                validatedContactAction(action.name, action.phones, action.emails)
            } else {
                null
            }
        is DetectedCodeAction.CreateCalendarEvent ->
            validatedCalendarEventAction(action.title, action.startMillis, action.endMillis)
        is DetectedCodeAction.OpenGeo -> validatedGeoAction(action.latitude, action.longitude)
        is DetectedCodeAction.OpenWifiSettings ->
            validatedWifiAction(action.ssid, action.password)
    }

internal fun systemActionForCandidate(
    candidate: DocumentEntityCandidate,
): DetectedCodeAction? =
    when (candidate.kind) {
        DocumentEntityKind.Email -> validatedEmailAction(candidate.value, null, null)
        DocumentEntityKind.Phone -> validatedDialAction(candidate.value)
        DocumentEntityKind.Url -> validatedUrlAction(candidate.value)
        DocumentEntityKind.Iban,
        DocumentEntityKind.PaymentCard,
        DocumentEntityKind.Money,
        DocumentEntityKind.Date,
        -> null
    }

private fun isValidTypedPhone(value: String): Boolean {
    if (
        value.length !in 7..32 ||
            value.any { it !in "0123456789+()- " } ||
            value.drop(1).contains('+') ||
            value.count { it == '(' } != value.count { it == ')' } ||
            value.count { it == '(' } > 1
    ) {
        return false
    }
    return value.count(Char::isDigit) in 7..15
}

private fun validatedBarcodeAction(barcode: Barcode): DetectedCodeAction? =
    when (barcode.valueType) {
        Barcode.TYPE_URL -> barcode.url?.url?.let(::validatedUrlAction)
        Barcode.TYPE_PHONE -> barcode.phone?.number?.let(::validatedDialAction)
        Barcode.TYPE_EMAIL ->
            barcode.email?.let { validatedEmailAction(it.address, it.subject, it.body) }
        Barcode.TYPE_SMS ->
            barcode.sms?.let { validatedSmsAction(it.phoneNumber, it.message) }
        Barcode.TYPE_CONTACT_INFO -> barcode.contactInfo?.let(::validatedContactAction)
        Barcode.TYPE_CALENDAR_EVENT -> barcode.calendarEvent?.let(::validatedCalendarAction)
        Barcode.TYPE_GEO -> barcode.geoPoint?.let { validatedGeoAction(it.lat, it.lng) }
        Barcode.TYPE_WIFI -> barcode.wifi?.let { validatedWifiAction(it.ssid, it.password) }
        else -> null
    }

private fun validatedContactAction(contact: Barcode.ContactInfo): DetectedCodeAction.CreateContact? {
    val phones = ArrayList<String>(contact.phones.size)
    for (phone in contact.phones) phones += phone.number ?: return null
    val emails = ArrayList<String>(contact.emails.size)
    for (email in contact.emails) emails += email.address ?: return null
    return validatedContactAction(contact.name?.formattedName, phones, emails)
}

private fun validatedCalendarAction(
    event: Barcode.CalendarEvent,
): DetectedCodeAction.CreateCalendarEvent? =
    validatedCalendarAction(event.summary, event.start, event.end)

private fun calendarDateTimeMillis(value: Barcode.CalendarDateTime): Long? =
    try {
        LocalDateTime.of(
            value.year,
            value.month,
            value.day,
            value.hours,
            value.minutes,
            value.seconds,
        ).atZone(if (value.isUtc) ZoneOffset.UTC else ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    } catch (_: DateTimeException) {
        null
    } catch (_: ArithmeticException) {
        null
    }

internal fun sanitizeTextExportFileName(baseName: String): String {
    val maxStemLength = MAX_TEXT_EXPORT_FILE_NAME_LENGTH - TEXT_EXPORT_SUFFIX.length
    val stem = StringBuilder(maxStemLength)
    var previousUnderscore = false
    baseName.trim().forEach { character ->
        if (stem.length >= maxStemLength) return@forEach
        val safe =
            when {
                character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' ->
                    character
                character == '-' || character == '_' -> character
                else -> '_'
            }
        if (safe != '_' || !previousUnderscore) stem.append(safe)
        previousUnderscore = safe == '_'
    }
    val cleaned = stem.toString().trim('_', '-')
    return "${cleaned.ifEmpty { "SeliaScan" }}$TEXT_EXPORT_SUFFIX"
}

internal fun writeDocumentTextUtf8(
    output: OutputStream,
    text: String,
    isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
) {
    require(text.isNotEmpty() && text.length <= MAX_DOCUMENT_TEXT_CHARACTERS) {
        "Document text is empty or too large"
    }
    if (isCancelled()) throw CancellationException("Text export was cancelled")
    val bytes = strictUtf8Bytes(text) ?: throw IllegalArgumentException("Document text is invalid")
    if (isCancelled()) throw CancellationException("Text export was cancelled")
    output.write(bytes)
    output.flush()
    if (isCancelled()) throw CancellationException("Text export was cancelled")
}

internal fun writeDocumentTextToDestination(
    text: String,
    openOutputStream: (mode: String) -> OutputStream?,
    isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
): Boolean {
    val output = openOutputStream("wt") ?: return false
    output.use { writeDocumentTextUtf8(it, text, isCancelled) }
    return true
}

internal fun isSafeTextExportDestination(
    scheme: String?,
    authority: String?,
    path: String?,
    query: String?,
    fragment: String?,
    uriLength: Int,
    mimeType: String?,
    writeGranted: Boolean,
): Boolean =
    scheme == "content" &&
        !authority.isNullOrBlank() &&
        authority.length <= 255 &&
        authority.all(Char::isSafeContentAuthorityCharacter) &&
        !path.isNullOrBlank() &&
        path.startsWith('/') &&
        path.length <= 2_048 &&
        path.none(Char::isUnsafeUriDisplayCharacter) &&
        query == null &&
        fragment == null &&
        uriLength in 1..4_096 &&
        writeGranted &&
        (mimeType == null || mimeType.equals("text/plain", ignoreCase = true))

private fun Char.isSafeContentAuthorityCharacter(): Boolean =
    this in 'a'..'z' ||
        this in 'A'..'Z' ||
        this in '0'..'9' ||
        this == '.' ||
        this == '_' ||
        this == '-'

private fun Char.isUnsafeUriDisplayCharacter(): Boolean =
    isISOControl() ||
        when (Character.getType(this)) {
            Character.FORMAT.toInt(),
            Character.LINE_SEPARATOR.toInt(),
            Character.PARAGRAPH_SEPARATOR.toInt(),
            -> true
            else -> false
        }

private fun strictUtf8Bytes(value: String): ByteArray? =
    try {
        val encoded =
            StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value))
        ByteArray(encoded.remaining()).also(encoded::get)
    } catch (_: CharacterCodingException) {
        null
    }

private fun decodeStrictUtf8(bytes: ByteArray): String? =
    try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

internal class DocumentActionProcessor(private val context: Context) {
    suspend fun extractOcr(
        pages: List<File>,
        script: OcrScript = OcrScript.Auto,
    ): DocumentOcrSnapshot {
        require(pages.size in 1..MAX_SCAN_PAGES) { "OCR page count is invalid" }
        val appLanguageTag = context.resources.configuration.locales.get(0)?.toLanguageTag()
        val recognizer = textRecognizer(resolveOcrScript(script, appLanguageTag))
        return try {
            val pageTexts = ArrayList<String>(pages.size)
            val elements = ArrayList<OcrElement>()
            var textCharacters = 0
            var elementCharacters = 0
            var truncated = false
            for ((index, page) in pages.withIndex()) {
                currentCoroutineContext().ensureActive()
                val image = inputImage(page)
                val result = recognizer.process(image).awaitResult()
                if (strictUtf8Bytes(result.text) == null) throw IOException("OCR text is invalid")
                val pageText =
                    boundedTextPrefix(result.text, MAX_DOCUMENT_TEXT_CHARACTERS - textCharacters)
                pageTexts += pageText
                textCharacters += pageText.length
                if (pageText.length != result.text.length) truncated = true
                for (block in result.textBlocks) {
                    for (line in block.lines) {
                        for (element in line.elements) {
                            val value = element.text
                            if (value.isBlank() || strictUtf8Bytes(value) == null) continue
                            if (value.length > MAX_DOCUMENT_TEXT_CHARACTERS - elementCharacters) {
                                truncated = true
                                continue
                            }
                            val box = element.boundingBox ?: continue
                            val bounds =
                                normalizedRect(
                                    box.left,
                                    box.top,
                                    box.right,
                                    box.bottom,
                                    image.width,
                                    image.height,
                                ) ?: continue
                            elements += OcrElement(index, value, bounds)
                            elementCharacters += value.length
                        }
                    }
                }
            }
            DocumentOcrSnapshot(pageTexts, elements, truncated)
        } finally {
            recognizer.close()
        }
    }

    private fun textRecognizer(script: OcrScript): TextRecognizer =
        when (script) {
            OcrScript.Auto -> error("OCR script must be resolved")
            OcrScript.Latin -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            OcrScript.Chinese ->
                TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        }

    suspend fun detectCodes(page: File): DocumentActionOutput.Codes {
        val scanner = BarcodeScanning.getClient()
        return try {
            val image = inputImage(page)
            buildDetectedCodes(
                scanner.process(image).awaitResult().asSequence().mapNotNull { barcode ->
                    val box = barcode.boundingBox
                    validatedDetectedCode(
                        rawValue = barcode.rawValue,
                        displayValue = barcode.displayValue,
                        rawBytes = barcode.rawBytes,
                        isQrCode = barcode.format == Barcode.FORMAT_QR_CODE,
                        typedUrl = null,
                        bounds =
                            box?.let {
                                normalizedRect(
                                    it.left,
                                    it.top,
                                    it.right,
                                    it.bottom,
                                    image.width,
                                    image.height,
                                )
                            },
                        typedAction = validatedBarcodeAction(barcode),
                    )
                },
            )
        } finally {
            scanner.close()
        }
    }

    private fun inputImage(file: File): InputImage {
        val page = validatedDocumentActionPage(file)
        return InputImage.fromFilePath(context, Uri.fromFile(page))
    }
}

internal fun validatedDocumentActionPage(file: File): File {
    val page = file.absoluteFile
    if (
        page.canonicalFile != page ||
            !page.isFile ||
            page.length() !in 1..MAX_DOCUMENT_ACTION_IMAGE_BYTES
    ) {
        throw IOException("Scan page is unavailable")
    }
    val dimensions = readJpegDimensions(page)
    if (
        dimensions.width > MAX_IMAGE_EXPORT_DIMENSION ||
            dimensions.height > MAX_IMAGE_EXPORT_DIMENSION ||
            dimensions.width.toLong() * dimensions.height > MAX_IMAGE_EXPORT_PIXELS
    ) {
        throw IOException("Scan page dimensions are invalid")
    }
    return page
}

private fun boundedTextPrefix(value: String, maxCharacters: Int): String {
    if (value.length <= maxCharacters) return value
    if (maxCharacters == 0) return ""
    val end =
        if (
            value[maxCharacters - 1].isHighSurrogate() &&
                value.getOrNull(maxCharacters)?.isLowSurrogate() == true
        ) {
            maxCharacters - 1
        } else {
            maxCharacters
        }
    return value.substring(0, end)
}

private val taskCompletionExecutor = Executor(Runnable::run)

internal suspend fun <T> Task<T>.awaitResult(): T {
    val outcome =
        suspendCoroutine<Result<T>> { continuation ->
            addOnCompleteListener(taskCompletionExecutor) { completed ->
                continuation.resume(
                    when {
                        completed.isCanceled ->
                            Result.failure(CancellationException("ML task cancelled"))
                        completed.isSuccessful -> Result.success(completed.result)
                        else ->
                            Result.failure(
                                completed.exception
                                    ?: IllegalStateException("ML task failed without an exception"),
                            )
                    },
                )
            }
        }
    currentCoroutineContext().ensureActive()
    return outcome.getOrThrow()
}
