package com.majkeylab.scanit

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.CopyOption
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

internal const val MAX_SCAN_CACHE_BYTES = 1024L * 1024L * 1024L
internal const val MAX_RETAINED_SCAN_BYTES = 512L * 1024L * 1024L

@Volatile
internal var scanCacheBudget: ScanCacheBudget? = null

internal fun scanCacheOutput(file: File, append: Boolean = false): FileOutputStream =
    scanCacheBudget?.open(file, append) ?: FileOutputStream(file, append)

internal fun moveScanCacheFile(source: Path, target: Path, vararg options: CopyOption): Path {
    val budget = scanCacheBudget ?: return Files.move(source, target, *options)
    // A concurrent move could hide a closed file behind the recount's traversal position.
    return synchronized(budget) { Files.move(source, target, *options) }
}

internal fun cacheDirectoryBytes(directory: File): Long {
    var total = 0L
    Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
            if (!attributes.isRegularFile) {
                throw IOException("Cache contains an unsafe file")
            }
            // Directory enumeration can report stale sizes for open files on Windows.
            val bytes = Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
                .use { it.size() }
            if (bytes > Long.MAX_VALUE - total) throw IOException("Cache size overflow")
            total += bytes
            return FileVisitResult.CONTINUE
        }
    })
    return total
}

internal class ScanCacheBudget(cacheRoot: File, private val maxBytes: Long = MAX_SCAN_CACHE_BYTES) {
    private val configuredRoot = cacheRoot.absoluteFile.toPath().normalize()
    private val root = cacheRoot.canonicalFile.toPath()
    private var usedBytes = 0L
    private var openWriters = 0

    init {
        require(maxBytes > 0) { "Cache byte limit must be positive" }
    }

    @Synchronized
    fun open(file: File, append: Boolean = false): FileOutputStream {
        val path = file.absoluteFile.toPath().normalize()
        val relative = when {
            path.startsWith(configuredRoot) -> configuredRoot.relativize(path)
            path.startsWith(root) -> root.relativize(path)
            else -> {
                if (file.canonicalFile.toPath().startsWith(root)) {
                    throw IOException("Cache output uses an unexpected alias")
                }
                return FileOutputStream(file, append)
            }
        }
        val target = root.resolve(relative).toFile()
        if (target.canonicalFile != target) throw IOException("Cache output path is unsafe")
        val measured = cacheDirectoryBytes(root.toFile())
        // An open writer may have been unlinked. Keep its charge until every writer closes.
        usedBytes = if (openWriters == 0) measured else maxOf(usedBytes, measured)
        val output = BudgetedOutput(target, append)
        openWriters++
        return output
    }

    private inner class BudgetedOutput(file: File, append: Boolean) : FileOutputStream(file, append) {
        private var released = false

        override fun write(value: Int) = synchronized(this@ScanCacheBudget) {
            charge(1)
            super.write(value)
        }

        override fun write(bytes: ByteArray) = write(bytes, 0, bytes.size)

        override fun write(bytes: ByteArray, offset: Int, length: Int) =
            synchronized(this@ScanCacheBudget) {
                if (offset < 0 || length < 0 || offset > bytes.size - length) {
                    throw IndexOutOfBoundsException()
                }
                charge(length)
                super.write(bytes, offset, length)
            }

        private fun charge(length: Int) {
            if (released || !fd.valid()) throw IOException("Cache output is closed")
            if (length.toLong() > maxBytes - usedBytes) {
                throw IOException("Scan cache byte limit exceeded")
            }
            // Charge first: a failed native write may still have written a prefix.
            usedBytes += length
        }

        override fun close() = synchronized(this@ScanCacheBudget) {
            try {
                super.close()
            } finally {
                if (!released && !fd.valid()) {
                    released = true
                    openWriters--
                }
            }
        }
    }
}
