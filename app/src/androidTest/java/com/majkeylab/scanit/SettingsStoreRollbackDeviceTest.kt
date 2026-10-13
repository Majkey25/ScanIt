package com.majkeylab.scanit

import android.content.Context
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsStoreRollbackDeviceTest {
    @Test
    fun failedPdfTreeCommitRestoresBothNativePreferences() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.majkeylab.scanit.internal")
        val name = "pdf-tree-rollback-${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val rejected = object : SharedPreferences by preferences {
            override fun edit(): SharedPreferences.Editor {
                val editor = preferences.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String, value: String?): SharedPreferences.Editor {
                        editor.putString(key, value)
                        return this
                    }

                    override fun commit(): Boolean {
                        // A failed native commit also changes the in-memory preferences.
                        editor.apply()
                        return false
                    }
                }
            }
        }
        val store = SettingsStore(preferences, "Scanned document")
        val failingStore = SettingsStore(rejected, "Scanned document")
        val original = "content://docs/tree/original"
        val pending = "content://docs/tree/pending"
        val previous: List<Pair<String?, String?>> = listOf(original to pending, null to null)
        val changes: List<Pair<String?, String?>> =
            listOf(null to null, "content://docs/tree/new" to original)
        try {
            for ((previousCurrent, previousPending) in previous) {
                store.savePdfTreeUris(previousCurrent, previousPending)
                for ((current, nextPending) in changes) {
                    assertThrows(IOException::class.java) {
                        failingStore.savePdfTreeUris(current, nextPending)
                    }
                    assertEquals(previousCurrent, canonicalPdfTreeUri(store))
                    assertEquals(previousPending, store.pendingPdfTreeUri())
                    assertEquals(previousCurrent, store.load().pdfTreeUri)
                }
            }
        } finally {
            assertTrue(preferences.edit().clear().commit())
            assertTrue(context.deleteSharedPreferences(name))
        }
    }
}
