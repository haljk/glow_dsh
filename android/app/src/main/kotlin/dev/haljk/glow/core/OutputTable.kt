package dev.haljk.glow.core

/**
 * Ratio × quality → the concrete pixel size sent to the service.
 *
 * The picker asks two questions instead of showing seven名不副实的 presets ("超清横版" vs
 * "电脑壁纸" both meant 16:9, and neither said how big it was). Every entry keeps the
 * service rules: both edges are multiples of 32 and sit within 256..2048.
 *
 * Reference mode does not use this table when "跟随原图" is on — it keeps the photo's own
 * aspect through [SizeFit] and only takes the budget from the chosen quality.
 */
enum class AspectRatio(val id: String, val label: String, val w: Int, val h: Int) {
    SQUARE("1:1", "方形", 1, 1),
    PORTRAIT("3:4", "竖版", 3, 4),
    LANDSCAPE("4:3", "横版", 4, 3),
    TALL("9:16", "手机壁纸", 9, 16),
    WIDE("16:9", "宽屏", 16, 9),
    ;

    companion object {
        fun fromId(id: String?): AspectRatio = entries.firstOrNull { it.id == id } ?: SQUARE
    }
}

object OutputTable {

    private val table: Map<Pair<AspectRatio, Density>, OutputSize> = mapOf(
        // 1:1
        (AspectRatio.SQUARE to Density.FAST) to OutputSize(768, 768, "1:1"),
        (AspectRatio.SQUARE to Density.NORMAL) to OutputSize(1024, 1024, "1:1"),
        (AspectRatio.SQUARE to Density.SHARP) to OutputSize(1536, 1536, "1:1"),
        // 3:4
        (AspectRatio.PORTRAIT to Density.FAST) to OutputSize(672, 896, "3:4"),
        (AspectRatio.PORTRAIT to Density.NORMAL) to OutputSize(896, 1216, "3:4"),
        (AspectRatio.PORTRAIT to Density.SHARP) to OutputSize(1152, 1536, "3:4"),
        // 4:3
        (AspectRatio.LANDSCAPE to Density.FAST) to OutputSize(896, 672, "4:3"),
        (AspectRatio.LANDSCAPE to Density.NORMAL) to OutputSize(1216, 896, "4:3"),
        (AspectRatio.LANDSCAPE to Density.SHARP) to OutputSize(1536, 1152, "4:3"),
        // 9:16
        (AspectRatio.TALL to Density.FAST) to OutputSize(576, 1024, "9:16"),
        (AspectRatio.TALL to Density.NORMAL) to OutputSize(864, 1536, "9:16"),
        (AspectRatio.TALL to Density.SHARP) to OutputSize(1152, 2048, "9:16"),
        // 16:9
        (AspectRatio.WIDE to Density.FAST) to OutputSize(1024, 576, "16:9"),
        (AspectRatio.WIDE to Density.NORMAL) to OutputSize(1536, 864, "16:9"),
        (AspectRatio.WIDE to Density.SHARP) to OutputSize(2048, 1152, "16:9"),
    )

    fun of(ratio: AspectRatio, quality: Density): OutputSize =
        table[ratio to quality] ?: OutputSize(1024, 1024, "1:1")
}
