package io.github.swiderekartmax.seconddisplay

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetworkInfoHelper {
    data class NetworkDetails(
        val ipAddress: String,
        val wifiSsid: String,
        val isConnected: Boolean,
        /** This device's own hotspot address when the hotspot is on; senders can join it. */
        val hotspotAddress: String? = null
    )

    /** Soft AP interface names: Xiaomi and AOSP "ap0", Samsung "swlan0", some vendors "softap0". */
    private val HOTSPOT_PREFIXES = listOf("ap", "swlan", "softap")

    fun getNetworkDetails(context: Context): NetworkDetails {
        val ip = getLocalIpAddress()
        val hotspot = getHotspotAddress()
        val (ssid, _) = getWifiInfo(context)
        val onlyHotspot = hotspot != null && ip == hotspot
        return NetworkDetails(
            ipAddress = ip ?: "Not connected",
            wifiSsid = if (onlyHotspot) "No Wi-Fi" else ssid,
            isConnected = ip != null,
            hotspotAddress = hotspot
        )
    }

    fun isHotspotInterface(name: String): Boolean = HOTSPOT_PREFIXES.any { name.startsWith(it) }

    fun getHotspotAddress(): String? = runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .filter { intf -> intf.isUp && isHotspotInterface(intf.name) }
            .flatMap { Collections.list(it.inetAddresses) }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()

    fun getLocalIpAddress(): String? {
        return try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            val sorted = interfaces.sortedByDescending {
                it.name.startsWith("wlan") || it.name.startsWith("eth")
            }
            for (intf in sorted) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in Collections.list(intf.inetAddresses)) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress
                        if (!host.isNullOrEmpty() && host != "127.0.0.1") {
                            return host
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun getWifiInfo(context: Context): Pair<String, Boolean> {
        return try {
            // WifiManager.connectionInfo is deprecated; the active network's transport info
            // carries the same WifiInfo from Android 10 (the SSID is redacted without location
            // permission). Android 9 only has the old call.
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                caps?.transportInfo as? WifiInfo
            } else {
                @Suppress("DEPRECATION")
                context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo
            }
            val ssid = info?.ssid?.removeSurrounding("\"")
            if (ssid != null && ssid != WifiManager.UNKNOWN_SSID && ssid.isNotBlank()) {
                Pair(ssid, true)
            } else if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true || getLocalIpAddress() != null) {
                Pair("Wi-Fi Connected", true)
            } else {
                Pair("Disconnected", false)
            }
        } catch (e: Exception) {
            Pair("Wi-Fi", false)
        }
    }
}
