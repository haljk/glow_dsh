package dev.haljk.glow.data

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

/**
 * The HTTP contract with the model services.
 *
 * Runs against a local fake server, so the pieces most likely to break on a real
 * phone — request shape, base64 parsing, 409 back-off, the error wording, and the
 * timeout that the desktop version lost a job to — are verified without a device.
 */
class ModelClientTest {

    private lateinit var server: MockWebServer
    private lateinit var settings: Settings

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        settings = Settings(imageBaseUrl = base, llmBaseUrl = base)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client() = ModelClient(settings, busyRetryDelayMs = 0)

    private fun pngBase64(): String = Base64.getEncoder().encodeToString(
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
    )

    private fun imagesJson(count: Int = 1, seed: Long = 7L, seconds: Double = 220.5): String {
        val entries = (0 until count).joinToString(",") {
            """
            {"b64_json":"${pngBase64()}","seed":${seed + it},"seconds":$seconds,"revised_prompt":"改写 $it","size":"1024x1024"}
            """.trimIndent()
        }
        return """{"created":1,"data":[$entries],"model":"minimax-h3-image","steps":6}"""
    }

    // ------------------------------------------------------------ generation

    @Test
    fun `text to image sends the documented body`() {
        server.enqueue(MockResponse().setBody(imagesJson()))

        val images = client().generate("雨后的旧书店", "1024x1024", count = 1, seed = 42L)

        assertEquals(1, images.size)
        val request = server.takeRequest()
        assertEquals("/v1/images/generations", request.path)
        val body = request.body.readUtf8()
        assertTrue(body, body.contains("\"prompt\":\"雨后的旧书店\""))
        assertTrue(body, body.contains("\"size\":\"1024x1024\""))
        assertTrue(body, body.contains("\"seed\":42"))
        assertTrue("base64 avoids a second round trip", body.contains("\"response_format\":\"b64_json\""))
    }

    @Test
    fun `generated images are decoded with their metadata`() {
        server.enqueue(MockResponse().setBody(imagesJson(count = 2, seed = 100L, seconds = 72.25)))

        val images = client().generate("x", "512x512", count = 2, seed = 100L)

        assertEquals(2, images.size)
        assertEquals(8, images[0].bytes.size)
        assertEquals(100L, images[0].seed)
        assertEquals(101L, images[1].seed)
        assertEquals(72.25, images[0].seconds, 0.001)
        assertEquals("改写 0", images[0].revisedPrompt)
    }

