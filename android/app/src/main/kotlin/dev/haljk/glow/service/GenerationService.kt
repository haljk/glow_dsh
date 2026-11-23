package dev.haljk.glow.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.haljk.glow.MainActivity
import dev.haljk.glow.R
import dev.haljk.glow.data.GalleryStore
import dev.haljk.glow.data.ImageIntake
import dev.haljk.glow.data.JobRegistry
import dev.haljk.glow.data.JobRequest
import dev.haljk.glow.data.ModelClient
import dev.haljk.glow.data.ModelException
import dev.haljk.glow.data.PendingJob
import dev.haljk.glow.data.PendingJobStore
import dev.haljk.glow.data.QueueBusy
import dev.haljk.glow.data.QueueGone
import dev.haljk.glow.data.QueueRejected
import dev.haljk.glow.data.QueueResultItem
import dev.haljk.glow.data.QueueRetriable
import dev.haljk.glow.data.QueueState
import dev.haljk.glow.data.RefImage
import dev.haljk.glow.data.Settings
import dev.haljk.glow.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * Runs one generation while the app is in the background.
 *
 * Without a server, the app is the backend, and Android suspends ordinary
 * background work. A 380-second edit would therefore die the moment the user
 * switches apps. A foreground service with a visible notification is the
 * supported way to keep it alive, and it also tells the user why the phone is
 * working.
 *
 * Two shapes since the queue exists. In queue mode this service submits a task,
 * polls it every few seconds, then downloads the result — every request is short,
 * so a dropped connection costs one poll instead of a whole generation. In sync
 * mode it keeps the old behaviour: one long request the service must survive.
 */
class GenerationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val raw = intent?.getStringExtra(EXTRA_REQUEST)
        val request = raw?.let { JobRequest.fromJson(it) }
        if (request == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        createChannel()
        JobRegistry.start(request.summary, request.summary, request.count)
        if (request.resumeRemoteId.isNotEmpty()) {
            // The queue never lost the task; this process did.
            JobRegistry.submitted(request.resumeRemoteId)
        }
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.app_name), "正在准备…", true))
        holdLocks()

        scope.launch {
            try {
                runJob(request)
            } catch (error: ModelException) {
                Log.w(TAG, "generation failed: ${error.message}", error)
                JobRegistry.fail(error.message ?: "生成失败")
                notifyFinal("生成失败", error.message ?: "生成失败")
            } catch (error: QueueRejected) {
                Log.w(TAG, "queue rejected: ${error.message}", error)
                JobRegistry.fail(error.message ?: "队列拒绝了这次请求")
                notifyFinal("生成失败", error.message ?: "队列拒绝了这次请求")
            } catch (error: Exception) {
                Log.w(TAG, "generation crashed: ${error.message}", error)
                JobRegistry.fail(error.message ?: "生成失败")
                notifyFinal("生成失败", error.message ?: "生成失败")
            } finally {
                // REMOVE rather than DETACH: detaching leaves the ongoing notification
                // behind and the system then reports "Stop FGS timeout".
                releaseLocks()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runJob(request: JobRequest) {
        val settings = SettingsStore(filesDir).load()
        if (settings.isQueueMode) runQueueJob(request, settings) else runSyncJob(request, settings)
    }

    // ------------------------------------------------------------- sync mode

    private suspend fun runSyncJob(request: JobRequest, settings: Settings) {
        val client = ModelClient(settings)
        val references = readReferences(request)
        JobRegistry.stage(JobRegistry.Stage.RUNNING, message = if (request.isEdit) "正在精修…" else "正在出图…")
        updateProgress(if (request.isEdit) "正在精修，最长约 6 分钟…" else "正在出图…")

        val startedAt = System.currentTimeMillis()
        val images = if (request.isEdit) {
            client.edit(request.prompt, request.size, request.seed, references)
        } else {
            client.generate(request.prompt, request.size, request.count, request.seed)
        }
        val produced = saveImages(
            request = request,
            settings = settings,
            startedAt = startedAt,
            items = images.map { QueueResultItem("", it.seed, request.size, it.seconds, it.revisedPrompt) },
            load = { images[it].bytes },
        )
        finishWith(request, produced)
    }

    // ------------------------------------------------------------ queue mode

    private suspend fun runQueueJob(request: JobRequest, settings: Settings) {
        val client = ModelClient(settings)
        val store = PendingJobStore(filesDir)
        val startedAt = System.currentTimeMillis()
        val deadline = startedAt + settings.imageTimeoutSeconds * 1000L

        val idempotencyKey = request.idempotencyKey.ifEmpty {
            "glow-${request.seed}-${System.currentTimeMillis()}"
        }
        // References are only needed to submit; a resumed task already has them.
        val references = if (request.resumeRemoteId.isEmpty()) readReferences(request) else emptyList()

        var remoteId = request.resumeRemoteId
        var discardResult = false

        if (remoteId.isEmpty()) {
            if (request.isEdit) updateProgress("正在提交精修任务…") else updateProgress("正在提交…")
            remoteId = submit(client, request, references, idempotencyKey)
            JobRegistry.submitted(remoteId)
        }
        store.save(
            PendingJob(
                remoteId = remoteId,
                summary = request.summary,
                expected = request.count,
                startedAt = startedAt,
                prompt = request.prompt,
                note = request.note,
                presetId = request.presetId,
                size = request.size,
                seed = request.seed,
                isEdit = request.isEdit,
                idempotencyKey = idempotencyKey,
            ),
        )

        var statusFailures = 0
        while (true) {
            val now = System.currentTimeMillis()
            if (now > deadline) {
                // Give the slot back if the model never started on it.
                if (JobRegistry.current.value?.stage != JobRegistry.Stage.RUNNING) {
                    runCatching { client.cancelJob(remoteId) }
                }
                store.clear()
                throw ModelException("等太久了（超过 ${settings.imageTimeoutSeconds} 秒），任务可能还在服务端跑")
            }

            if (JobRegistry.cancelRequested) {
                val accepted = runCatching { client.cancelJob(remoteId) }.getOrDefault(false)
                if (accepted) {
                    store.clear()
                    throw ModelException("已取消，模型还没开始")
                }
                // 409 means it is already generating; the model cannot be interrupted.
                discardResult = true
                JobRegistry.stage(JobRegistry.Stage.RUNNING, message = "已经开跑，做完不保存")
            }

            delay(POLL_INTERVAL_MS)

            val state = try {
                val read = client.jobState(remoteId)
                statusFailures = 0
                read
            } catch (gone: QueueGone) {
                store.clear()
                throw ModelException("队列里查不到这个任务了（可能已被清理）")
            } catch (retriable: QueueRetriable) {
                statusFailures++
                if (statusFailures >= MAX_STATUS_FAILURES) {
                    store.clear()
                    throw ModelException("一直读不到任务进度：${retriable.message}")
                }
                JobRegistry.stage(
                    JobRegistry.current.value?.stage ?: JobRegistry.Stage.QUEUED,
                    message = "暂时读不到进度（第 $statusFailures 次），继续试",
                )
                updateProgress("暂时读不到进度，继续试（第 $statusFailures 次）")
                continue
            }

            when (state.status) {
                QueueState.STATUS_QUEUED -> {
                    JobRegistry.stage(
                        JobRegistry.Stage.QUEUED,
                        percent = state.percent,
                        etaSeconds = state.etaSeconds,
                        queuePosition = state.queuePosition,
                    )
                    updateProgress(queueLine(state))
                }

                QueueState.STATUS_RUNNING -> {
                    JobRegistry.stage(
                        JobRegistry.Stage.RUNNING,
                        percent = state.percent,
                        etaSeconds = state.etaSeconds,
                    )
                    updateProgress("正在生成，约 ${state.percent}%")
                }

                QueueState.STATUS_SUCCEEDED -> {
                    JobRegistry.stage(JobRegistry.Stage.FETCHING, percent = 99, message = "正在取图…")
                    updateProgress("图好了，正在取回…")
                    if (discardResult) {
                        store.clear()
                        throw ModelException("已放弃这次结果，模型那边已经跑完了")
                    }
                    val items = state.data.ifEmpty { fallbackItems(remoteId, request) }
                    val produced = saveImages(
                        request = request,
                        settings = settings,
                        startedAt = startedAt,
                        items = items,
                        load = { client.fetchResult(items[it].url) },
                        queueJobId = remoteId,
                    )
                    store.clear()
                    finishWith(request, produced)
                    return
                }

                QueueState.STATUS_FAILED -> {
                    store.clear()
                    throw ModelException(state.error.ifEmpty { "这次没成功，换个描述再试一次" })
                }

                QueueState.STATUS_CANCELED -> {
                    store.clear()
                    throw ModelException("任务已取消")
                }

                else -> {
                    // An unknown status is not terminal: keep polling until the deadline.
                    JobRegistry.stage(JobRegistry.Stage.QUEUED, message = "状态是 $state.status，继续等")
                }
            }
        }
    }

    /** Submit, honouring the queue's own back-off instead of one we invented. */
    private suspend fun submit(
        client: ModelClient,
        request: JobRequest,
        references: List<RefImage>,
        idempotencyKey: String,
    ): String {
        var attempt = 0
        while (true) {
            attempt++
            try {
                val state = if (request.isEdit) {
                    client.submitEdit(request.prompt, request.size, request.seed, references, idempotencyKey)
                } else {
                    client.submitTextToImage(request.prompt, request.size, request.count, request.seed, idempotencyKey)
                }
                if (state.id.isEmpty()) throw QueueRetriable("队列没有返回任务号")
                return state.id
            } catch (busy: QueueBusy) {
                if (attempt >= MAX_SUBMIT_ATTEMPTS) throw ModelException("队列一直满（${busy.message}）")
                updateProgress("队列已满，${busy.retryAfterMs / 1000} 秒后重试（第 $attempt 次）")
                delay(busy.retryAfterMs)
            } catch (retriable: QueueRetriable) {
                if (attempt >= MAX_SUBMIT_ATTEMPTS) {
                    throw ModelException("提交失败：${retriable.message}")
                }
                val backoff = (1000L * attempt * attempt).coerceAtMost(15_000L)
                updateProgress("提交没成功，${backoff / 1000} 秒后重试（第 $attempt 次）")
                delay(backoff)
            }
        }
    }

    /** When the queue lists no results, the URLs are predictable. */
    private fun fallbackItems(remoteId: String, request: JobRequest): List<QueueResultItem> =
        (0 until request.count.coerceIn(1, 2)).map { index ->
            val suffix = if (index == 0) "result.png" else "result-$index.png"
            QueueResultItem(
                url = "${SettingsStore(filesDir).load().imageEndpoint}/v1/images/jobs/$remoteId/$suffix",
                seed = request.seed + index,
                size = request.size,
                seconds = 0.0,
                revisedPrompt = "",
            )
        }

    private fun readReferences(request: JobRequest): List<RefImage> {
        if (!request.isEdit) return emptyList()
        val references = request.referenceUris.mapNotNull { raw ->
            runCatching { ImageIntake.read(applicationContext, Uri.parse(raw)) }.getOrNull()
        }
        if (references.isEmpty()) throw ModelException("没能读取参考图，请重新选择")
        return references
    }

    private suspend fun saveImages(
        request: JobRequest,
        settings: Settings,
        startedAt: Long,
        items: List<QueueResultItem>,
        load: (Int) -> ByteArray,
        queueJobId: String = "",
    ): Int {
        val gallery = GalleryStore(File(filesDir, "gallery")).also { it.ensureDir() }
        val references = if (request.isEdit) {
            request.referenceUris.mapNotNull { raw ->
                runCatching { ImageIntake.read(applicationContext, Uri.parse(raw)) }.getOrNull()
            }.map { it.bytes }
        } else {
            emptyList()
        }
        var produced = 0
        items.forEachIndexed { index, item ->
            val bytes = try {
                load(index)
            } catch (error: Exception) {
                throw ModelException("图已经生成好，取图没成功：${error.message}")
            }
            val seed = if (item.seed != 0L) item.seed else request.seed + index
            val (width, height) = GalleryStore.pngSize(bytes)
            gallery.save(
                baseName = GalleryStore.baseName(seed),
                pngBytes = bytes,
                prompt = request.prompt,
                note = request.note,
                presetId = request.presetId,
                seed = seed,
                width = width,
                height = height,
                seconds = if (item.seconds > 0) item.seconds
                else (System.currentTimeMillis() - startedAt) / 1000.0,
                revisedPrompt = item.revisedPrompt,
                referenceBytes = references,
                keepReferenceCopies = settings.keepReferenceCopies,
                queueJobId = queueJobId,
            )
            produced++
            JobRegistry.progress(produced)
        }
        return produced
    }

    private fun finishWith(request: JobRequest, produced: Int) {
        JobRegistry.finish(produced)
        val verb = if (request.isEdit) "精修" else "生成"
        Log.i(TAG, "generation done: $produced image(s)")
        notifyFinal("${verb}完成", "已保存 $produced 张，回到阁楼查看")
    }

    /** "排队中（前面还有 2 个）" — the wait is real, so say why. */
    private fun queueLine(state: QueueState): String {
        val ahead = state.queuePosition
        val eta = state.etaSeconds
        return when {
            ahead > 1 -> "排队中，前面还有 ${ahead - 1} 个"
            ahead == 1 -> "排队中，下一个就是"
            eta > 0 -> "排队中，大约还要 ${eta / 60} 分"
            else -> "排队中"
        }
    }

    // ------------------------------------------------------------------- locks

    /**
     * Keep the CPU and the Wi-Fi radio awake for the duration of a job.
     *
     * A foreground service keeps the *process* alive, but the screen going off is
     * a separate hazard: the device can enter Doze (network suspended) and Wi-Fi
     * power save can stall an open socket. Both would surface as a five-minute
     * generation that never returns. Being exempt from battery optimisation covers
     * Doze; these two locks cover the rest, and they are released the moment the
     * job ends.
     */
    private fun holdLocks() {
        runCatching {
            val power = getSystemService(PowerManager::class.java)
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Glow:generation").apply {
                setReferenceCounted(false)
                acquire(MAX_LOCK_MS)
            }
        }
        runCatching {
            val wifi = applicationContext.getSystemService(WifiManager::class.java)
            wifiLock = wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Glow:generation")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wakeLock = null
        wifiLock = null
    }

    // ------------------------------------------------------------ notifications

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            // The system keeps a channel across app updates and even across
            // `pm clear`, so importance can only be changed by shipping a new id.
            // v2 switched LOW -> DEFAULT: a silent channel gets no status-bar icon,
            // which made a multi-minute job invisible in the background. The noise
            // is switched off explicitly instead of by lowering importance.
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "生成任务", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "图像生成与精修的进度"
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                },
            )
            // Retire the v1 channel so the settings list does not grow a dead entry.
            manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        }
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildNotification(title: String, text: String, ongoing: Boolean): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_glow)
            .setContentIntent(openAppIntent())
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun updateProgress(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification("阁楼 · 正在生成", text, true))
    }

    private fun notifyFinal(title: String, text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(
            FINAL_NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat_glow)
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build(),
        )
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Glow"
        private const val CHANNEL_ID = "glow_jobs_v2"
        private const val LEGACY_CHANNEL_ID = "glow_jobs"

        /** Safety net only; the job path releases far sooner. */
        private const val MAX_LOCK_MS = 20 * 60 * 1000L
        private const val NOTIFICATION_ID = 1001
        private const val FINAL_NOTIFICATION_ID = 1002
        private const val EXTRA_REQUEST = "request"

        /** The service polls its own worker every 3 s; 4 s keeps us from queueing up. */
        const val POLL_INTERVAL_MS = 4000L

        /** ~80 s of unreadable status before giving up, so a blip is not fatal. */
        private const val MAX_STATUS_FAILURES = 20

        private const val MAX_SUBMIT_ATTEMPTS = 6

        fun start(context: Context, request: JobRequest) {
            val intent = Intent(context, GenerationService::class.java)
                .putExtra(EXTRA_REQUEST, request.toJson())
            context.startForegroundService(intent)
        }

        fun summarise(prompt: String, isEdit: Boolean): String {
            val head = if (isEdit) "精修" else "生成"
            val snippet = prompt.take(18).replace("\n", " ")
            return String.format(Locale.US, "%s · %s…", head, snippet)
        }
    }
}
