package com.majkeylab.scanit

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.security.MessageDigest

internal const val SECURITY_TARGET_PACKAGE = "com.majkeylab.scanit.internal"
internal const val SECURITY_AUTHORITY = "$SECURITY_TARGET_PACKAGE.storage-security-fixture"
internal const val SECURITY_ROOT_ID = "synthetic-root"
internal const val SECURITY_RW =
    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

internal fun securityTreeUri(): Uri =
    DocumentsContract.buildTreeDocumentUri(SECURITY_AUTHORITY, SECURITY_ROOT_ID)

internal fun securitySha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal enum class SecurityProviderMode {
    Stable, WrongIdAfterBytes, WrongMimeAfterBytes, NoDeleteAfterBytes, UnsafeNameAfterBytes,
    NotChildAfterBytes, SafeRenameAfterClose, PoisonIdentityOnSecondCreate, PoisonBytesOnSecondCreate,
    PoisonIdentityOnSecondRead, PoisonBytesOnSecondRead,
}
