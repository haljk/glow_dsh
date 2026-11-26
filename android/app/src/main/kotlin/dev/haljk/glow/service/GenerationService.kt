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
import dev.haljk.glow.data.TaskStore
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
        // A resume must continue the row that already exists. The record is the only
        // durable link to the server's task, so it is looked up by task id first; an id the
        // caller passed wins, because it read the same disk and may know better.
        // The registry's store is shared, but the service can be started with no screen
        // alive, so this reads the same directory directly when it has to.
        val existingTaskId = if (request.resumeRemoteId.isEmpty()) {
            ""
        } else {
            request.resumeTaskId.ifEmpty {
                val taskStore = JobRegistry.tasks ?: TaskStore(File(filesDir, TASKS_DIR))
                taskStore.findByRemote(request.resumeRemoteId)?.id.orEmpty()
            }
        }
        JobRegistry.start(
        id = "t-${request.seed}-${System.currentTimeMillis()}",
        summary = request.summary,
        expected = request.count,
        note = request.note,
        prompt = request.prompt,
        size = request.size,
        existingTaskId = existingTaskId,
        remoteId = request.resumeRemoteId,
    )
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
                Log.w(TAG, "server rejected the request: ${error.message}", error)
                JobRegistry.fail(error.message ?: "服务端没有接这次请求")
                notifyFinal("生成失败", error.message ?: "服务端没有接这次请求")
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
        val producedNames = saveImages(
            request = request,
            settings = settings,
            startedAt = startedAt,
            items = images.map { QueueResultItem("", it.seed, request.size, it.seconds, it.revisedPrompt) },
            load = { images[it].bytes },
            referenceBytes = references.map { it.bytes },
        )
        finishWith(request, producedNames.size, producedNames)
    }

    // ------------------------------------------------------------ queue mode

    private suspend fun runQueueJob(request: JobRequest, settings: Settings) {
        val client = ModelClient(settings)
        val store = PendingJobStore(filesDir)
        val startedAt = System.currentTimeMillis()

        val idempotencyKey = request.idempotencyKey.ifEmpty {
            "glow-${request.seed}-${System.currentTimeMillis()}"
        }
        // References are only needed to submit; a resumed task already has them.
        // Both paths read the references: a resumed edit needs them to keep its original
        // copy, and reading costs one decode. If the grants are gone the read comes back
        // empty, which is the old behaviour rather than a crash.
        val references = readReferences(request)

        var remoteId = request.resumeRemoteId
        // True once the server refused to cancel. It ends the waiting, not the job: the
        // result still arrives, and throwing it away was the defect. Latched so the
        // server is asked to cancel once, not on every poll.
        var stoppedWaiting = false

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
                referenceUris = request.referenceUris,
            ),
        )

        // No wall-clock ceiling. A queued job keeps its place until the service says it
        // finished, failed or was cancelled; the user can stop waiting at any time from
        // the bottom bar. The old deadline turned a slow queue into a client-side failure
        // while the model was still working, which is the worst of both worlds.
        var statusFailures = 0
        while (true) {
            if (JobRegistry.cancelRequested && !stoppedWaiting) {
                val accepted = runCatching { client.cancelJob(remoteId) }.getOrDefault(false)
                if (accepted) {
                    store.clear()
                    throw ModelException("已取消，模型还没开始")
                }
                // The server says the model is already working and cannot be stopped.
                // Only the interactive wait ends here — the task is still ours, so it is
                // followed to the end and the image is saved. Dropping it here is what
                // made the user say we lost track of the server's progress.
                stoppedWaiting = true
                // Pass the last known progress through: stage() defaults to zero, and a bar
                // dropping to 0% right after "cancel" reads as if the work restarted.
                val known = JobRegistry.current.value
                JobRegistry.stage(
                    JobRegistry.Stage.RUNNING,
                    percent = known?.percent ?: 0,
                    etaSeconds = known?.etaSeconds ?: 0,
                    message = STOPPED_WAITING,
                )
                updateProgress(STOPPED_WAITING)
            }

            delay(POLL_INTERVAL_MS)

            val state = try {
                val read = client.jobState(remoteId)
                statusFailures = 0
                read
            } catch (gone: QueueGone) {
                // The server looked and answered: this task does not exist for it. That is
                // a real failure and the pending file is what must not survive it.
                store.clear()
                throw ModelException("服务端没有这个任务了（可能已被清理，或结果已过期）")
            } catch (retriable: QueueRetriable) {
                statusFailures++
                if (statusFailures >= MAX_STATUS_FAILURES) {
                    // The connection went away; the task did not. Deleting the pending file
                    // here was the bug — the server went on producing an image nobody could
                    // claim afterwards. Keep it, say so plainly, and let 继续取回 finish it.
                    val message = "现在连不上服务，任务还在服务端。回到应用点『继续取回』"
                    // The id stays in the log, not in the sentence: a support case can still
                    // find the task, and the user is not asked to read a machine name.
                    Log.w(TAG, "status unreadable $statusFailures times, task id $remoteId: ${retriable.message}")
                    JobRegistry.unretrieved(message)
                    notifyFinal("暂时取不回来", message)
                    // Ends the service normally rather than throwing: the outer catch would
                    // overwrite the stage with a plain failure, which is not what happened.
                    return
                }
                // Keep the last known stage and progress: a failed read is no news, so it
                // must not look like the job slid back to 0%.
                val known = JobRegistry.current.value
                JobRegistry.stage(
                    known?.stage ?: JobRegistry.Stage.QUEUED,
                    percent = known?.percent ?: 0,
                    etaSeconds = known?.etaSeconds ?: 0,
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
                        message = if (stoppedWaiting) STOPPED_WAITING else "",
                    )
                    updateProgress(
                        if (stoppedWaiting) "正在生成，约 ${state.percent}%，跑完会自动保存"
                        else "正在生成，约 ${state.percent}%",
                    )
                }

                QueueState.STATUS_SUCCEEDED -> {
                    JobRegistry.stage(JobRegistry.Stage.FETCHING, percent = 99, message = "正在取图…")
                    updateProgress("图好了，正在取回…")
                    val items = state.data.ifEmpty { fallbackItems(remoteId, request) }
                    val producedNames = try {
                        saveImages(
                            request = request,
                            settings = settings,
                            startedAt = startedAt,
                            items = items,
                            load = { client.fetchResult(items[it].url) },
                            queueJobId = remoteId,
                            referenceBytes = references.map { it.bytes },
                        )
                    } catch (gone: QueueGone) {
                        // Second time the server answers with "gone", and this one is final:
                        // the result expired, so keeping the pending file would make every
                        // launch retry a download that can never succeed.
                        store.clear()
                        throw ModelException("结果在服务端已经过期，取不回来了（最多保留 7 天）")
                    }
                    store.clear()
                    finishWith(request, producedNames.size, producedNames, stoppedWaiting)
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
                    // An unknown status is not terminal: keep polling.
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
                if (state.id.isEmpty()) throw QueueRetriable("服务端没有返回任务号")
                return state.id
            } catch (busy: QueueBusy) {
                if (attempt >= MAX_SUBMIT_ATTEMPTS) {
                    Log.w(TAG, "server stayed busy: ${busy.message}")
                    throw ModelException("服务端一直很忙，换个时间再试")
                }
                updateProgress("服务端正忙，${busy.retryAfterMs / 1000} 秒后重试（第 $attempt 次）")
                delay(busy.retryAfterMs)
            } catch (retriable: QueueRetriable) {
                if (attempt >= MAX_SUBMIT_ATTEMPTS) {
                    // The transport detail goes to the log; the user gets a sentence they
                    // can act on instead of the service's own wording.
                    Log.w(TAG, "submit gave up: ${retriable.message}")
                    throw ModelException("没能提交到服务端，稍后再试")
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
        // Passed in rather than re-read: the photo-picker grant is minute-scoped on
        // some ROMs, and a queue job can run for six of them. Re-reading after the
        // wait could come back empty, which silently dropped the reference copy —
        // and with it the whole comparison view.
        referenceBytes: List<ByteArray> = emptyList(),
    ): List<String> {
        val gallery = GalleryStore(File(filesDir, "gallery")).also { it.ensureDir() }
        val references = if (request.isEdit) referenceBytes else emptyList()
        var produced = 0
        val savedNames = mutableListOf<String>()
        items.forEachIndexed { index, item ->
            val bytes = try {
                load(index)
            } catch (gone: QueueGone) {
                // Passed through untouched: a result the server says no longer exists is
                // handled differently from a download that merely failed, and turning it
                // into a generic failure here would hide the difference from the caller.
                throw gone
            } catch (error: Exception) {
                throw ModelException("图已经生成好，取图没成功：${error.message}")
            }
            val seed = if (item.seed != 0L) item.seed else request.seed + index
            val (width, height) = GalleryStore.pngSize(bytes)
            val saved = gallery.save(
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
            // Recorded even when the copies are not kept, so the viewer can explain why
            // there is nothing to compare.
            isEditResult = request.isEdit,
                queueJobId = queueJobId,
            )
            savedNames += saved.name
            produced++
            JobRegistry.progress(produced)
        }
        // Keep the original inside the task directory as well: the gallery copy can be
        // turned off, deleted, or lost with an expired picker grant, and the comparison
        // view should not care.
        JobRegistry.tasks?.let { store ->
            val taskId = JobRegistry.current.value?.id
            if (taskId != null) {
                val names = store.saveReferences(taskId, references)
                store.update(taskId) { it.copy(referenceFiles = names, resultNames = savedNames) }
            }
        }
        return savedNames
    }

    /**
     * [stoppedWaiting] is set when the user cancelled and the server was already working:
     * the final message has to say the result arrived anyway, because that is exactly
     * what the user was told would happen.
     */
    private fun finishWith(
        request: JobRequest,
        produced: Int,
        resultNames: List<String> = emptyList(),
        stoppedWaiting: Boolean = false,
    ) {
        JobRegistry.finish(produced, resultNames)
        val verb = if (request.isEdit) "精修" else "生成"
        Log.i(TAG, "generation done: $produced image(s)")
        val text = if (stoppedWaiting) {
            "已停止等待，结果已保存 $produced 张，回到阁楼查看"
        } else {
            "已保存 $produced 张，回到阁楼查看"
        }
        notifyFinal("${verb}完成", text)
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

        /** Where the task records live; the screen reads the same directory. */
        private const val TASKS_DIR = "tasks"

        /** Shown while a cancelled wait keeps running, so the promise stays visible. */
        private const val STOPPED_WAITING = "已停止等待，跑完会自动保存"

        /** The service polls its own worker every 3 s; 4 s keeps us from queueing up. */
        const val POLL_INTERVAL_MS = 4000L

        // Consecutive unreadable polls, not elapsed time: 45 x 4s ≈ 3 minutes of a service
        // that will not answer. A dropped Wi-Fi frame or a server restart should not stop a
        // job that is still alive on the other side — and reaching this limit no longer
        // means the job is lost, only that this phone stops watching it.
        private const val MAX_STATUS_FAILURES = 45

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
