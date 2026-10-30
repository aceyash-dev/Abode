package dev.abode.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.abode.core.common.theme.KeyShape
import dev.abode.core.common.theme.KeyboardTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.themeDataStore by preferencesDataStore(name = "keyboard_theme")

/**
 * Persists the keyboard appearance.
 *
 * Flat preference keys rather than a serialised blob: a theme written by a newer
 * build with an extra field still loads on an older one, and a corrupt value
 * loses one colour instead of the whole theme.
 */
class ThemePreferences(private val context: Context) {

    val theme: Flow<KeyboardTheme> = context.themeDataStore.data.map { prefs ->
        val defaults = KeyboardTheme()
        KeyboardTheme(
            id = prefs[KEY_ID] ?: defaults.id,
            name = prefs[KEY_NAME] ?: defaults.name,
            background = prefs[KEY_BACKGROUND] ?: defaults.background,
            keyBackground = prefs[KEY_KEY_BACKGROUND] ?: defaults.keyBackground,
            keyPressed = prefs[KEY_KEY_PRESSED] ?: defaults.keyPressed,
            keyLabel = prefs[KEY_KEY_LABEL] ?: defaults.keyLabel,
            popupBackground = prefs[KEY_POPUP_BACKGROUND] ?: defaults.popupBackground,
            accent = prefs[KEY_ACCENT] ?: defaults.accent,
            fontPath = prefs[KEY_FONT_PATH]?.takeIf { it.isNotBlank() },
            fontFamily = prefs[KEY_FONT_FAMILY] ?: defaults.fontFamily,
            cornerRadiusDp = prefs[KEY_CORNER] ?: defaults.cornerRadiusDp,
            keyGapDp = prefs[KEY_GAP] ?: defaults.keyGapDp,
            rowHeightDp = prefs[KEY_ROW_HEIGHT] ?: defaults.rowHeightDp,
            autocorrectSensitivity = prefs[KEY_SENSITIVITY] ?: defaults.autocorrectSensitivity,
            haptics = prefs[KEY_HAPTICS] ?: defaults.haptics,
            keySound = prefs[KEY_SOUND] ?: defaults.keySound,
            doubleSpacePeriod = prefs[KEY_DOUBLE_SPACE] ?: defaults.doubleSpacePeriod,
            seedColor = prefs[KEY_SEED] ?: defaults.seedColor,
            isDark = prefs[KEY_DARK] ?: defaults.isDark,
            keyShape = prefs[KEY_SHAPE]?.let { value ->
                KeyShape.entries.firstOrNull { it.name == value }
            } ?: defaults.keyShape,
        )
    }

    suspend fun save(theme: KeyboardTheme) {
        context.themeDataStore.edit { prefs ->
            prefs[KEY_ID] = theme.id
            prefs[KEY_NAME] = theme.name
            prefs[KEY_BACKGROUND] = theme.background
            prefs[KEY_KEY_BACKGROUND] = theme.keyBackground
            prefs[KEY_KEY_PRESSED] = theme.keyPressed
            prefs[KEY_KEY_LABEL] = theme.keyLabel
            prefs[KEY_POPUP_BACKGROUND] = theme.popupBackground
            prefs[KEY_ACCENT] = theme.accent
            prefs[KEY_FONT_PATH] = theme.fontPath.orEmpty()
            prefs[KEY_FONT_FAMILY] = theme.fontFamily
            prefs[KEY_CORNER] = theme.cornerRadiusDp
            prefs[KEY_GAP] = theme.keyGapDp
            prefs[KEY_ROW_HEIGHT] = theme.rowHeightDp
            prefs[KEY_SHAPE] = theme.keyShape.name
            prefs[KEY_SENSITIVITY] = theme.autocorrectSensitivity
            prefs[KEY_HAPTICS] = theme.haptics
            prefs[KEY_SOUND] = theme.keySound
            prefs[KEY_DOUBLE_SPACE] = theme.doubleSpacePeriod
            prefs[KEY_SEED] = theme.seedColor
            prefs[KEY_DARK] = theme.isDark
        }
    }

    private companion object {
        val KEY_ID = stringPreferencesKey("theme_id")
        val KEY_NAME = stringPreferencesKey("theme_name")
        val KEY_BACKGROUND = intPreferencesKey("color_background")
        val KEY_KEY_BACKGROUND = intPreferencesKey("color_key")
        val KEY_KEY_PRESSED = intPreferencesKey("color_pressed")
        val KEY_KEY_LABEL = intPreferencesKey("color_label")
        val KEY_POPUP_BACKGROUND = intPreferencesKey("color_popup")
        val KEY_ACCENT = intPreferencesKey("color_accent")
        val KEY_FONT_PATH = stringPreferencesKey("font_path")
        val KEY_FONT_FAMILY = stringPreferencesKey("font_family")
        val KEY_CORNER = floatPreferencesKey("corner_radius")
        val KEY_GAP = floatPreferencesKey("key_gap")
        val KEY_ROW_HEIGHT = floatPreferencesKey("row_height")
        val KEY_SHAPE = stringPreferencesKey("key_shape")
        val KEY_SENSITIVITY = intPreferencesKey("autocorrect_sensitivity")
        val KEY_HAPTICS = booleanPreferencesKey("haptics")
        val KEY_SOUND = booleanPreferencesKey("key_sound")
        val KEY_DOUBLE_SPACE = booleanPreferencesKey("double_space_period")
        val KEY_SEED = intPreferencesKey("seed_color")
        val KEY_DARK = booleanPreferencesKey("is_dark")
    }
}

