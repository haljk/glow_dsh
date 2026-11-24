package dev.haljk.glow.core

/**
 * The text a pairing QR carries, and the only place that text is parsed.
 *
 * A QR code is meant to save typing an address and three ports on a second device, not to
 * be a secret channel: the payload is a plain, versioned query string so a user can read
 * it, and so a future version can add fields without breaking older builds.
 *
 * Rules:
 * - `v` is required and must match a version this build understands;
 * - unknown fields are ignored, missing ones keep the value already on the device;
 * - no credentials are ever included (the service has no auth).
 */
data class PairingConfig(
    val host: String,
    val imagePort: Int,
    val helperPort: Int,
    val legacyPort: Int,
    val mode: String,
    val model: String,
    val timeout: String,
) {
    fun encode(): String = buildString {
        append(SCHEME)
        append("?v=").append(VERSION)
        append("&host=").append(host)
        append("&img=").append(imagePort)
        append("&llm=").append(helperPort)
        append("&legacy=").append(legacyPort)
        append("&mode=").append(mode)
        append("&model=").append(model)
        append("&timeout=").append(timeout)
    }

    companion object {
        const val SCHEME = "glow://config"
        const val VERSION = 1

        /**
         * Parse a scanned payload.
         *
         * Returns [Result.failure] with a message meant for the user when the payload is
         * not ours, is from a newer build, or is missing the address.
         */
        fun decode(raw: String): Result<PairingConfig> {
            val text = raw.trim()
            if (!text.startsWith(SCHEME)) {
                return Result.failure(IllegalArgumentException("这不是阁楼的配置码"))
            }
            val query = text.substringAfter('?', "")
            val pairs = query.split('&')
                .filter { it.contains('=') }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
            val version = pairs["v"]?.toIntOrNull()
                ?: return Result.failure(IllegalArgumentException("配置码缺少版本号"))
            if (version > VERSION) {
                return Result.failure(IllegalArgumentException("配置码来自更新的版本（v$version），请先升级"))
            }
            val host = pairs["host"]?.takeIf { it.isNotBlank() }
                ?: return Result.failure(IllegalArgumentException("配置码里没有电脑地址"))
            fun port(key: String, fallback: Int): Int =
                pairs[key]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: fallback
            return Result.success(
                PairingConfig(
                    host = host,
                    imagePort = port("img", 9742),
                    helperPort = port("llm", 9739),
                    legacyPort = port("legacy", 9741),
                    mode = pairs["mode"]?.takeIf { it == "queue" || it == "sync" } ?: "queue",
                    model = pairs["model"]?.takeIf { it.isNotBlank() } ?: "default_model",
                    timeout = pairs["timeout"]?.takeIf { it in setOf("fast", "std", "patient") } ?: "std",
                ),
            )
        }
    }
}
