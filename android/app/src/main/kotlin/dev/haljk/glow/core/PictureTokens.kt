package dev.haljk.glow.core

/**
 * 「图 1」 → `<Picture 1>`, and only that.
 *
 * The image service names its references by that token, so a user in 直发模式 who writes
 * 「把图1的人放进图2」 would otherwise hand the model a Chinese word it cannot resolve.
 * This is notation, not wording: when the text names no photo, not a character is added.
 *
 * The character class is deliberately a blacklist, the same one the desktop build uses
 * (地图、示意图、流程图、效果图…). Missing a word costs one unreplaced phrase; the other
 * direction would silently rewrite a word the user meant literally, so this is the
 * cheaper mistake to make.
 */
object PictureTokens {
    private const val GUARD = "地示意蓝试视构版草程工型果线系度通想面平"

    private val FIGURE = Regex("(?<![$GUARD])图\\s*(\\d{1,2})")
    private val ORDINAL = Regex("第\\s*(\\d{1,2})\\s*张")

    /** [count] is how many references this job actually carries; a number beyond it is left alone. */
    fun bind(text: String, count: Int): String {
        if (count <= 0) return text
        val figures = FIGURE.replace(text) { match ->
            token(match.groupValues[1], count, match.value)
        }
        return ORDINAL.replace(figures) { match ->
            token(match.groupValues[1], count, match.value)
        }
    }

    private fun token(raw: String, count: Int, whole: String): String {
        val index = raw.toIntOrNull() ?: return whole
        return if (index in 1..count) "<Picture $index>" else whole
    }
}
