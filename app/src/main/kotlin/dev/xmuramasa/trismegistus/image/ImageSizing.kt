package dev.xmuramasa.trismegistus.image

import kotlin.math.max
import kotlin.math.roundToInt

/** The size maths for images sent with a prompt: the longest edge at most [MAX_EDGE] px, never enlarged. */
object ImageSizing {
    const val MAX_EDGE: Int = 2048

    /** The output size: the aspect ratio kept, each side at least 1 px. */
    fun fit(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val longest = max(width, height)
        if (longest <= maxEdge) return width to height
        val scale = maxEdge.toDouble() / longest
        return if (width >= height) {
            maxEdge to max(1, (height * scale).roundToInt())
        } else {
            max(1, (width * scale).roundToInt()) to maxEdge
        }
    }

    /** BitmapFactory's inSampleSize: the largest power of two that still decodes at least [maxEdge] on the long side. */
    fun sampleSize(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Int {
        val longest = max(width, height)
        var sample = 1
        while (longest / (sample * 2) >= maxEdge) sample *= 2
        return sample
    }
}
