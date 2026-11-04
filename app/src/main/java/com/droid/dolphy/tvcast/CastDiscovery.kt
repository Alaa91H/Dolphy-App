@file:Suppress("DEPRECATION")

package com.droid.dolphy.tvcast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.droid.dolphy.core.security.LocalHttpPolicy
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Finds Cast receivers without assuming one port for every protocol.
 *
 * Google Cast receivers are resolved through mDNS and keep the advertised
 * CASTV2 port (normally 8009). DIAL and UPnP/DLNA are resolved through mDNS
 * and SSDP; the AVTransport control URL comes from the television's own device
 * description instead of a hard-coded path.
 */
class CastDiscovery(
    context: Context,
    private val onDevice: (CastDevice) -> Unit,
    private val onDeviceLost: (String) -> Unit,
    private val onScanning: (Boolean) -> Unit,
    private val onError: (String) -> Unit,
) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val nsdManager =
        appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val found = ConcurrentHashMap<String, CastDevice>()
    private val resolveQueue = ConcurrentLinkedQueue<Pair<CastProtocol, NsdServiceInfo>>()
    private val resolving = AtomicBoolean(false)

    @Volatile
    private var running = false
    private var scope: CoroutineScope? = null
    private var googleListener: NsdManager.DiscoveryListener? = null
    private var dialListener: NsdManager.DiscoveryListener? = null

    @Volatile
    private var multicastLock: WifiManager.MulticastLock? = null
    private var stopJob: Job? = null

    fun start() {
        if (running) return
        running = true
        mainHandler.post { onScanning(true) }
        startNsd()
        val discoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = discoveryScope
        stopJob = discoveryScope.launch {
            runCatching { discoverSsdp() }
                .onFailure { Log.w(TAG, "SSDP discovery failed: ${it.message}") }
            delay(SCAN_WINDOW_MS)
            if (running) mainHandler.post { onScanning(false) }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        stopJob?.cancel()
        stopJob = null
        scope?.cancel()
        scope = null
        resolveQueue.clear()
        resolving.set(false)
        stopNsd()
        multicastLock?.let { lock ->
            runCatching { if (lock.isHeld) lock.release() }
        }
        multicastLock = null
        mainHandler.post { onScanning(false) }
    }

    fun restart() {
        stop()
        mainHandler.postDelayed({
            if (!running) start()
        }, RESTART_DELAY_MS)
    }

    private fun startNsd() {
        val manager = nsdManager ?: run {
            onError("mDNS unavailable")
            return
        }
        val google = discoveryListener(CastProtocol.CHROMECAST)
        val dial = discoveryListener(CastProtocol.DIAL)
        googleListener = google
        dialListener = dial
        runCatching {
            manager.discoverServices("_googlecast._tcp", NsdManager.PROTOCOL_DNS_SD, google)
        }.onFailure {
            Log.w(TAG, "Google Cast mDNS start failed", it)
            onError("Google Cast discovery failed")
        }
        runCatching {
            manager.discoverServices("_dial._tcp", NsdManager.PROTOCOL_DNS_SD, dial)
        }.onFailure {
            Log.w(TAG, "DIAL mDNS start failed", it)
            onError("DIAL discovery failed")
        }
    }

    private fun stopNsd() {
        val manager = nsdManager ?: return
        listOf(googleListener, dialListener).filterNotNull().forEach { listener ->
            runCatching { manager.stopServiceDiscovery(listener) }
                .onFailure { Log.d(TAG, "mDNS stop ignored: ${it.message}") }
        }
        googleListener = null
        dialListener = null
    }

    private fun discoveryListener(protocol: CastProtocol): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "mDNS started: $serviceType")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "mDNS stopped: $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!running) return
                if (protocol == CastProtocol.CHROMECAST &&
                    !serviceInfo.serviceType.contains("_googlecast", ignoreCase = true)
                ) {
                    return
                }
                if (protocol == CastProtocol.DIAL &&
                    !serviceInfo.serviceType.contains("_dial", ignoreCase = true)
                ) {
                    return
                }
                resolveQueue.add(protocol to serviceInfo)
                drainResolveQueue()
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                if (!running) return
                val host = serviceInfo.host?.hostAddress ?: return
                val id = deviceId(protocol, host, serviceInfo.port)
                found.remove(id)?.let { mainHandler.post { onDeviceLost(id) } }
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "mDNS start failed: $serviceType ($errorCode)")
                if (running) onError("mDNS start failed")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.d(TAG, "mDNS stop failed: $serviceType ($errorCode)")
            }
        }
    }

    private fun drainResolveQueue() {
        val manager = nsdManager ?: return
        if (!running) return
        if (!resolving.compareAndSet(false, true)) return
        val next = resolveQueue.poll()
        if (next == null) {
            resolving.set(false)
            return
        }
        val (protocol, serviceInfo) = next
        runCatching {
            manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) {
                    Log.d(TAG, "mDNS resolve failed: ${info?.serviceName} ($errorCode)")
                    resolving.set(false)
                    drainResolveQueue()
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    if (!running) {
                        resolving.set(false)
                        return
                    }
                    val host = info.host?.hostAddress
                    if (host != null && info.port > 0) {
                        publish(resolvedDevice(protocol, info, host))
                    }
                    resolving.set(false)
                    drainResolveQueue()
                }
            })
        }.onFailure {
            Log.d(TAG, "mDNS resolve request failed: ${it.message}")
            resolving.set(false)
            drainResolveQueue()
        }
    }

    private fun resolvedDevice(
        protocol: CastProtocol,
        info: NsdServiceInfo,
        host: String,
    ): CastDevice {
        val txt = info.attributes
            .mapKeys { it.key.lowercase(Locale.US) }
            .mapValues { it.value.toString(StandardCharsets.UTF_8) }
        val name = txt["fn"]?.takeIf { it.isNotBlank() }
            ?: info.serviceName.takeIf { it.isNotBlank() }
            ?: "Cast device"
        val model = txt["md"]?.takeIf { it.isNotBlank() }
        val port = info.port
        val appUrl = if (protocol == CastProtocol.DIAL) {
            txt["application-url"]?.takeIf { it.isNotBlank() }
                ?: "${CastNetwork.origin(host, port)}/apps/"
        } else {
            null
        }
        return CastDevice(
            id = deviceId(protocol, host, port),
            name = name,
            host = host,
            port = port,
            protocol = protocol,
            model = model,
            dialApplicationUrl = appUrl,
        )
    }

    private suspend fun discoverSsdp() {
        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifiManager?.createMulticastLock("dolphy-cast-ssdp")?.apply {
            setReferenceCounted(false)
            try {
                acquire()
            } catch (error: SecurityException) {
                Log.w(TAG, "Multicast lock denied", error)
            }
        }
        multicastLock = lock
        try {
            val socket = try {
                DatagramSocket().apply { soTimeout = SSDP_READ_TIMEOUT_MS }
            } catch (error: SocketException) {
                Log.w(TAG, "Unable to open SSDP socket", error)
                return
            }
            try {
                sendSsdpSearches(socket)
                receiveSsdp(socket)
            } finally {
                socket.close()
            }
        } finally {
            lock?.let { runCatching { if (it.isHeld) it.release() } }
            if (multicastLock === lock) multicastLock = null
        }
    }

    private fun sendSsdpSearches(socket: DatagramSocket) {
        val group = InetAddress.getByName(SSDP_GROUP)
        listOf(DIAL_SERVICE_TYPE, MEDIA_RENDERER_TYPE).forEach { type ->
            val request = "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 1\r\n" +
                "ST: $type\r\n\r\n"
            val bytes = request.toByteArray(StandardCharsets.US_ASCII)
            socket.send(DatagramPacket(bytes, bytes.size, group, SSDP_PORT))
        }
    }

    private fun receiveSsdp(socket: DatagramSocket) {
        val deadline = System.currentTimeMillis() + SSDP_WINDOW_MS
        val seen = mutableSetOf<String>()
        val buffer = ByteArray(16 * 1024)
        while (running && System.currentTimeMillis() < deadline) {
            val packet = receivePacket(socket, buffer) ?: continue
            handleSsdpPacket(packet, seen)
        }
    }

    private fun receivePacket(socket: DatagramSocket, buffer: ByteArray): DatagramPacket? {
        val packet = DatagramPacket(buffer, buffer.size)
        return try {
            socket.receive(packet)
            packet
        } catch (_: SocketTimeoutException) {
            // The socket timeout is the normal polling boundary for SSDP.
            null
        } catch (error: IOException) {
            if (running) Log.d(TAG, "SSDP receive stopped: ${error.message}")
            null
        }
    }

    private fun handleSsdpPacket(packet: DatagramPacket, seen: MutableSet<String>) {
        val raw = String(packet.data, 0, packet.length, StandardCharsets.UTF_8)
        val response = parseSsdpResponse(raw, packet.address?.hostAddress) ?: return
        val location = response.location ?: return
        if (!seen.add(location) || !LocalHttpPolicy.isLocalUrl(location)) return
        runCatching { resolveSsdp(response) }
            .onFailure { Log.d(TAG, "SSDP description failed: ${it.message}") }
    }

    private fun resolveSsdp(response: SsdpResponse) {
        val location = response.location ?: return
        val description = fetchDescription(location) ?: return
        val isDial = response.serviceType?.contains("dial", ignoreCase = true) == true ||
            response.usn?.contains("dial", ignoreCase = true) == true
        val isDlna = response.serviceType?.contains("MediaRenderer", ignoreCase = true) == true ||
            response.usn?.contains("MediaRenderer", ignoreCase = true) == true
        when {
            isDial -> publishDial(location, description)
            isDlna -> publishDlna(location, description)
        }
    }

    private fun publishDial(location: String, description: HttpDescription) {
        val uri = runCatching { URI(location) }.getOrNull() ?: return
        val host = uri.host ?: return
        val port = if (uri.port > 0) uri.port else 80
        val appUrl = parseDialApplicationUrl(location, description.applicationUrl)
        if (!LocalHttpPolicy.isLocalUrl(appUrl)) return
        val name = parseFriendlyName(description.body)
            ?: uri.host
            ?: "DIAL device"
        publish(
            CastDevice(
                id = deviceId(CastProtocol.DIAL, host, port),
                name = name,
                host = host,
                port = port,
                protocol = CastProtocol.DIAL,
                dialApplicationUrl = appUrl,
            ),
        )
    }

    private fun publishDlna(location: String, description: HttpDescription) {
        val parsed = parseUpnpDescription(description.body, location) ?: return
        val controlUrl = parsed.controlUrl ?: return
        if (!LocalHttpPolicy.isLocalUrl(controlUrl)) return
        val uri = runCatching { URI(location) }.getOrNull() ?: return
        val host = uri.host ?: return
        val port = if (uri.port > 0) uri.port else 80
        publish(
            CastDevice(
                id = deviceId(CastProtocol.DLNA, host, port),
                name = parsed.friendlyName ?: host,
                host = host,
                port = port,
                protocol = CastProtocol.DLNA,
                model = parsed.modelName,
                dlnaControlUrl = controlUrl,
                dlnaServiceType = parsed.serviceType,
            ),
        )
    }

    private fun fetchDescription(location: String): HttpDescription? {
        val connection = LocalHttpPolicy.requireLocal(location)
            .openConnection() as java.net.HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = HTTP_TIMEOUT_MS
            connection.readTimeout = HTTP_TIMEOUT_MS
            connection.setRequestProperty("Connection", "close")
            if (connection.responseCode !in 200..299) return null
            HttpDescription(
                applicationUrl = connection.getHeaderField("Application-URL"),
                body = readLimited(connection.inputStream),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(input: InputStream): String {
        val buffer = ByteArray(8 * 1024)
        val output = java.io.ByteArrayOutputStream()
        var remaining = MAX_DESCRIPTION_BYTES
        while (remaining > 0) {
            val read = input.read(buffer)
            if (read <= 0) break
            val allowed = minOf(read, remaining)
            output.write(buffer, 0, allowed)
            remaining -= allowed
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    private fun publish(device: CastDevice) {
        if (!running) return
        val previous = found.put(device.id, device)
        if (previous != device) {
            mainHandler.post {
                if (running) onDevice(device)
            }
        }
    }

    private fun deviceId(protocol: CastProtocol, host: String, port: Int): String =
        "${protocol.name.lowercase(Locale.US)}:$host:$port"

    private data class HttpDescription(
        val applicationUrl: String?,
        val body: String,
    )

    private companion object {
        const val TAG = "SmartTvCast"
        const val SSDP_GROUP = "239.255.255.250"
        const val SSDP_PORT = 1900
        const val SSDP_WINDOW_MS = 4_000L
        const val SSDP_READ_TIMEOUT_MS = 600
        const val SCAN_WINDOW_MS = 10_000L
        const val RESTART_DELAY_MS = 350L
        const val HTTP_TIMEOUT_MS = 2_500
        const val MAX_DESCRIPTION_BYTES = 512 * 1024
        const val DIAL_SERVICE_TYPE = "urn:dial-multiscreen-org:service:dial:1"
        const val MEDIA_RENDERER_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1"
    }
}
