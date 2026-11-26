package dev.haljk.glow.core

/**
 * The single instruction builder.
 *
 * Order of assembly, identical to the shipped Node implementation:
 *   1. the preset's own instruction
 *   2. one sentence per reference image, numbered by submit order
 *   3. the user's own wording, when present
 *   4. the protection clause, generated from what the preset may not change
 *
 * Everything is joined with `；` and closed with `。`.
 *
 * [EditInstructionRequest.raw] is the way out of that assembly: a user who already
 * wrote the whole instruction gets it sent as written, because every sentence added
 * here is one they did not ask for.
 */
data class ReferenceSpec(val role: Role)

data class EditInstructionRequest(
    val refs: List<ReferenceSpec>,
    val presetId: String? = null,
    val note: String = "",
    val raw: Boolean = false,
)

data class ComposedInstruction(
    val prompt: String,
    val presetId: String,
    val references: Int,
    val roles: List<Role>,
)

class InstructionRejected(message: String) : IllegalArgumentException(message)

object InstructionComposer {
    const val MAX_REFERENCES = 16

    fun compose(request: EditInstructionRequest): ComposedInstruction {
        if (request.raw) return composeRaw(request)

        val preset = request.presetId?.takeIf { it.isNotBlank() }?.let { id ->
            Presets.byId(id) ?: throw InstructionRejected("未知的预设：$id")
        }
        val roles = request.refs.map { it.role }

        if (roles.isEmpty()) throw InstructionRejected("请先添加至少一张参考图")
        if (roles.size > MAX_REFERENCES) throw InstructionRejected("参考图最多 $MAX_REFERENCES 张")

        if (preset != null) {
            val missing = preset.needs.filter { it !in roles }
            if (missing.isNotEmpty()) {
                throw InstructionRejected("「${preset.label}」还需要指定：${missing.joinToString("、") { it.label }}")
            }
        }

        val mayChange = preset?.mayChange ?: emptyList()
        val parts = buildList {
            if (preset != null) add(preset.instruction)
            roles.forEachIndexed { index, role ->
                add(Preserve.roleSentence(role, index + 1, mayChange))
            }
            val note = request.note.trim()
            if (note.isNotEmpty()) add("另外，$note")
            if (preset != null || Role.SUBJECT in roles) {
                val clause = Preserve.clause(mayChange)
                if (clause.isNotEmpty()) add(clause)
            }
        }

        return ComposedInstruction(
            prompt = parts.joinToString("；") + "。",
            presetId = preset?.id ?: "",
            references = roles.size,
            roles = roles,
        )
    }

    /**
     * The whole instruction is what the user typed, so nothing is looked up or appended.
     *
     * The reference count is still enforced: the service needs one picture and refuses
     * more than [MAX_REFERENCES] whatever the wording says, and a preset id would point
     * at an instruction that is not in the request.
     */
    private fun composeRaw(request: EditInstructionRequest): ComposedInstruction {
        val roles = request.refs.map { it.role }

        if (roles.isEmpty()) throw InstructionRejected("请先添加至少一张参考图")
        if (roles.size > MAX_REFERENCES) throw InstructionRejected("参考图最多 $MAX_REFERENCES 张")

        val note = request.note.trim()
        if (note.isEmpty()) throw InstructionRejected("直发模式需要自己写下要改什么")

        return ComposedInstruction(
            // The one rewrite that survives 直发模式: 图1 becomes <Picture 1>, because the
            // service resolves references by that token and the user's own wording is the
            // only place the mapping is written down.
            prompt = PictureTokens.bind(note, roles.size),
            presetId = "",
            references = roles.size,
            roles = roles,
        )
    }
}
