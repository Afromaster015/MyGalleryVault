package id.bayu.mygalleryvault.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * sanity design system (needmcp) — dark command-center palette.
 * Canvas #0B0B0B, surfaces #212121..#353535, orange CTA #FF8A3D,
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
    val Stone = Color(0xFF8A8A8A)

    val Orange = Color(0xFFFF8A3D)
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
        primary = p.Orange,
        onPrimary = p.Canvas,
        primaryContainer = Color(0xFF3A2413),
        onPrimaryContainer = Color(0xFFFFD3B0),
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
        inversePrimary = Color(0xFFA85400),
        scrim = p.Deep,
    )
}

/**
 * Material components inherit the tight end of the scale. The soft 12/16 corners are gone:
 * a 16dp corner reads as a shop card, and this app reads as an instrument panel.
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(2.dp),
    medium = RoundedCornerShape(4.dp),
    large = RoundedCornerShape(4.dp),
    extraLarge = RoundedCornerShape(8.dp),
)

/**
 * Radius marks role, never decoration: media sits square so the picture is the picture, a
 * container gets one tight corner, and a control or a short label is a pill. Pill stays rare
 * on purpose, because it marks things you press or read as a chip, never a surface.
 */
object AppRadius {
    val media: Shape = RoundedCornerShape(0.dp)
    val container: Shape = RoundedCornerShape(4.dp)
    val pill: Shape = RoundedCornerShape(percent = 50)
}

/**
 * Motion carries meaning, never decoration: a press answers the finger, a tab hop shows the
 * direction of travel, a detail push shows where you came from, an image settles into place.
 * Nothing loops.
 */
object AppMotion {
    const val PRESS_SCALE = 0.97f
    const val PRESS_MS = 90
    const val TAB_MS = 260
    const val UNDERLINE_MS = 220
    const val DETAIL_MS = 200
    const val DETAIL_SLIDE_FRACTION = 0.12f
    const val VIEWER_FADE_MS = 220
}

/**
 * One settle curve for every interactive movement. A spring answers the velocity of the
 * gesture, which is what reads as fluid; a linear tween of the same length reads mechanical.
 */
fun <T> settleSpring(): FiniteAnimationSpec<T> =
    spring<T>(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)

@Composable
fun sanityColors(): SanityColors = LocalSanityColors.current

/** Always-on nocturnal stage: the vault has no light mode. */
@Composable
fun SecureVaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = sanityDarkColorScheme(),
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
