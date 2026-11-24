package dev.haljk.glow.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import org.json.JSONArray
import org.json.JSONObject
import dev.haljk.glow.core.AspectRatio
import dev.haljk.glow.core.OutputTable
import dev.haljk.glow.core.Density
import dev.haljk.glow.core.EditInstructionRequest
import dev.haljk.glow.core.InstructionComposer
import dev.haljk.glow.core.InstructionRejected
import dev.haljk.glow.core.Presets
import dev.haljk.glow.core.ReferenceSpec
import dev.haljk.glow.core.Role
import dev.haljk.glow.core.SizeFit
import dev.haljk.glow.data.FavoriteStore
import dev.haljk.glow.data.GalleryItem
import dev.haljk.glow.data.GalleryStore
import dev.haljk.glow.data.ImageIntake
import dev.haljk.glow.data.JobRegistry
import dev.haljk.glow.data.JobRequest
import dev.haljk.glow.data.ModelClient
import dev.haljk.glow.data.ModelException
import dev.haljk.glow.data.PendingJobStore
import dev.haljk.glow.data.Settings
import dev.haljk.glow.data.SettingsStore
import dev.haljk.glow.service.GenerationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** The three paths through the app. */
enum class Scenario(val title: String, val blurb: String) {
    TEXT("一句话出图", "从零得到一张图"),
    RETOUCH("照片精修", "换背景换衣服"),
    COMBINE("多图合成", "把人物放进别处"),
}

/**
 * One reference image and what it is for.
 *
 * [width]/[height] are filled in off the main thread right after the pick, so
 * aspect inheritance never touches the disk during composition.
 */
data class RefSlot(
    val uri: String,
    val role: Role,
    val width: Int = 0,
    val height: Int = 0,
) {
    val hasSize: Boolean get() = width > 0 && height > 0
}

/** A named output size for text mode. */

