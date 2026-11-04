package com.droid.dolphy.feature.catalog

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.ui.graphics.vector.ImageVector
import com.droid.dolphy.R

enum class FeatureCategory(
    val id: String,
    @StringRes val titleRes: Int,
    val icon: ImageVector,
) {
    BLUETOOTH(FeatureTaxonomy.BLUETOOTH, R.string.cat_bluetooth, Icons.Default.Bluetooth),
    NETWORK(FeatureTaxonomy.NETWORK, R.string.cat_network, Icons.Default.Wifi),
    INFRARED(FeatureTaxonomy.INFRARED, R.string.cat_infrared, Icons.Default.SettingsRemote),
    NFC(FeatureTaxonomy.NFC, R.string.cat_nfc, Icons.Outlined.Nfc),
    QR(FeatureTaxonomy.QR, R.string.cat_qr, Icons.Default.QrCodeScanner),
    USB(FeatureTaxonomy.USB, R.string.cat_usb, Icons.Default.Usb),
    MEDIA(FeatureTaxonomy.MEDIA, R.string.cat_media, Icons.Default.Cast),
    OTHER(FeatureTaxonomy.OTHER, R.string.cat_other, Icons.Default.Extension);

    val route: String
        get() = if (this == BLUETOOTH) "bluetooth" else "category/$id"

    companion object {
        fun fromId(id: String?): FeatureCategory? = entries.firstOrNull { it.id == id }

        val bottomBar: List<FeatureCategory>
            get() = entries.filter { it.id in FeatureTaxonomy.bottomBar }
    }
}
