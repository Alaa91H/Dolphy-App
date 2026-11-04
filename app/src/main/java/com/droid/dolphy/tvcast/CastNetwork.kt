@file:Suppress("DEPRECATION")

package com.droid.dolphy.tvcast

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

/**
 * Selects the address the television should use to reach Dolphy.
 *
 * Android can keep a VPN, mobile data and Wi-Fi active at the same time. Taking
 * the first non-loopback interface (the old behaviour) could hand the TV a
 * cellular or VPN address, so prefer an actual Wi-Fi/Ethernet link and only
 * fall back to a private IPv4 interface when ConnectivityManager has no answer.
 */
object CastNetwork {

    fun localIpv4(context: Context): String? {
        val manager = context.applicationContext
            .getSystemService(ConnectivityManager::class.java)
            ?: return fallbackLanAddress()

        val candidates = manager.allNetworks
            .mapNotNull { network ->
                val capabilities = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
                val priority = when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 0
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 1
                    else -> return@mapNotNull null
                }
                val link = manager.getLinkProperties(network) ?: return@mapNotNull null
                val address = link.linkAddresses
                    .asSequence()
                    .map { it.address }
                    .firstOrNull(::isLanIpv4)
                    ?: return@mapNotNull null
                priority to address.hostAddress
            }
            .sortedBy { it.first }
            .mapNotNull { it.second }

        return candidates.firstOrNull() ?: fallbackLanAddress()
    }

    internal fun isLanIpv4(address: InetAddress): Boolean {
        if (address !is Inet4Address) return false
        if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isMulticastAddress) {
            return false
        }
        return address.isSiteLocalAddress
    }

    fun formatHost(host: String): String {
        val clean = host.trim().trim('[', ']')
        return if (clean.contains(':')) "[$clean]" else clean
    }

    fun origin(host: String, port: Int): String =
        "http://${formatHost(host)}:$port"

    private fun fallbackLanAddress(): String? {
        return runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .asSequence()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .filterNot { it.name.startsWith("rmnet", ignoreCase = true) }
                .filterNot { it.name.startsWith("tun", ignoreCase = true) }
                .filterNot { it.name.startsWith("tap", ignoreCase = true) }
                .flatMap { Collections.list(it.inetAddresses).asSequence() }
                .filter(::isLanIpv4)
                .mapNotNull { it.hostAddress }
                .firstOrNull()
        }.getOrNull()
    }
}
