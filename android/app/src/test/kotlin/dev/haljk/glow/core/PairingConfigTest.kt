package dev.haljk.glow.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingConfigTest {

    private val sample = PairingConfig(
        host = "100.99.63.62",
        imagePort = 9742,
        helperPort = 9739,
        legacyPort = 9741,
        mode = "queue",
        model = "default_model",
        timeout = "std",
    )

    @Test
    fun roundTrips() {
        val decoded = PairingConfig.decode(sample.encode()).getOrThrow()
        assertEquals(sample, decoded)
    }

    @Test
    fun rejectsForeignCodes() {
        assertTrue(PairingConfig.decode("https://example.com").isFailure)
        assertTrue(PairingConfig.decode("").isFailure)
    }

    @Test
    fun rejectsNewerVersion() {
        val newer = sample.encode().replace("v=1", "v=9")
        val failure = PairingConfig.decode(newer).exceptionOrNull()
        assertEquals("配置码来自更新的版本（v9），请先升级", failure?.message)
    }

    @Test
    fun fillsDefaultsForMissingFields() {
        val decoded = PairingConfig.decode("glow://config?v=1&host=10.0.0.5").getOrThrow()
        assertEquals("10.0.0.5", decoded.host)
        assertEquals(9742, decoded.imagePort)
        assertEquals("queue", decoded.mode)
        assertEquals("std", decoded.timeout)
    }

    @Test
    fun ignoresUnknownFieldsAndBadPorts() {
        val decoded = PairingConfig.decode(
            "glow://config?v=1&host=10.0.0.5&img=abc&llm=70000&future=x",
        ).getOrThrow()
        assertEquals(9742, decoded.imagePort)
        assertEquals(9739, decoded.helperPort)
    }
}