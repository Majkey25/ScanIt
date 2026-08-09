package com.majkeylab.scanit

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val INTERNAL_GEMINI_MAX_SOURCE_BYTES = 32L * 1024L * 1024L
private const val INTERNAL_GEMINI_INPUT_FILE = "internal-gemini-input"

internal data class InternalGeminiState(
    val selectedImage: Uri? = null,
    val preview: Bitmap? = null,
    val keyStored: Boolean = false,
    val busy: Boolean = false,
    val message: Int? = null,
)

@Throws(IOException::class)
internal fun copyInternalGeminiInput(
    input: InputStream,
    output: OutputStream,
    maxBytes: Long,
): Long {
    require(maxBytes > 0L) { "Maximum input size must be positive" }
    val buffer = ByteArray(8 * 1024)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read == -1) return total
        if (total > maxBytes - read) {
            throw IOException("Selected image is too large")
        }
        output.write(buffer, 0, read)
        total += read
    }
}

internal class InternalGeminiViewModel(application: Application) : AndroidViewModel(application) {
    private val keyStore = InternalGeminiKeyStore(application)
    private val mutableState = MutableStateFlow(InternalGeminiState(busy = true))
    val state: StateFlow<InternalGeminiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            mutableState.value =
                try {
                    val stored = withContext(Dispatchers.IO) { keyStore.load() != null }
                    InternalGeminiState(keyStored = stored)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    InternalGeminiState(message = R.string.internal_gemini_key_error)
                }
        }
    }

    fun selectImage(uri: Uri?) {
        if (mutableState.value.busy) return
        if (uri == null) return
        if (uri.scheme != "content") {
            mutableState.value = mutableState.value.copy(message = R.string.internal_gemini_image_error)
            return
        }
        mutableState.value =
            mutableState.value.copy(selectedImage = uri, preview = null, message = null)
    }

    fun saveApiKey(value: String) {
        if (mutableState.value.busy) return
        val normalized =
            try {
                normalizeInternalGeminiApiKey(value)
            } catch (_: IllegalArgumentException) {
                mutableState.value =
                    mutableState.value.copy(message = R.string.internal_gemini_key_invalid)
                return
            }
        runKeyOperation {
            keyStore.save(normalized)
            true
        }
    }

    fun clearApiKey() {
        if (mutableState.value.busy) return
        runKeyOperation {
            keyStore.clear()
            false
        }
    }

    fun process() {
        val current = mutableState.value
        val uri = current.selectedImage ?: return
        if (current.busy) return
        mutableState.value = current.copy(busy = true, preview = null, message = null)
        viewModelScope.launch {
            var inputFile: File? = null
            try {
                val apiKey = withContext(Dispatchers.IO) { keyStore.load() }
                if (apiKey == null) {
                    mutableState.value =
                        mutableState.value.copy(
                            keyStored = false,
                            busy = false,
                            message = R.string.internal_gemini_key_missing,
                        )
                    return@launch
                }
                val preview =
                    withContext(Dispatchers.IO) {
                        val file = internalInputFile()
                        inputFile = file
                        copySelectedImage(uri, file)
                        val jpeg = requestGeminiCleanup(file, apiKey)
                        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                            ?: throw IOException("Gemini preview could not be decoded")
                    }
                mutableState.value =
                    mutableState.value.copy(preview = preview, busy = false, message = null)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                mutableState.value =
                    mutableState.value.copy(
                        busy = false,
                        message = R.string.internal_gemini_process_error,
                    )
            } finally {
                inputFile?.let { file ->
                    withContext(NonCancellable + Dispatchers.IO) { file.delete() }
                }
            }
        }
    }

    private fun runKeyOperation(operationBlock: () -> Boolean) {
        val current = mutableState.value
        mutableState.value = current.copy(busy = true, message = null)
        viewModelScope.launch {
            try {
                val stored = withContext(Dispatchers.IO) { operationBlock() }
                mutableState.value = mutableState.value.copy(keyStored = stored, busy = false)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                mutableState.value =
                    mutableState.value.copy(
                        busy = false,
                        message = R.string.internal_gemini_key_error,
                    )
            }
        }
    }

    private fun copySelectedImage(uri: Uri, destination: File) {
        if (destination.exists() && !destination.delete()) {
            throw IOException("Old selected image could not be deleted")
        }
        val resolver = getApplication<Application>().contentResolver
        val input = resolver.openInputStream(uri) ?: throw IOException("Selected image could not be opened")
        try {
            val copied =
                input.use { source ->
                    FileOutputStream(destination).use { output ->
                        copyInternalGeminiInput(source, output, INTERNAL_GEMINI_MAX_SOURCE_BYTES)
                    }
                }
            if (copied <= 0L || destination.length() != copied) {
                throw IOException("Selected image is empty")
            }
        } catch (failure: Throwable) {
            destination.delete()
            throw failure
        }
    }

    private fun internalInputFile(): File {
        val cacheRoot = getApplication<Application>().cacheDir.canonicalFile
        val input = File(cacheRoot, INTERNAL_GEMINI_INPUT_FILE).absoluteFile
        if (input.canonicalFile != input || input.parentFile != cacheRoot) {
            throw IOException("Internal input path is unsafe")
        }
        return input
    }
}
