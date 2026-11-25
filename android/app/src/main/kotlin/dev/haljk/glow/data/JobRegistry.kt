package dev.haljk.glow.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/**
 * The one place the UI learns what the background job is doing.
 *
 * A generation runs in a foreground service, not in the screen, so the screen
 * may be torn down and rebuilt at any point. The registry survives both because
 * it is process-scoped and because finished results live on disk.
 *
 * Since the queue exists, "running" is not one state any more: a task can sit in
 * the queue behind someone else's edit for minutes before the model sees it. The
 * stage is what the banner says out loud, because "已等 4 分钟" with no word about
 * queueing reads as a hang.
 */
object JobRegistry {

    enum class Stage {
        /** Building the request and posting it; no task id yet. */
        SUBMITTING,
        QUEUED,
        RUNNING,
        /** The image exists; we are downloading it. */
        FETCHING,
        DONE,
        FAILED,
    }

    data class Snapshot(
        val id: String,
        val summary: String,
        val stage: Stage,
        val startedAt: Long,
        val expected: Int,
        val produced: Int,
        val message: String,
        /** The queue's task id, empty until the submit comes back. */
        val remoteId: String = "",
        val percent: Int = 0,
        val etaSeconds: Int = 0,
        /** 1 means "next"; only set while queued. */
        val queuePosition: Int = 0,
        /** Set when the job reaches DONE or FAILED. */
        val finishedAt: Long = 0,
    ) {
        val isActive: Boolean get() = stage != Stage.DONE && stage != Stage.FAILED
    }

    private val _current = MutableStateFlow<Snapshot?>(null)
    val current: StateFlow<Snapshot?> = _current.asStateFlow()

    /**
     * Jobs that already ended, newest first.
     *
     * The bottom bar only speaks for the job in front of you; once it is cleared or the
     * user walks away, nothing in the app remembers it. This is what the 任务 screen lists
     * behind the live one.
     */
    private val _recent = MutableStateFlow<List<Snapshot>>(emptyList())
    val recent: StateFlow<List<Snapshot>> = _recent.asStateFlow()

    /** Bumped whenever a job finishes, so screens can refresh their gallery. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** Set from the UI; the service reads it between polls. */
    @Volatile
    var cancelRequested: Boolean = false
        private set

    fun start(id: String, summary: String, expected: Int) {
        cancelRequested = false
        _current.value = Snapshot(
            id = id,
            summary = summary,
            stage = Stage.SUBMITTING,
            startedAt = System.currentTimeMillis(),
            expected = expected,
            produced = 0,
            message = "",
        )
    }

    fun submitted(remoteId: String) {
        val snapshot = _current.value ?: return
        _current.value = snapshot.copy(remoteId = remoteId, stage = Stage.QUEUED, message = "")
    }

    /** One poll's worth of news. [percent] and [eta] are estimates; never promise them. */
    fun stage(
        stage: Stage,
        percent: Int = 0,
        etaSeconds: Int = 0,
        queuePosition: Int = 0,
        message: String = "",
    ) {
        val snapshot = _current.value ?: return
        _current.value = snapshot.copy(
            stage = stage,
            percent = percent.coerceIn(0, 100),
            etaSeconds = etaSeconds.coerceAtLeast(0),
            queuePosition = queuePosition.coerceAtLeast(0),
            message = message,
        )
    }

    fun progress(produced: Int, message: String = "") {
        val snapshot = _current.value ?: return
        _current.value = snapshot.copy(produced = produced, message = message)
    }

    fun finish(produced: Int) {
        val snapshot = _current.value ?: return
        val done = snapshot.copy(
            stage = Stage.DONE,
            produced = produced,
            percent = 100,
            finishedAt = System.currentTimeMillis(),
        )
        _current.value = done
        remember(done)
        cancelRequested = false
        _revision.value = _revision.value + 1
    }

    fun fail(message: String) {
        val snapshot = _current.value ?: return
        val failed = snapshot.copy(
            stage = Stage.FAILED,
            message = message,
            finishedAt = System.currentTimeMillis(),
        )
        _current.value = failed
        remember(failed)
        cancelRequested = false
        _revision.value = _revision.value + 1
    }

    private fun remember(snapshot: Snapshot) {
        _recent.value = (listOf(snapshot) + _recent.value).take(MAX_REMEMBERED)
    }

    /** Drop one finished job from the list, or a job the user no longer cares about. */
    fun forget(id: String) {
        _recent.value = _recent.value.filterNot { it.id == id }
    }

    fun forgetAll() {
        _recent.value = emptyList()
    }

    fun requestCancel() {
        cancelRequested = true
    }

    private const val MAX_REMEMBERED = 12

    /** Called by the screen once it has shown the outcome. */
    fun clear() {
        _current.value = null
        cancelRequested = false
    }
}

/**
 * The queue task we are currently following, written to disk.
 *
 * The cutover note asks for this explicitly: a client that forgets its task id
 * cannot pick the result back up, and the result expires after seven days anyway.
 * One job at a time is enough here — the UI only ever runs one.
 */
data class PendingJob(
    val remoteId: String,
    val summary: String,
    val expected: Int,
    val startedAt: Long,
    val prompt: String,
    val note: String,
    val presetId: String?,
    val size: String,
    val seed: Long,
    val isEdit: Boolean,
    val idempotencyKey: String,
)

class PendingJobStore(private val directory: File) {
    private val file = File(directory, "pending-job.json")

    fun save(job: PendingJob) = runCatching {
        directory.mkdirs()
        file.writeText(
            JSONObject().apply {
                put("remoteId", job.remoteId)
                put("summary", job.summary)
                put("expected", job.expected)
                put("startedAt", job.startedAt)
                put("prompt", job.prompt)
                put("note", job.note)
                put("presetId", job.presetId ?: "")
                put("size", job.size)
                put("seed", job.seed)
                put("isEdit", job.isEdit)
                put("idempotencyKey", job.idempotencyKey)
            }.toString(2),
        )
    }.isSuccess

    fun load(): PendingJob? = runCatching {
        if (!file.isFile) return null
        val json = JSONObject(file.readText())
        val remoteId = json.optString("remoteId", "")
        if (remoteId.isEmpty()) return null
        PendingJob(
            remoteId = remoteId,
            summary = json.optString("summary", ""),
            expected = json.optInt("expected", 1),
            startedAt = json.optLong("startedAt", System.currentTimeMillis()),
            prompt = json.optString("prompt", ""),
            note = json.optString("note", ""),
            presetId = json.optString("presetId", "").ifEmpty { null },
            size = json.optString("size", ""),
            seed = json.optLong("seed", 0L),
            isEdit = json.optBoolean("isEdit", false),
            idempotencyKey = json.optString("idempotencyKey", ""),
        )
    }.getOrNull()

    fun clear() {
        runCatching { if (file.isFile) file.delete() }
    }
}
