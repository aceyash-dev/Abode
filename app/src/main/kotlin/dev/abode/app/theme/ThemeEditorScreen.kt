package dev.abode.app.theme

import android.graphics.Typeface
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.abode.core.common.theme.KeyShape
import dev.abode.core.common.theme.KeyboardTheme
import dev.abode.core.common.theme.ThemePresets
import dev.abode.data.ThemePreferences
import java.io.File
import kotlinx.coroutines.launch

/**
 * Every visual property of the keyboard, editable: six colour roles through a
 * full HSV picker, a font imported from any file on the device, and the geometry
 * that decides how the keys sit.
 *
 * Edits are saved as they happen, so the keyboard behind the settings sheet is
 * already using the new look when the user goes back to it.
 */
@Composable
fun ThemeEditorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember { ThemePreferences(context) }
    val scope = rememberCoroutineScope()

    val stored by preferences.theme.collectAsState(initial = KeyboardTheme())
    var draft by remember { mutableStateOf<KeyboardTheme?>(null) }
    val theme = draft ?: stored

    var pickingSeed by remember { mutableStateOf(false) }
    var fontError by remember { mutableStateOf<String?>(null) }

    // The system back gesture should leave this screen, not close the app.
    BackHandler(onBack = onBack)

    fun update(next: KeyboardTheme) {
        draft = next
        scope.launch { preferences.save(next) }
    }

    val fontPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val target = File(context.filesDir, "keyboard_font_${System.currentTimeMillis()}.ttf")
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }.isSuccess
        // Validate before saving: a file that is not a font must not be able to
        // take the keyboard down on the next keystroke. Say why it was refused,
        // because a silent no-op looks like a broken button.
        val usable = copied && runCatching { Typeface.createFromFile(target) }.isSuccess
        if (usable) {
            fontError = null
            update(theme.copy(fontPath = target.absolutePath))
        } else {
            target.delete()
            fontError = "That file isn't a usable font. Try a .ttf or .otf file."
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Appearance", style = MaterialTheme.typography.titleLarge)
        }

        // A real text field: the point of a theme is how it types, and the only
        // way to check that without leaving the app is to type in it here.
        var testText by remember { mutableStateOf("") }
        OutlinedTextField(
            value = testText,
            onValueChange = { testText = it },
            label = { Text("Test the keyboard") },
            placeholder = { Text("Tap here and type with Abode") },
            modifier = Modifier.fillMaxWidth(),
        )

        ThemePreview(theme)

        // Chips show which choice is live. An unmarked chip reads as "not chosen"
        // to nobody, and people keep tapping a preset to see whether it changed.
        Text("Presets", style = MaterialTheme.typography.titleSmall)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ThemePresets.all.forEach { preset ->
                FilterChip(
                    selected = preset.id == theme.id,
                    onClick = { update(preset) },
                    label = { Text(preset.name) },
                )
            }
        }

        Text("Key shape", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyShape.entries.forEach { shape ->
                FilterChip(
                    selected = theme.keyShape == shape,
                    onClick = { update(theme.copy(keyShape = shape)) },
                    label = { Text(if (shape == KeyShape.HEXAGON) "Hexagon" else "Rounded") },
                )
            }
        }

        Text("Autocorrect", style = MaterialTheme.typography.titleSmall)
        Text(
            text = when (theme.autocorrectSensitivity) {
                0 -> "Off — nothing is corrected"
                2 -> "Aggressive — corrects short words too"
                else -> "Normal — corrects words of four letters or more"
            },
            style = MaterialTheme.typography.bodySmall,
        )
        Slider(
            value = theme.autocorrectSensitivity.toFloat(),
            onValueChange = { update(theme.copy(autocorrectSensitivity = it.toInt())) },
            valueRange = 0f..2f,
            steps = 1,
        )

        Text("Feedback", style = MaterialTheme.typography.titleSmall)
        ToggleRow("Haptic feedback", theme.haptics) { update(theme.copy(haptics = it)) }
        ToggleRow("Key sounds", theme.keySound) { update(theme.copy(keySound = it)) }
        ToggleRow("Double space ends a sentence", theme.doubleSpacePeriod) {
            update(theme.copy(doubleSpacePeriod = it))
        }

        // One colour makes the whole keyboard. The seed is the only thing to pick;
        // the six surfaces are derived from it, and shown below so the result is
        // visible without opening anything.
        Text("Colour", style = MaterialTheme.typography.titleSmall)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { pickingSeed = true }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(theme.seedColor)),
            )
            Column {
                Text("Seed colour", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "#%06X".format(0xFFFFFF and theme.seedColor),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        ToggleRow("Dark keyboard", theme.isDark) { dark ->
            update(KeyboardTheme.fromSeed(theme.seedColor, dark, theme))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                theme.background,
                theme.keyBackground,
                theme.keyPressed,
                theme.keyLabel,
                theme.popupBackground,
                theme.accent,
            ).forEach { colour ->
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(colour)),
                )
            }
        }

        Text("Font", style = MaterialTheme.typography.titleSmall)
        Text(
            text = theme.fontPath?.let { File(it).name }
                ?: when (theme.fontFamily) {
                    KeyboardTheme.FONT_SERIF -> "Serif"
                    KeyboardTheme.FONT_MONO -> "Monospace"
                    else -> "System font"
                },
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((family, label) in listOf(
                KeyboardTheme.FONT_SANS to "Default",
                KeyboardTheme.FONT_SERIF to "Serif",
                KeyboardTheme.FONT_MONO to "Mono",
            )) {
                val selected = theme.fontPath == null && theme.fontFamily == family
                if (selected) {
                    Button(onClick = { update(theme.copy(fontFamily = family, fontPath = null)) }) {
                        Text(label)
                    }
                } else {
                    OutlinedButton(onClick = { update(theme.copy(fontFamily = family, fontPath = null)) }) {
                        Text(label)
                    }
                }
            }
        }
        fontError?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { fontPicker.launch(arrayOf("*/*")) }) {
                Text("Choose font file")
            }
            if (theme.fontPath != null) {
                TextButton(onClick = { update(theme.copy(fontPath = null)) }) {
                    Text("Reset")
                }
            }
        }

        Text("Shape", style = MaterialTheme.typography.titleSmall)
        LabelledSlider(
            label = "Corner radius",
            value = theme.cornerRadiusDp,
            range = 0f..20f,
            onChange = { update(theme.copy(cornerRadiusDp = it)) },
        )
        LabelledSlider(
            label = "Key gap",
            value = theme.keyGapDp,
            range = 0f..10f,
            onChange = { update(theme.copy(keyGapDp = it)) },
        )
        LabelledSlider(
            label = "Key height",
            value = theme.rowHeightDp,
            range = 36f..72f,
            onChange = { update(theme.copy(rowHeightDp = it)) },
        )

        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Done") }
    }

    if (pickingSeed) {
        ColorPickerDialog(
            initial = theme.seedColor,
            onDismiss = { pickingSeed = false },
            onConfirm = { seed ->
                update(KeyboardTheme.fromSeed(seed, theme.isDark, theme))
                pickingSeed = false
            },
        )
    }
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun LabelledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text("%.1f".format(value), style = MaterialTheme.typography.bodySmall)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

