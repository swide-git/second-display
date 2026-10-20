package io.github.pgodlews.wormhole

/**
 * Resolution advertised to the sender. Fewer pixels mean a lower bitrate on a congested link;
 * the decoder output is scaled back up to fill the screen. A fixed preset whose aspect differs
 * from the panel is letterboxed rather than stretched. Fixed presets larger than the panel are
 * not offered: the sender would encode pixels the panel then throws away.
 */
enum class ResolutionPreset(val id: String, private val tag: String, private val percent: Int, private val fixedLong: Int, private val fixedShort: Int) {
    NATIVE("native", "native", 100, 0, 0),
    WUXGA("1920x1200", "16:10", 0, 1920, 1200),
    FHD("1080p", "16:9", 0, 1920, 1080),
    WSXGA_PLUS("1680x1050", "16:10", 0, 1680, 1050),
    P80("80", "80%", 80, 0, 0),
    HD_PLUS("1600x900", "16:9", 0, 1600, 900),
    WXGA_PLUS("1440x900", "16:10", 0, 1440, 900),
    P64("64", "64%", 64, 0, 0),
    HD("720p", "16:9", 0, 1280, 720),
    P50("50", "50%", 50, 0, 0);

    /** Advertised size for a panel of [width] x [height], keeping the panel's orientation. */
    fun size(width: Int, height: Int): Pair<Int, Int> {
        if (fixedLong > 0) {
            return if (width >= height) Pair(fixedLong, fixedShort) else Pair(fixedShort, fixedLong)
        }
        if (percent == 100) return Pair(width, height)
        return Pair(scale(width), scale(height))
    }

    fun fits(width: Int, height: Int): Boolean =
        fixedLong == 0 || (fixedLong <= maxOf(width, height) && fixedShort <= minOf(width, height))

    /** "W × H (tag)" in the orientation the receiver advertises. */
    fun label(width: Int, height: Int, portrait: Boolean): String {
        val (w, h) = size(width, height)
        val long = maxOf(w, h)
        val short = minOf(w, h)
        return if (portrait) "$short × $long ($tag)" else "$long × $short ($tag)"
    }

    // Multiples of 8 suit the sender's encoder.
    private fun scale(value: Int): Int = ((value * percent / 100) / 8 * 8).coerceAtLeast(8)

    companion object {
        fun fromId(id: String?): ResolutionPreset =
            values().firstOrNull { it.id.equals(id, ignoreCase = true) } ?: NATIVE

        /** Presets that fit the panel: native first, then by pixel count, largest first. */
        fun available(width: Int, height: Int): List<ResolutionPreset> =
            listOf(NATIVE) + values()
                .filter { it != NATIVE && it.fits(width, height) }
                .sortedByDescending { it.size(width, height).let { (w, h) -> w.toLong() * h } }
    }
}
