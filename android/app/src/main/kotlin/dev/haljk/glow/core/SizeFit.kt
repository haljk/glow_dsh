package dev.haljk.glow.core

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Output size rules.
 *
 * Reference mode inherits the first photo's aspect ratio and only picks a pixel
 * budget; the service requires every edge to be a multiple of 32 within
 * 256..2048. Measured against the DSH implementation by 24 conformance vectors.
 */
enum class Density(val id: String, val label: String, val budget: Int) {
    FAST("fast", "快速（省时间，细节少一些）", 640 * 640),
    NORMAL("normal", "推荐（约 1024² 的清晰度）", 1024 * 1024),
    SHARP("sharp", "高清（更锐利，慢一些）", 1440 * 1440),
    ;

    companion object {
        fun fromId(id: String?): Density = entries.firstOrNull { it.id == id } ?: NORMAL
    }
}

data class OutputSize(
    val width: Int,
    val height: Int,
    val ratio: String,
) {
    val text: String get() = "${width}x$height"
    val pixels: Int get() = width * height
}

object SizeFit {
    const val MIN_EDGE = 256
    const val MAX_EDGE = 2048
    const val MULTIPLE = 32

    /**
     * Fit a source aspect ratio into the service grid at a target pixel budget.
     *
     * Wide and very tall photos clamp one edge, so the width is derived twice and
     * the closer result wins.
     */
    fun fit(
        sourceWidth: Int,
        sourceHeight: Int,
        budget: Int = Density.NORMAL.budget,
        multiple: Int = MULTIPLE,
        minEdge: Int = MIN_EDGE,
        maxEdge: Int = MAX_EDGE,
    ): OutputSize? {
        if (sourceWidth <= 0 || sourceHeight <= 0) return null
        val aspect = sourceWidth.toDouble() / sourceHeight.toDouble()

        fun snap(value: Double): Int {
            val rounded = (value / multiple).roundToInt() * multiple
            return minOf(maxEdge, maxOf(minEdge, rounded))
        }

        val width = snap(kotlin.math.sqrt(budget * aspect))
        val height = snap(width / aspect)
        val alternative = snap(height * aspect)
        val useAlternative =
            relativeError(alternative.toDouble() / height, aspect) <
                relativeError(width.toDouble() / height, aspect) - 0.0005
        val finalWidth = if (useAlternative) alternative else width
        return OutputSize(finalWidth, height, ratioLabel(finalWidth, height))
    }

    private fun relativeError(a: Double, b: Double): Double = kotlin.math.abs(a - b) / b

    fun ratioLabel(width: Int, height: Int): String {
        if (width <= 0 || height <= 0) return ""
        val divisor = gcd(width, height)
        val rw = width / divisor
        val rh = height / divisor
        if (rw > 40 || rh > 40) {
            val ratio = (width.toDouble() / height.toDouble() * 100).roundToInt() / 100.0
            return "${jsNumber(ratio)}:1"
        }
        return "$rw:$rh"
    }

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    /** Match JavaScript's number formatting: whole numbers print without ".0". */
    private fun jsNumber(value: Double): String {
        val rounded = max(-1e15, minOf(1e15, value))
        return if (rounded == kotlin.math.floor(rounded)) rounded.toLong().toString() else rounded.toString()
    }
}
