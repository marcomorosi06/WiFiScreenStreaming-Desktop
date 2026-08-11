/*
 * Copyright (c) 2026 Marco Morosi
 *
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 * https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and
 * limitations under the Licence.
 */
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

enum class Theme { Light, Dark, System }

fun themeOf(value: String): Theme = when (value) {
    THEME_LIGHT -> Theme.Light
    THEME_DARK -> Theme.Dark
    else -> Theme.System
}

fun Theme.stored(): String = when (this) {
    Theme.Light -> THEME_LIGHT
    Theme.Dark -> THEME_DARK
    Theme.System -> THEME_SYSTEM
}

val ACCENT_SWATCHES: List<Pair<String, Long>> = listOf(
    "Default" to 0L,
    "Indigo" to 0xFF4B3F72,
    "Ocean" to 0xFF1E6F9F,
    "Teal" to 0xFF14746F,
    "Forest" to 0xFF3E7B3E,
    "Amber" to 0xFFB07A16,
    "Rust" to 0xFFA75434,
    "Plum" to 0xFF7B3F6B
)

object MaterialYou {

    fun scheme(seedColor: Color, isDark: Boolean): ColorScheme {
        val hsb = FloatArray(3)
        java.awt.Color.RGBtoHSB(
            (seedColor.red * 255).toInt(),
            (seedColor.green * 255).toInt(),
            (seedColor.blue * 255).toInt(),
            hsb
        )
        val h = hsb[0]
        val s = hsb[1].coerceAtLeast(0.15f)
        val b = hsb[2]
        val bFactor = b.coerceIn(0.5f, 1.0f)
        val bgFactor = b.coerceIn(0.85f, 1.0f)
        val tH = (h + 0.15f) % 1f

        fun fromHsb(hue: Float, sat: Float, bright: Float, factor: Float = 1f): Color =
            Color(java.awt.Color.HSBtoRGB(hue % 1f, sat.coerceIn(0f, 1f), (bright * factor).coerceIn(0f, 1f)))

        return if (!isDark) lightColorScheme(
            primary = fromHsb(h, s * 0.9f, 0.4f, bFactor),
            onPrimary = Color.White,
            primaryContainer = fromHsb(h, s * 0.4f, 0.9f, bFactor),
            onPrimaryContainer = fromHsb(h, s * 1.0f, 0.15f),
            secondary = fromHsb(h, s * 0.3f, 0.45f, bFactor),
            onSecondary = Color.White,
            secondaryContainer = fromHsb(h, s * 0.2f, 0.92f, bFactor),
            onSecondaryContainer = fromHsb(h, s * 0.5f, 0.15f),
            tertiary = fromHsb(tH, s * 0.4f, 0.45f, bFactor),
            onTertiary = Color.White,
            tertiaryContainer = fromHsb(tH, s * 0.25f, 0.92f, bFactor),
            onTertiaryContainer = fromHsb(tH, s * 0.6f, 0.15f),
            background = fromHsb(h, s * 0.05f, 0.98f, bgFactor),
            onBackground = fromHsb(h, s * 0.1f, 0.1f),
            surface = fromHsb(h, s * 0.05f, 0.98f, bgFactor),
            onSurface = fromHsb(h, s * 0.1f, 0.1f),
            surfaceVariant = fromHsb(h, s * 0.1f, 0.9f, bgFactor),
            onSurfaceVariant = fromHsb(h, s * 0.15f, 0.3f),
            surfaceContainerLowest = fromHsb(h, s * 0.02f, 1.0f, bgFactor),
            surfaceContainerLow = fromHsb(h, s * 0.05f, 0.96f, bgFactor),
            surfaceContainer = fromHsb(h, s * 0.08f, 0.94f, bgFactor),
            surfaceContainerHigh = fromHsb(h, s * 0.1f, 0.92f, bgFactor),
            surfaceContainerHighest = fromHsb(h, s * 0.12f, 0.90f, bgFactor),
            outline = fromHsb(h, s * 0.1f, 0.5f, bFactor),
            outlineVariant = fromHsb(h, s * 0.1f, 0.8f, bFactor)
        ) else darkColorScheme(
            primary = fromHsb(h, s * 0.7f, 0.85f, bFactor),
            onPrimary = fromHsb(h, s * 1.0f, 0.2f),
            primaryContainer = fromHsb(h, s * 0.8f, 0.3f, bFactor),
            onPrimaryContainer = fromHsb(h, s * 0.4f, 0.9f),
            secondary = fromHsb(h, s * 0.4f, 0.8f, bFactor),
            onSecondary = fromHsb(h, s * 0.6f, 0.2f),
            secondaryContainer = fromHsb(h, s * 0.4f, 0.3f, bFactor),
            onSecondaryContainer = fromHsb(h, s * 0.2f, 0.9f),
            tertiary = fromHsb(tH, s * 0.5f, 0.8f, bFactor),
            onTertiary = fromHsb(tH, s * 0.7f, 0.2f),
            tertiaryContainer = fromHsb(tH, s * 0.5f, 0.3f, bFactor),
            onTertiaryContainer = fromHsb(tH, s * 0.2f, 0.9f),
            background = fromHsb(h, s * 0.15f, 0.10f, bFactor),
            onBackground = fromHsb(h, s * 0.1f, 0.9f),
            surface = fromHsb(h, s * 0.15f, 0.10f, bFactor),
            onSurface = fromHsb(h, s * 0.1f, 0.9f),
            surfaceVariant = fromHsb(h, s * 0.20f, 0.25f, bFactor),
            onSurfaceVariant = fromHsb(h, s * 0.15f, 0.8f),
            surfaceContainerLowest = fromHsb(h, s * 0.15f, 0.05f, bFactor),
            surfaceContainerLow = fromHsb(h, s * 0.15f, 0.12f, bFactor),
            surfaceContainer = fromHsb(h, s * 0.15f, 0.15f, bFactor),
            surfaceContainerHigh = fromHsb(h, s * 0.15f, 0.18f, bFactor),
            surfaceContainerHighest = fromHsb(h, s * 0.15f, 0.22f, bFactor),
            outline = fromHsb(h, s * 0.15f, 0.6f, bFactor),
            outlineVariant = fromHsb(h, s * 0.15f, 0.3f, bFactor)
        )
    }
}