/** A live sample drawn with the current theme, so changes are visible immediately. */
@Composable
private fun ThemePreview(theme: KeyboardTheme) {
    val family = remember(theme.fontPath, theme.fontFamily) {
        val path = theme.fontPath
        val file = path?.let { File(it) }
        val usable = file != null && file.exists() &&
            runCatching { Typeface.createFromFile(file) }.isSuccess
        when {
            usable && file != null -> FontFamily(Font(file))
            theme.fontFamily == KeyboardTheme.FONT_SERIF -> FontFamily.Serif
            theme.fontFamily == KeyboardTheme.FONT_MONO -> FontFamily.Monospace
            else -> FontFamily.Default
        }
    }

    val keyShape = remember(theme.keyShape, theme.cornerRadiusDp) {
        if (theme.keyShape == KeyShape.HEXAGON) hexagonShape() else RoundedCornerShape(theme.cornerRadiusDp.dp)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape((theme.cornerRadiusDp + 6).dp))
            .background(Color(theme.background))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(theme.keyGapDp.dp),
    ) {
        listOf("q w e r t y u i o p", "a s d f g h j k l").forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(theme.keyGapDp.dp)) {
                row.split(" ").forEach { letter ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height((theme.rowHeightDp * 0.62f).dp)
                            .clip(keyShape)
                            .background(Color(theme.keyBackground)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = letter,
                            color = Color(theme.keyLabel),
                            fontFamily = family,
                            fontSize = 15.sp,
                        )
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(theme.keyGapDp.dp)) {
            listOf("space" to 4f, "return" to 2f).forEach { (label, weight) ->
                Box(
                    modifier = Modifier
                        .weight(weight)
                        .height((theme.rowHeightDp * 0.62f).dp)
                        .clip(keyShape)
                        .background(Color(theme.keyPressed)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        color = Color(theme.keyLabel),
                        fontFamily = family,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

/**
 * The same flat-top hexagon KeyGridView draws, so the preview and the keyboard
 * agree about what a key looks like.
 */
private fun hexagonShape(): Shape = GenericShape { size, _ ->
    val w = size.width
    val h = size.height
    moveTo(w * 0.17f, h / 2f)
    lineTo(w * 0.33f, 0f)
    lineTo(w * 0.67f, 0f)
    lineTo(w * 0.83f, h / 2f)
    lineTo(w * 0.67f, h)
    lineTo(w * 0.33f, h)
    close()
}
