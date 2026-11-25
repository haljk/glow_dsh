package dev.haljk.glow.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * One finished image plus its sidecar metadata.
 *
 * A result is a PNG on disk next to a `.json` file with the same base name —
 * the same layout the desktop version uses, so a gallery can be copied between
 * the two without a migration step.
 */
data class GalleryItem(
    val name: String,
    val prompt: String,
    val note: String,
    val presetId: String,
    val seed: Long,
    val width: Int,
    val height: Int,
    val seconds: Double,
    val createdAt: Long,
    val archived: Boolean,
    val refFiles: List<String>,
    val revisedPrompt: String,
    /** The queue task this came from, so a result can be traced back upstream. */
    val queueJobId: String = "",
    /**
     * True when this result came from a reference photo.
     *
     * Without it the viewer cannot tell a 文生图 result (nothing to compare) from an
     * edit whose copy was deleted; both look like a missing feature.
     */
    val isEdit: Boolean = false,
) {
    val file: File? = null

    val displaySize: String get() = "${width}×$height"

    val ratio: Float get() = if (height == 0) 1f else width.toFloat() / height.toFloat()

    fun elapsedLabel(): String =
        if (seconds <= 0) "" else String.format(Locale.US, "%.0f 秒", seconds)
}

class GalleryStore(private val directory: File) {

    fun ensureDir() {
        directory.mkdirs()
    }

    fun fileOf(name: String): File = File(directory, name)

    /**
     * Persist one generated image and its metadata.
     *
     * @param referenceBytes the reference images for this edit, copied so the
     *        comparison view keeps working after the originals change.
     */
    fun save(
        baseName: String,
        pngBytes: ByteArray,
        prompt: String,
        note: String,
        presetId: String,
        seed: Long,
        width: Int,
        height: Int,
        seconds: Double,
        revisedPrompt: String,
        referenceBytes: List<ByteArray>,
        keepReferenceCopies: Boolean,
        queueJobId: String = "",
        isEditResult: Boolean = referenceBytes.isNotEmpty(),
    ): GalleryItem {
        directory.mkdirs()
        val imageName = "$baseName.png"
        File(directory, imageName).writeBytes(pngBytes)

        val refNames = mutableListOf<String>()
        if (keepReferenceCopies) {
            referenceBytes.forEachIndexed { index, bytes ->
                val refName = "$baseName.ref-${index + 1}.png"
                File(directory, refName).writeBytes(bytes)
                refNames.add(refName)
            }
        }

        val item = GalleryItem(
            name = imageName,
            prompt = prompt,
            note = note,
            presetId = presetId,
            seed = seed,
            width = width,
            height = height,
            seconds = seconds,
            createdAt = System.currentTimeMillis(),
            archived = false,
            refFiles = refNames,
            isEdit = isEditResult,
            revisedPrompt = revisedPrompt,
            queueJobId = queueJobId,
        )
        writeSidecar(item)
        return item
    }

    fun writeSidecar(item: GalleryItem) {
        val json = JSONObject().apply {
            put("prompt", item.prompt)
            put("note", item.note)
            put("presetId", item.presetId)
            put("seed", item.seed)
            put("width", item.width)
            put("height", item.height)
            put("seconds", item.seconds)
            put("createdAt", item.createdAt)
            put("archived", item.archived)
            put("revisedPrompt", item.revisedPrompt)
            put("refFiles", JSONArray(item.refFiles))
        put("isEdit", item.isEdit)
            put("queueJobId", item.queueJobId)
        }
        File(directory, sidecarName(item.name)).writeText(json.toString(2))
    }

    /** Directory scan, newest first — no database to fall out of sync with the files. */
    fun list(archived: Boolean): List<GalleryItem> {
        if (!directory.isDirectory) return emptyList()
        val files = directory.listFiles { f -> f.isFile && f.name.endsWith(".png") && !f.name.contains(".ref-") }
            ?: return emptyList()
        return files.mapNotNull { read(it) }
            .filter { it.archived == archived }
            .sortedByDescending { it.createdAt }
    }

    fun counts(): Pair<Int, Int> {
        if (!directory.isDirectory) return 0 to 0
        val items = directory.listFiles { f -> f.isFile && f.name.endsWith(".png") && !f.name.contains(".ref-") }
            ?.mapNotNull { read(it) } ?: return 0 to 0
        return items.count { !it.archived } to items.count { it.archived }
    }

    fun find(name: String): GalleryItem? {
        val file = File(directory, name)
        return if (file.isFile) read(file) else null
    }

