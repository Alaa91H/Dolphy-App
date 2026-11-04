package com.droid.dolphy.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle

/**
 * Platform typography with a restrained scale.
 *
 * The app used to bundle a decorative display font and render every role in
 * bold, which made dense technical screens harder to scan than they needed to
 * be. Roles now keep the Material 3 system font and weights, and only the size
 * responds to the user's scale setting.
 */
fun buildAppTypography(fontScale: Float): Typography {
    val scale = fontScale.coerceIn(0.85f, 1.15f)
    val base = Typography()
    fun TextStyle.scaled(extra: Float = 1f): TextStyle = copy(fontSize = fontSize * scale * extra)

    return base.copy(
        displayLarge = base.displayLarge.scaled(),
        displayMedium = base.displayMedium.scaled(),
        displaySmall = base.displaySmall.scaled(),
        headlineLarge = base.headlineLarge.scaled(),
        headlineMedium = base.headlineMedium.scaled(),
        headlineSmall = base.headlineSmall.scaled(),
        titleLarge = base.titleLarge.scaled(),
        titleMedium = base.titleMedium.scaled(),
        titleSmall = base.titleSmall.scaled(),
        bodyLarge = base.bodyLarge.scaled(),
        bodyMedium = base.bodyMedium.scaled(),
        bodySmall = base.bodySmall.scaled(),
        labelLarge = base.labelLarge.scaled(),
        labelMedium = base.labelMedium.scaled(),
        labelSmall = base.labelSmall.scaled(),
    )
}

fun buildExpressiveTypography(fontScale: Float): Typography {
    val base = buildAppTypography(fontScale)
    return base.copy(
        displayLarge = base.displayLarge.copy(fontSize = base.displayLarge.fontSize * 1.1f),
        displayMedium = base.displayMedium.copy(fontSize = base.displayMedium.fontSize * 1.08f),
        displaySmall = base.displaySmall.copy(fontSize = base.displaySmall.fontSize * 1.06f),
        headlineLarge = base.headlineLarge.copy(fontSize = base.headlineLarge.fontSize * 1.06f),
        headlineMedium = base.headlineMedium.copy(fontSize = base.headlineMedium.fontSize * 1.04f),
        headlineSmall = base.headlineSmall.copy(fontSize = base.headlineSmall.fontSize * 1.02f),
        titleLarge = base.titleLarge.copy(fontSize = base.titleLarge.fontSize * 1.03f),
    )
}

val Typography = buildAppTypography(fontScale = 1f)
