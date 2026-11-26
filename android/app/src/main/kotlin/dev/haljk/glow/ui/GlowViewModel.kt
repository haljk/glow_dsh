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
import dev.haljk.glow.data.TaskRecord
import dev.haljk.glow.data.TaskStore
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
    val taskStore = TaskStore(File(getApplication<Application>().filesDir, "tasks"))

    init {
        // One store for the whole process: the registry writes every state change through
        // it, and the viewer reads originals back from it.
        JobRegistry.attach(taskStore)
    }

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
    // One draft per mode. A shared field meant switching to 精修 carried the 文生图
    // sentence over, and the reverse overwrote what the user had typed for the other.
    private var draftText by savedValue(saved, KEY_PROMPT_TEXT, "")
    private var draftRefine by savedValue(saved, KEY_PROMPT_REFINE, "")
    private var draftCompose by savedValue(saved, KEY_PROMPT_COMPOSE, "")

    var prompt: String
        get() = when (scenario) {
            Scenario.TEXT -> draftText
            Scenario.RETOUCH -> draftRefine
            Scenario.COMBINE -> draftCompose
        }
        set(value) {
            when (scenario) {
                Scenario.TEXT -> draftText = value
                Scenario.RETOUCH -> draftRefine = value
                Scenario.COMBINE -> draftCompose = value
            }
        }

    init {
        // Builds before per-mode drafts kept one "prompt"; carry that text into the
        // 文生图 draft so an upgrade does not silently lose what the user had typed.
        if (draftText.isEmpty()) {
            saved.get<String>(KEY_PROMPT_LEGACY)?.takeIf { it.isNotBlank() }?.let { draftText = it }
        }
    }
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

    /** Set when the gallery opens a result straight into 并排, then cleared on close. */
    var openInSideBySide by mutableStateOf(false)

    val presets get() = Presets.ALL


    val maxRefs: Int get() = if (scenario == Scenario.RETOUCH) 2 else 5

    init {
        refreshGallery()
        probeServices()
        resumePendingJob()
        // [submitting] used to be cleared only when the workbench screen saw a job end,
        // so a job that ended while the user stood on 任务 left the flag stuck: 生成图片
        // did nothing and 继续取回 refused. The registry is the one source of truth.
        viewModelScope.launch {
            JobRegistry.current.collect { snapshot ->
                if (snapshot == null || !snapshot.isActive) submitting = false
            }
        }
    }

    /**
     * The queue still owns whatever we submitted before the process died; the only
     * thing that went missing is our memory of it. The task id is on disk, so the
     * poll picks up where it stopped instead of paying for a second generation.
     */
    private fun resumePendingJob(announce: Boolean = false): Boolean {
        if (JobRegistry.current.value?.isActive == true) return false
        val store = PendingJobStore(getApplication<Application>().filesDir)
        val pending = store.load() ?: return false
        if (pending.remoteId.isEmpty()) return false
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
        return try {
            GenerationService.start(getApplication(), request)
            if (announce) post("正在继续取回，结果一旦跑完就会存进来")
            true
        } catch (error: Exception) {
            submitting = false
            // The task id stays on disk: a failure to start the service is a local
            // problem, and throwing the id away would lose a result the server still has.
            post("没能启动继续取回：${error.message ?: "未知原因"}")
            false
        }
    }

    /**
     * The 继续取回 action on a task that ended while the result was still on the server.
     *
     * Same path as the automatic resume, but reachable by hand: the automatic one only
     * runs when the app starts, so a job that gave up while the user was looking at the
     * screen would otherwise wait for the next launch.
     */
    fun recoverPending(): Boolean {
        // Read the registry rather than [submitting]: the flag is only cleared when the
        // workbench notices a job ending, so a task that ended while the user stood on
        // 任务 would refuse every 继续取回 for the rest of the session.
        if (JobRegistry.current.value?.isActive == true) return false
        if (!resumePendingJob(announce = true)) {
            post("这次任务的取回信息已经不在了，可能已经被清理")
            return false
        }
        return true
    }

    /**
     * 继续取回 for one row in 任务.
     *
     * The row is the source of truth here, not the pending file: only one job can be
     * pending at a time, so a later submit overwrites it and the old row's 继续取回 would
     * otherwise go and fetch the new task. The task id is on the record, which is enough
     * to ask the server again.
     */
    fun recoverTask(record: TaskRecord): Boolean {
        if (JobRegistry.current.value?.isActive == true) return true
        if (record.remoteId.isEmpty()) {
            post("这条记录没有留下任务号，取不回来了")
            return false
        }
        val request = JobRequest(
            mode = JobRequest.MODE_TEXT,
            prompt = record.prompt.ifBlank { record.note },
            note = record.note,
            presetId = record.presetId,
            size = record.size.ifBlank { "1024x1024" },
            // The record does not keep the seed; the queue returns the real one with the
            // result, and a resumed job is not regenerated, so an empty seed changes
            // nothing but the file name.
            seed = 0L,
            count = record.count.coerceIn(1, 2),
            referenceUris = emptyList(),
            summary = record.summary,
            resumeRemoteId = record.remoteId,
            resumeTaskId = record.id,
            idempotencyKey = "",
        )
        return try {
            GenerationService.start(getApplication(), request)
            submitting = true
            post("正在继续取回，结果一旦跑完就会存进来")
            true
        } catch (error: Exception) {
            post("没能启动继续取回：${error.message ?: "未知原因"}")
            false
        }
    }

    // -------------------------------------------------------------- settings

    fun saveSettings(next: Settings) {
        settings = settingsStore.save(next)
        probeServices()
    }

    /**
     * 直发模式, switched from the workbench rather than the settings screen.
     *
     * It is saved like any other setting so it survives a restart, but it is not a
     * connection setting: probing both services on every tap would be noise, and the
     * status pill has nothing new to say.
     */
    fun setRawInstruction(on: Boolean) {
        if (settings.rawInstruction == on) return
        settings = settingsStore.save(settings.copy(rawInstruction = on))
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

    fun addRefs(uris: List<String>, defaultRole: Role = Role.SCENE) {
        val room = maxRefs - refs.size
        if (room <= 0) {
            post("最多 ${maxRefs} 张参考图")
            return
        }
        val added = uris.take(room).mapIndexed { index, uri ->
            // The first image is always the subject; extras default to context.
            // The first photo is what you are changing; anything after it is context.
            // Defaulting every slot to 主体 made the second photo read as a second
            // subject, which is not what 合成 means.
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

    /**
     * The panel shows what the user typed and what actually gets sent as two blocks.
     *
     * They used to be one string, which invited the suspicion that the app rewrites the
     * description behind your back. It does not: the note goes in verbatim and the
     * constraints are appended around it.
     */
    fun previewParts(): Pair<String, String> {
        val typed = prompt.trim().ifEmpty { "（还没写）" }
        return typed to previewInstruction()
    }

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
                    // 直发原文 drops the preset sentence, the per-photo role sentence and the
                    // protection clause: what the user wrote is the whole instruction. The
                    // photos still go along — they are input, not wording.
                    raw = settings.rawInstruction,
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
        // 直发模式下预设不再提供任何文字，所以「有预设就能提交」不再成立。
        Scenario.RETOUCH -> refs.isNotEmpty() && (
            if (settings.rawInstruction) prompt.isNotBlank() else (presetId != null || prompt.isNotBlank())
            )
        Scenario.COMBINE -> refs.isNotEmpty() && (!settings.rawInstruction || prompt.isNotBlank())
    }

    fun submitBlockedReason(): String? = when {
        scenario == Scenario.TEXT && prompt.isBlank() -> "先写一句描述，或点「帮我写」"
        scenario != Scenario.TEXT && refs.isEmpty() -> "先选一张照片"
        settings.rawInstruction && scenario != Scenario.TEXT && prompt.isBlank() ->
            "直发模式下预设不参与，要把要求自己写全"
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
    /**
     * The original that belongs to this result.
     *
     * Prefers the copy next to the image, then falls back to the task that produced it —
     * the task copy is written at submit time and survives what the gallery copy does not.
     */
    fun originalOf(item: GalleryItem): File? {
        refFileOf(item, 0)?.let { return it }
        val task = taskStore.findByResult(item.name) ?: return null
        return taskStore.referenceFiles(task.id).firstOrNull()
    }

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
        private const val KEY_PROMPT_TEXT = "promptText"
    private const val KEY_PROMPT_REFINE = "promptRefine"
    private const val KEY_PROMPT_COMPOSE = "promptCompose"
    private const val KEY_PROMPT_LEGACY = "prompt"
        private const val KEY_PRESET = "presetId"
        private const val KEY_REFS = "refs"
    
        private const val KEY_RATIO = "outputRatio"
    private const val KEY_QUALITY = "outputQuality"
        private const val KEY_AUTO_SIZE = "autoSize"
        private const val KEY_SEED = "seedText"
        private const val KEY_BATCH = "batchCount"
    }
}
