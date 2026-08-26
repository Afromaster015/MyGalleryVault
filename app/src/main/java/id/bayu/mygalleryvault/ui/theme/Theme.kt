package id.bayu.mygalleryvault.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * sanity design system (needmcp) — dark command-center palette.
 * Canvas #0B0B0B, surfaces #212121..#353535, coral CTA #F36458,
 * electric-blue accents, neon-green success. Hierarchy via color, not shadow.
 */
object SanityPalette {
    val Canvas = Color(0xFF0B0B0B)
    val Card = Color(0xFF212121)
    val SurfaceMuted = Color(0xFF2A2A2A)
    val SurfaceHover = Color(0xFF353535)
    val SurfaceActive = Color(0xFF404040)
    val Deep = Color(0xFF000000)

    val Ink = Color(0xFFFFFFFF)
    val Ash = Color(0xFFB9B9B9)
    val Stone = Color(0xFF797979)

    val Coral = Color(0xFFF36458)
    val BlueLink = Color(0xFF55BEFF)
    val BlueElectric = Color(0xFF0052EF)
    val Success = Color(0xFF19D600)
    val SuccessBright = Color(0xFF37CD84)
    val Warning = Color(0xFFF59E0B)
    val WarningBright = Color(0xFFFBB52B)
    val Info = Color(0xFF55BEFF)
    val Danger = Color(0xFFDD0000)
    val DangerBright = Color(0xFFFF4444)

    val Hairline = Color(0xFF212121)
    val BorderMedium = Color(0xFF353535)
    val BorderStrong = Color(0xFF797979)

    val RingFocus = Color(0x4D0052EF)
}

/** Extended semantic colors beyond Material3's scheme (sanity badges/status). */
data class SanityColors(
    val success: Color,
    val successBright: Color,
    val warning: Color,
    val warningBright: Color,
    val info: Color,
    val link: Color,
    val hairline: Color,
    val borderMedium: Color,
    val stone: Color,
)

val LocalSanityColors = staticCompositionLocalOf {
    SanityColors(
        success = SanityPalette.Success,
        successBright = SanityPalette.SuccessBright,
        warning = SanityPalette.Warning,
        warningBright = SanityPalette.WarningBright,
        info = SanityPalette.Info,
        link = SanityPalette.BlueLink,
        hairline = SanityPalette.Hairline,
        borderMedium = SanityPalette.BorderMedium,
        stone = SanityPalette.Stone,
    )
}

private fun sanityDarkColorScheme(): ColorScheme {
    val p = SanityPalette
    return darkColorScheme(
        primary = p.Coral,
        onPrimary = p.Ink,
        primaryContainer = Color(0xFF2E1817),
        onPrimaryContainer = Color(0xFFFFB4A9),
        secondary = p.BlueLink,
        onSecondary = p.Canvas,
        secondaryContainer = Color(0xFF09162D),
        onSecondaryContainer = Color(0xFFAFE3FF),
        tertiary = p.Success,
        onTertiary = p.Canvas,
        tertiaryContainer = Color(0xFF0D2909),
        onTertiaryContainer = p.SuccessBright,
        error = p.DangerBright,
        onError = p.Ink,
        errorContainer = Color(0xFF2B0909),
        onErrorContainer = Color(0xFFFF8888),
        background = p.Canvas,
        onBackground = p.Ink,
        surface = p.Canvas,
        onSurface = p.Ink,
        surfaceVariant = p.Card,
        onSurfaceVariant = p.Ash,
        surfaceContainerLowest = p.Deep,
        surfaceContainerLow = Color(0xFF141414),
        surfaceContainer = p.Card,
        surfaceContainerHigh = p.SurfaceMuted,
        surfaceContainerHighest = p.SurfaceHover,
        outline = p.BorderMedium,
        outlineVariant = p.Hairline,
        inverseSurface = Color(0xFFF7F7F7),
        inverseOnSurface = p.Canvas,
        inversePrimary = Color(0xFFE14B40),
        scrim = p.Deep,
    )
}

/** Corner language of sanity: xs 3 / sm 5 / md 6 / lg 12 / xl 16, buttons pill. */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(3.dp),
    small = RoundedCornerShape(5.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

@Composable
fun sanityColors(): SanityColors = LocalSanityColors.current

/** Always-on nocturnal stage — the vault has no light mode. */
@Composable
fun SecureVaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = sanityDarkColorScheme(),
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
