package com.droid.dolphy.plugin

import android.content.Context
import androidx.annotation.StringRes
import com.droid.dolphy.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * A plugin published by the official Dolphy repository. The catalog is a compiled-in index:
 * the app knows the published version of every entry and compares it with the installed
 * manifest to offer updates.
 */
data class CatalogPlugin(
    val id: String,
    val name: String,
    val author: String,
    val version: String,
    val fileName: String,
    val sizeBytes: Long,
    val icon: String,
    val permissions: List<String>,
    @StringRes val descriptionRes: Int,
    val url: String = "https://raw.githubusercontent.com/unvoiddd/Dolphy-App/main/plugin-examples/$fileName",
)

val PLUGIN_CATALOG: List<CatalogPlugin> = listOf(
    CatalogPlugin(
        id = "wifi_scanner_md3e",
        name = "Wi-Fi Scanner MD3E",
        author = "Dolphy",
        version = "1.0.0",
        fileName = "wifi_scanner_md3e.dolphyplugin",
        sizeBytes = 7_800,
        icon = "rounded:wifi",
        permissions = listOf("wifi"),
        descriptionRes = R.string.plugin_catalog_desc_wifi_scanner,
    ),
    CatalogPlugin(
        id = "ble_scanner_dex_md3e",
        name = "BLE Scanner DEX MD3E",
        author = "Dolphy",
        version = "1.0.0",
        fileName = "ble_scanner_dex_md3e.dolphyplugin",
        sizeBytes = 12_075,
        icon = "rounded:bluetooth_searching",
        permissions = listOf("bluetooth", "dex"),
        descriptionRes = R.string.plugin_catalog_desc_ble_scanner,
    ),
    CatalogPlugin(
        id = "radio_lab",
        name = "Radio Lab",
        author = "Dolphy",
        version = "1.0.0",
        fileName = "radio_lab.dolphyplugin",
        sizeBytes = 2_436,
        icon = "filled:bluetooth_searching",
        permissions = emptyList(),
        descriptionRes = R.string.plugin_catalog_desc_radio_lab,
    ),
    CatalogPlugin(
        id = "integration_hooks",
        name = "Integration Hooks",
        author = "Dolphy",
        version = "1.0.0",
        fileName = "integration_hooks.dolphyplugin",
        sizeBytes = 1_820,
        icon = "rounded:extension",
        permissions = emptyList(),
        descriptionRes = R.string.plugin_catalog_desc_integration_hooks,
    ),
    CatalogPlugin(
        id = "ble_asset_pack",
        name = "BLE Asset Pack",
        author = "Dolphy",
        version = "1.0.0",
        fileName = "ble_asset_pack.dolphyplugin",
        sizeBytes = 2_324,
        icon = "rounded:radar",
        permissions = listOf("ble_spam", "bluetooth", "download", "files", "infrared"),
        descriptionRes = R.string.plugin_catalog_desc_ble_asset_pack,
    ),
)

/** Compares dot separated versions; returns true when [remote] is newer than [installed]. */
fun isCatalogVersionNewer(remote: String, installed: String): Boolean {
    val a = remote.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    val b = installed.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    for (index in 0 until maxOf(a.size, b.size)) {
        val left = a.getOrElse(index) { 0 }
        val right = b.getOrElse(index) { 0 }
        if (left != right) return left > right
    }
    return false
}

/**
 * Downloads catalog plugins into the private cache and hands them to [PluginManager],
 * exposing per-entry progress so the store UI stays responsive.
 */
object PluginCatalog {

    enum class Phase { Downloading, Installing, Done, Failed }

    data class State(
        val phase: Phase,
        val progress: Int = 0,
        val message: String? = null,
    )

    private const val MAX_DOWNLOAD_BYTES = 16L * 1024L * 1024L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    val states: StateFlow<Map<String, State>> = _states.asStateFlow()

    fun isBusy(pluginId: String): Boolean =
        _states.value[pluginId]?.phase.let { it == Phase.Downloading || it == Phase.Installing }

    fun clear(pluginId: String) {
        _states.value = _states.value - pluginId
    }

    fun install(context: Context, entry: CatalogPlugin) {
        if (isBusy(entry.id)) return
        val app = context.applicationContext
        update(entry.id, State(Phase.Downloading, 0))
        scope.launch {
            try {
                val dir = File(app.cacheDir, "plugin_catalog").apply { mkdirs() }
                val target = File(dir, entry.fileName)
                download(app, entry, target) { progress ->
                    update(entry.id, State(Phase.Downloading, progress))
                }
                update(entry.id, State(Phase.Installing, 100))
                val result = PluginManager.installFromFile(target)
                target.delete()
                result.fold(
                    onSuccess = { manifest ->
                        update(
                            entry.id,
                            State(
                                Phase.Done,
                                100,
                                app.getString(R.string.plugin_catalog_installed_toast, manifest.name, manifest.version),
                            ),
                        )
                    },
                    onFailure = { error ->
                        update(
                            entry.id,
                            State(
                                Phase.Failed,
                                message = error.message ?: app.getString(R.string.plugin_catalog_error_generic),
                            ),
                        )
                    },
                )
            } catch (t: Throwable) {
                update(
                    entry.id,
                    State(
                        Phase.Failed,
                        message = t.message ?: app.getString(R.string.plugin_catalog_error_generic),
                    ),
                )
            }
        }
    }

    private fun update(pluginId: String, state: State) {
        _states.value = _states.value + (pluginId to state)
    }

    private fun download(
        context: Context,
        entry: CatalogPlugin,
        target: File,
        onProgress: (Int) -> Unit,
    ) {
        val connection = (URL(entry.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Dolphy-Android")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException(context.getString(R.string.plugin_catalog_download_failed, code))
            }
            val total = connection.contentLengthLong
            var received = 0L
            var lastProgress = -1
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        received += read
                        if (received > MAX_DOWNLOAD_BYTES) {
                            throw IllegalStateException(context.getString(R.string.plugin_catalog_download_too_large))
                        }
                        output.write(buffer, 0, read)
                        if (total > 0) {
                            val progress = ((received * 100) / total).toInt().coerceIn(0, 100)
                            if (progress != lastProgress) {
                                lastProgress = progress
                                onProgress(progress)
                            }
                        }
                    }
                }
            }
            if (target.length() == 0L) {
                throw IllegalStateException(context.getString(R.string.plugin_catalog_download_empty))
            }
        } finally {
            connection.disconnect()
        }
    }
}
