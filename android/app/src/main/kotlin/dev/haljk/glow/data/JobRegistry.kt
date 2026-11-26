package dev.haljk.glow.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
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
        /**
         * We stopped reading progress; the server did not stop working.
         *
         * Treated as ended by the UI so no bar keeps spinning, but not as a failure: the
         * task id is still on disk and the result may still be waiting to be fetched.
         */
        UNRETRIEVED,
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
        /** Set when the job reaches DONE, FAILED or UNRETRIEVED. */
        val finishedAt: Long = 0,
    ) {
        /**
         * UNRETRIEVED counts as ended: we are no longer following the job in this
         * process, so a live progress bar would be a lie. It is not a failure either.
         */
        val isActive: Boolean
            get() = stage != Stage.DONE && stage != Stage.FAILED && stage != Stage.UNRETRIEVED
    }

    /**
     * Task records on disk. Attached once at startup; every state change below writes
     * through, so the 任务 screen and the comparison view keep working after the process
     * dies and after the in-memory history is cleared.
     */
    var tasks: TaskStore? = null
        private set

    fun attach(store: TaskStore) {
        tasks = store
    }

    private fun recordId(): String = _current.value?.id ?: ""

    private fun writeThrough(change: (TaskRecord) -> TaskRecord) {
        val store = tasks ?: return
        val id = recordId()
        if (id.isEmpty()) return
        if (store.find(id) == null) return
        store.update(id, change)
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

    /**
     * Begin a job.
     *
     * [existingTaskId] is set when the job is resumed after the process died: the record
     * is already on disk, and upserting a second one made one task appear once per
     * restart in 任务. [remoteId] is the server's task id when it is already known — which
     * it is for a resumed job — so it reaches the record instead of living only in memory.
     */
    fun start(
        id: String,
        summary: String,
        expected: Int,
        note: String = "",
        prompt: String = "",
        size: String = "",
        existingTaskId: String = "",
        remoteId: String = "",
    ) {
        cancelRequested = false
        val recordId = existingTaskId.ifEmpty { id }
        tasks?.let { store ->
            val now = System.currentTimeMillis()
            if (store.find(recordId) == null) {
                store.upsert(
                    TaskRecord(
                        id = recordId,
                        remoteId = remoteId,
                        createdAt = now,
                        updatedAt = now,
                        status = TaskRecord.STATUS_QUEUED,
                        summary = summary,
                        note = note,
                        prompt = prompt,
                        size = size,
                        count = expected,
                    ),
                )
            } else {
                store.update(recordId) {
                    it.copy(
                        remoteId = remoteId.ifEmpty { it.remoteId },
                        // Followed again, so whatever it said before is stale.
                        status = TaskRecord.STATUS_QUEUED,
                        summary = it.summary.ifEmpty { summary },
                        message = "",
                    )
                }
            }
        }
        _current.value = Snapshot(
            // The snapshot id must be the record id, or nothing below can write through.
            id = recordId,
            summary = summary,
            stage = Stage.SUBMITTING,
            startedAt = System.currentTimeMillis(),
            expected = expected,
            produced = 0,
            message = "",
            remoteId = remoteId,
        )
    }

    fun submitted(remoteId: String) {
        val snapshot = _current.value ?: return
        _current.value = snapshot.copy(remoteId = remoteId, stage = Stage.QUEUED, message = "")
        // Written to disk, not just held in memory: the record is what a restart reads,
        // and without this the task id was lost the moment the process died.
        writeThrough { it.copy(remoteId = remoteId) }
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
        writeThrough {
            it.copy(
                status = when (stage) {
                    Stage.QUEUED -> TaskRecord.STATUS_QUEUED
                    Stage.RUNNING, Stage.SUBMITTING, Stage.FETCHING -> TaskRecord.STATUS_RUNNING
                    Stage.DONE -> TaskRecord.STATUS_DONE
                    Stage.FAILED -> TaskRecord.STATUS_FAILED
                    Stage.UNRETRIEVED -> TaskRecord.STATUS_UNRETRIEVED
                },
                percent = percent.coerceIn(0, 100),
                etaSeconds = etaSeconds.coerceAtLeast(0),
                message = message,
            )
        }
    }

    fun progress(produced: Int, message: String = "") {
        val snapshot = _current.value ?: return
        _current.value = snapshot.copy(produced = produced, message = message)
    }

    fun finish(produced: Int, resultNames: List<String> = emptyList()) {
        val snapshot = _current.value ?: return
        val done = snapshot.copy(
            stage = Stage.DONE,
            produced = produced,
            percent = 100,
            finishedAt = System.currentTimeMillis(),
        )
        _current.value = done
        remember(done)
        writeThrough {
            it.copy(
                status = TaskRecord.STATUS_DONE,
                percent = 100,
                resultNames = if (resultNames.isEmpty()) it.resultNames else resultNames,
            )
        }
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
        writeThrough { it.copy(status = TaskRecord.STATUS_FAILED, message = message) }
        cancelRequested = false
        _revision.value = _revision.value + 1
    }

    /**
     * We lost the connection, not the job.
     *
     * The progress percentage is kept as it was — it is the last thing the server told
     * us, and resetting it would suggest the work started over. Marking FAILED here was
     * the defect: it announced a failure for a task that was still running, and it
     * invited the caller to delete the only note of the task id.
     */
    fun unretrieved(message: String) {
        val snapshot = _current.value ?: return
        val paused = snapshot.copy(
            stage = Stage.UNRETRIEVED,
            message = message,
            finishedAt = System.currentTimeMillis(),
        )
        _current.value = paused
        remember(paused)
        writeThrough { it.copy(status = TaskRecord.STATUS_UNRETRIEVED, message = message) }
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
    /**
     * The picker URIs the job was submitted with.
     *
     * A resumed edit used to arrive with no references at all, so its result was saved
     * without the original copy and the comparison view silently showed one image. The
     * grants usually survive a process death; when they do not, reading them fails and
     * the result is stored without a copy as before.
     */
    val referenceUris: List<String> = emptyList(),
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
                put("referenceUris", JSONArray(job.referenceUris))
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
            referenceUris = json.optJSONArray("referenceUris")?.let { array ->
                buildList { for (i in 0 until array.length()) add(array.optString(i)) }
            } ?: emptyList(),
        )
    }.getOrNull()

    fun clear() {
        runCatching { if (file.isFile) file.delete() }
    }
}
