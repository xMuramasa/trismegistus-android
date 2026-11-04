package dev.xmuramasa.trismegistus.ui.theme

import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// The contours' opacity (trismegistus-mac's EndfieldBackground): every fifth line stronger.
private const val CONTOUR_STRONG = 0.9f
private const val CONTOUR_WEAK = 0.55f

/**
 * Endfield paper: [Ink.Surface.window] with the topographic contour lines around (78 %, 72 %). The paths are built once
 * per size (drawWithCache) and only redrawn after that.
 */
fun Modifier.endfieldPaper(color: Color = Ink.Line.rule, opacity: Float = 1f): Modifier =
    background(Ink.Surface.window).endfieldContours(color, opacity)

/** The contour lines alone, on whatever is behind them (the transcript sheet draws them faintly on ink). */
fun Modifier.endfieldContours(color: Color, opacity: Float = 1f): Modifier = drawWithCache {
    val contours = contours(size)
    val stroke = Stroke(width = Strokes.hairline.toPx())
    onDrawBehind {
        contours.forEachIndexed { i, path ->
            val strength = if ((i + 1) % 5 == 0) CONTOUR_STRONG else CONTOUR_WEAK
            drawPath(path, color.copy(alpha = color.alpha * strength * opacity), style = stroke)
        }
    }
}

/** 18 closed curves, ported from the Mac's `drawContours`. */
internal fun contours(size: Size): List<Path> {
    val w = size.width
    val h = size.height
    val cx = w * 0.78f
    val cy = h * 0.72f
    return (1..18).map { k ->
        val r0 = k * min(w, h) * 0.045
        Path().apply {
            for (i in 0..180) {
                val t = i / 180.0 * 2 * PI
                val r = r0 * (1 + 0.10 * sin(3 * t + k * 0.35) + 0.06 * sin(5 * t - k * 0.2) + 0.04 * sin(2 * t + 1.3))
                val p = Offset((cx + r * cos(t) * 1.35).toFloat(), (cy + r * sin(t)).toFloat())
                if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
            }
            close()
        }
    }
}
