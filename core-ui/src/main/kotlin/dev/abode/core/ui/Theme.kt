package dev.abode.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val AbodeOrange = Color(0xFFE8641A)
val AbodeOrangeDeep = Color(0xFFB44A12)
val AbodeCream = Color(0xFFFCF2EC)

private val Light = lightColorScheme(
    primary = AbodeOrange,
    onPrimary = Color.White,
    secondary = AbodeOrangeDeep,
    surface = Color(0xFFFFFBF8),
    background = Color(0xFFFFFBF8),
)

private val Dark = darkColorScheme(
    primary = AbodeOrange,
    onPrimary = Color.Black,
    secondary = AbodeOrangeDeep,
)

/**
 * The app's theme.
 *
 * On Material 3 1.4.0, which is what Compose BOM 2026.05.00 pins — the expressive
 * APIs (`MaterialExpressiveTheme`, `MotionScheme`) exist there but are still
 * internal, and they only became public in 1.5.0, which pulls Compose 1.12 and
 * needs compileSdk 37. This project is on AGP 8.9.1 and compileSdk 36, so the
 * theme stays on `MaterialTheme` and the motion is done with the animation APIs,
 * which are stable in every version.
 */
@Composable
fun AbodeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) Dark else Light,
        content = content,
    )
}
