package io.github.swiderekartmax.seconddisplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkInfoHelperTest {
    @Test fun `soft AP interface names count as the hotspot`() {
        listOf("ap0", "swlan0", "softap0").forEach { assertTrue(it, NetworkInfoHelper.isHotspotInterface(it)) }
    }

    @Test fun `Wi-Fi, USB, cellular and VPN interfaces do not`() {
        listOf("wlan0", "rndis0", "ncm0", "rmnet_data0", "tun0", "eth0").forEach {
            assertFalse(it, NetworkInfoHelper.isHotspotInterface(it))
        }
    }
}
