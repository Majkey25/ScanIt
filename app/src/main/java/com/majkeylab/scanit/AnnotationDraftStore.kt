package com.majkeylab.scanit

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

private const val ANNOTATION_DRAFT_FILE = "annotation-draft.json"
private const val ANNOTATION_DRAFT_STAGING = "annotation-draft.json.tmp"
private const val MAX_ANNOTATION_DRAFT_BYTES = 4 * 1024 * 1024

internal data class AnnotationDraft(
    val source: MarkEditorSource,
    val annotations: List<PageAnnotation>,
)

internal class AnnotationDraftStore(private val directory: File) {
    fun load(): AnnotationDraft? {
        val root = checkedDirectory(create = false) ?: return null
        val target = File(root, ANNOTATION_DRAFT_FILE)
        if (!Files.exists(target.toPath(), NOFOLLOW_LINKS)) return null
        requireRegularFile(target)
        val text = FileChannel.open(target.toPath(), READ, NOFOLLOW_LINKS).use { input ->
            val size = input.size()
            if (size !in 1..MAX_ANNOTATION_DRAFT_BYTES.toLong()) return null
            val buffer = ByteBuffer.allocate(size.toInt())
            while (buffer.hasRemaining()) {
                if (input.read(buffer) < 0) return null
            }
            if (input.read(ByteBuffer.allocate(1)) != -1) return null
            buffer.flip()
            try {
                StandardCharsets.UTF_8.newDecoder().decode(buffer).toString()
            } catch (_: CharacterCodingException) {
                return null
            }
        }
        return try {
            DraftJsonSyntax(text).check()
            val value = JSONObject(text)
            value.requireKeys("version", "source", "annotations")
            require(value.strictInt("version") == 1)
            val source = value.getJSONObject("source")
            source.requireKeys("cacheId", "entryId", "pageIndex")
            val identity = MarkEditorSource(
                source.strictString("cacheId"), source.strictString("entryId"), source.strictInt("pageIndex"),
            )
            require(identity.pageIndex < MAX_SCAN_PAGES)
            val values = value.getJSONArray("annotations")
            require(values.length() <= MAX_PAGE_ANNOTATIONS)
            var pointCount = 0
            val annotations = (0 until values.length()).map { index ->
                val annotation = values.getJSONObject(index)
                when (annotation.strictString("type")) {
                    "ink" -> {
                        annotation.requireKeys("type", "points", "color", "widthFraction", "highlighter")
                        val points = annotation.getJSONArray("points")
                        require(points.length() <= MAX_PAGE_ANNOTATION_POINTS - pointCount)
                        pointCount += points.length()
                        PageAnnotation.Ink(
                            points = (0 until points.length()).map { pointIndex ->
                                val point = points.getJSONObject(pointIndex)
                                point.requireKeys("x", "y", "pressure")
                                AnnotationPoint(point.fraction("x"), point.fraction("y"), point.fraction("pressure"))
                            },
                            color = annotation.strictInt("color"),
                            widthFraction = annotation.fraction("widthFraction"),
                            highlighter = requireNotNull(annotation.get("highlighter") as? Boolean),
                        )
                    }
                    "text" -> {
                        annotation.requireKeys("type", "value", "x", "y", "sizeFraction", "color")
                        PageAnnotation.Text(
                            value = annotation.strictString("value"),
                            x = annotation.fraction("x"),
                            y = annotation.fraction("y"),
                            sizeFraction = annotation.fraction("sizeFraction"),
                            color = annotation.strictInt("color"),
                        )
                    }
                    else -> throw IllegalArgumentException("Unknown annotation type")
                }
            }
            validatePageAnnotations(annotations)
            AnnotationDraft(identity, annotations)
        } catch (_: JSONException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun save(draft: AnnotationDraft) {
        require(draft.source.pageIndex in 0 until MAX_SCAN_PAGES) { "Annotation draft page is invalid" }
        require(draft.source.cacheId.length <= MAX_ANNOTATION_DRAFT_BYTES) { "Annotation draft source is too large" }
        validatePageAnnotations(draft.annotations)
        val annotations = JSONArray()
        draft.annotations.forEach { annotation ->
            annotations.put(when (annotation) {
                is PageAnnotation.Ink -> JSONObject()
                    .put("type", "ink")
                    .put("points", JSONArray().also { points ->
                        annotation.points.forEach { point ->
                            points.put(JSONObject().put("x", point.x).put("y", point.y).put("pressure", point.pressure))
                        }
                    })
                    .put("color", annotation.color)
                    .put("widthFraction", annotation.widthFraction)
                    .put("highlighter", annotation.highlighter)
                is PageAnnotation.Text -> JSONObject()
                    .put("type", "text")
                    .put("value", annotation.value)
                    .put("x", annotation.x)
                    .put("y", annotation.y)
                    .put("sizeFraction", annotation.sizeFraction)
                    .put("color", annotation.color)
            })
        }
        val json = JSONObject()
            .put("version", 1)
            .put("source", JSONObject()
                .put("cacheId", draft.source.cacheId)
                .put("entryId", draft.source.entryId)
                .put("pageIndex", draft.source.pageIndex))
            .put("annotations", annotations)
            .toString()
        val buffer = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(json))
        require(buffer.remaining() <= MAX_ANNOTATION_DRAFT_BYTES) { "Annotation draft is too large" }
        val root = requireNotNull(checkedDirectory(create = true))
        val target = File(root, ANNOTATION_DRAFT_FILE)
        val staging = File(root, ANNOTATION_DRAFT_STAGING)
        if (Files.exists(target.toPath(), NOFOLLOW_LINKS)) requireRegularFile(target)
        if (Files.exists(staging.toPath(), NOFOLLOW_LINKS)) requireRegularFile(staging)
        Files.deleteIfExists(staging.toPath())
        try {
            FileChannel.open(staging.toPath(), CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { output ->
                while (buffer.hasRemaining()) output.write(buffer)
                output.force(true)
            }
            Files.move(staging.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (failure: Exception) {
            try {
                Files.deleteIfExists(staging.toPath())
            } catch (cleanupFailure: Exception) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    fun clear() {
        val root = checkedDirectory(create = false) ?: return
        for (name in listOf(ANNOTATION_DRAFT_FILE, ANNOTATION_DRAFT_STAGING)) {
            val file = File(root, name)
            if (Files.exists(file.toPath(), NOFOLLOW_LINKS)) {
                requireRegularFile(file)
                Files.delete(file.toPath())
            }
        }
    }

    private fun checkedDirectory(create: Boolean): File? {
        val root = directory.absoluteFile
        if (generateSequence(root) { it.parentFile }.any {
                it.canonicalFile != it || Files.isSymbolicLink(it.toPath())
            }) {
            throw IOException("Annotation draft directory is unsafe")
        }
        if (!Files.exists(root.toPath(), NOFOLLOW_LINKS)) {
            if (!create) return null
            Files.createDirectories(root.toPath())
        }
        if (root.canonicalFile != root || !Files.isDirectory(root.toPath(), NOFOLLOW_LINKS)) {
            throw IOException("Annotation draft directory is unsafe")
        }
        return root
    }

    private fun requireRegularFile(file: File) {
        if (file.canonicalFile != file || !Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) {
            throw IOException("Annotation draft file is unsafe")
        }
    }
}

private fun JSONObject.requireKeys(vararg names: String) {
    require(length() == names.size && names.all(::has)) { "Annotation draft fields are invalid" }
}

private fun JSONObject.strictString(name: String): String = requireNotNull(get(name) as? String)

private fun JSONObject.strictInt(name: String): Int = when (val value = get(name)) {
    is Int -> value
    is Long -> {
        require(value in Int.MIN_VALUE..Int.MAX_VALUE)
        value.toInt()
    }
    else -> throw IllegalArgumentException("Annotation draft integer is invalid")
}

private fun JSONObject.fraction(name: String): Float {
    val value = requireNotNull(get(name) as? Number).toDouble()
    require(value.isFinite() && value in 0.0..1.0) { "Annotation draft geometry is invalid" }
    return value.toFloat()
}

// Android's JSONTokener accepts non-JSON syntax; check grammar and nesting before building its object tree.
private class DraftJsonSyntax(private val text: String) {
    private var offset = 0

    fun check() {
        value(0)
        whitespace()
        require(offset == text.length)
    }

    private fun value(depth: Int) {
        require(depth <= 5)
        whitespace()
        require(offset < text.length)
        when (text[offset]) {
            '{' -> {
                offset++
                val keys = HashSet<String>()
                if (take('}')) return
                do {
                    whitespace()
                    require(keys.add(string()))
                    require(take(':'))
                    value(depth + 1)
                } while (take(','))
                require(take('}'))
            }
            '[' -> {
                offset++
                if (take(']')) return
                do { value(depth + 1) } while (take(','))
                require(take(']'))
            }
            '"' -> string()
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            else -> {
                val number = requireNotNull(NUMBER.matchAt(text, offset))
                offset = number.range.last + 1
            }
        }
    }

    private fun string(): String {
        val start = offset
        require(offset < text.length && text[offset++] == '"')
        while (offset < text.length) {
            val character = text[offset++]
            if (character == '"') return JSONTokener(text.substring(start, offset)).nextValue() as String
            require(character >= ' ')
            if (character == '\\') {
                require(offset < text.length)
                when (text[offset++]) {
                    '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                    'u' -> repeat(4) {
                        require(offset < text.length && text[offset++] in "0123456789abcdefABCDEF")
                    }
                    else -> throw IllegalArgumentException("Invalid JSON escape")
                }
            }
        }
        throw IllegalArgumentException("Unterminated JSON string")
    }

    private fun literal(value: String) {
        require(text.startsWith(value, offset))
        offset += value.length
    }

    private fun take(character: Char): Boolean {
        whitespace()
        if (offset >= text.length || text[offset] != character) return false
        offset++
        return true
    }

    private fun whitespace() {
        while (offset < text.length && text[offset] in " \t\r\n") offset++
    }

    companion object {
        private val NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
    }
}
