package dev.haljk.glow.data

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * One generation, stored on disk.
 *
 * The registry only knows the job in front of you: it is a StateFlow that is gone the
 * moment the process dies, and its history was kept in memory. That left two holes the
 * user hit in real use — a task disappeared from the app after the bar collapsed, and a
 * result could end up with no original to compare because the picker grant was gone by
 * the time the copy would have been written.
 *
 * A task record fixes both: status is written on every poll, and the reference images are
 * copied into the task's own directory at submit time, before anything can revoke them.
 * The result then points back at the task, so the comparison view no longer depends on the
 * gallery copy surviving.
 */
data class TaskRecord(
    val id: String,
    /** The queue's task id, empty until the submit comes back. */
    val remoteId: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    /** one of [STATUS_QUEUED], [STATUS_RUNNING], [STATUS_DONE], [STATUS_FAILED], [STATUS_CANCELLED]. */
    val status: String,
    val summary: String = "",
    val note: String = "",
    val prompt: String = "",
    val presetId: String = "",
    val size: String = "",
    val count: Int = 1,
    val percent: Int = 0,
    val etaSeconds: Int = 0,
    val message: String = "",
    /** Gallery file names this task produced. */
    val resultNames: List<String> = emptyList(),
    /** File names inside this task's directory; see [TaskStore.taskDir]. */
    val referenceFiles: List<String> = emptyList(),
) {
    fun encode(): JSONObject = JSONObject().apply {
        put("id", id)
        put("remoteId", remoteId)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("status", status)
        put("summary", summary)
        put("note", note)
        put("prompt", prompt)
        put("presetId", presetId)
        put("size", size)
        put("count", count)
        put("percent", percent)
        put("etaSeconds", etaSeconds)
        put("message", message)
        put("resultNames", JSONArray(resultNames))
        put("referenceFiles", JSONArray(referenceFiles))
    }

    companion object {
        const val STATUS_QUEUED = "queued"
        const val STATUS_RUNNING = "running"
        const val STATUS_DONE = "done"
        const val STATUS_FAILED = "failed"
        const val STATUS_CANCELLED = "cancelled"

        fun decode(json: JSONObject): TaskRecord = TaskRecord(
            id = json.optString("id"),
            remoteId = json.optString("remoteId", ""),
            createdAt = json.optLong("createdAt", 0L),
            updatedAt = json.optLong("updatedAt", 0L),
            status = json.optString("status", STATUS_FAILED),
            summary = json.optString("summary", ""),
            note = json.optString("note", ""),
            prompt = json.optString("prompt", ""),
            presetId = json.optString("presetId", ""),
            size = json.optString("size", ""),
            count = json.optInt("count", 1),
            percent = json.optInt("percent", 0),
            etaSeconds = json.optInt("etaSeconds", 0),
            message = json.optString("message", ""),
            resultNames = json.optJSONArray("resultNames")?.toStringList() ?: emptyList(),
            referenceFiles = json.optJSONArray("referenceFiles")?.toStringList() ?: emptyList(),
        )

        private fun JSONArray.toStringList(): List<String> =
            buildList { for (i in 0 until length()) add(optString(i)) }
    }
}

/**
 * Task records on disk, one JSON file each.
 *
 * One file per task rather than a single list: a crash mid-write costs one record instead
 * of the whole history, and the directory listing is the index.
 */
class TaskStore(private val directory: File) {

    fun taskDir(id: String): File = File(directory, id).apply { mkdirs() }

    fun file(id: String): File = File(directory, "$id.json")

    fun upsert(record: TaskRecord) = runCatching {
        directory.mkdirs()
        file(record.id).writeText(record.encode().toString(2))
        Unit
    }.isSuccess

    fun update(id: String, change: (TaskRecord) -> TaskRecord): TaskRecord? {
        val current = find(id) ?: return null
        val next = change(current).copy(updatedAt = System.currentTimeMillis())
        upsert(next)
        return next
    }

    fun find(id: String): TaskRecord? = runCatching {
        val target = file(id)
        if (!target.isFile) null else TaskRecord.decode(JSONObject(target.readText()))
    }.getOrNull()

    /** Newest first. */
    fun list(limit: Int = 40): List<TaskRecord> {
        if (!directory.isDirectory) return emptyList()
        return directory.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.mapNotNull { runCatching { TaskRecord.decode(JSONObject(it.readText())) }.getOrNull() }
            ?.sortedByDescending { it.createdAt }
            ?.take(limit)
            ?: emptyList()
    }

    /** Newest task that produced [resultName], so a result can find its original. */
    fun findByResult(resultName: String): TaskRecord? =
        list(200).firstOrNull { it.resultNames.contains(resultName) }

    /**
     * Copy the reference images next to the task record.
     *
     * Done at submit time, while the picker grant is still valid; a resumed job and the
     * viewer both read them from here afterwards.
     */
    fun saveReferences(id: String, bytes: List<ByteArray>): List<String> {
        if (bytes.isEmpty()) return emptyList()
        val dir = taskDir(id)
        return bytes.mapIndexed { index, data ->
            val name = "ref-${index + 1}.png"
            File(dir, name).writeBytes(data)
            name
        }
    }

    fun referenceFiles(id: String): List<File> {
        val record = find(id) ?: return emptyList()
        val dir = taskDir(id)
        return record.referenceFiles.map { File(dir, it) }.filter { it.isFile }
    }

    fun clear() {
        runCatching {
            directory.listFiles()?.forEach { target ->
                if (target.isDirectory) target.deleteRecursively() else target.delete()
            }
        }
    }
}
