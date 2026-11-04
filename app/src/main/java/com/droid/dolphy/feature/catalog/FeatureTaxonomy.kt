package com.droid.dolphy.feature.catalog

/**
 * Pure classification rules for every built-in feature and plugin card.
 * Kept free of Android/Compose types so it can be unit-tested on the JVM.
 */
object FeatureTaxonomy {
    const val BLUETOOTH = "bluetooth"
    const val NETWORK = "network"
    const val INFRARED = "infrared"
    const val NFC = "nfc"
    const val QR = "qr"
    const val USB = "usb"
    const val MEDIA = "media"
    const val OTHER = "other"

    val all: List<String> = listOf(BLUETOOTH, NETWORK, INFRARED, NFC, QR, USB, MEDIA, OTHER)
    val bottomBar: List<String> = listOf(BLUETOOTH, NETWORK, INFRARED, NFC)

    private val routeExactCategories = mapOf("hid" to BLUETOOTH)
    private val routePrefixCategories = listOf(
        "ble_section/" to BLUETOOTH,
        "ble_spam" to BLUETOOTH,
        "bond_spam" to BLUETOOTH,
        "audio_scanner" to BLUETOOTH,
        "scooter_hack" to BLUETOOTH,
        "nrf_scanner" to BLUETOOTH,
        "dolphy_chat" to BLUETOOTH,
        "bluetooth_jammer" to BLUETOOTH,
        "bluetooth_whisperpair" to BLUETOOTH,
        "bt_audio_stress" to BLUETOOTH,
        "ir_" to INFRARED,
        "universal_remote" to INFRARED,
        "flipper_" to INFRARED,
        "tv_" to INFRARED,
        "nfc_" to NFC,
        "qr_" to QR,
        "network_" to NETWORK,
        "lan_" to NETWORK,
        "wifi_" to NETWORK,
        "smarttv_cast" to MEDIA,
        "device_graph" to USB,
    )
    private val pluginSectionCategories = mapOf(
        "infrared" to INFRARED,
        "ir" to INFRARED,
        "инфракрас" to INFRARED,
        "инфракрасный" to INFRARED,
        "инфракрасные" to INFRARED,
        "bluetooth" to BLUETOOTH,
        "bt" to BLUETOOTH,
        "ble" to BLUETOOTH,
        "блютуз" to BLUETOOTH,
        "bluetooths" to BLUETOOTH,
        "wifi" to NETWORK,
        "wi-fi" to NETWORK,
        "network" to NETWORK,
        "сеть" to NETWORK,
        "lan" to NETWORK,
        "nfc" to NFC,
        "qr" to QR,
        "usb" to USB,
        "hardware" to USB,
        "flipper" to USB,
        "media" to MEDIA,
        "cast" to MEDIA,
        "tv" to MEDIA,
        "system" to OTHER,
        "settings" to OTHER,
        "tools" to OTHER,
    )
    private val pluginTextMarkers = listOf(
        "qr" to QR,
        "nfc" to NFC,
        "infrared" to INFRARED,
        "ir " to INFRARED,
        "remote" to INFRARED,
        "bluetooth" to BLUETOOTH,
        "ble" to BLUETOOTH,
        " bt" to BLUETOOTH,
        "wifi" to NETWORK,
        "wi-fi" to NETWORK,
        "network" to NETWORK,
        "lan" to NETWORK,
        "router" to NETWORK,
        "usb" to USB,
        "serial" to USB,
        "cast" to MEDIA,
        "dlna" to MEDIA,
        "media" to MEDIA,
    )

    fun categoryIdForRoute(route: String): String? {
        val r = route.removePrefix("other/").removePrefix("category/")
        if (r in all) return r
        return routeExactCategories[r]
            ?: routePrefixCategories.firstOrNull { (prefix, _) -> r.startsWith(prefix) }?.second
    }

    fun categoryIdForPlugin(section: String?, title: String, description: String): String {
        val normalized = section?.trim()?.lowercase().orEmpty()
        pluginSectionCategories[normalized]?.let { return it }
        val text = "$title $description".lowercase()
        return pluginTextMarkers.firstOrNull { (marker, _) -> text.contains(marker) }?.second
            ?: OTHER
    }

    fun isBottomBarCategory(id: String?): Boolean = id in bottomBar
}
