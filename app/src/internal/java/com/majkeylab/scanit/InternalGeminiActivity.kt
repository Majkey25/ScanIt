package com.majkeylab.scanit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

class InternalGeminiActivity : ComponentActivity() {
    private val viewModel: InternalGeminiViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.state.collectAsState()
            MaterialTheme {
                InternalGeminiHarness(
                    state = state,
                    onSelectImage = viewModel::selectImage,
                    onSaveKey = viewModel::saveApiKey,
                    onClearKey = viewModel::clearApiKey,
                    onProcess = viewModel::process,
                )
            }
        }
    }
}

@Composable
private fun InternalGeminiHarness(
    state: InternalGeminiState,
    onSelectImage: (android.net.Uri?) -> Unit,
    onSaveKey: (String) -> Unit,
    onClearKey: () -> Unit,
    onProcess: () -> Unit,
) {
    var apiKey by remember { mutableStateOf("") }
    val imageLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent(), onSelectImage)

    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.internal_gemini_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(stringResource(R.string.internal_gemini_warning))
            }
            item {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it.take(INTERNAL_GEMINI_MAX_API_KEY_LENGTH) },
                    label = { Text(stringResource(R.string.internal_gemini_api_key)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            onSaveKey(apiKey)
                            apiKey = ""
                        },
                        enabled = apiKey.isNotBlank() && !state.busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.internal_gemini_save_key))
                    }
                    OutlinedButton(
                        onClick = onClearKey,
                        enabled = state.keyStored && !state.busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.internal_gemini_delete_key))
                    }
                }
                Text(
                    stringResource(
                        if (state.keyStored) {
                            R.string.internal_gemini_key_present
                        } else {
                            R.string.internal_gemini_key_missing
                        },
                    ),
                )
            }
            item {
                OutlinedButton(
                    onClick = { imageLauncher.launch("image/*") },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.internal_gemini_select_image))
                }
                if (state.selectedImage != null) {
                    Text(stringResource(R.string.internal_gemini_image_selected))
                }
            }
            item {
                Button(
                    onClick = onProcess,
                    enabled = state.selectedImage != null && state.keyStored && !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) {
                    Text(stringResource(R.string.internal_gemini_process))
                }
            }
            if (state.busy) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
            state.message?.let { message ->
                item { Text(stringResource(message), color = MaterialTheme.colorScheme.error) }
            }
            state.preview?.let { preview ->
                item {
                    Image(
                        bitmap = preview.asImageBitmap(),
                        contentDescription = stringResource(R.string.internal_gemini_preview),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 480.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
            item { Spacer(Modifier.height(4.dp)) }
        }
    }
}
