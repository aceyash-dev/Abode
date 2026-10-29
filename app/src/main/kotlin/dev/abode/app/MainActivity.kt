package dev.abode.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.abode.app.theme.ThemeEditorScreen
import dev.abode.core.ui.OnkTheme
import dev.abode.voice.CapabilityProbe

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OnkTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var showAppearance by remember { mutableStateOf(false) }

                    if (showAppearance) {
                        ThemeEditorScreen(onBack = { showAppearance = false })
                    } else {
                        StatusScreen(onCustomise = { showAppearance = true })
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusScreen(onCustomise: () -> Unit) {
    val context = LocalContext.current
    val enabled = remember { isAbodeEnabled(context) }
    val capabilities = remember { CapabilityProbe.probe(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = "Abode", style = MaterialTheme.typography.displaySmall)
        Text(
            text = "Keyboard with AI snippets, clipboard history and tiered voice input.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Card {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Status", style = MaterialTheme.typography.titleMedium)
                Text(if (enabled) "Keyboard is enabled in system settings." else "Keyboard is not enabled yet.")
                Text("Voice tier: ${capabilities.recommended.name}")
                Text(capabilities.reason, style = MaterialTheme.typography.bodySmall)
                Text(
                    "Device: ${capabilities.totalRamGb.round1()} GB RAM, " +
                        "${capabilities.cores} cores, ${capabilities.freeDiskMb} MB free",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Button(
            onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Open keyboard settings")
        }

        OutlinedButton(onClick = onCustomise, modifier = Modifier.fillMaxWidth()) {
            Text("Appearance — colours, font, shape")
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Clipboard history and the AI sheet land in M2 to M4. See README for the roadmap.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun Float.round1(): String {
    val rounded = kotlin.math.round(this * 10f) / 10f
    return rounded.toString()
}

private fun isAbodeEnabled(context: Context): Boolean {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    return imm.enabledInputMethodList.any { it.packageName == context.packageName }
}
