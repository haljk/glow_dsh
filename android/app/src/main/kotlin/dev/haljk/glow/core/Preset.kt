package dev.haljk.glow.core

/**
 * The nine retouch presets.
 *
 * `mayChange` is the whole point: it names the aspects this preset is allowed to
 * touch, and everything else is frozen by [Preserve.clause]. A non-technical user
 * cannot reliably write that instruction, which is why it lives here as data.
 */
data class Preset(
    val id: String,
    val label: String,
    val summary: String,
    val instruction: String,
    val mayChange: List<String>,
    val caution: String,
    /** Roles that must be assigned before this preset can run. */
    val needs: List<Role> = listOf(Role.SUBJECT),
    /** Roles it can use when present, but does not require. */
    val optional: List<Role> = emptyList(),
)

object Presets {
    val BG_REPLACE = Preset(
        id = "bg-replace",
        label = "换背景",
        summary = "保留人物，只换后面的场景",
        instruction = "把人物从原背景中干净地分离出来，放到新的背景里",
        // A new background brings its own light and colour.
        mayChange = listOf("background", "lighting", "palette"),
        caution = "复杂发丝边缘可能出现毛边；用 1024² 及以上出图更稳。可以再选一张图当新背景，也可以直接用一句话描述新背景。",
        optional = listOf(Role.BACKGROUND),
    )

    val OUTFIT_REPLACE = Preset(
        id = "outfit-replace",
        label = "换衣服",
        summary = "保留人物、表情与背景，只换身上的衣服",
        instruction = "把人物身上的服装替换掉，其余部分不动",
        mayChange = listOf("outfit"),
        caution = "描述衣服时写清颜色、版型、材质，比说\"好看一点\"可靠得多。",
        optional = listOf(Role.OUTFIT),
    )

    val HAIR_CHANGE = Preset(
        id = "hair-change",
        label = "换发型",
        summary = "只改头发，脸和其余部分不动",
        instruction = "改变人物的发型与发色，脸部与身体其余部分完全不动",
        mayChange = listOf("hair"),
        caution = "发型大幅改变（如长发变短发）时，模型可能连带微调脸型。",
    )

    val EXPRESSION = Preset(
        id = "expression",
        label = "微调表情",
        summary = "只改表情，五官与身份不动",
        instruction = "只调整人物的表情，五官形状、身份特征与姿态保持不变",
        mayChange = listOf("expression"),
        caution = "表情改动越大，越可能被误读成换脸，建议幅度小一些。",
    )

    val LIGHT_CHANGE = Preset(
        id = "light-change",
        label = "换光线或时段",
        summary = "白天改黄昏、室内改窗外光等，构图不变",
        instruction = "只改变整体光线方向、色温与时段氛围，构图与所有物体位置不变",
        // This preset exists to change the light; freezing it would contradict it.
        mayChange = listOf("lighting", "palette"),
        caution = "阴影方向要跟着新光源走，否则会显得假。",
    )

    val CLEAN_UP = Preset(
        id = "clean-up",
        label = "去掉路人或杂物",
        summary = "抹掉画面里多余的人或东西",
        instruction = "移除画面中多余的路人、杂物与干扰元素，用合理的背景自然补上",
        mayChange = listOf("objects", "background"),
        caution = "被移走物体后面原本被挡住的内容是模型猜的，复杂处可能不自然。",
    )

    val CUTOUT_WHITE = Preset(
        id = "cutout-white",
        label = "抠图换纯色底",
        summary = "去掉背景，换成纯白或指定底色，适合做图库与商品图",
        instruction = "把背景完整移除，换成干净的纯色背景",
        mayChange = listOf("background"),
        caution = "半透明物体（玻璃、纱）与细发丝是难点，建议出图后放大检查边缘。",
    )

    val RESTORE = Preset(
        id = "restore",
        label = "老照片修复与变清晰",
        summary = "去噪点、补细节、整体变清晰，不改变长相",
        instruction = "修复照片的老化痕迹：去除噪点与划痕，提升清晰度与细节，但人物长相、神态与年代感必须保留",
        mayChange = listOf("sharpness"),
        caution = "过度修复会让脸变得\"塑料感\"，建议一次只做一档。",
    )

    val ID_PHOTO = Preset(
        id = "id-photo",
        label = "证件照换底色",
        summary = "换成证件照标准底色，保持正面与五官端正",
        instruction = "处理成证件照样式：纯色背景、正面端正、光线均匀、五官清晰无遮挡",
        mayChange = listOf("background", "lighting", "sharpness"),
        caution = "只做视觉处理，不等同于任何官方证件照规格，正式用途请按具体要求另拍。",
    )

    /** Panel order. */
    val ALL: List<Preset> = listOf(
        BG_REPLACE, OUTFIT_REPLACE, HAIR_CHANGE, EXPRESSION, LIGHT_CHANGE,
        CLEAN_UP, CUTOUT_WHITE, RESTORE, ID_PHOTO,
    )

    fun byId(id: String?): Preset? = if (id.isNullOrBlank()) null else ALL.firstOrNull { it.id == id }
}
