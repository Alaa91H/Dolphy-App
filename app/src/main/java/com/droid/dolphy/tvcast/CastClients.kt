package com.droid.dolphy.tvcast

import android.util.Log
import com.droid.dolphy.core.security.LocalHttpPolicy
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/**
 * Protocol adapters for the three families Dolphy can actually drive.
 *
 * Chromecast is not DIAL: it speaks CASTV2 over TLS on the mDNS-advertised
 * port and loads media into the Default Media Receiver. DIAL only launches an
 * application, so it is used as a separate, best-effort path. DLNA sends
 * AVTransport SOAP requests to the control URL advertised by the TV.
 */
internal object CastClients {

    suspend fun play(
        device: CastDevice,
        videoUrl: String,
        mimeType: String,
        title: String,
    ): CastOutcome = withContext(Dispatchers.IO) {
        when (device.protocol) {
            CastProtocol.CHROMECAST -> GoogleCastClient.play(device, videoUrl, mimeType, title)
            CastProtocol.DIAL -> DialClient.play(device, videoUrl)
            CastProtocol.DLNA -> DlnaClient.play(device, videoUrl, mimeType, title)
        }
    }
}

private object GoogleCastClient {

    private const val TAG = "SmartTvCast"
    private const val SOURCE_ID = "sender-0"
    private const val RECEIVER_ID = "receiver-0"
    private const val DEFAULT_MEDIA_RECEIVER = "CC1AD845"
    private const val NAMESPACE_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
    private const val NAMESPACE_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
    private const val NAMESPACE_RECEIVER = "urn:x-cast:com.google.cast.receiver"
    private const val NAMESPACE_MEDIA = "urn:x-cast:com.google.cast.media"
    private const val MAX_MESSAGE_BYTES = 1024 * 1024
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 6_000

    suspend fun play(
        device: CastDevice,
        videoUrl: String,
        mimeType: String,
        title: String,
    ): CastOutcome = withContext(Dispatchers.IO) {
        if (!LocalHttpPolicy.isLocalHost(device.host)) {
            return@withContext fail("The Chromecast address is not on the local network")
        }
        var socket: SSLSocket? = null
        try {
            val connected = connect(device)
            socket = connected.socket
            val input = DataInputStream(BufferedInputStream(socket.inputStream))
            val output = DataOutputStream(BufferedOutputStream(socket.outputStream))
            send(
                output,
                RECEIVER_ID,
                NAMESPACE_CONNECTION,
                """{"type":"CONNECT"}""",
            )
            send(
                output,
                RECEIVER_ID,
                NAMESPACE_RECEIVER,
                """{"type":"GET_STATUS","requestId":1}""",
            )

            val initialStatus = awaitReceiverStatus(input, output)
                ?: return@withContext fail("Chromecast did not answer")
            var app = findApplication(initialStatus, DEFAULT_MEDIA_RECEIVER)
            if (app == null) {
                send(
                    output,
                    RECEIVER_ID,
                    NAMESPACE_RECEIVER,
                    JSONObject()
                        .put("type", "LAUNCH")
                        .put("appId", DEFAULT_MEDIA_RECEIVER)
                        .put("requestId", 2)
                        .toString(),
                )
                var attempts = 0
                while (app == null && attempts < 12) {
                    val status = awaitReceiverStatus(input, output) ?: break
                    app = findApplication(status, DEFAULT_MEDIA_RECEIVER)
                    attempts++
                }
            }
            val application = app ?: return@withContext fail("Default Media Receiver is unavailable")
            val transportId = application.optString("transportId")
                .takeIf { it.isNotBlank() }
                ?: return@withContext fail("Chromecast returned no media transport")
            send(
                output,
                transportId,
                NAMESPACE_CONNECTION,
                """{"type":"CONNECT"}""",
            )

            val media = JSONObject()
                .put("contentId", videoUrl)
                .put("streamType", "BUFFERED")
                .put("contentType", mimeType)
                .put(
                    "metadata",
                    JSONObject()
                        .put("type", 0)
                        .put("metadataType", 0)
                        .put("title", title),
                )
            send(
                output,
                transportId,
                NAMESPACE_MEDIA,
                JSONObject()
                    .put("type", "LOAD")
                    .put("requestId", 3)
                    .put("media", media)
                    .put("autoplay", true)
                    .toString(),
            )
            val mediaStatus = awaitMediaStatus(input, output, videoUrl)
                ?: return@withContext fail("Chromecast rejected the media")
            val sessionId = mediaStatus.optLong(
                "mediaSessionId",
                application.optLong("sessionId", -1L),
            )
            CastOutcome.Success(
                GoogleCastSession(socket, output, transportId, sessionId),
                "Chromecast",
            )
        } catch (error: IOException) {
            Log.w(TAG, "CASTV2 I/O failed: ${error.message}", error)
            runCatching { socket?.close() }
            fail(error.message ?: "Chromecast connection failed")
        } catch (error: JSONException) {
            Log.w(TAG, "CASTV2 protocol failed: ${error.message}", error)
            runCatching { socket?.close() }
            fail("Chromecast returned an invalid message")
        }
    }

