package dev.haljk.glow.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Storage behaviour, tested on a plain JVM.
 *
 * These classes touch only files and JSON, so they can be verified without a
 * device — which matters because the emulator needs a hypervisor this machine
 * does not have yet.
 */
class StorageTest {

    @get:Rule
    val temp = TemporaryFolder()

    // ---- settings

    @Test
    fun `service urls must be usable http addresses`() {
        assertEquals("http://10.0.0.5:9741", Settings.normaliseUrl(" http://10.0.0.5:9741/ "))
        assertEquals("https://glow.local:9741", Settings.normaliseUrl("https://glow.local:9741"))
        assertNull(Settings.normaliseUrl(""))
        assertNull(Settings.normaliseUrl("10.0.0.5:9741"))
        assertNull(Settings.normaliseUrl("ftp://x"))
        assertNull(Settings.normaliseUrl("http://"))
        assertNull(Settings.normaliseUrl("http://has space"))
    }

    @Test
    fun `timeouts are clamped to a sane range`() {
        // A queued 2K edit can wait before it even starts, so the floor moved up.
        assertEquals(60, Settings(imageTimeoutSeconds = 1).normalised().imageTimeoutSeconds)
        assertEquals(7200, Settings(imageTimeoutSeconds = 99_999).normalised().imageTimeoutSeconds)
        assertEquals(10, Settings(llmTimeoutSeconds = 0).normalised().llmTimeoutSeconds)
        assertEquals(600, Settings(llmTimeoutSeconds = 5_000).normalised().llmTimeoutSeconds)
    }

    @Test
    fun `settings survive a round trip and a corrupt file`() {
        val dir = temp.newFolder("settings")
        val store = SettingsStore(dir)
        val saved = store.save(
            Settings(
                imageBaseUrl = "http://192.168.1.9:9741",
                llmBaseUrl = "http://192.168.1.9:9739",
                llmModel = "default_model",
                imageTimeoutSeconds = 1200,
                keepReferenceCopies = false,
            ),
        )
        assertEquals("http://192.168.1.9:9741", saved.imageBaseUrl)
        val reloaded = store.load()
        assertEquals(1200, reloaded.imageTimeoutSeconds)
        assertFalse(reloaded.keepReferenceCopies)

        File(dir, "settings.json").writeText("{ not json")
        // A corrupt file falls back to defaults instead of crashing the app.
        assertEquals(Settings.DEFAULT_IMAGE_URL, store.load().imageBaseUrl)
    }

    // ---- favourites

    @Test
    fun `favourites cap at five and reject duplicates`() {
        val store = FavoriteStore(temp.newFolder("fav"))
        for (i in 1..5) assertNotNull(store.add("提示词 $i"))
        val sixth = store.add("第六条")
        assertNull("the sixth must be refused, not silently dropped", sixth)
        assertEquals(5, store.load().size)

        assertEquals(5, store.add("提示词 3")?.size)
        assertEquals(5, store.load().size)

        assertEquals(4, store.remove("提示词 3").size)
    }

    // ---- job request

    @Test
    fun `job request survives the intent round trip`() {
        val request = JobRequest(
            mode = JobRequest.MODE_EDIT,
            prompt = "把背景换成海边日落；其余不变。",
            note = "海边日落",
            presetId = "bg-replace",
            size = "1024x1024",
            seed = 42L,
            count = 1,
            referenceUris = listOf("content://media/1", "content://media/2"),
            summary = "精修 · 把背景换成海边日落…",
        )
        val restored = JobRequest.fromJson(request.toJson())
        assertNotNull(restored)
        assertEquals(request, restored)
        assertNull(JobRequest.fromJson("nonsense"))
    }

    // ---- gallery

    private fun pngBytes(width: Int = 64, height: Int = 48): ByteArray {
        val bytes = ByteArray(24)
        bytes[0] = 0x89.toByte(); bytes[1] = 0x50; bytes[2] = 0x4E; bytes[3] = 0x47
        fun put(offset: Int, value: Int) {
            bytes[offset] = (value ushr 24).toByte()
            bytes[offset + 1] = (value ushr 16).toByte()
            bytes[offset + 2] = (value ushr 8).toByte()
            bytes[offset + 3] = value.toByte()
        }
        put(16, width)
        put(20, height)
        return bytes
    }

    @Test
    fun `saving an image writes a png and a sidecar`() {
        val dir = temp.newFolder("gallery")
        val store = GalleryStore(dir)
        store.ensureDir()
        val item = store.save(
            baseName = "20261122-101010-7",
            pngBytes = pngBytes(1024, 768),
            prompt = "一条指令",
            note = "备注",
            presetId = "bg-replace",
            seed = 7L,
            width = 1024,
            height = 768,
            seconds = 220.0,
            revisedPrompt = "模型改写",
            referenceBytes = listOf(pngBytes(), pngBytes()),
            keepReferenceCopies = true,
        )
        assertTrue(File(dir, item.name).isFile)
        assertTrue(File(dir, "20261122-101010-7.json").isFile)
        assertEquals(2, item.refFiles.size)
        assertTrue(File(dir, item.refFiles[0]).isFile)

        val listed = store.list(archived = false)
        assertEquals(1, listed.size)
        assertEquals("备注", listed[0].note)
        assertEquals(1024, listed[0].width)
        assertEquals("bg-replace", listed[0].presetId)
        assertEquals(220.0, listed[0].seconds, 0.001)
    }

    @Test
    fun `archiving hides a result without touching the file`() {
        val dir = temp.newFolder("archive")
        val store = GalleryStore(dir).also { it.ensureDir() }
        val item = store.save(
            "x-1", pngBytes(), "p", "", "", 1L, 64, 48, 10.0, "", emptyList(), keepReferenceCopies = false,
        )
        store.setArchived(item.name, true)

        assertEquals(0, store.list(archived = false).size)
        assertEquals(1, store.list(archived = true).size)
        assertEquals(0 to 1, store.counts())
        assertTrue("archiving must not delete the image", File(dir, item.name).isFile)

        store.setArchived(item.name, false)
        assertEquals(1, store.list(archived = false).size)
        assertEquals(1 to 0, store.counts())
    }

    @Test
    fun `deleting a result removes the image, the sidecar and the reference copies`() {
        val dir = temp.newFolder("delete")
        val store = GalleryStore(dir).also { it.ensureDir() }
        val item = store.save(
            "y-2", pngBytes(), "p", "", "", 2L, 64, 48, 10.0, "", listOf(pngBytes()), keepReferenceCopies = true,
        )
        assertTrue(store.delete(item.name))
        assertFalse(File(dir, item.name).exists())
        assertFalse(File(dir, "y-2.json").exists())
        assertFalse(File(dir, item.refFiles.first()).exists())
        assertEquals(0, store.list(archived = false).size)
    }

    @Test
    fun `favorites file is never mistaken for a result`() {
        val dir = temp.newFolder("mixed")
        val store = GalleryStore(dir).also { it.ensureDir() }
        store.save("z-3", pngBytes(), "p", "", "", 3L, 64, 48, 1.0, "", emptyList(), keepReferenceCopies = false)
        FavoriteStore(dir).add("一条常用提示词")
        assertEquals(1, store.list(archived = false).size)
    }

    @Test
    fun `png size is read from the header`() {
        assertEquals(1024 to 768, GalleryStore.pngSize(pngBytes(1024, 768)))
        assertEquals(0 to 0, GalleryStore.pngSize(ByteArray(4)))
    }
}
