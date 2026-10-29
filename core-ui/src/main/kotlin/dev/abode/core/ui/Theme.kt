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