    private fun connect(device: CastDevice): ConnectedCast {
        val raw = Socket()
        var secure: SSLSocket? = null
        try {
            raw.connect(InetSocketAddress(device.host, device.port), CONNECT_TIMEOUT_MS)
            raw.soTimeout = READ_TIMEOUT_MS
            val context = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(TrustLocalCastCertificate), SecureRandom())
            }
            secure = context.socketFactory.createSocket(
                raw,
                device.host,
                device.port,
                true,
            ) as SSLSocket
            secure.sslParameters = secure.sslParameters.apply {
                endpointIdentificationAlgorithm = null
            }
            secure.startHandshake()
            return ConnectedCast(secure)
        } catch (error: IOException) {
            runCatching { secure?.close() }
            runCatching { raw.close() }
            throw error
        } catch (error: GeneralSecurityException) {
            runCatching { secure?.close() }
            runCatching { raw.close() }
            throw IOException("Unable to initialise Chromecast TLS", error)
        }
    }

    private fun awaitReceiverStatus(
        input: DataInputStream,
        output: DataOutputStream,
    ): JSONObject? {
        repeat(16) {
            val message = readJson(input, output) ?: return null
            if (message.optString("type") == "RECEIVER_STATUS") return message
        }
        return null
    }

    private fun awaitMediaStatus(
        input: DataInputStream,
        output: DataOutputStream,
        videoUrl: String,
    ): JSONObject? {
        repeat(16) {
            val message = readJson(input, output) ?: return null
            if (message.optString("type") != "MEDIA_STATUS") return@repeat
            val statuses = message.optJSONArray("status")
            val status = statuses?.optJSONObject(0) ?: return@repeat
            val contentId = status.optJSONObject("media")?.optString("contentId")
            val isThisLoad = message.optInt("requestId", -1) == 3 || contentId == videoUrl
            if (!isThisLoad) return@repeat
            val state = status.optString("playerState")
            if (state == "PLAYING" || state == "BUFFERING" || state == "PAUSED") {
                return status
            }
        }
        return null
    }

    private fun findApplication(status: JSONObject, appId: String): JSONObject? {
        val applications = status.optJSONObject("status")?.optJSONArray("applications") ?: return null
        for (index in 0 until applications.length()) {
            val application = applications.optJSONObject(index) ?: continue
            if (application.optString("appId") == appId) return application
        }
        return null
    }

    private fun readJson(input: DataInputStream, output: DataOutputStream): JSONObject? {
        val message = readMessage(input) ?: return null
        val json = runCatching { JSONObject(message.payload) }.getOrNull() ?: return null
        if (json.optString("type") == "PING") {
            send(output, RECEIVER_ID, NAMESPACE_HEARTBEAT, """{"type":"PONG"}""")
        }
        return json
    }

    private fun readMessage(input: DataInputStream): CastV2Message? {
        val length =        try {
            input.readInt()
        } catch (error: IOException) {
            Log.d(TAG, "CASTV2 read ended: ${error.message}")
            return null
        }
        if (length <= 0 || length > MAX_MESSAGE_BYTES) return null
        val body = ByteArray(length)
        input.readFully(body)
        return CastV2Codec.decodeBody(body, 0, body.size)
    }

    private fun send(
        output: DataOutputStream,
        destinationId: String,
        namespace: String,
        payload: String,
    ) {
        val frame = CastV2Codec.encodeFrame(
            CastV2Message(SOURCE_ID, destinationId, namespace, payload),
        )
        output.write(frame)
        output.flush()
    }

    private fun fail(detail: String): CastOutcome = CastOutcome.Failure(detail)

    private data class ConnectedCast(val socket: SSLSocket)

    private class GoogleCastSession(
        private val socket: SSLSocket,
        private val output: DataOutputStream,
        private val transportId: String,
        private val mediaSessionId: Long,
    ) : CastSession {

        @Volatile
        private var closed = false

        override fun stop() {
            if (closed) return
            runCatching {
                val payload = JSONObject()
                    .put("type", "STOP")
                    .put("requestId", 99)
                if (mediaSessionId >= 0) payload.put("mediaSessionId", mediaSessionId)
                send(output, transportId, NAMESPACE_MEDIA, payload.toString())
                output.flush()
            }.onFailure { Log.d(TAG, "CASTV2 stop ignored: ${it.message}") }
            close()
        }

        override fun close() {
            if (closed) return
            closed = true
            runCatching { socket.close() }
        }
    }

    private object TrustLocalCastCertificate : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}

