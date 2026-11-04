package com.droid.dolphy.feature.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureTaxonomyTest {

    @Test
    fun `every built-in route maps to the expected category`() {
        val expected = mapOf(
            "ble_spam_screen" to "bluetooth",
            "bond_spam_screen" to "bluetooth",
            "other/audio_scanner" to "bluetooth",
            "other/scooter_hack" to "bluetooth",
            "other/nrf_scanner" to "bluetooth",
            "other/dolphy_chat_global" to "bluetooth",
            "hid" to "bluetooth",
            "other/bluetooth_jammer" to "bluetooth",
            "other/ir_flipper_home" to "infrared",
            "other/ir_storm" to "infrared",
            "other/ir_jammer" to "infrared",
            "other/universal_remotes_home" to "infrared",
            "other/nfc_tools" to "nfc",
            "other/qr_tools" to "qr",
            "other/network_diagnostic_hub" to "network",
            "other/lan_scanner" to "network",
            "other/smarttv_cast" to "media",
            "other/device_graph" to "usb",
            "category/bluetooth" to "bluetooth",
            "category/network" to "network",
            "category/infrared" to "infrared",
            "category/nfc" to "nfc",
            "category/qr" to "qr",
            "category/usb" to "usb",
            "category/media" to "media",
            "category/other" to "other",
        )
        expected.forEach { (route, category) ->
            assertEquals("route=$route", category, FeatureTaxonomy.categoryIdForRoute(route))
        }
    }

    @Test
    fun `plugin sections and metadata are classified`() {
        assertEquals("bluetooth", FeatureTaxonomy.categoryIdForPlugin("BLUETOOTH", "x", ""))
        assertEquals("infrared", FeatureTaxonomy.categoryIdForPlugin("INFRARED", "x", ""))
        assertEquals("network", FeatureTaxonomy.categoryIdForPlugin("Wi-Fi", "x", ""))
        assertEquals("nfc", FeatureTaxonomy.categoryIdForPlugin("", "NFC Reader", ""))
        assertEquals("qr", FeatureTaxonomy.categoryIdForPlugin("", "QR generator", ""))
        assertEquals("usb", FeatureTaxonomy.categoryIdForPlugin("", "USB gadget", ""))
        assertEquals("media", FeatureTaxonomy.categoryIdForPlugin("", "DLNA cast", ""))
        assertEquals("other", FeatureTaxonomy.categoryIdForPlugin("", "Something else", ""))
    }

    @Test
    fun `bottom bar exposes exactly the four primary categories`() {
        assertEquals(listOf("bluetooth", "network", "infrared", "nfc"), FeatureTaxonomy.bottomBar)
        assertTrue(FeatureTaxonomy.isBottomBarCategory("nfc"))
    }
}
