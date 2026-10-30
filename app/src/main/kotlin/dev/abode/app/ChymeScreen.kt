package dev.abode.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.abode.ai.ChymeClient
import dev.abode.ai.ChymeConfig
import dev.abode.ai.ChymeProvider
import dev.abode.ai.ChymeStore
import kotlinx.coroutines.launch

/**
 * Where Chyme is pointed at a key and an endpoint.
 *
 * The presets are prefills, not switches: choosing one writes its endpoint and
 * model into the fields, and both stay editable afterwards, because a key you
 * own is often a key pointed somewhere else — a gateway, a proxy, a model
 * server on your own machine.
 */
@Composable
fun ChymeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { ChymeStore(context) }
    val scope = rememberCoroutineScope()

    val saved = remember { store.load() }
    var provider by remember { mutableStateOf(saved.provider) }
    var endpoint by remember { mutableStateOf(saved.endpoint) }
    var model by remember { mutableStateOf(saved.model) }
    var apiKey by remember { mutableStateOf(saved.apiKey) }
    var status by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var findingModels by remember { mutableStateOf(false) }

    fun currentConfig() = ChymeConfig(
        provider = provider,
        endpoint = endpoint,
        model = model,
        apiKey = apiKey,
    )

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(text = "Chyme", style = MaterialTheme.typography.headlineSmall)
            }

            Text(
                text = "The AI actions on the keyboard send your text to the endpoint you " +
                    "choose here. Abode has no server of its own and ships no key.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Text("Provider", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (option in ChymeProvider.values()) {
                    val choose = {
                        provider = option
                        // The preset's values are the starting point; a custom
                        // endpoint keeps whatever is already typed.
                        if (option != ChymeProvider.CUSTOM) {
                            endpoint = option.endpoint
                            model = option.model
                        }
                        status = ""
                    }
                    // A selected/unselected pair of buttons rather than a chip:
                    // FilterChip is still experimental in some Material 3
                    // versions, and an opt-in annotation is not worth it here.
                    if (provider == option) {
                        Button(onClick = choose) { Text(option.label) }
                    } else {
                        OutlinedButton(onClick = choose) { Text(option.label) }
                    }
                }
            }

            OutlinedTextField(
                value = endpoint,
                onValueChange = { endpoint = it; status = "" },
                label = { Text("Endpoint") },
                singleLine = true,
                placeholder = { Text("https://\u2026/v1/chat/completions") },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = model,
                onValueChange = { model = it; status = "" },
                label = { Text("Model") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it; status = "" },
                label = { Text("API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        store.save(currentConfig())
                        status = "Saved"
                    },
                ) {
                    Text("Save")
                }
                OutlinedButton(
                    enabled = !findingModels && currentConfig().isConfigured,
                    onClick = {
                        findingModels = true
                        status = "Looking for models\u2026"
                        val config = currentConfig()
                        scope.launch {
                            runCatching { ChymeClient.models(config) }
                                .onSuccess { found ->
                                    models = found
                                    status = if (found.isEmpty()) {
                                        "That endpoint listed no models"
                                    } else {
                                        "${found.size} models \u2014 tap one to use it"
                                    }
                                }
                                .onFailure { status = it.message ?: "Could not list models" }
                            findingModels = false
                        }
                    },
                ) {
                    Text(if (findingModels) "Looking\u2026" else "Find models")
                }
                OutlinedButton(
                    enabled = !testing && currentConfig().isConfigured,
                    onClick = {
                        testing = true
                        status = "Testing\u2026"
                        val config = currentConfig()
                        scope.launch {
                            status = runCatching {
                                val reply = ChymeClient.complete(
                                    config,
                                    "Reply with the single word: OK",
                                )
                                "Reachable: ${reply.take(60)}"
                            }.getOrElse { it.message ?: "Request failed" }
                            testing = false
                        }
                    },
                ) {
                    Text(if (testing) "Testing\u2026" else "Test")
                }
            }

            if (status.isNotEmpty()) {
                Text(text = status, style = MaterialTheme.typography.bodyMedium)
            }

            // The list the endpoint returned. Tapping one writes it into the model
            // field, so a name that the provider actually serves is one tap away
            // instead of a guess typed from memory.
            if (models.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (id in models.take(24)) {
                        if (id == model) {
                            Button(onClick = { model = id }) { Text(id) }
                        } else {
                            OutlinedButton(onClick = { model = id }) { Text(id) }
                        }
                    }
                }
            }

            Card {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Where the key is kept", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "In Abode's own preferences on this device, unencrypted. " +
                            "That is fine for a key you can revoke from the provider's " +
                            "dashboard, and it is why there is no default key: if this " +
                            "device is shared, set the key only on a device you keep.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            TextButton(onClick = onBack) { Text("Back to setup") }
        }
    }
}