private object DialClient {

    private const val TAG = "SmartTvCast"
    private const val TIMEOUT_MS = 4_000
    private const val MAX_BODY_BYTES = 256 * 1024

    fun play(device: CastDevice, videoUrl: String): CastOutcome {
        val base = device.dialApplicationUrl
            ?.takeIf { it.startsWith("http", ignoreCase = true) }
            ?: "${CastNetwork.origin(device.host, device.port)}/apps/"
        val normalizedBase = if (base.endsWith('/')) base else "$base/"
        val apps = discoverApps(normalizedBase)
        val app = chooseApp(apps)
            ?: return CastOutcome.Failure("No DIAL media application was found")
        val launchUrl = dialAppUrl(normalizedBase, app)
        val launch = post(launchUrl, videoUrl, "text/plain; charset=utf-8")
            ?: return CastOutcome.Failure("DIAL launch failed")
        if (launch.code !in 200..299) {
            return CastOutcome.Failure("DIAL launch returned HTTP ${launch.code}")
        }
        val instanceUrl = launch.location?.let { resolveLocalUrl(launchUrl, it) }
        if (instanceUrl != null) {
            val playBody = "v=" + URLEncoder.encode(videoUrl, StandardCharsets.UTF_8.name())
            val play = post(instanceUrl, playBody, "application/x-www-form-urlencoded")
            if (play == null || play.code !in 200..299) {
                return CastOutcome.Failure("The DIAL application did not accept the video")
            }
        }
        return CastOutcome.Success(DialSession(instanceUrl), app)
    }

    private fun discoverApps(base: String): List<String> {
        val result = get(base) ?: return emptyList()
        if (result.code !in 200..299) return emptyList()
        return parseDialApps(result.body)
    }

    private fun chooseApp(apps: List<String>): String? {
        val preferred = listOf("DefaultMediaReceiver", "YouTube", "Video", "Media", "Player", "AVPlayer")
        return preferred.firstOrNull { candidate ->
            apps.any { it.equals(candidate, ignoreCase = true) }
        } ?: apps.firstOrNull { app ->
            app.contains("video", ignoreCase = true) ||
                app.contains("media", ignoreCase = true) ||
                app.contains("player", ignoreCase = true)
        } ?: apps.firstOrNull()
    }

    private fun get(url: String): HttpResult? = request(url, "GET", null, null)

    private fun post(url: String, body: String, contentType: String): HttpResult? =
        request(url, "POST", body, contentType)

    private fun request(
        url: String,
        method: String,
        body: String?,
        contentType: String?,
    ): HttpResult? {
        return runCatching {
            val connection = LocalHttpPolicy.requireLocal(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.instanceFollowRedirects = false
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("Connection", "close")
                if (contentType != null) connection.setRequestProperty("Content-Type", contentType)
                if (body != null) {
                    val bytes = body.toByteArray(StandardCharsets.UTF_8)
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.outputStream.use { it.write(bytes) }
                }
                val code = connection.responseCode
                HttpResult(
                    code = code,
                    location = connection.getHeaderField("Location"),
                    body = readLimited(
                        if (code in 200..299) connection.inputStream else connection.errorStream,
                    ),
                )
            } finally {
                connection.disconnect()
            }
        }.onFailure { Log.d(TAG, "DIAL $url failed: ${it.message}") }.getOrNull()
    }

