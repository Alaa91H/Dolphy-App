package com.droid.dolphy.network

/**
 * Reads the Wi-Fi passwords Android keeps on the device.
 *
 * Android stores every saved network, including its pre-shared key, in a
 * root-only `WifiConfigStore.xml` (under `/data/misc/wifi` before Android 11 and
 * under the Wi-Fi APEX afterwards). This object only builds the elevated shell
 * snippet that prints that file and parses the XML it returns; it never touches
 * the file system itself, so it is unit-testable.
 */
object SavedWifiCredentials {

    /** Candidate store locations, newest layout first. */
    val storePaths: List<String> = listOf(
        "/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml",
        "/data/misc/wifi/WifiConfigStore.xml",
        "/data/misc/apexdata/com.android.wifi/misc/WifiConfigStore.xml",
    )

    /** Prefix of the marker line that identifies the store that was read. */
    const val SOURCE_MARKER = "__DOLPHY_WIFI_STORE__="

    data class Credential(
        val ssid: String,
        val password: String,
        val security: String,
    )

    /**
     * Shell snippet that prints the first readable store, prefixed by a marker
     * line so the caller can report where the data came from.
     */
    fun dumpCommand(): String = buildString {
        append("for f in ")
        storePaths.forEach { path -> append('"').append(path).append("\" ") }
        append("; do if [ -r \"\$f\" ]; then echo \"$SOURCE_MARKER\$f\"; cat \"\$f\"; break; fi; done")
    }

    private val NETWORK_BLOCK = Regex("<network\\b", RegexOption.IGNORE_CASE)

    /**
     * Returns one entry per saved network that has a recoverable password.
     * Open and enterprise networks carry no pre-shared key and are skipped.
     */
    fun parse(xml: String): List<Credential> {
        if (xml.isBlank()) return emptyList()
        return NETWORK_BLOCK.split(xml)
            .asSequence()
            .drop(1) // Text before the first <Network> is the file header.
            .mapNotNull(::toCredential)
            .distinctBy { it.ssid to it.password }
            .toList()
    }

    private fun toCredential(block: String): Credential? {
        val ssid = unwrap(field(block, "SSID"))?.takeIf { it.isNotBlank() } ?: return null
        val password = unwrap(field(block, "PreSharedKey"))?.takeIf { it.isNotBlank() } ?: return null
        return Credential(ssid, password, security(block))
    }

    /** Android stores values as `"value"`; strip only that enclosing pair. */
    private fun unwrap(value: String?): String? {
        val trimmed = value?.trim() ?: return null
        return if (trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"') {
            trimmed.substring(1, trimmed.length - 1)
        } else {
            trimmed
        }
    }

    /** `<string name="ConfigKey">&quot;Home&quot;WPA_PSK</string>` -> `WPA_PSK`. */
    private fun security(block: String): String =
        field(block, "ConfigKey").orEmpty().substringAfterLast('"').trim()

    private fun field(block: String, name: String): String? {
        val regex = Regex(
            "<string\\s+name=\"" + Regex.escape(name) + "\"[^>]*>(.*?)</string>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        return regex.find(block)?.groupValues?.get(1)?.let(::unescape)
    }

    private fun unescape(value: String): String = value
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
}
