package dev.haljk.glow.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Where the two model services live and how long to wait.
 *
 * Defaults are loopback so a fresh install never carries anyone's private
 * address; the user points it at their own machine in the settings screen.
 *
 * Since the queue service exists, the image side has two shapes: [MODE_QUEUE]
 * submits a task and polls, [MODE_SYNC] blocks on the old endpoint. The queue is
 * the default because it is the only one that survives a long edit or a second
 * device asking at the same time.
 */
data class Settings(
    /** `queue` talks to the job service, `sync` blocks on the old endpoint. */
    val imageApiMode: String = MODE_QUEUE,
    /** Queue base. In sync mode this is unused but kept, so switching back is one field. */
    val imageBaseUrl: String = DEFAULT_IMAGE_URL,
    /** The blocking service, kept as the rollback target. */
    val imageSyncBaseUrl: String = DEFAULT_SYNC_URL,
    val llmBaseUrl: String = DEFAULT_LLM_URL,
    val llmModel: String = DEFAULT_LLM_MODEL,
    /**
     * Read timeout for one request, and the whole budget of a 同步模式 job.
     *
     * 排队模式 does not use it as a deadline: the client polls until the service reports
     * success, failure or cancellation, however long that takes. Polling is cheap, so
     * this is the only long number left; it has to outlast one queued 2K edit.
     */
    val imageTimeoutSeconds: Int = 900,
    val llmTimeoutSeconds: Int = 180,
    /** Keep the reference-image copies used by the comparison view. */
    val keepReferenceCopies: Boolean = true,
) {
    val isQueueMode: Boolean get() = imageApiMode == MODE_QUEUE

    /** The image service this mode actually talks to. */
    val imageEndpoint: String
        get() = (if (isQueueMode) imageBaseUrl else imageSyncBaseUrl).trimEnd('/')

    val llmEndpoint: String get() = llmBaseUrl.trimEnd('/')

    fun normalised(): Settings = copy(
        imageApiMode = if (imageApiMode == MODE_SYNC) MODE_SYNC else MODE_QUEUE,
        imageBaseUrl = normaliseUrl(imageBaseUrl) ?: DEFAULT_IMAGE_URL,
        imageSyncBaseUrl = normaliseUrl(imageSyncBaseUrl) ?: DEFAULT_SYNC_URL,
        llmBaseUrl = normaliseUrl(llmBaseUrl) ?: DEFAULT_LLM_URL,
        llmModel = llmModel.trim().ifEmpty { DEFAULT_LLM_MODEL },
        // A queued 2K edit can sit in the queue before it even starts.
        imageTimeoutSeconds = imageTimeoutSeconds.coerceIn(60, 7200),
        llmTimeoutSeconds = llmTimeoutSeconds.coerceIn(10, 600),
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("imageApiMode", imageApiMode)
        put("imageBaseUrl", imageBaseUrl)
        put("imageSyncBaseUrl", imageSyncBaseUrl)
        put("llmBaseUrl", llmBaseUrl)
        put("llmModel", llmModel)
        put("imageTimeoutSeconds", imageTimeoutSeconds)
        put("llmTimeoutSeconds", llmTimeoutSeconds)
        put("keepReferenceCopies", keepReferenceCopies)
    }

    companion object {
        const val MODE_QUEUE = "queue"
        const val MODE_SYNC = "sync"

        /** The queue: submit, poll, fetch. */
        const val DEFAULT_IMAGE_URL = "http://127.0.0.1:9742"

        /** The blocking service, still online and the rollback target. */
        const val DEFAULT_SYNC_URL = "http://127.0.0.1:9741"

        const val DEFAULT_LLM_URL = "http://127.0.0.1:9739"
        const val DEFAULT_LLM_MODEL = "default_model"

        /** Returns null when the text is not a usable http(s) URL. */
        fun normaliseUrl(raw: String): String? {
            val text = raw.trim().trimEnd('/')
            if (text.isEmpty()) return null
            if (!text.startsWith("http://") && !text.startsWith("https://")) return null
            val host = text.removePrefix("http://").removePrefix("https://")
            if (host.isEmpty() || host.startsWith(":")) return null
            if (host.contains(' ')) return null
            return text
        }

        fun fromJson(json: JSONObject): Settings {
            val migrated = migrate(json)
            return Settings(
                imageApiMode = migrated.optString("imageApiMode", MODE_QUEUE),
                imageBaseUrl = migrated.optString("imageBaseUrl", DEFAULT_IMAGE_URL),
                imageSyncBaseUrl = migrated.optString("imageSyncBaseUrl", DEFAULT_SYNC_URL),
                llmBaseUrl = migrated.optString("llmBaseUrl", DEFAULT_LLM_URL),
                llmModel = migrated.optString("llmModel", DEFAULT_LLM_MODEL),
                imageTimeoutSeconds = migrated.optInt("imageTimeoutSeconds", 900),
                llmTimeoutSeconds = migrated.optInt("llmTimeoutSeconds", 180),
                keepReferenceCopies = migrated.optBoolean("keepReferenceCopies", true),
            ).normalised()
        }

        /**
         * Settings written before the queue existed point at :9741 and carry no
         * mode. Port 9741 is unambiguously the blocking service, so the value is
         * moved to :9742 and the old address is kept as the rollback target.
         */
        fun migrate(json: JSONObject): JSONObject {
            if (json.has("imageApiMode")) return json
            val next = JSONObject(json.toString())
            next.put("imageApiMode", MODE_QUEUE)
            val url = json.optString("imageBaseUrl", "").trim()
            if (url.isEmpty()) return next
            if (!next.has("imageSyncBaseUrl")) next.put("imageSyncBaseUrl", url)
            if (url.contains(":9741")) {
                next.put("imageBaseUrl", url.replace(":9741", ":9742"))
            }
            return next
        }
    }
}

/** Settings live in a plain file so they survive reinstall-free upgrades and stay inspectable. */
class SettingsStore(private val directory: File) {
    private val file = File(directory, "settings.json")

    fun load(): Settings = runCatching {
        if (file.isFile) Settings.fromJson(JSONObject(file.readText())) else Settings()
    }.getOrElse { Settings() }

    fun save(settings: Settings): Settings {
        val normalised = settings.normalised()
        directory.mkdirs()
        file.writeText(normalised.toJson().toString(2))
        return normalised
    }
}

/** At most [MAX] saved prompts, matching the desktop version. */
class FavoriteStore(private val directory: File) {
    private val file = File(directory, "favorites.json")

    fun load(): List<String> = runCatching {
        if (!file.isFile) return emptyList()
        val array = JSONArray(file.readText())
        buildList {
            for (index in 0 until array.length()) {
                val text = array.optString(index, "").trim()
                if (text.isNotEmpty() && text !in this) add(text)
            }
        }.take(MAX)
    }.getOrElse { emptyList() }

    /** @return the new list, or null when the cap would be exceeded. */
    fun add(text: String): List<String>? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return load()
        val current = load().toMutableList()
        if (current.any { it == trimmed }) return current
        if (current.size >= MAX) return null
        current.add(trimmed)
        write(current)
        return current
    }

    fun remove(text: String): List<String> {
        val next = load().filterNot { it == text }
        write(next)
        return next
    }

    private fun write(items: List<String>) {
        directory.mkdirs()
        file.writeText(JSONArray(items).toString(2))
    }

    companion object {
        const val MAX = 5
    }
}
