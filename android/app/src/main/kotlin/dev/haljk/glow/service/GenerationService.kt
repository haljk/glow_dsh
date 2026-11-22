package dev.haljk.glow.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.haljk.glow.MainActivity
import dev.haljk.glow.R
import dev.haljk.glow.data.GalleryStore
import dev.haljk.glow.data.ImageIntake
import dev.haljk.glow.data.JobRegistry
import dev.haljk.glow.data.JobRequest
import dev.haljk.glow.data.ModelClient
import dev.haljk.glow.data.ModelException
import dev.haljk.glow.data.RefImage
import dev.haljk.glow.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 */
class GenerationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.app_name), "正在准备…", true))

        scope.launch {
            try {
                runJob(request)
            } catch (error: ModelException) {
                JobRegistry.fail(error.message ?: "生成失败")
                notifyFinal("生成失败", error.message ?: "生成失败")
            } catch (error: Exception) {
                JobRegistry.fail(error.message ?: "生成失败")
                notifyFinal("生成失败", error.message ?: "生成失败")
            } finally {
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun runJob(request: JobRequest) {
        val settings = SettingsStore(filesDir).load()
        val gallery = GalleryStore(File(filesDir, "gallery")).also { it.ensureDir() }
        val client = ModelClient(settings)

        updateProgress("正在读取参考图…")
        val references: List<RefImage> = if (request.isEdit) {
            request.referenceUris.mapNotNull { raw ->
                runCatching { ImageIntake.read(applicationContext, Uri.parse(raw)) }.getOrNull()
            }
        } else {
            emptyList()
        }
        if (request.isEdit && references.isEmpty()) {
            throw ModelException("没能读取参考图，请重新选择")
        }

        updateProgress(if (request.isEdit) "正在精修，最长约 6 分钟…" else "正在出图…")

        val startedAt = System.currentTimeMillis()
        val images = if (request.isEdit) {
            client.edit(request.prompt, request.size, request.seed, references)
        } else {
            client.generate(request.prompt, request.size, request.count, request.seed)
        }

        var produced = 0
        images.forEachIndexed { index, image ->
            val seed = if (image.seed != 0L) image.seed else request.seed + index
            val (width, height) = GalleryStore.pngSize(image.bytes)
            gallery.save(
                baseName = GalleryStore.baseName(seed),
                pngBytes = image.bytes,
                prompt = request.prompt,
                note = request.note,
                presetId = request.presetId,
                seed = seed,
                width = width,
                height = height,
                seconds = if (image.seconds > 0) image.seconds
                else (System.currentTimeMillis() - startedAt) / 1000.0,
                revisedPrompt = image.revisedPrompt,
                referenceBytes = references.map { it.bytes },
                keepReferenceCopies = settings.keepReferenceCopies,
            )
            produced++
            JobRegistry.progress(produced)
        }

        JobRegistry.finish(produced)
        val verb = if (request.isEdit) "精修" else "生成"
        notifyFinal("${verb}完成", "已保存 $produced 张，回到阁楼查看")
    }

    // ------------------------------------------------------------ notifications

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "生成任务", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "图像生成与精修的进度"
                    setShowBadge(false)
                },
            )
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
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
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
            buildNotification(title, text, false)
                .let { builder ->
                    NotificationCompat.Builder(this, CHANNEL_ID)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setSmallIcon(android.R.drawable.ic_menu_gallery)
                        .setContentIntent(openAppIntent())
                        .setAutoCancel(true)
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .build()
                },
        )
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "glow_jobs"
        private const val NOTIFICATION_ID = 1001
        private const val FINAL_NOTIFICATION_ID = 1002
        private const val EXTRA_REQUEST = "request"

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
