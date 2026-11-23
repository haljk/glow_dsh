package dev.haljk.glow.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One generated image returned by the service. */
class GeneratedImage(
    val bytes: ByteArray,
    val seed: Long,
    val seconds: Double,
    val revisedPrompt: String,
)

/** A reference image on its way to the service. */
class RefImage(val bytes: ByteArray, val mimeType: String, val fileName: String)

class ModelException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The queue accepted the task but asked us to wait before trying again (429). */
class QueueBusy(message: String, val retryAfterMs: Long) : Exception(message)

/** The queue does not know this task any more: it was cleaned up, or never existed. */
class QueueGone(message: String) : Exception(message)

/** Worth another try: transport hiccup, 5xx, or an unreadable answer. */
class QueueRetriable(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The request itself is wrong; sending it again would fail the same way. */
class QueueRejected(message: String) : Exception(message)

/** What the queue says about one task. */
data class QueueState(
    val id: String,
    val status: String,
    val percent: Int,
    val etaSeconds: Int,
    /** 1 means "next"; only meaningful while `status == queued`. */
    val queuePosition: Int,
    val error: String,
    val data: List<QueueResultItem>,
) {
    val isTerminal: Boolean get() = status == STATUS_SUCCEEDED || status == STATUS_FAILED || status == STATUS_CANCELED

    companion object {
        const val STATUS_QUEUED = "queued"
        const val STATUS_RUNNING = "running"
        const val STATUS_SUCCEEDED = "succeeded"
        const val STATUS_FAILED = "failed"
        const val STATUS_CANCELED = "canceled"
    }
}

/** One finished image as the queue describes it. The bytes are fetched separately. */
data class QueueResultItem(
    val url: String,
    val seed: Long,
    val size: String,
    val seconds: Double,
    val revisedPrompt: String,
)

/**
 * Client for the two local model services.
 *
 * Two shapes for the image side. The queue ([Settings.MODE_QUEUE]) takes a task
 * and answers immediately, so every request here is short; the blocking endpoint
 * ([Settings.MODE_SYNC]) needs a timeout as long as one generation, which is
 * exactly the fragility the queue was built to remove.
 *
 * The sync timeouts are still generous because an edit can take 400 seconds, and
 * OkHttp's default read timeout is 10 seconds. The desktop version hit the same
 * class of bug with Node's fetch, which silently caps response headers at 300 s.
 */
class ModelClient(
    private val settings: Settings,
    /** Back-off between 409 retries on the blocking endpoint; tests pass 0. */
    private val busyRetryDelayMs: Long = BUSY_RETRY_DELAY_MS,
) {

    private val imageClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.MINUTES)
        .readTimeout(settings.imageTimeoutSeconds.toLong(), TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Queue calls are all short: submit, one status read, one download. */
    private val queueClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(SUBMIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val downloadClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val llmClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(settings.llmTimeoutSeconds.toLong(), TimeUnit.SECONDS)
        .build()

    // ---------------------------------------------------------------- health

    /**
     * Queue mode reports queue depth and whether the queue's own upstream is up; a
     * degraded queue still accepts tasks, so "ok" means "we can submit", not "the
     * model is warm".
     */
    fun probeImage(): ProbeResult = try {
        if (settings.isQueueMode) probeQueue() else probeSync()
    } catch (error: IOException) {
        ProbeResult(false, "连不上 ${settings.imageEndpoint}")
    }

    private fun probeSync(): ProbeResult {
        val request = Request.Builder().url("${settings.imageEndpoint}/health").get().build()
        imageClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return ProbeResult(false, "HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            val model = runCatching { JSONObject(body).optString("model", "") }.getOrDefault("")
            return ProbeResult(true, model.ifEmpty { "已连接" })
        }
    }

    private fun probeQueue(): ProbeResult {
        val request = Request.Builder().url("${settings.imageEndpoint}/health").get().build()
        queueClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return ProbeResult(false, "HTTP ${response.code}")
            val root = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull()
                ?: return ProbeResult(false, "队列返回了无法解析的内容")
            val upstream = root.optJSONObject("upstream")
            val upstreamOk = upstream?.optBoolean("ok", true) ?: true
            val depth = root.optJSONObject("queue")
            val queued = depth?.optInt("queued", 0) ?: 0
            val running = depth?.optInt("running", 0) ?: 0
            // A degraded queue still queues; surfacing it as "down" would make the
            // user chase a working service.
            val detail = buildString {
                append(if (upstreamOk) "队列已连接" else "队列在，但模型机没响应")
                if (queued > 0 || running > 0) append("（进行中 $running，排队 $queued）")
            }
            return ProbeResult(true, detail)
        }
    }

    fun probeLlm(): ProbeResult = try {
        val request = Request.Builder().url("${settings.llmEndpoint}/health").get().build()
        llmClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                ProbeResult(false, "HTTP ${response.code}")
            } else {
                val body = response.body?.string().orEmpty()
                val model = runCatching { JSONObject(body).optString("model", "") }.getOrDefault("")
                ProbeResult(true, model.ifEmpty { "已连接" })
            }
        }
    } catch (error: IOException) {
        ProbeResult(false, "连不上 ${settings.llmEndpoint}")
    }

    // ------------------------------------------------------- queue: submit

    /**
     * Submit a text-to-image job. Returns the queue's id; nothing is generated yet.
     *
     * The idempotency key is what makes a retry safe: the same key cannot produce a
     * second image, so a submit that timed out after the service accepted it is not
     * a lost job.
     */
    fun submitTextToImage(
        prompt: String,
        size: String,
        count: Int,
        seed: Long,
        idempotencyKey: String,
    ): QueueState {
        val payload = JSONObject().apply {
            put("prompt", prompt)
            put("size", size)
            put("n", count.coerceIn(1, 2))
            put("seed", seed)
        }
        val request = Request.Builder()
            .url("${settings.imageEndpoint}/v1/images/jobs")
            .header("Idempotency-Key", idempotencyKey)
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        return parseSubmitted(callQueue(request))
    }

    /** Submit an edit job. Same flow, multipart body with the references attached. */
    fun submitEdit(
        prompt: String,
        size: String,
        seed: Long,
        references: List<RefImage>,
        idempotencyKey: String,
    ): QueueState {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        builder.addFormDataPart("prompt", prompt)
        builder.addFormDataPart("size", size)
        builder.addFormDataPart("seed", seed.toString())
        references.forEachIndexed { index, ref ->
            builder.addFormDataPart(
                "image",
                ref.fileName.ifEmpty { "ref-${index + 1}.png" },
                ref.bytes.toRequestBody(ref.mimeType.toMediaType()),
            )
        }
        val request = Request.Builder()
            .url("${settings.imageEndpoint}/v1/images/jobs/edits")
            .header("Idempotency-Key", idempotencyKey)
            .post(builder.build())
            .build()
        return parseSubmitted(callQueue(request))
    }

    // --------------------------------------------------------- queue: status

    fun jobState(remoteId: String): QueueState {
        val request = Request.Builder()
            .url("${settings.imageEndpoint}/v1/images/jobs/$remoteId")
            .get()
            .build()
        val response = callQueue(request)
        val body = response.body
        val status = response.code
        if (status == 404) throw QueueGone("队列里查不到这个任务了")
        if (status !in 200..299) {
            val message = describeServiceError(body) ?: "查状态返回 HTTP $status"
            if (status >= 500) throw QueueRetriable(message) else throw QueueRejected(message)
        }
        val root = runCatching { JSONObject(body) }.getOrElse { throw QueueRetriable("队列状态不是 JSON") }
        return parseState(root)
    }

    /** Download one finished image. Results expire after a week, so a 404 is final. */
    fun fetchResult(url: String): ByteArray = try {
        val request = Request.Builder().url(url).get().build()
        downloadClient.newCall(request).execute().use { response ->
            if (response.code == 404) throw QueueGone("结果已过期（队列结果只保留 7 天）")
            if (!response.isSuccessful) throw QueueRetriable("下载结果失败：HTTP ${response.code}")
            response.body?.bytes() ?: throw QueueRetriable("下载结果失败：内容为空")
        }
    } catch (error: IOException) {
        throw QueueRetriable("下载结果失败：${error.message}", error)
    }

    /**
     * Ask the queue to drop a task.
     *
     * @return true when the queue accepted it; false when the task already started
     *   (HTTP 409) — the model cannot be interrupted, so "cancel" stops being
     *   possible at that point.
     */
    fun cancelJob(remoteId: String): Boolean {
        val request = Request.Builder()
            .url("${settings.imageEndpoint}/v1/images/jobs/$remoteId/cancel")
            .post(EMPTY_BODY)
            .build()
        val response = callQueue(request)
        if (response.code in 200..299) return true
        if (response.code == 409) return false
        throw QueueRejected(describeServiceError(response.body) ?: "取消返回 HTTP ${response.code}")
    }

    // ------------------------------------------------------------ queue glue

    private class Reply(val code: Int, val body: String, val retryAfterMs: Long?)

    /**
     * One round trip. Only the answers that are the same everywhere are classified
     * here (busy, retriable transport/5xx); 4xx is left to the caller because a 404
     * means "gone" for a status read but "rejected" for a submit.
     */
    private fun callQueue(request: Request): Reply = try {
        queueClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code == 429) {
                throw QueueBusy("队列已满（服务端最多 64 个任务），稍后自动重试", retryAfterMs(response.header("Retry-After")))
            }
            if (response.code == 408 || response.code >= 500) {
                throw QueueRetriable(describeServiceError(text) ?: "队列服务返回 HTTP ${response.code}")
            }
            Reply(response.code, text, null)
        }
    } catch (error: IOException) {
        throw QueueRetriable("连不上队列服务 ${settings.imageEndpoint}：${error.message}", error)
    }

    private fun parseSubmitted(reply: Reply): QueueState {
        if (reply.code !in 200..299) {
            // A 4xx on submit is a parameter problem: the same body fails again.
            throw QueueRejected(describeServiceError(reply.body) ?: "提交返回 HTTP ${reply.code}")
        }
        val root = runCatching { JSONObject(reply.body) }.getOrElse {
            throw QueueRetriable("队列服务返回了非 JSON 响应")
        }
        val id = root.optString("id", "")
        if (id.isEmpty()) throw QueueRetriable("队列服务没有返回任务号，无法跟进")
        return parseState(root)
    }

    private fun parseState(root: JSONObject): QueueState {
        val progress = root.optJSONObject("progress")
        val list = root.optJSONArray("data")
        val items = buildList {
            if (list != null) {
                for (index in 0 until list.length()) {
                    val entry = list.optJSONObject(index) ?: continue
                    val url = entry.optString("url", "")
                    if (url.isEmpty()) continue
                    add(
                        QueueResultItem(
                            url = url,
                            seed = entry.optLong("seed", 0L),
                            size = entry.optString("size", ""),
                            seconds = entry.optDouble("seconds", 0.0),
                            revisedPrompt = entry.optString("revised_prompt", ""),
                        ),
                    )
                }
            }
        }
        val status = root.optString("status", QueueState.STATUS_QUEUED)
        val ahead = progress?.optInt("queue_position", 0) ?: 0
        return QueueState(
            id = root.optString("id", ""),
            status = status,
            percent = (progress?.optInt("percent", 0) ?: 0).coerceIn(0, 100),
            etaSeconds = (progress?.optInt("eta_seconds", 0) ?: 0).coerceAtLeast(0),
            queuePosition = if (status == QueueState.STATUS_QUEUED && ahead > 0) ahead else 0,
            error = root.optString("error", ""),
            data = items,
        )
    }

    /** Honour the delay the service asks for instead of one we invented. */
    private fun retryAfterMs(header: String?): Long {
        val seconds = header?.trim()?.toLongOrNull() ?: return DEFAULT_RETRY_AFTER_MS
        if (seconds <= 0) return DEFAULT_RETRY_AFTER_MS
        return (seconds * 1000).coerceIn(1_000, 300_000)
    }

    private fun describeServiceError(body: String): String? = runCatching {
        val root = JSONObject(body)
        root.optString("error").ifEmpty {
            root.optString("detail").ifEmpty {
                root.optJSONObject("error")?.optString("message").orEmpty()
            }
        }.take(160)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    // ------------------------------------------------- blocking endpoint (sync)

    fun generate(prompt: String, size: String, count: Int, seed: Long): List<GeneratedImage> {
        val payload = JSONObject().apply {
            put("prompt", prompt)
            put("size", size)
            put("n", count.coerceIn(1, 2))
            put("seed", seed)
            put("response_format", "b64_json")
        }
        val request = Request.Builder()
            .url("${settings.imageEndpoint}/v1/images/generations")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        return callImage(request)
    }

    fun edit(
        prompt: String,
        size: String,
        seed: Long,
        references: List<RefImage>,
    ): List<GeneratedImage> {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        builder.addFormDataPart("prompt", prompt)
        builder.addFormDataPart("size", size)
        builder.addFormDataPart("seed", seed.toString())
        builder.addFormDataPart("response_format", "b64_json")
        references.forEachIndexed { index, ref ->
            builder.addFormDataPart(
                "image",
                ref.fileName.ifEmpty { "ref-${index + 1}.png" },
                ref.bytes.toRequestBody(ref.mimeType.toMediaType()),
            )
        }
        val request = Request.Builder()
            .url("${settings.imageEndpoint}/v1/images/edits")
            .post(builder.build())
            .build()
        return callImage(request)
    }

    // ---------------------------------------------------------------- shared

    private fun callImage(request: Request): List<GeneratedImage> {
        var attempt = 0
        while (true) {
            attempt++
            try {
                imageClient.newCall(request).execute().use { response ->
                    if (response.code == 409) {
                        // The service runs one generation at a time; back off and retry.
                        if (attempt > MAX_BUSY_RETRIES) {
                            throw ModelException("图像服务一直很忙，请稍后再试")
                        }
                        Thread.sleep(busyRetryDelayMs)
                        return@use
                    }
                    if (!response.isSuccessful) {
                        throw ModelException(describeFailure(response.code, response.body?.string().orEmpty()))
                    }
                    return parseImages(response.body?.string().orEmpty())
                }
            } catch (error: IOException) {
                throw ModelException(
                    "连不上图像服务 ${settings.imageEndpoint}，请检查地址与网络（${error.message ?: "未知原因"}）",
                    error,
                )
            }
        }
    }

    private fun parseImages(body: String): List<GeneratedImage> {
        val root = runCatching { JSONObject(body) }.getOrElse {
            throw ModelException("图像服务返回了无法解析的内容")
        }
        val data = root.optJSONArray("data") ?: throw ModelException("图像服务没有返回图片")
        val images = buildList {
            for (index in 0 until data.length()) {
                val entry = data.optJSONObject(index) ?: continue
                val base64 = entry.optString("b64_json", "")
                val bytes = when {
                    // java.util.Base64 needs API 26, which is this app's minimum.
                    base64.isNotEmpty() -> java.util.Base64.getDecoder().decode(base64)
                    entry.optString("url", "").isNotEmpty() -> download(entry.optString("url"))
                    else -> continue
                }
                add(
                    GeneratedImage(
                        bytes = bytes,
                        seed = entry.optLong("seed", 0L),
                        seconds = entry.optDouble("seconds", 0.0),
                        revisedPrompt = entry.optString("revised_prompt", ""),
                    ),
                )
            }
        }
        if (images.isEmpty()) throw ModelException("图像服务没有返回可用的图片")
        return images
    }

    private fun download(url: String): ByteArray = try {
        val request = Request.Builder().url(url).get().build()
        downloadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ModelException("下载生成的图片失败（HTTP ${response.code}）")
            response.body?.bytes() ?: throw ModelException("下载生成的图片失败（内容为空）")
        }
    } catch (error: IOException) {
        throw ModelException("下载生成的图片失败：${error.message}", error)
    }

    private fun describeFailure(code: Int, body: String): String {
        val detail = runCatching {
            JSONObject(body).let { it.optString("error").ifEmpty { it.optString("detail") } }
        }.getOrDefault("")
        val reason = detail.ifBlank { body.take(160) }
        return when {
            code == 422 -> "服务没有接受这个请求：描述不能为空，或尺寸不合法"
            code == 400 -> "请求被拒绝：$reason"
            code in 500..599 -> "图像服务出错了（HTTP $code）：$reason"
            else -> "图像服务返回 HTTP $code：$reason"
        }
    }

    // ------------------------------------------------------------------- LLM

    /**
     * Ask the local model to write or sharpen a prompt.
     *
     * @param mode one of [MODE_EXPAND], [MODE_REFINE], [MODE_VARIATIONS], [MODE_EDIT]
     */
    fun assist(
        mode: String,
        userText: String,
        context: String = "",
        count: Int = 3,
    ): List<String> {
        val messages = JSONArray().apply {
            put(JSONObject().apply { put("role", "system"); put("content", systemPrompt(mode, count)) })
            val userPayload = if (context.isBlank()) userText else "$userText\n\n补充条件：$context"
            put(JSONObject().apply { put("role", "user"); put("content", userPayload) })
        }
        val payload = JSONObject().apply {
            put("model", settings.llmModel)
            put("messages", messages)
            put("temperature", 0.8)
            put("max_tokens", 700)
        }
        val request = Request.Builder()
            .url("${settings.llmEndpoint}/v1/chat/completions")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        val content = try {
            llmClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw ModelException("写作助手返回 HTTP ${response.code}")
                }
                val body = response.body?.string().orEmpty()
                runCatching {
                    JSONObject(body).optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("message")
                        ?.optString("content")
                        .orEmpty()
                }.getOrElse { throw ModelException("写作助手返回了无法解析的内容") }
            }
        } catch (error: IOException) {
            throw ModelException(
                "连不上写作助手 ${settings.llmEndpoint}，请检查地址与网络",
                error,
            )
        }
        val cleaned = content.trim().trim('"', '“', '”', '「', '」').trim()
        if (cleaned.isEmpty()) throw ModelException("写作助手这次没有写出内容，请再点一次")
        if (mode != MODE_VARIATIONS) return listOf(cleaned)

        val lines = cleaned.lines()
            .map { it.trim().removePrefix("-").removePrefix("*").trim() }
            .filter { it.isNotEmpty() }
            .map { it.replace(Regex("^\\d+[.、)]\\s*"), "") }
        return lines.ifEmpty { listOf(cleaned) }.take(count.coerceIn(1, 4))
    }

    private fun systemPrompt(mode: String, count: Int): String = when (mode) {
        MODE_VARIATIONS -> """
            你是图像提示词编辑。针对用户的描述，写出 $count 个风格不同的完整生图提示词，每行一条，不要编号，不要解释。
            硬性要求：只写可观察的视觉事实（主体、材质、光线方向与质感、构图、镜头、色调）；禁止"高质量""杰作""8K""震撼"这类空话；
            禁止任何否定表述（不要、避免、没有、no、without、avoid）；画面中要出现的文字必须用引号原样写出并说明位置。
        """.trimIndent()

        MODE_REFINE -> """
            你是图像提示词编辑。把用户的话改写成一条可直接用于出图的完整提示词，只输出正文，不要解释，不要用引号包裹。
            硬性要求：补充可观察的视觉事实（材质、光线方向、构图、镜头、色调）；删除所有空话；
            把所有排除要求改写成正向陈述，不得出现"不要/避免/没有/no/without/avoid"；画面中要出现的文字用引号原样写出并指定位置。
        """.trimIndent()

        MODE_EDIT -> """
            你是照片修图指令编辑。用户提供一张照片和一句改动要求，你要写出精确的修改指令，只输出正文，不要解释。
            硬性要求：明确"只改哪里"，并把其余部分逐项声明保持不变（人物身份与面部特征、肤色、发型、表情、姿态、服装、镜头角度、光照、清晰度、背景、构图）；
            用可观察的视觉事实描述目标状态（位置、颜色、材质、光线方向）；
            把所有排除要求改写成正向陈述，不得出现否定表述；只输出一段话。
        """.trimIndent()

        else -> """
            你是图像提示词编辑。把用户的简短想法扩写成一条完整、可执行的生图提示词，只输出正文，不要解释，不要用引号包裹。
            硬性要求：写清主体、动作与关系、环境与时间、构图与机位、镜头与焦段、光线方向与质感、色调；只写可观察的视觉事实；
            禁止"高质量""杰作""8K""超精细"这类空话；禁止任何否定表述（不要、避免、没有、no、without、avoid）；
            画面中要出现的文字必须用引号原样写出并说明位置。
        """.trimIndent()
    }

    /** Exposed so a test can prove the 10-second default was actually overridden. */
    fun imageReadTimeoutSeconds(): Long = imageClient.readTimeoutMillis.toLong() / 1000

    /** Queue calls must never inherit the long blocking budget. */
    fun queueReadTimeoutSeconds(): Long = queueClient.readTimeoutMillis.toLong() / 1000

    data class ProbeResult(val ok: Boolean, val detail: String)

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)

        const val MODE_EXPAND = "expand"
        const val MODE_REFINE = "refine"
        const val MODE_VARIATIONS = "variations"
        const val MODE_EDIT = "edit-instruction"

        const val SUBMIT_TIMEOUT_SECONDS = 30L
        const val RESULT_TIMEOUT_SECONDS = 180L
        const val DEFAULT_RETRY_AFTER_MS = 30_000L

        private const val MAX_BUSY_RETRIES = 3
        private const val BUSY_RETRY_DELAY_MS = 20_000L
    }
}
