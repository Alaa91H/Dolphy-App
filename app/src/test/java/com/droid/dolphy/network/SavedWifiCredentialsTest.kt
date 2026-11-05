package com.droid.dolphy.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedWifiCredentialsTest {

    private val android11Store = """
        <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
        <WifiConfigStoreData>
        <int name="Version" value="3" />
        <NetworkList>
        <Network>
        <NetworkId>
        <string name="SSID">&quot;Home&quot;</string>
        </NetworkId>
        <WifiConfiguration>
        <string name="ConfigKey">&quot;Home&quot;WPA_PSK</string>
        <string name="SSID">&quot;Home&quot;</string>
        <string name="PreSharedKey">&quot;s3cret&amp;1&quot;</string>
        </WifiConfiguration>
        </Network>
        <Network>
        <WifiConfiguration>
        <string name="ConfigKey">&quot;Office&quot;WPA_PSK</string>
        <string name="SSID">&quot;Office &lt;5G&gt;&quot;</string>
        <string name="PreSharedKey">&quot;pass &quot;quoted&quot;&quot;</string>
        </WifiConfiguration>
        </Network>
        <Network>
        <WifiConfiguration>
        <string name="ConfigKey">&quot;OpenCafe&quot;NONE</string>
        <string name="SSID">&quot;OpenCafe&quot;</string>
        <string name="PreSharedKey"></string>
        </WifiConfiguration>
        </Network>
        </NetworkList>
        </WifiConfigStoreData>
    """.trimIndent()

    @Test
    fun `parses pre-shared keys with security type`() {
        val entries = SavedWifiCredentials.parse(android11Store)

        assertEquals(2, entries.size)
        assertEquals("Home", entries[0].ssid)
        assertEquals("s3cret&1", entries[0].password)
        assertEquals("WPA_PSK", entries[0].security)
        assertEquals("Office <5G>", entries[1].ssid)
        assertEquals("pass \"quoted\"", entries[1].password)
    }

    @Test
    fun `skips networks without a password`() {
        val ssids = SavedWifiCredentials.parse(android11Store).map { it.ssid }

        assertTrue(ssids.none { it == "OpenCafe" })
    }

    @Test
    fun `parses legacy store layout`() {
        val legacy = """
            <WifiConfigStoreData>
            <int name="Version" value="1" />
            <NetworkList>
            <Network>
            <WifiConfiguration>
            <string name="ConfigKey">&quot;Legacy&quot;WPA_PSK</string>
            <string name="SSID">&quot;Legacy&quot;</string>
            <string name="PreSharedKey">&quot;oldpass&quot;</string>
            </WifiConfiguration>
            </Network>
            </NetworkList>
            </WifiConfigStoreData>
        """.trimIndent()

        val entries = SavedWifiCredentials.parse(legacy)

        assertEquals(1, entries.size)
        assertEquals("Legacy", entries[0].ssid)
        assertEquals("oldpass", entries[0].password)
    }

    @Test
    fun `blank and unrelated input yields no entries`() {
        assertTrue(SavedWifiCredentials.parse("").isEmpty())
        assertTrue(SavedWifiCredentials.parse("<WifiConfigStoreData/>").isEmpty())
    }

    @Test
    fun `dump command probes the known store locations`() {
        val command = SavedWifiCredentials.dumpCommand()

        SavedWifiCredentials.storePaths.forEach { assertTrue(command.contains(it)) }
        assertTrue(command.contains(SavedWifiCredentials.SOURCE_MARKER))
    }
}
