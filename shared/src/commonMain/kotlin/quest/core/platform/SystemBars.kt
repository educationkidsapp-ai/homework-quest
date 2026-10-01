package quest.core.platform

import androidx.compose.runtime.Composable

/**
 * Makes the system's own chrome follow the app's theme: dark status- and navigation-bar glyphs over the light
 * palette, light ones over the dark palette. The bars themselves are transparent (the app draws edge to edge), so
 * their colour is the theme's ground already; this is only about what is legible on top of it.
 */
@Composable
expect fun SystemBarsAppearance(dark: Boolean)