    @Test
    fun `batch count is clamped to the two the service allows`() {
        server.enqueue(MockResponse().setBody(imagesJson()))
        client().generate("x", "512x512", count = 9, seed = 1L)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("\"n\":2"))
    }

    // ------------------------------------------------------------------ edit

    @Test
    fun `edit uploads a multipart body with one part per reference`() {
        server.enqueue(MockResponse().setBody(imagesJson()))

        val references = listOf(
            RefImage(byteArrayOf(1, 2, 3), "image/jpeg", "reference.jpg"),
            RefImage(byteArrayOf(4, 5, 6), "image/jpeg", "reference.jpg"),
        )
        val images = client().edit("把背景换成海边日落；", "1024x1024", 5L, references)

        assertEquals(1, images.size)
        val request = server.takeRequest()
        assertEquals("/v1/images/edits", request.path)
        assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        val body = request.body.readUtf8()
        assertEquals("two image parts expected", 2, Regex("name=\"image\"").findAll(body).count())
        assertTrue(body, body.contains("name=\"prompt\""))
        assertTrue(body, body.contains("把背景换成海边日落；"))
        assertTrue(body, body.contains("name=\"size\""))
        assertTrue(body, body.contains("name=\"seed\""))
        assertTrue(body, body.contains("image/jpeg"))
    }

    // ------------------------------------------------------------ busy retry

    @Test
    fun `a busy service is retried and then succeeds`() {
        server.enqueue(MockResponse().setResponseCode(409))
        server.enqueue(MockResponse().setBody(imagesJson()))

        val images = client().edit("x", "512x512", 1L, listOf(RefImage(byteArrayOf(1), "image/jpeg", "a.jpg")))

        assertEquals(1, images.size)
        assertEquals("both attempts were made", 2, server.requestCount)
    }

    @Test
    fun `a permanently busy service gives up with a readable message`() {
        repeat(5) { server.enqueue(MockResponse().setResponseCode(409)) }

        val error = assertThrows(ModelException::class.java) {
            client().generate("x", "512x512", 1, 1L)
        }
        assertTrue(error.message!!, error.message!!.contains("忙"))
        assertEquals("must not retry forever", 4, server.requestCount)
    }

    // ------------------------------------------------------------ error text

    @Test
    fun `empty prompt is reported as a rejected request`() {
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"error":"prompt required"}"""))
        val error = assertThrows(ModelException::class.java) { client().generate("", "512x512", 1, 1L) }
        assertTrue(error.message!!, error.message!!.contains("描述不能为空"))
    }

    @Test
    fun `server errors carry the service's own explanation`() {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"error":"size must be a multiple of 32"}"""),
        )
        val error = assertThrows(ModelException::class.java) { client().generate("x", "1000x1000", 1, 1L) }
        assertTrue(error.message!!, error.message!!.contains("multiple of 32"))
    }

    @Test
    fun `an unreachable service is reported with the address`() {
        server.shutdown()
        val error = assertThrows(ModelException::class.java) { client().generate("x", "512x512", 1, 1L) }
        assertTrue(error.message!!, error.message!!.contains("连不上图像服务"))
    }

    @Test
    fun `a response without images is an error, not an empty list`() {
        server.enqueue(MockResponse().setBody("""{"created":1,"data":[]}"""))
        val error = assertThrows(ModelException::class.java) { client().generate("x", "512x512", 1, 1L) }
        assertTrue(error.message!!, error.message!!.contains("没有返回"))
    }

    // --------------------------------------------------------------- timeout

    @Test
    fun `the image read timeout is raised far above the 10 second default`() {
        // OkHttp defaults to 10 s. An edit takes up to ~380 s, so an untouched
        // default would kill every real request — this is the exact failure the
        // desktop version hit with Node's fetch.
        val timeout = client().imageReadTimeoutSeconds()
        assertEquals(900L, timeout)
        assertTrue("must exceed the longest measured edit", timeout >= 400)
    }

    @Test
    fun `a longer budget in settings is honoured`() {
        val custom = client().let { ModelClient(settings.copy(imageTimeoutSeconds = 1800)) }
        assertEquals(1800L, custom.imageReadTimeoutSeconds())
    }

    // ------------------------------------------------------------------ probe

    @Test
    fun `probe reports the model name when the service answers`() {
        server.enqueue(MockResponse().setBody("""{"ok":true,"model":"minimax-h3-image"}"""))
        val result = client().probeImage()
        assertTrue(result.ok)
        assertEquals("minimax-h3-image", result.detail)
        assertEquals("/health", server.takeRequest().path)
    }

    @Test
    fun `probe fails quietly when nothing is listening`() {
        server.shutdown()
        val result = client().probeImage()
        assertTrue(!result.ok)
        assertTrue(result.detail, result.detail.contains("连不上"))
    }

    // -------------------------------------------------------------------- llm

    @Test
    fun `prompt assistance returns the model's text without decoration`() {
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","content":"\"雨后的旧书店，暖黄吊灯。\""}}]}""",
            ),
        )
        val result = client().assist(ModelClient.MODE_EXPAND, "书店")
        assertEquals(listOf("雨后的旧书店，暖黄吊灯。"), result)
    }

    @Test
    fun `variations are split into separate lines and trimmed to the asked count`() {
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"content":"- 第一条：暖调\n- 第二条：冷调\n- 第三条：黑白\n- 第四条：多余"}}]}""",
            ),
        )
        val result = client().assist(ModelClient.MODE_VARIATIONS, "人像", count = 3)
        assertEquals(3, result.size)
        assertEquals("第一条：暖调", result[0])
        assertEquals("第三条：黑白", result[2])
    }

    @Test
    fun `an empty completion is reported instead of replacing the user's text with nothing`() {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"   "}}]}"""))
        val error = assertThrows(ModelException::class.java) { client().assist(ModelClient.MODE_EXPAND, "x") }
        assertTrue(error.message!!, error.message!!.contains("没有写出内容"))
    }

    @Test
    fun `the assistant request names the configured model`() {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"ok"}}]}"""))
        client().assist(ModelClient.MODE_EXPAND, "x")
        val request = server.takeRequest()
        assertEquals("/v1/chat/completions", request.path)
        assertTrue(request.body.readUtf8().contains("\"model\":\"default_model\""))
    }
}
