package com.majkeylab.scanit

import android.app.Application
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

private const val PROVIDER_INPUT_DIRECTORY = ".provider-input-staging"

class ScanApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Application startup runs before imports; activity recreation must not clear active work.
        val staging = providerInputStagingDirectory(cacheDir)
        val orphans = staging.listFiles() ?: throw IOException("Input staging could not be listed")
        orphans.forEach { orphan ->
            if (!deleteTreeWithoutFollowingLinks(orphan)) {
                throw IOException("Orphaned input staging could not be removed")
            }
        }
    }
}

internal fun providerInputStagingDirectory(cacheRoot: File): File {
    val directory = File(cacheRoot.canonicalFile, PROVIDER_INPUT_DIRECTORY)
    if (!directory.exists() && !directory.mkdir()) {
        throw IOException("Input staging could not be created")
    }
    if (
        !Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS) ||
        directory.canonicalFile != directory
    ) {
        throw IOException("Input staging directory is unsafe")
    }
    return directory
}