    fun setArchived(name: String, archived: Boolean): GalleryItem? {
        val item = find(name) ?: return null
        val next = item.copy(archived = archived)
        writeSidecar(next)
        return next
    }

    fun delete(name: String): Boolean {
        val item = find(name)
        val target = File(directory, name)
        var removed = target.delete()
        item?.refFiles?.forEach { ref ->
            File(directory, ref).delete()
        }
        File(directory, sidecarName(name)).delete()
        return removed
    }

    private fun read(imageFile: File): GalleryItem? {
        val name = imageFile.name
        val sidecar = File(directory, sidecarName(name))
        val json = runCatching { if (sidecar.isFile) JSONObject(sidecar.readText()) else null }.getOrNull()
        val (width, height) = readPngSize(imageFile)
        val promptText = json?.optString("prompt", "") ?: ""
        // Sidecars written before the flag existed have no isEdit; an edit payload always
        // opens with the reference placeholder, so that inference is safe.
        val inferredEdit = if (json?.has("isEdit") == true) {
            json.optBoolean("isEdit")
        } else {
            promptText.contains("<Picture 1>")
        }
        val recorded = json?.optJSONArray("refFiles")?.let { array ->
            buildList { for (i in 0 until array.length()) add(array.optString(i)) }
        } ?: emptyList()
        // Self-healing for edits only: an older build could write the copy without
        // recording it, leaving a ref file nothing points at. A text generation has no
        // business adopting stray files, so the prompt prefix gates this.
        val refs = when {
            recorded.isNotEmpty() -> recorded
            inferredEdit -> {
                val base = name.removeSuffix(".png")
                (1..8).map { "$base.ref-$it.png" }.filter { File(directory, it).isFile }
            }
            else -> emptyList()
        }

        return GalleryItem(
            name = name,
            prompt = promptText,
            note = json?.optString("note", "") ?: "",
            presetId = json?.optString("presetId", "") ?: "",
            seed = json?.optLong("seed", 0L) ?: 0L,
            width = json?.optInt("width", width) ?: width,
            height = json?.optInt("height", height) ?: height,
            seconds = json?.optDouble("seconds", 0.0) ?: 0.0,
            createdAt = json?.optLong("createdAt", imageFile.lastModified()) ?: imageFile.lastModified(),
            archived = json?.optBoolean("archived", false) ?: false,
            refFiles = refs,
            isEdit = inferredEdit,
            revisedPrompt = json?.optString("revisedPrompt", "") ?: "",
            queueJobId = json?.optString("queueJobId", "") ?: "",
        )
    }

    private fun sidecarName(pngName: String): String =
        if (pngName.endsWith(".png")) pngName.dropLast(4) + ".json" else "$pngName.json"

    /** Reads the PNG IHDR header so the UI can lay out the card without decoding pixels. */
    private fun readPngSize(file: File): Pair<Int, Int> = runCatching {
        file.inputStream().use { stream ->
            val header = ByteArray(24)
            var read = 0
            while (read < header.size) {
                val count = stream.read(header, read, header.size - read)
                if (count <= 0) break
                read += count
            }
            val width = ((header[16].toInt() and 0xFF) shl 24) or
                ((header[17].toInt() and 0xFF) shl 16) or
                ((header[18].toInt() and 0xFF) shl 8) or
                (header[19].toInt() and 0xFF)
            val height = ((header[20].toInt() and 0xFF) shl 24) or
                ((header[21].toInt() and 0xFF) shl 16) or
                ((header[22].toInt() and 0xFF) shl 8) or
                (header[23].toInt() and 0xFF)
            width to height
        }
    }.getOrElse { 0 to 0 }

    companion object {
        fun baseName(seed: Long): String {
            val now = java.time.LocalDateTime.now()
            return String.format(
                Locale.US,
                "%04d%02d%02d-%02d%02d%02d-%d",
                now.year, now.monthValue, now.dayOfMonth, now.hour, now.minute, now.second, seed,
            )
        }

        /** Reads width/height straight out of the PNG header, without decoding pixels. */
        fun pngSize(bytes: ByteArray): Pair<Int, Int> {
            if (bytes.size < 24) return 0 to 0
            fun intAt(offset: Int): Int =
                ((bytes[offset].toInt() and 0xFF) shl 24) or
                    ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                    ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                    (bytes[offset + 3].toInt() and 0xFF)
            return intAt(16) to intAt(20)
        }
    }
}
