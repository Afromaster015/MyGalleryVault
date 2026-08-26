package id.bayu.mygalleryvault.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import id.bayu.mygalleryvault.R

/** Space Grotesk (OFL) — display & body voice of the sanity design system. */
val SpaceGroteskFamily = FontFamily(
    Font(R.font.space_grotesk_regular, FontWeight.Normal),
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_semibold, FontWeight.SemiBold),
    Font(R.font.space_grotesk_bold, FontWeight.Bold),
)

/**
 * IBM Plex Mono (OFL) — technical register: sizes, dates, PIN meta, badges.
 * Wide tracking per sanity button-sm / code tokens; uppercase at call sites.
 */
val PlexMonoFamily = FontFamily(
    Font(R.font.plex_mono_regular, FontWeight.Normal),
    Font(R.font.plex_mono_medium, FontWeight.Medium),
    Font(R.font.plex_mono_semibold, FontWeight.SemiBold),
)

private val Grotesk = SpaceGroteskFamily
private val Mono = PlexMonoFamily

val MonoLabel = TextStyle(
    fontFamily = Mono,
    fontWeight = FontWeight.SemiBold,
    fontSize = 11.sp,
    lineHeight = 12.sp,
    letterSpacing = 0.5.sp,
)

val MonoBody = TextStyle(
    fontFamily = Mono,
    fontWeight = FontWeight.Normal,
    fontSize = 13.sp,
    lineHeight = 18.sp,
)

/**
 * sanity scale: tight negative tracking on display/headings (Space Grotesk),
 * mono labels for technical metadata.
 */
val AppTypography = Typography(
    displayLarge = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Bold, fontSize = 56.sp, lineHeight = 58.sp, letterSpacing = (-2.5).sp),
    displayMedium = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Bold, fontSize = 44.sp, lineHeight = 47.sp, letterSpacing = (-1.9).sp),
    displaySmall = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.SemiBold, fontSize = 36.sp, lineHeight = 39.sp, letterSpacing = (-1.4).sp),
    headlineLarge = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 33.sp, letterSpacing = (-0.9).sp),
    headlineMedium = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 29.sp, letterSpacing = (-0.7).sp),
    headlineSmall = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 27.sp, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 25.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 16.sp),
    labelMedium = MonoLabel.copy(fontSize = 12.sp, lineHeight = 13.sp),
    labelSmall = MonoLabel,
)
