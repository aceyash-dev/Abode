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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.abode.app.theme.ThemeEditorScreen
import dev.abode.core.ui.AbodeTheme
import dev.abode.voice.CapabilityProbe
import compose.icons.TablerIcons
import compose.icons.tablericons.Keyboard
import compose.icons.tablericons.Microphone
import compose.icons.tablericons.Palette

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AbodeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var showAppearance by remember { mutableStateOf(false) }
                    var showChyme by remember { mutableStateOf(false) }

                    when {
                        showAppearance -> ThemeEditorScreen(onBack = { showAppearance = false })
                        showChyme -> ChymeScreen(onBack = { showChyme = false })
                        else -> SetupScreen(
                            onCustomise = { showAppearance = true },
                            onChyme = { showChyme = true },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The setup flow, in the order a person has to do it: turn Abode on, make it the
 * keyboard, then try it. Each step says what it is for and what is still missing,
 * and the steps re-check themselves when the person comes back from system
 * settings, so the screen never claims something the system has since undone.
 */
@Composable
private fun SetupScreen(onCustomise: () -> Unit, onChyme: () -> Unit) {
    val context = LocalContext.current

    var enabled by remember { mutableStateOf(isAbodeEnabled(context)) }
    var selected by remember { mutableStateOf(isAbodeSelected(context)) }
    var showDetails by remember { mutableStateOf(false) }
    var tryText by remember { mutableStateOf("") }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        enabled = isAbodeEnabled(context)
        selected = isAbodeSelected(context)
    }

    val allDone = enabled && selected

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = TablerIcons.Keyboard,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Text(text = "Abode", style = MaterialTheme.typography.displaySmall)
        }

        Text(
            text = if (allDone) {
                "You're set up. Try typing below, or change how it looks."
            } else {
                "Three steps and you're typing with Abode."
            },
            style = MaterialTheme.typography.bodyMedium,
        )

        Card {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SetupStep(
                    number = "1",
                    title = "Turn on Abode",
                    detail = "Android needs your permission before any keyboard can be used.",
                    done = enabled,
                ) {
                    Button(onClick = { openInputMethodSettings(context) }) {
                        Text("Open settings")
                    }
                }

                SetupStep(
                    number = "2",
                    title = "Make Abode your keyboard",
                    detail = if (enabled) {
                        "Choose Abode from the list that appears."
                    } else {
                        "Available once step 1 is done."
                    },
                    done = selected,
                ) {
                    Button(
                        onClick = { showKeyboardPicker(context) },
                        enabled = enabled,
                    ) {
                        Text("Choose keyboard")
                    }
                }

                SetupStep(
                    number = "3",
                    title = "Try it",
                    detail = "Type below to check it works in a real text field.",
                    done = false,
                ) {
                    OutlinedTextField(
                        value = tryText,
                        onValueChange = { tryText = it },
                        placeholder = { Text("Tap here to type") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        OutlinedButton(onClick = onCustomise, modifier = Modifier.fillMaxWidth()) {
            Icon(TablerIcons.Palette, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Appearance: colours, font and shape")
        }

        OutlinedButton(onClick = onChyme, modifier = Modifier.fillMaxWidth()) {
            Text("Chyme: AI key and endpoint")
        }

        OutlinedButton(
            onClick = { openSubtypeSettings(context) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Languages: choose what the globe cycles")
        }

        // Device and voice facts are for troubleshooting, not for the first screen.
        TextButton(onClick = { showDetails = !showDetails }) {
            Text(if (showDetails) "Hide details" else "Voice and device details")
        }
        if (showDetails) {
            VoiceDetails(context)
        }
    }
}

@Composable
private fun SetupStep(
    number: String,
    title: String,
    detail: String,
    done: Boolean,
    action: @Composable () -> Unit,
) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = if (done) "✓" else number,
            style = MaterialTheme.typography.titleMedium,
            color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = if (done) "$title (done)" else title,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(detail, style = MaterialTheme.typography.bodySmall)
            if (!done) {
                Spacer(Modifier.height(8.dp))
                action()
            }
        }
    }
}

@Composable
private fun VoiceDetails(context: Context) {
    val capabilities = remember { CapabilityProbe.probe(context) }
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = TablerIcons.Microphone,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("Voice: ${capabilities.recommended.name}", style = MaterialTheme.typography.bodyMedium)
            }
            Text(capabilities.reason, style = MaterialTheme.typography.bodySmall)
            Text(
                "Device: ${capabilities.totalRamGb.round1()} GB RAM, " +
                    "${capabilities.cores} cores, ${capabilities.freeDiskMb} MB free",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun openInputMethodSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/**
 * The subtypes screen for Abode specifically.
 *
 * Android owns the list of languages a keyboard offers, and the only supported
 * way to change it is that screen. Passing the IME id opens the list filtered to
 * Abode's subtypes rather than every keyboard's, which is the difference between
 * a language picker and a settings maze.
 */
private fun openSubtypeSettings(context: Context) {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    val id = imm?.enabledInputMethodList?.firstOrNull { it.packageName == context.packageName }?.id
    val intent = if (id != null) {
        Intent(Settings.ACTION_INPUT_METHOD_SUBTYPE_SETTINGS)
            .putExtra(Settings.EXTRA_INPUT_METHOD_ID, id)
    } else {
        // Not enabled yet: the subtype screen would be empty, so send them to
        // step 1 instead of to a list with nothing in it.
        Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
    }
    runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun showKeyboardPicker(context: Context) {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
    imm.showInputMethodPicker()
}

private fun Float.round1(): String {
    val rounded = kotlin.math.round(this * 10f) / 10f
    return rounded.toString()
}

private fun isAbodeEnabled(context: Context): Boolean {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return false
    return imm.enabledInputMethodList.any { it.packageName == context.packageName }
}

/**
 * The system stores the active keyboard as "package/service". Reading it is
 * allowed to any app, and it is the only reliable way to know whether Abode is
 * the one in use rather than merely installed.
 */
private fun isAbodeSelected(context: Context): Boolean {
    val active = runCatching {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
    }.getOrNull().orEmpty()
    return active.startsWith(context.packageName + "/")
}
