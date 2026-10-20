package io.github.pgodlews.wormhole

import android.content.Context
import android.net.wifi.WifiManager
import java.security.SecureRandom

/**
 * Device identity for AirPlay plus the Wi-Fi multicast lock that the native
 * mDNS responder (UxPlay's embedded mdnsd) needs to receive Bonjour queries.
 */
class WormholeIdentity(context: Context) {
    private val prefs = context.getSharedPreferences("identity", Context.MODE_PRIVATE)
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    companion object {
        const val DEFAULT_SERVICE_NAME = "Second Display"

        /** Android TV / Google TV: the system UI mode or the leanback/television feature. */
        fun isTvDevice(context: Context? = null): Boolean {
            if (context == null) return false
            val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
            if (uiModeManager?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION) {
                return true
            }
            val pm = context.packageManager
            return pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
        }

        /** Auto rotation needs an accelerometer and makes no sense on a TV. */
        fun supportsAutoOrientation(context: Context? = null): Boolean {
            if (isTvDevice(context)) return false
            if (context != null) {
                val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager
                if (sensorManager?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER) == null) {
                    return false
                }
            }
            return true
        }

        fun hasOrientationSensor(context: Context): Boolean = supportsAutoOrientation(context)

        fun sanitizeServiceName(raw: String?): String {
            val trimmed = raw?.trim().orEmpty()
            val def = DEFAULT_SERVICE_NAME
            return if (trimmed.isBlank() || trimmed == def) {
                def
            } else {
                trimmed.take(60)
            }
        }
    }

    var serviceName: String
        get() = prefs.getString("service_name", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_SERVICE_NAME
        set(value) {
            val sanitized = sanitizeServiceName(value)
            if (sanitized != DEFAULT_SERVICE_NAME) {
                prefs.edit().putString("service_name", sanitized).apply()
            } else {
                prefs.edit().remove("service_name").apply()
            }
        }

    /** Stable, locally administered 6-byte identity used as AirPlay deviceid. */
    val deviceIdHex: String by lazy {
        prefs.getString("deviceid", null)?.takeIf { it.length == 12 }
            ?: ByteArray(6).also { bytes ->
                SecureRandom().nextBytes(bytes)
                bytes[0] = ((bytes[0].toInt() or 0x02) and 0xFE).toByte()
            }.joinToString("") { "%02x".format(it) }
                .also { prefs.edit().putString("deviceid", it).apply() }
    }

    @Synchronized
    fun holdMulticastLock() {
        if (multicastLock == null) {
            multicastLock = wifi.createMulticastLock("wormhole").apply {
                setReferenceCounted(true); acquire()
            }
        }
    }

    @Synchronized
    fun releaseMulticastLock() {
        runCatching { multicastLock?.release() }
        multicastLock = null
    }

    /**
     * Keeps the Wi-Fi radio out of power save while the receiver runs. Without it the radio
     * dozes between packets, so a sender sees 100 ms to 1 s round trips and delivers the mirror
     * stream in bursts. LOW_LATENCY only takes effect while the app is in the foreground with
     * the screen on, which is exactly when a mirror is being shown.
     */
    @Synchronized
    fun holdWifiLock() {
        if (wifiLock == null) {
            wifiLock = runCatching {
                wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "wormhole").apply {
                    setReferenceCounted(false); acquire()
                }
            }.getOrNull()
        }
    }

    @Synchronized
    fun releaseWifiLock() {
        runCatching { wifiLock?.release() }
        wifiLock = null
    }
}