    private fun readLimited(input: InputStream?): String {
        if (input == null) return ""
        val buffer = ByteArray(8 * 1024)
        val output = ByteArrayOutputStream()
        var remaining = MAX_BODY_BYTES
        while (remaining > 0) {
            val read = input.read(buffer)
            if (read <= 0) break
            val allowed = minOf(read, remaining)
            output.write(buffer, 0, allowed)
            remaining -= allowed
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    private class DialSession(private val instanceUrl: String?) : CastSession {
        override fun stop() {
            val url = instanceUrl ?: return
            runCatching {
                val connection = LocalHttpPolicy.requireLocal(url).openConnection() as HttpURLConnection
                connection.requestMethod = "DELETE"
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.responseCode
                connection.disconnect()
            }.onFailure { Log.d(TAG, "DIAL stop ignored: ${it.message}") }
        }
    }

    private data class HttpResult(
        val code: Int,
        val location: String?,
        val body: String,
    )
}

private object DlnaClient {

    private const val TAG = "SmartTvCast"
    private const val TIMEOUT_MS = 5_000
    private const val MAX_BODY_BYTES = 256 * 1024
    private const val DEFAULT_SERVICE = "urn:schemas-upnp-org:service:AVTransport:1"

    fun play(
        device: CastDevice,
        videoUrl: String,
        mimeType: String,
        title: String,
    ): CastOutcome {
        val controlUrl = device.dlnaControlUrl
            ?.takeIf { LocalHttpPolicy.isLocalUrl(it) }
            ?: return CastOutcome.Failure("The TV did not advertise an AVTransport control URL")
        val serviceType = device.dlnaServiceType ?: DEFAULT_SERVICE
        val metadata = escapeXml(buildDidlMetadata(videoUrl, mimeType, title))
        val setBody = """
            <u:SetAVTransportURI xmlns:u="$serviceType">
              <InstanceID>0</InstanceID>
              <CurrentURI>${escapeXml(videoUrl)}</CurrentURI>
              <CurrentURIMetaData>$metadata</CurrentURIMetaData>
            </u:SetAVTransportURI>
        """.trimIndent()
        val setResult = soap(controlUrl, serviceType, "SetAVTransportURI", setBody)
            ?: return CastOutcome.Failure("DLNA SetAVTransportURI failed")
        if (setResult.code !in 200..299) {
            return CastOutcome.Failure("The TV rejected SetAVTransportURI (HTTP ${setResult.code})")
        }
        val playBody = """
            <u:Play xmlns:u="$serviceType">
              <InstanceID>0</InstanceID>
              <Speed>1</Speed>
            </u:Play>
        """.trimIndent()
        val playResult = soap(controlUrl, serviceType, "Play", playBody)
            ?: return CastOutcome.Failure("DLNA Play failed")
        if (playResult.code !in 200..299) {
            return CastOutcome.Failure("The TV rejected Play (HTTP ${playResult.code})")
        }
        return CastOutcome.Success(DlnaSession(controlUrl, serviceType), "DLNA")
    }

    private fun soap(
        controlUrl: String,
        serviceType: String,
        action: String,
        body: String,
    ): HttpResult? {
        val envelope = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"
             xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body>$body</s:Body>
            </s:Envelope>
        """.trimIndent()
        return runCatching {
            val connection = LocalHttpPolicy.requireLocal(controlUrl)
                .openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                connection.setRequestProperty("SOAPAction", "\"$serviceType#$action\"")
                connection.setRequestProperty("Connection", "close")
                val bytes = envelope.toByteArray(StandardCharsets.UTF_8)
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
                val code = connection.responseCode
                HttpResult(
                    code = code,
                    body = readLimited(
                        if (code in 200..299) connection.inputStream else connection.errorStream,
                    ),
                )
            } finally {
                connection.disconnect()
            }
        }.onFailure { Log.d(TAG, "DLNA $action failed: ${it.message}") }.getOrNull()
    }

    private fun readLimited(input: InputStream?): String {
        if (input == null) return ""
        val buffer = ByteArray(8 * 1024)
        val output = ByteArrayOutputStream()
        var remaining = MAX_BODY_BYTES
        while (remaining > 0) {
            val read = input.read(buffer)
            if (read <= 0) break
            val allowed = minOf(read, remaining)
            output.write(buffer, 0, allowed)
            remaining -= allowed
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    private class DlnaSession(
        private val controlUrl: String,
        private val serviceType: String,
    ) : CastSession {
        override fun stop() {
            runCatching {
                val body = """
                    <u:Stop xmlns:u="$serviceType">
                      <InstanceID>0</InstanceID>
                    </u:Stop>
                """.trimIndent()
                soap(controlUrl, serviceType, "Stop", body)
            }.onFailure { Log.d(TAG, "DLNA stop ignored: ${it.message}") }
        }
    }

    private data class HttpResult(
        val code: Int,
        val body: String,
    )
}
