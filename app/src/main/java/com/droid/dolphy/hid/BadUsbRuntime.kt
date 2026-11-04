package com.droid.dolphy.hid

import com.droid.dolphy.RootUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * Single entry point for running BadUSB payloads.
 *
 * Dolphy can inject keystrokes over two transports:
 *  - the Bluetooth HID session owned by [KeyboardService] (no root required), and
 *  - a rooted USB gadget (`/dev/hidg0`, see [RootHidController]).
 *
 * The runtime keeps whichever transport is currently usable, serialises payloads
 * so only one runs at a time, and exposes the same behaviour to the built-in HID
 * workspace and to plugins through the device bridge.
 */
object BadUsbRuntime {

    enum class Mode {
        /** No usable transport. */
        NONE,

        /** Rooted USB gadget descriptor at /dev/hidg0. */
        USB_GADGET,

        /** Bluetooth HID device session registered by KeyboardService. */
        BLUETOOTH_HID,
    }

    data class Status(
        val mode: Mode,
        val rooted: Boolean,
        val usbGadgetReady: Boolean,
        val bluetoothConnected: Boolean,
        val running: Boolean,
        val reason: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJob = AtomicReference<Job?>(null)

    @Volatile
    private var bluetoothController: HidController? = null

    @Volatile
    private var rootController: RootHidController? = null

    @Volatile
    private var onLog: ((String) -> Unit)? = null

    @Volatile
    private var rootProbeAt = 0L

    @Volatile
    private var rootProbeResult = false

    /**
     * Root probes fork `su`, which fails loudly when the app has no root grant.
     * Cache the answer so status refreshes do not spawn a process every time.
     */
    private fun isRootAvailable(): Boolean {
        val now = System.currentTimeMillis()
        if (now - rootProbeAt < ROOT_PROBE_TTL_MS) return rootProbeResult
        val result = runCatching { RootUtils.isRooted() }.getOrDefault(false)
        rootProbeResult = result
        rootProbeAt = now
        return result
    }

    fun attachBluetooth(controller: HidController) {
        bluetoothController = controller
    }

    fun detachBluetooth(controller: HidController) {
        if (bluetoothController === controller) bluetoothController = null
    }

    fun isRunning(): Boolean = activeJob.get()?.isActive == true

    /**
     * Reports which transport is available. Root probes spawn `su`, so call this
     * off the main thread.
     */
    fun status(): Status {
        val running = isRunning()
        val bluetooth = bluetoothController != null
        val rooted = if (bluetooth) rootProbeResult else isRootAvailable()
        val gadgetReady = if (rooted) {
            val controller = rootController ?: RootHidController().also { rootController = it }
            runCatching { controller.hasHidNodes() }.getOrDefault(false)
        } else {
            false
        }
        val mode = when {
            bluetooth -> Mode.BLUETOOTH_HID
            gadgetReady -> Mode.USB_GADGET
            rooted -> Mode.USB_GADGET
            else -> Mode.NONE
        }
        val reason = when {
            bluetooth || gadgetReady -> null
            rooted -> "Root granted but no HID gadget node is available yet"
            else -> "Connect a Bluetooth HID host or grant root for the USB gadget"
        }
        return Status(
            mode = mode,
            rooted = rooted,
            usbGadgetReady = gadgetReady,
            bluetoothConnected = bluetooth,
            running = running,
            reason = reason,
        )
    }

    fun validate(source: String): DuckyProgram = DuckyScript.parse(source)

    /**
     * Compiles [source] and runs it on the active transport. Returns false when
     * another payload is already running. Progress is delivered as short log
     * lines and a single completion callback.
     */
    fun run(
        source: String,
        log: (String) -> Unit = {},
        onFinished: (DuckyRunResult?, String?) -> Unit = { _, _ -> },
    ): Boolean {
        if (isRunning()) return false
        val program = DuckyScript.parse(source)
        if (!program.isValid) {
            val first = program.errors.firstOrNull()
            onFinished(null, first?.let { "Line ${it.line}: ${it.message}" } ?: "Invalid payload")
            return false
        }
        val controller = resolveController()
        if (controller == null) {
            onFinished(null, statusReason())
            return false
        }
        onLog = log
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                log("Running ${program.stepCount} steps on ${describe(controller)}")
                val result = DuckyRunner(controller).run(program) { message -> log(message) }
                log(
                    "Finished ${result.executed} steps in ${result.elapsedMs} ms" +
                        if (result.stopped) " (stopped)" else "",
                )
                onFinished(result, null)
            } catch (t: kotlinx.coroutines.CancellationException) {
                log("Payload cancelled")
                onFinished(null, "cancelled")
            } catch (t: Throwable) {
                log("Payload failed: ${t.message ?: t.javaClass.simpleName}")
                onFinished(null, t.message ?: t.javaClass.simpleName)
            } finally {
                onLog = null
                activeJob.compareAndSet(coroutineContext[Job], null)
            }
        }
        activeJob.set(job)
        job.start()
        return true
    }

    fun stop() {
        activeJob.getAndSet(null)?.cancel()
        onLog?.invoke("Payload cancelled")
    }

    private fun resolveController(): HidController? {
        bluetoothController?.let { return it }
        val rooted = isRootAvailable()
        if (!rooted) return null
        val controller = rootController ?: RootHidController().also { rootController = it }
        val ready = runCatching { controller.checkDevices() }.getOrDefault(false)
        return if (ready) controller else null
    }

    private fun statusReason(): String =
        "No HID transport available: connect a Bluetooth HID host or grant root for /dev/hidg0"

    private fun describe(controller: HidController): String =
        if (controller is RootHidController) "the USB gadget" else "the Bluetooth HID session"

    private const val ROOT_PROBE_TTL_MS = 60_000L
}
