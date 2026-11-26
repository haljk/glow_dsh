package dev.haljk.glow.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The three ways a finished image used to go missing.
 *
 * All three are bookkeeping, not rendering: the server kept the task, we forgot its id,
 * deleted the note of it, or wrote the same task twice. They are pure file and JSON logic,
 * so they are checked here without a device.
 */
class RecoveryTest {

    @get:Rule
    val temp = TemporaryFolder()

    @After
    fun detach() {
        // The registry is a process-wide singleton; leaving a job in it would leak into
        // whatever test class runs next in the same JVM.
        JobRegistry.clear()
        JobRegistry.forgetAll()
        JobRegistry.attach(TaskStore(temp.newFolder("after-test")))
    }

    // ---- the task id has to survive the process

    @Test
    fun `pending job keeps its task id and everything else across a restart`() {
        val dir = temp.newFolder("pending")
        val job = PendingJob(
            remoteId = "job-9f3a",
            summary = "生成 · 海边日落…",
            expected = 2,
            startedAt = 1_700_000_000_000L,
            prompt = "海边的日落，宽幅",
            note = "日落",
            presetId = "bg-replace",
            size = "1024x1024",
            seed = 42L,
            isEdit = true,
            idempotencyKey = "glow-42-1",
            referenceUris = listOf("content://media/1", "content://media/2"),
        )
        assertTrue(PendingJobStore(dir).save(job))
        assertTrue(File(dir, "pending-job.json").isFile)

        // A new store object stands in for the next process: only the file carries over.
        assertEquals(job, PendingJobStore(dir).load())

        PendingJobStore(dir).clear()
        assertNull("an explicitly finished job leaves nothing behind", PendingJobStore(dir).load())
    }

    // ---- one task, one record

    @Test
    fun `find by remote id returns the newest record and nothing for an empty id`() {
        val store = TaskStore(temp.newFolder("tasks"))
        store.upsert(
            TaskRecord(
                id = "old",
                remoteId = "job-1",
                createdAt = 100L,
                updatedAt = 100L,
                status = TaskRecord.STATUS_RUNNING,
            ),
        )
        store.upsert(
            TaskRecord(
                id = "new",
                remoteId = "job-1",
                createdAt = 200L,
                updatedAt = 200L,
                status = TaskRecord.STATUS_UNRETRIEVED,
            ),
        )
        store.upsert(
            TaskRecord(
                id = "other",
                remoteId = "job-2",
                createdAt = 300L,
                updatedAt = 300L,
                status = TaskRecord.STATUS_QUEUED,
            ),
        )

        assertEquals("new", store.findByRemote("job-1")?.id)
        assertEquals("other", store.findByRemote("job-2")?.id)
        assertNull(store.findByRemote("job-missing"))
        assertNull("an empty id must not match the first record with no task yet", store.findByRemote(""))
    }

    @Test
    fun `submitting writes the task id onto the record on disk`() {
        val store = TaskStore(temp.newFolder("submit"))
        JobRegistry.attach(store)

        JobRegistry.start("t-42-1", "生成 · 海边日落…", 1, prompt = "海边的日落")
        JobRegistry.submitted("job-abc")

        assertEquals("job-abc", JobRegistry.current.value?.remoteId)
        // The disk copy is the one that survives; the in-memory snapshot is not enough.
        assertEquals(
            "a restart can only resume what the record knows",
            "job-abc",
            store.find("t-42-1")?.remoteId,
        )
    }

    @Test
    fun `resuming a job continues its record instead of adding a second one`() {
        val store = TaskStore(temp.newFolder("resume"))
        JobRegistry.attach(store)

        // First run: the record is created, then learns the server's task id.
        JobRegistry.start("t-1-1", "生成 · 海边日落…", 1)
        JobRegistry.submitted("job-11")
        JobRegistry.clear()

        val existing = requireNotNull(store.findByRemote("job-11")) { "the first run must leave a record" }

        // Second run, after the process died: the service looks the record up by task id
        // and passes it in, so the same job cannot appear twice in 任务.
        JobRegistry.start(
            id = "t-1-2",
            summary = "生成 · 海边日落…",
            expected = 1,
            existingTaskId = existing.id,
            remoteId = "job-11",
        )

        assertEquals(existing.id, JobRegistry.current.value?.id)
        assertEquals("job-11", JobRegistry.current.value?.remoteId)
        assertEquals("the same job must not get a second row", 1, store.list().size)
        assertEquals("job-11", store.find(existing.id)?.remoteId)
    }

    // ---- losing the connection is not losing the job

    @Test
    fun `an unretrievable task ends as neither done nor failed`() {
        val store = TaskStore(temp.newFolder("unretrieved"))
        JobRegistry.attach(store)
        JobRegistry.start("t-7-1", "生成 · 海边日落…", 1)
        JobRegistry.submitted("job-7")
        JobRegistry.stage(JobRegistry.Stage.RUNNING, percent = 40)

        val message = "现在连不上服务，任务还在服务端（任务号 job-7）。回到应用点『继续取回』"
        JobRegistry.unretrieved(message)

        val snapshot = requireNotNull(JobRegistry.current.value)
        assertEquals(JobRegistry.Stage.UNRETRIEVED, snapshot.stage)
        assertEquals("the last known progress is kept", 40, snapshot.percent)
        assertFalse("no bar may keep spinning for a job we stopped reading", snapshot.isActive)
        assertTrue(snapshot.finishedAt > 0)
        assertFalse(JobRegistry.cancelRequested)

        val record = requireNotNull(store.find("t-7-1"))
        assertEquals(TaskRecord.STATUS_UNRETRIEVED, record.status)
        assertTrue("the record has to explain itself", record.message.contains("job-7"))
        assertEquals("the task must still be listed", 1, JobRegistry.recent.value.count { it.id == "t-7-1" })
    }
}
