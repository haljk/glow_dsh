package dev.haljk.glow.core

/**
 * Aspects an edit protects by default, plus the sentence built from them.
 *
 * A preset declares which aspects it is allowed to change; both the protection
 * clause and the subject sentence are generated from the remainder. This is what
 * prevents the contradiction the DSH version had: "change the lighting" next to
 * "lighting stays the same".
 */
enum class PreserveItem(val id: String, val label: String) {
    IDENTITY("identity", "人物身份与面部特征"),
    SKIN("skin", "肤色与体型"),
    EXPRESSION("expression", "表情与视线"),
    HAIR("hair", "发型"),
    POSE("pose", "姿态与手部"),
    OUTFIT("outfit", "服装其余部分"),
    CAMERA("camera", "镜头角度与焦段"),
    LIGHTING("lighting", "光照方向与强度"),
    SHARPNESS("sharpness", "画面清晰度与噪点"),
    PALETTE("palette", "整体色调"),
    BACKGROUND("background", "背景内容"),
    COMPOSITION("composition", "主体在画面中的位置与构图"),
    ;

    companion object {
        /** Not a preserve item: it only appears in "what this preset changes". */
        const val OBJECTS_ID = "objects"
        const val OBJECTS_TEXT = "不增加也不删除任何物体"
        const val OBJECTS_LABEL = "画面中的物体"

        fun fromId(id: String): PreserveItem? = entries.firstOrNull { it.id == id }
    }
}

/** The subject sentence keeps the same aspects the preset may not change. */
private val SUBJECT_ITEMS = listOf(
    PreserveItem.IDENTITY to "身份、面部特征与五官比例",
    PreserveItem.SKIN to "肤色",
    PreserveItem.HAIR to "发型",
    PreserveItem.EXPRESSION to "表情",
    PreserveItem.POSE to "姿态",
    PreserveItem.CAMERA to "镜头角度",
)

object Preserve {
    /**
     * @return the sentence appended to every edit, or an empty string when
     *         nothing is left to protect.
     */
    fun clause(mayChange: Collection<String>): String {
        val skip = mayChange.toSet()
        val kept = PreserveItem.entries
            .filter { it.id !in skip }
            .map { it.label }
        val clauses = buildList {
            if (kept.isNotEmpty()) add("其他一切保持不变：${kept.joinToString("、")}")
            if (PreserveItem.OBJECTS_ID !in skip) add(PreserveItem.OBJECTS_TEXT)
        }
        if (clauses.isEmpty()) return ""
        return "除上述明确指定的改动之外，${clauses.joinToString("；")}"
    }

    /** `以 <Picture n> 为主体，完整保留其…` — or the bare prefix when empty. */
    fun subjectSentence(n: Int, mayChange: Collection<String>): String {
        val skip = mayChange.toSet()
        val kept = SUBJECT_ITEMS.filter { (item, _) -> item.id !in skip }.map { (_, label) -> label }
        return if (kept.isEmpty()) {
            "以 <Picture $n> 为主体"
        } else {
            "以 <Picture $n> 为主体，完整保留其${kept.joinToString("、")}"
        }
    }

    /** Human labels for what a preset may change, shown in the UI. */
    fun describeChanges(mayChange: Collection<String>): List<String> {
        val skip = mayChange.toSet()
        val labels = PreserveItem.entries.filter { it.id in skip }.map { it.label }.toMutableList()
        if (PreserveItem.OBJECTS_ID in skip) labels.add(PreserveItem.OBJECTS_LABEL)
        return labels
    }

    /** The instruction sentence for one reference image. */
    fun roleSentence(role: Role, n: Int, mayChange: Collection<String>): String = when (role) {
        Role.SUBJECT -> subjectSentence(n, mayChange)
        Role.BACKGROUND -> "把背景替换为 <Picture $n> 中的场景"
        Role.OUTFIT -> "把人物服装替换为 <Picture $n> 中的服装，保留版型、材质与褶皱"
        Role.STYLE -> "画面色调、光线质感与整体画风参考 <Picture $n>"
        Role.SCENE -> "构图与机位参考 <Picture $n>"
        Role.DETAIL -> "局部细节参考 <Picture $n>"
    }
}
