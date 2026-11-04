package com.droid.dolphy.tvcast

import java.io.File

data class CastDevice(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val protocol: CastProtocol,
    val model: String? = null,
    val dialApplicationUrl: String? = null,
    val dlnaControlUrl: String? = null,
    val dlnaServiceType: String? = null,
) {
    val endpointLabel: String
        get() = when (protocol) {
            CastProtocol.CHROMECAST -> "Chromecast :$port"
            CastProtocol.DIAL -> "DIAL :$port"
            CastProtocol.DLNA -> "DLNA"
        }
}

enum class CastProtocol(val label: String) {
    CHROMECAST("Chromecast"),
    DIAL("DIAL"),
    DLNA("DLNA"),
}

data class PreparedVideo(
    val file: File,
    val mimeType: String,
    val displayName: String,
)

/**
 * A live playback handle. Closing a session must never throw; the screen uses it
 * while leaving the composable and while replacing the selected video.
 */
interface CastSession {
    fun stop()

    fun close() {}
}

internal object NoopCastSession : CastSession {
    override fun stop() = Unit
}

sealed interface CastOutcome {
    data class Success(
        val session: CastSession,
        val detail: String,
    ) : CastOutcome

    data class Failure(
        val detail: String,
    ) : CastOutcome
}
