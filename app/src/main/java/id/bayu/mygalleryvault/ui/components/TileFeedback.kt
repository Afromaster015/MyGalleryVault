package id.bayu.mygalleryvault.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import id.bayu.mygalleryvault.ui.theme.AppMotion
import kotlin.math.min

/**
 * Press feedback for a tile. The grid has no ripple: at contact-sheet density a ripple
 * washes over the neighbours, while a 3% dip answers the finger and leaves the row still.
 */
@Composable
internal fun Modifier.pressScale(
    interactionSource: InteractionSource,
    scale: Float = AppMotion.PRESS_SCALE,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val current by animateFloatAsState(
        targetValue = if (pressed) scale else 1f,
        animationSpec = tween(AppMotion.PRESS_MS),
        label = "pressScale",
    )
    return this.graphicsLayer {
        scaleX = current
        scaleY = current
    }
}

/**
 * Selection marker: four corner brackets instead of a full border. A border draws a box
 * around the media, brackets mark the media itself, so a selected photo is still the photo.
 */
@Composable
internal fun SelectionBrackets(
    color: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 3.dp,
    armLength: Dp = 18.dp,
) {
    Canvas(modifier = modifier) {
        val stroke = strokeWidth.toPx()
        val inset = stroke / 2f
        val arm = min(armLength.toPx(), min(size.width, size.height) / 3f)

        fun bracket(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(color, Offset(x, y), Offset(x + arm * dx, y), stroke, cap = StrokeCap.Square)
            drawLine(color, Offset(x, y), Offset(x, y + arm * dy), stroke, cap = StrokeCap.Square)
        }

        bracket(inset, inset, 1f, 1f)
        bracket(size.width - inset, inset, -1f, 1f)
        bracket(inset, size.height - inset, 1f, -1f)
        bracket(size.width - inset, size.height - inset, -1f, -1f)
    }
}
