package dev.haljk.glow.core

/**
 * How one reference image is meant to be used.
 *
 * The wire id must stay identical to the DSH implementation: the conformance
 * vectors in `app/src/test/resources/conformance/vectors.json` were recorded
 * against those exact strings.
 */
enum class Role(val id: String, val label: String) {
    SUBJECT("subject", "主体（要保留的人或物）"),
    BACKGROUND("background", "背景"),
    OUTFIT("outfit", "服装"),
    STYLE("style", "色调与画风"),
    SCENE("scene", "构图与机位"),
    DETAIL("detail", "局部细节"),
    ;

    companion object {
        /** Unknown ids fall back to DETAIL, matching the host's coercion. */
        fun fromId(id: String?): Role = entries.firstOrNull { it.id == id } ?: DETAIL
    }
}
