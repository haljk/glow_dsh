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

/**
 * Client for the two local model services.
 *
 * The timeouts here are deliberately large: an edit can take 380 seconds, and
 * OkHttp's default read timeout is 10 seconds, which would kill every real
 * request. The desktop version hit exactly this class of bug with Node's fetch,
 * which silently caps response headers at 300 seconds.
 */
class ModelClient(private val settings: Settings) {

    private val imageClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.MINUTES)
        .readTimeout(settings.imageTimeoutSeconds.toLong(), TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Downloading a finished image needs none of the long budget. */
    private val downloadClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val llmClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(settings.llmTimeoutSeconds.toLong(), TimeUnit.SECONDS)
        .build()

    // ---------------------------------------------------------------- health

    fun probeImage(): ProbeResult = probe(settings.imageEndpoint, imageClient)
    fun probeLlm(): ProbeResult = probe(settings.llmEndpoint, llmClient)

    private fun probe(baseUrl: String, client: OkHttpClient): ProbeResult = try {
        val request = Request.Builder().url("$baseUrl/health").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                ProbeResult(false, "HTTP ${response.code}")
            } else {
                val body = response.body?.string().orEmpty()
                val model = runCatching { JSONObject(body).optString("model", "") }.getOrDefault("")
                ProbeResult(true, model.ifEmpty { "已连接" })
            }
        }
    } catch (error: IOException) {
        ProbeResult(false, "连不上 $baseUrl")
    }

    // ------------------------------------------------------------ text to image

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

    // -------------------------------------------------------------- edit / compose

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
                        Thread.sleep(BUSY_RETRY_DELAY_MS)
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
                    base64.isNotEmpty() -> android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
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

    data class ProbeResult(val ok: Boolean, val detail: String)

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        const val MODE_EXPAND = "expand"
        const val MODE_REFINE = "refine"
        const val MODE_VARIATIONS = "variations"
        const val MODE_EDIT = "edit-instruction"

        private const val MAX_BUSY_RETRIES = 3
        private const val BUSY_RETRY_DELAY_MS = 20_000L
    }
}
