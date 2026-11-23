package dev.haljk.glow.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything the background service needs to run one generation.
 *
 * Kept as JSON in an Intent extra because reference images are passed as URIs —
 * a photo is far too large for the Binder transaction limit, and the service
 * reads and downscales it itself.
 */
data class JobRequest(
    /** "text" or "edit". */
    val mode: String,
    val prompt: String,
    val note: String,
    val presetId: String,
    val size: String,
    val seed: Long,
    val count: Int,
    val referenceUris: List<String>,
    val summary: String,
    /**
     * Set when the service is picking up a task it already submitted — after a
     * restart, or after the user came back to an app that had been killed. The
     * queue still owns the task, so there is nothing to submit.
     */
    val resumeRemoteId: String = "",
    /**
     * Passed to the queue so a retried submit cannot produce a second image. Empty
     * means "make one up when submitting".
     */
    val idempotencyKey: String = "",
) {
    val isEdit: Boolean get() = mode == MODE_EDIT

    fun toJson(): String = JSONObject().apply {
        put("mode", mode)
        put("prompt", prompt)
        put("note", note)
        put("presetId", presetId)
        put("size", size)
        put("seed", seed)
        put("count", count)
        put("refUris", JSONArray(referenceUris))
        put("summary", summary)
        put("resumeRemoteId", resumeRemoteId)
        put("idempotencyKey", idempotencyKey)
    }.toString()

    companion object {
        const val MODE_TEXT = "text"
        const val MODE_EDIT = "edit"

        fun fromJson(raw: String): JobRequest? = runCatching {
            val json = JSONObject(raw)
            val array = json.optJSONArray("refUris") ?: JSONArray()
            JobRequest(
                mode = json.optString("mode", MODE_TEXT),
                prompt = json.optString("prompt", ""),
                note = json.optString("note", ""),
                presetId = json.optString("presetId", ""),
                size = json.optString("size", "1024x1024"),
                seed = json.optLong("seed", 0L),
                count = json.optInt("count", 1),
                referenceUris = buildList { for (i in 0 until array.length()) add(array.optString(i)) },
                summary = json.optString("summary", ""),
                resumeRemoteId = json.optString("resumeRemoteId", ""),
                idempotencyKey = json.optString("idempotencyKey", ""),
            )
        }.getOrNull()
    }
}
