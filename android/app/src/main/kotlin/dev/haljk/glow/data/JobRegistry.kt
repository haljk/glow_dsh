package dev.haljk.glow.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one place the UI learns what the background job is doing.
 *
 * A generation runs in a foreground service, not in the screen, so the screen
 * may be torn down and rebuilt at any point. The registry survives both because
 * it is process-scoped and because finished results live on disk.
 */
object JobRegistry {

    enum class Status { RUNNING, DONE, FAILED }

    data class Snapshot(
        val id: String,
        val summary: String,
        val status: Status,
        val startedAt: Long,
        val expected: Int,
        val produced: Int,
        val message: String,
    )

    private val _current = MutableStateFlow<Snapshot?>(null)
    val current: StateFlow<Snapshot?> = _current.asStateFlow()

    /** Bumped whenever a job finishes, so screens can refresh their gallery. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun start(id: String, summary: String, expected: Int) {
        _current.value = Snapshot(
            id = id,
            summary = summary,
            status = Status.RUNNING,
            startedAt = System.currentTimeMillis(),
            expected = expected,
            produced = 0,
            message = "",
        )
    }

    fun progress(produced: Int, message: String = "") {
        val snapshot = _current.value ?: return
        _current.value = snapshot.copy(produced = produced, message = message)
    }

    fun finish(produced: Int) {
        val snapshot = _current.value ?: return
        _current.value = snapshot.copy(status = Status.DONE, produced = produced)
        _revision.value = _revision.value + 1
    }

    fun fail(message: String) {
        val snapshot = _current.value ?: return
        _current.value = snapshot.copy(status = Status.FAILED, message = message)
        _revision.value = _revision.value + 1
    }

    /** Called by the screen once it has shown the outcome. */
    fun clear() {
        _current.value = null
    }
}
