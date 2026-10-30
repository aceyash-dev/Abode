package dev.abode.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
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
 * The app's theme, on Material 3 Expressive.
 *
 * Expressive is a motion and shape system as much as a colour one: the theme
 * brings spring-based motion, which is what makes a screen change feel like it
 * moved rather than cut. The keyboard itself draws its own Canvas and does not
 * use Compose, so none of this reaches it — this is the app's surface only.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AbodeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialExpressiveTheme(
        colorScheme = if (darkTheme) Dark else Light,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
