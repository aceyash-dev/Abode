package dev.abode.data

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import dev.abode.core.common.theme.KeyboardTheme

/**
 * Turns a stored theme into the one to draw right now.
 *
 * Two things about a theme are not really the user's to fix: whether it is night
 * where they are, and what colour their wallpaper is. Both are read here, at the
 * moment of drawing, rather than baked into the stored theme — so a phone that
 * switches to dark at sunset changes the keyboard without the user touching a
 * setting, and nothing has to be written to disk when it happens.
 */
object SystemTheme {

    /** True when the system is in night mode. */
    fun isNight(context: Context): Boolean {
        val mode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * The wallpaper's accent colour on Android 12+, or null anywhere else.
     *
     * `system_accent1_200` is the tone Material You uses for light surfaces; it
     * is read by name because the constant does not exist below API 31 and the
     * identifier lookup costs nothing when it does.
     */
    fun wallpaperSeed(context: Context): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val id = context.resources.getIdentifier("system_accent1_200", "color", "android")
        if (id == 0) return null
        return runCatching { context.getColor(id) }.getOrNull()
    }

    /**
     * The theme with the system's answers applied. When neither switch is on this
     * returns the theme unchanged, so a person who never touches them gets exactly
     * the colours they picked.
     */
    fun resolve(context: Context, theme: KeyboardTheme): KeyboardTheme {
        val seed = if (theme.dynamicColor) wallpaperSeed(context) ?: theme.seedColor else theme.seedColor
        val dark = if (theme.followSystemDark) isNight(context) else theme.isDark
        if (seed == theme.seedColor && dark == theme.isDark) return theme
        // The six colour roles are derived from the seed, so a change to either
        // input rebuilds the palette rather than patching one colour.
        return KeyboardTheme.fromSeed(seed, dark, theme)
    }
}
