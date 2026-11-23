package dev.haljk.glow.data

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The queue contract: submit, poll, fetch, cancel.
 *
 * Everything here is a rule the cutover note states explicitly, so each test names
 * the promise it holds: the submit answers 202 with an id, a 429 carries its own
 * back-off, a failed task carries the model's own error, and only a queued task can
 * be cancelled.
 */
class QueueClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: ModelClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        client = ModelClient(
            Settings(imageApiMode = Settings.MODE_QUEUE, imageBaseUrl = base, llmBaseUrl = base),
            busyRetryDelayMs = 0,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `submit sends the documented body and reads the id back`() {
        server.enqueue(
            MockResponse().setResponseCode(202)
                .setBody("""{"id":"job_20261123-095937_dbf7e100","status":"queued"}"""),
        )

        val state = client.submitTextToImage("一盏暖光台灯", "1024x1024", 1, 42L, "glow-42-1")

        assertEquals("job_20261123-095937_dbf7e100", state.id)
        assertEquals("queued", state.status)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/images/jobs", request.path)
        assertEquals("glow-42-1", request.getHeader("Idempotency-Key"))
        val body = request.body.readUtf8()
        assertTrue(body, body.contains("\"prompt\""))
        assertTrue(body, body.contains("\"size\":\"1024x1024\""))
        // response_format is ignored by the queue; sending it invites confusion.
        assertTrue(body, !body.contains("response_format"))
    }

    @Test
    fun `an edit submits the references as multipart`() {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"id":"job_edit","status":"queued"}"""))

        client.submitEdit(
            prompt = "把背景换成浅灰",
            size = "1024x1024",
            seed = 7L,
            references = listOf(RefImage(byteArrayOf(1, 2, 3), "image/png", "ref-1.png")),
            idempotencyKey = "glow-7-2",
        )

        val request = server.takeRequest()
        assertEquals("/v1/images/jobs/edits", request.path)
        assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        val body = request.body.readUtf8()
        assertTrue(body, body.contains("name=\"image\"; filename=\"ref-1.png\""))
    }

    @Test
    fun `a full queue reports the retry after it asked for`() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30"))

        val busy = assertThrows(QueueBusy::class.java) {
            client.submitTextToImage("x", "512x512", 1, 1L, "k")
        }
        assertEquals(30_000L, busy.retryAfterMs)
    }

    @Test
    fun `a queue without retry after falls back to thirty seconds`() {
        server.enqueue(MockResponse().setResponseCode(429))

        val busy = assertThrows(QueueBusy::class.java) {
            client.submitTextToImage("x", "512x512", 1, 1L, "k")
        }
        assertEquals(ModelClient.DEFAULT_RETRY_AFTER_MS, busy.retryAfterMs)
    }

    @Test
    fun `a bad request is rejected rather than retried`() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"size 必须是 32 的倍数"}"""))

        val rejected = assertThrows(QueueRejected::class.java) {
            client.submitTextToImage("x", "1000x1000", 1, 1L, "k")
        }
        assertTrue(rejected.message!!, rejected.message!!.contains("32"))
    }

    @Test
    fun `a status read carries progress and queue position`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"j1","status":"queued","progress":{"percent":0,"queue_position":3,"eta_seconds":120}}""",
            ),
        )

        val state = client.jobState("j1")

        assertEquals(QueueState.STATUS_QUEUED, state.status)
        assertEquals(3, state.queuePosition)
        assertEquals(120, state.etaSeconds)
        assertTrue(!state.isTerminal)
        assertEquals("/v1/images/jobs/j1", server.takeRequest().path)
    }

    @Test
    fun `a finished task lists its images`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"j1","status":"succeeded","progress":{"percent":100},
                    "data":[{"url":"http://x/v1/images/jobs/j1/result.png","seed":42,"size":"512x512","seconds":24.1},
                            {"url":"http://x/v1/images/jobs/j1/result-1.png","seed":43,"size":"512x512","seconds":24.1}]}""",
            ),
        )

        val state = client.jobState("j1")

        assertTrue(state.isTerminal)
        assertEquals(QueueState.STATUS_SUCCEEDED, state.status)
        assertEquals(2, state.data.size)
        assertEquals(42L, state.data[0].seed)
        assertTrue(state.data[1].url.endsWith("result-1.png"))
    }

    @Test
    fun `a failed task keeps the model's own words`() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"id":"j1","status":"failed","error":"上游显存不足","http_status":500}"""),
        )

        val state = client.jobState("j1")

        assertEquals(QueueState.STATUS_FAILED, state.status)
        assertEquals("上游显存不足", state.error)
    }

    @Test
    fun `an unknown task is gone, not retriable`() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"not found"}"""))

        assertThrows(QueueGone::class.java) { client.jobState("j404") }
    }

    @Test
    fun `a five hundred on status is worth retrying`() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"busy"}"""))

        assertThrows(QueueRetriable::class.java) { client.jobState("j1") }
    }

    @Test
    fun `cancelling a queued task is accepted`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"canceled"}"""))

        assertTrue(client.cancelJob("j1"))
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/images/jobs/j1/cancel", request.path)
    }

    @Test
    fun `cancelling a running task is refused with 409`() {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"任务已经开始"}"""))

        // false, not an exception: the caller needs to know "you are too late",
        // which is a normal answer, not a failure.
        assertTrue(!client.cancelJob("j1"))
    }

    @Test
    fun `an expired result says so`() {
        server.enqueue(MockResponse().setResponseCode(404))

        val gone = assertThrows(QueueGone::class.java) {
            client.fetchResult("${server.url("/")}v1/images/jobs/j1/result.png")
        }
        assertTrue(gone.message!!, gone.message!!.contains("7 天"))
    }

    @Test
    fun `a result download returns the bytes`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(png)))

        val bytes = client.fetchResult("${server.url("/")}v1/images/jobs/j1/result.png")

        assertEquals(4, bytes.size)
    }

    @Test
    fun `the queue probe reports depth and a degraded upstream`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":"ok","upstream":{"ok":false},"queue":{"queued":2,"running":1}}""",
            ),
        )

        val result = client.probeImage()

        assertTrue(result.ok)
        assertTrue(result.detail, result.detail.contains("模型机没响应"))
        assertTrue(result.detail, result.detail.contains("排队 2"))
    }
}