class GlowViewModel(
    application: Application,
    private val saved: SavedStateHandle = SavedStateHandle(),
) : AndroidViewModel(application) {

    private val settingsStore = SettingsStore(application.filesDir)
    private val galleryStore = GalleryStore(File(application.filesDir, "gallery")).also { it.ensureDir() }
    private val favoriteStore = FavoriteStore(application.filesDir)

    // ---- settings
    var settings by mutableStateOf(settingsStore.load())
        private set
    var imageProbe by mutableStateOf<ModelClient.ProbeResult?>(null)
        private set
    var llmProbe by mutableStateOf<ModelClient.ProbeResult?>(null)
        private set
    var probing by mutableStateOf(false)
        private set

    // ---- workbench state
    //
    // Every field here is backed by SavedStateHandle. Android destroys a
    // backgrounded Activity while keeping the process (the foreground service is
    // why), so a plain mutableStateOf loses the user's scenario, wording and
    // picked photos while the job keeps running. That is a real defect seen on a
    // device: the banner still counted up while the page had reset itself.
    var scenario by savedValue(saved, KEY_SCENARIO, Scenario.TEXT)
    var prompt by savedValue(saved, KEY_PROMPT, "")
    var presetId by savedValue<String?>(saved, KEY_PRESET, null)
    var refs by savedRefs(saved)
        private set

    // Ratio and quality replace the old seven fixed presets. "跟随原图" (autoSize) still
    // wins for reference modes, in which case the ratio is ignored and the photo's own
    // aspect is kept through SizeFit.
    var ratio by savedValue(saved, KEY_RATIO, AspectRatio.SQUARE)
    var quality by savedValue(saved, KEY_QUALITY, Density.NORMAL)
    var autoSize by savedValue(saved, KEY_AUTO_SIZE, true)
    var seedText by savedValue(saved, KEY_SEED, "")
    var batchCount by savedValue(saved, KEY_BATCH, 1)

    // ---- gallery
    var activeItems by mutableStateOf<List<GalleryItem>>(emptyList())
        private set
    var archivedItems by mutableStateOf<List<GalleryItem>>(emptyList())
        private set
    var activeCount by mutableStateOf(0)
        private set
    var archivedCount by mutableStateOf(0)
        private set

    // ---- favorites & messages
    var favorites by mutableStateOf(favoriteStore.load())
        private set
    var message by mutableStateOf<String?>(null)
        private set

    /**
     * Read from the process-scoped registry rather than remembered locally: after
     * an Activity recreation a local flag would say "idle" while a job was running.
     */
    var submitting by mutableStateOf(JobRegistry.current.value?.isActive == true)
        private set

    /** Which image the viewer is showing, if any. */
    var viewing by mutableStateOf<GalleryItem?>(null)

    val presets get() = Presets.ALL


    val maxRefs: Int get() = if (scenario == Scenario.RETOUCH) 2 else 5

    init {
        refreshGallery()
        probeServices()
        resumePendingJob()
    }

    /**
     * The queue still owns whatever we submitted before the process died; the only
     * thing that went missing is our memory of it. The task id is on disk, so the
     * poll picks up where it stopped instead of paying for a second generation.
     */
    private fun resumePendingJob() {
        if (JobRegistry.current.value?.isActive == true) return
        val store = PendingJobStore(getApplication<Application>().filesDir)
        val pending = store.load() ?: return
        if (pending.remoteId.isEmpty()) return
        val request = JobRequest(
            mode = if (pending.isEdit) JobRequest.MODE_EDIT else JobRequest.MODE_TEXT,
            prompt = pending.prompt,
            note = pending.note,
            presetId = pending.presetId ?: "",
            size = pending.size,
            seed = pending.seed,
            count = pending.expected,
            // The picker grants are gone after a restart, so a resumed edit keeps its
            // result but not fresh reference copies. The comparison view then shows
            // the result alone, which is honest.
            referenceUris = emptyList(),
            summary = pending.summary,
            resumeRemoteId = pending.remoteId,
            idempotencyKey = pending.idempotencyKey,
        )
        submitting = true
        runCatching { GenerationService.start(getApplication(), request) }
            .onFailure {
                submitting = false
                store.clear()
            }
    }

    // -------------------------------------------------------------- settings

    fun saveSettings(next: Settings) {
        settings = settingsStore.save(next)
        probeServices()
    }

    fun probeServices() {
        probing = true
        viewModelScope.launch {
            val client = ModelClient(settings)
            val image = withContext(Dispatchers.IO) { client.probeImage() }
            val llm = withContext(Dispatchers.IO) { client.probeLlm() }
            imageProbe = image
            llmProbe = llm
            probing = false
        }
    }

    // ------------------------------------------------------------- references

    fun addRefs(uris: List<String>, defaultRole: Role = Role.SUBJECT) {
        val room = maxRefs - refs.size
        if (room <= 0) {
            post("最多 ${maxRefs} 张参考图")
            return
        }
        val added = uris.take(room).mapIndexed { index, uri ->
            // The first image is always the subject; extras default to context.
            val role = if (refs.isEmpty() && index == 0) Role.SUBJECT else defaultRole
            RefSlot(uri, role)
        }
        refs = refs + added
        if (uris.size > room) post("只放得下 ${maxRefs} 张，多余的没有加")
        loadDimensions(added)
    }

    /** Reads intrinsic size once, off the main thread. */
    private fun loadDimensions(slots: List<RefSlot>) {
        if (slots.isEmpty()) return
        viewModelScope.launch {
            val measured = withContext(Dispatchers.IO) {
                slots.map { slot ->
                    val dims = ImageIntake.dimensions(getApplication(), android.net.Uri.parse(slot.uri))
                    dims?.let { slot.copy(width = it.first, height = it.second) } ?: slot
                }
            }
            val byUri = measured.associateBy { it.uri }
            refs = refs.map { byUri[it.uri] ?: it }
        }
    }

    fun removeRef(uri: String) {
        refs = refs.filterNot { it.uri == uri }
    }

    fun setRole(uri: String, role: Role) {
        refs = refs.map { if (it.uri == uri) it.copy(role = role) else it }
    }

    fun clearRefs() {
        refs = emptyList()
    }

    // --------------------------------------------------------------- compose

    /** What the app will actually send, for the "查看将发送的指令" sheet. */
    fun previewInstruction(): String = runCatching { buildInstruction() }.getOrElse { it.message ?: "还缺少信息" }

    private fun buildInstruction(): String {
        val note = prompt.trim()
        return when (scenario) {
            Scenario.TEXT -> {
                if (note.isEmpty()) throw InstructionRejected("先写一句描述，或点旁边的按钮让我帮你写")
                note
            }
            Scenario.RETOUCH, Scenario.COMBINE -> {
                if (refs.isEmpty()) throw InstructionRejected("先选一张照片")
                val request = EditInstructionRequest(
                    refs = refs.map { ReferenceSpec(it.role) },
                    presetId = if (scenario == Scenario.COMBINE) null else presetId,
                    note = note,
                )
                InstructionComposer.compose(request).prompt
            }
        }
    }

    /** Output size: text mode uses a named size, edit modes inherit the photo. */
    fun resolveSize(): Pair<Int, Int> {
        val followsPhoto = scenario != Scenario.TEXT && autoSize
        if (!followsPhoto) {
            val fixed = OutputTable.of(ratio, quality)
            return fixed.width to fixed.height
        }
        val first = refs.firstOrNull()
        val (w, h) = if (first != null && first.hasSize) first.width to first.height else 1024 to 1024
        val fitted = SizeFit.fit(w, h, quality.budget) ?: return 1024 to 1024
        return fitted.width to fitted.height
    }

    /** "1:1 · 推荐 · 1 张" — the one-line summary the output row shows. */
    fun outputSummary(): String {
        val followsPhoto = scenario != Scenario.TEXT && autoSize
        val ratioText = if (followsPhoto) "跟随原图" else ratio.id
        val count = if (scenario == Scenario.TEXT) batchCount.coerceIn(1, 2) else 1
        return "$ratioText · ${quality.label.take(2)} · $count 张"
    }

    fun resolvedSizeLabel(): String {
        val (w, h) = resolveSize()
        val ratio = SizeFit.ratioLabel(w, h)
        val waiting = scenario != Scenario.TEXT && autoSize && refs.firstOrNull()?.hasSize == false
        return when {
            waiting -> "正在读取原图尺寸…"
            scenario == Scenario.TEXT || !autoSize -> "${w}×$h"
            else -> "${w}×$h · 跟随原图比例 $ratio"
        }
    }

    /** Whether pressing the primary action can produce anything. */
    fun canSubmit(): Boolean = when (scenario) {
        Scenario.TEXT -> prompt.isNotBlank()
        Scenario.RETOUCH -> refs.isNotEmpty() && (presetId != null || prompt.isNotBlank())
        Scenario.COMBINE -> refs.isNotEmpty()
    }

    fun submitBlockedReason(): String? = when {
        scenario == Scenario.TEXT && prompt.isBlank() -> "先写一句描述，或点「帮我写」"
        scenario != Scenario.TEXT && refs.isEmpty() -> "先选一张照片"
        scenario == Scenario.RETOUCH && presetId == null && prompt.isBlank() -> "选一个预设，或者写下要改什么"
        else -> null
    }

    // ---------------------------------------------------------------- submit

    fun submit() {
        if (submitting) return
        // Explain rather than silently refusing: a dead button is worse than a message.
        val blocked = submitBlockedReason()
        if (blocked != null) {
            post(blocked)
            return
        }
        val instruction = try {
            buildInstruction()
        } catch (error: InstructionRejected) {
            post(error.message ?: "还缺少信息")
            return
        }
        val (width, height) = resolveSize()
        val seed = seedText.trim().toLongOrNull() ?: (System.currentTimeMillis() % 2_000_000_000L)
        val count = if (scenario == Scenario.TEXT) batchCount.coerceIn(1, 2) else 1
        val isEdit = scenario != Scenario.TEXT
        val request = JobRequest(
            mode = if (isEdit) JobRequest.MODE_EDIT else JobRequest.MODE_TEXT,
            prompt = instruction,
            note = prompt.trim(),
            presetId = if (scenario == Scenario.COMBINE) "" else (presetId ?: ""),
            size = "${width}x$height",
            seed = seed,
            count = count,
            referenceUris = refs.map { it.uri },
            summary = GenerationService.summarise(instruction, isEdit),
            // A retried submit must not produce a second image, so the key travels
            // with the request and the service reuses it on every attempt.
            idempotencyKey = "glow-$seed-${System.currentTimeMillis()}",
        )
        submitting = true
        try {
            GenerationService.start(getApplication(), request)
            post("已开始，等结果就行——可以切走做别的")
        } catch (error: Exception) {
            submitting = false
            post("没能启动后台任务：${error.message ?: "未知原因"}")
        }
    }

    /** Called when the service reports completion or failure. */
    fun onJobFinished() {
        submitting = false
        refreshGallery()
    }

    // --------------------------------------------------------------- gallery

    fun refreshGallery() {
        activeItems = galleryStore.list(archived = false)
        archivedItems = galleryStore.list(archived = true)
        val (active, archived) = galleryStore.counts()
        activeCount = active
        archivedCount = archived
    }

    fun fileOf(item: GalleryItem): File = galleryStore.fileOf(item.name)
    fun refFileOf(item: GalleryItem, index: Int): File? =
        item.refFiles.getOrNull(index)?.let { galleryStore.fileOf(it) }

    fun archive(item: GalleryItem, archived: Boolean) {
        galleryStore.setArchived(item.name, archived)
        refreshGallery()
        post(if (archived) "已收进归档箱" else "已移出归档箱")
    }

    fun delete(item: GalleryItem) {
        galleryStore.delete(item.name)
        if (viewing?.name == item.name) viewing = null
        refreshGallery()
        post("已删除")
    }

    // ------------------------------------------------------------- favorites

    fun saveFavorite() {
        val text = prompt.trim()
        if (text.isEmpty()) {
            post("先写一句，再存成常用")
            return
        }
        val next = favoriteStore.add(text)
        if (next == null) {
            post("常用提示词最多 ${FavoriteStore.MAX} 条，先删一条")
            return
        }
        favorites = next
        post("已存为常用")
    }

    fun removeFavorite(text: String) {
        favorites = favoriteStore.remove(text)
    }

    fun useFavorite(text: String) {
        prompt = text
    }

    // --------------------------------------------------------------- prompt AI

    fun sharpenPrompt(onDone: (String) -> Unit) {
        val text = prompt.trim()
        if (text.isEmpty()) {
            post("先写几个字，我照着扩写")
            return
        }
        val mode = if (scenario == Scenario.TEXT) ModelClient.MODE_EXPAND else ModelClient.MODE_EDIT
        viewModelScope.launch {
            post("正在写…")
            try {
                val result = withContext(Dispatchers.IO) {
                    ModelClient(settings).assist(mode, text)
                }
                val first = result.firstOrNull()
                if (first.isNullOrBlank()) {
                    post("写作助手没有写出内容，请再试一次")
                } else {
                    onDone(first)
                    message = null
                }
            } catch (error: ModelException) {
                post(error.message ?: "写作助手不可用")
            } catch (error: Exception) {
                post("写作助手不可用：${error.message ?: "未知原因"}")
            }
        }
    }

    fun post(text: String) {
        message = text
    }

    fun consumeMessage() {
        message = null
    }

    // ------------------------------------------------------------- persistence

    /** Backs a field with SavedStateHandle so Activity recreation cannot lose it. */
    private fun <T> savedValue(
        handle: SavedStateHandle,
        key: String,
        initial: T,
    ): ReadWriteProperty<Any?, T> = object : ReadWriteProperty<Any?, T> {
        private val state = mutableStateOf(handle.get<T>(key) ?: initial)
        override fun getValue(thisRef: Any?, property: KProperty<*>): T = state.value
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            state.value = value
            handle[key] = value
        }
    }

    /**
     * Reference images need a converter: the bundle can hold primitives and
     * Serializable values, and a list of slots is neither. The URIs may stop being
     * readable if the process died, which the picker path already reports.
     */
    private fun savedRefs(handle: SavedStateHandle): ReadWriteProperty<Any?, List<RefSlot>> =
        object : ReadWriteProperty<Any?, List<RefSlot>> {
            private val state = mutableStateOf(decodeRefs(handle.get<String>(KEY_REFS)))
            override fun getValue(thisRef: Any?, property: KProperty<*>): List<RefSlot> = state.value
            override fun setValue(thisRef: Any?, property: KProperty<*>, value: List<RefSlot>) {
                state.value = value
                handle[KEY_REFS] = encodeRefs(value)
            }
        }

    private fun encodeRefs(slots: List<RefSlot>): String = JSONArray().apply {
        slots.forEach { slot ->
            put(
                JSONObject().apply {
                    put("uri", slot.uri)
                    put("role", slot.role.id)
                    put("w", slot.width)
                    put("h", slot.height)
                },
            )
        }
    }.toString()

    private fun decodeRefs(raw: String?): List<RefSlot> = runCatching {
        if (raw.isNullOrBlank()) return emptyList()
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val entry = array.optJSONObject(index) ?: continue
                val uri = entry.optString("uri", "")
                if (uri.isEmpty()) continue
                add(
                    RefSlot(
                        uri = uri,
                        role = Role.fromId(entry.optString("role", Role.SUBJECT.id)),
                        width = entry.optInt("w", 0),
                        height = entry.optInt("h", 0),
                    ),
                )
            }
        }
    }.getOrElse { emptyList() }

    companion object {
        private const val KEY_SCENARIO = "scenario"
        private const val KEY_PROMPT = "prompt"
        private const val KEY_PRESET = "presetId"
        private const val KEY_REFS = "refs"
    
        private const val KEY_RATIO = "outputRatio"
    private const val KEY_QUALITY = "outputQuality"
        private const val KEY_AUTO_SIZE = "autoSize"
        private const val KEY_SEED = "seedText"
        private const val KEY_BATCH = "batchCount"
    }
}
