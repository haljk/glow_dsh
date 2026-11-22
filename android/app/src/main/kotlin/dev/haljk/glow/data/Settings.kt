package dev.haljk.glow.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Where the two model services live and how long to wait.
 *
 * Defaults are loopback so a fresh install never carries anyone's private
 * address; the user points it at their own machine in the settings screen.
 */
data class Settings(
    val imageBaseUrl: String = DEFAULT_IMAGE_URL,
    val llmBaseUrl: String = DEFAULT_LLM_URL,
    val llmModel: String = DEFAULT_LLM_MODEL,
    /** One edit can legitimately take 380 s; the service gets a generous budget. */
    val imageTimeoutSeconds: Int = 900,
    val llmTimeoutSeconds: Int = 180,
    /** Keep the reference-image copies used by the comparison view. */
    val keepReferenceCopies: Boolean = true,
) {
    val imageEndpoint: String get() = imageBaseUrl.trimEnd('/')
    val llmEndpoint: String get() = llmBaseUrl.trimEnd('/')

    fun normalised(): Settings = copy(
        imageBaseUrl = normaliseUrl(imageBaseUrl) ?: DEFAULT_IMAGE_URL,
        llmBaseUrl = normaliseUrl(llmBaseUrl) ?: DEFAULT_LLM_URL,
        llmModel = llmModel.trim().ifEmpty { DEFAULT_LLM_MODEL },
        imageTimeoutSeconds = imageTimeoutSeconds.coerceIn(30, 3600),
        llmTimeoutSeconds = llmTimeoutSeconds.coerceIn(10, 600),
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("imageBaseUrl", imageBaseUrl)
        put("llmBaseUrl", llmBaseUrl)
        put("llmModel", llmModel)
        put("imageTimeoutSeconds", imageTimeoutSeconds)
        put("llmTimeoutSeconds", llmTimeoutSeconds)
        put("keepReferenceCopies", keepReferenceCopies)
    }

    companion object {
        const val DEFAULT_IMAGE_URL = "http://127.0.0.1:9741"
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

        fun fromJson(json: JSONObject): Settings = Settings(
            imageBaseUrl = json.optString("imageBaseUrl", DEFAULT_IMAGE_URL),
            llmBaseUrl = json.optString("llmBaseUrl", DEFAULT_LLM_URL),
            llmModel = json.optString("llmModel", DEFAULT_LLM_MODEL),
            imageTimeoutSeconds = json.optInt("imageTimeoutSeconds", 900),
            llmTimeoutSeconds = json.optInt("llmTimeoutSeconds", 180),
            keepReferenceCopies = json.optBoolean("keepReferenceCopies", true),
        ).normalised()
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
