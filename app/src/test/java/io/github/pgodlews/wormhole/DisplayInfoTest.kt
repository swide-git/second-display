package io.github.pgodlews.wormhole

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayInfoTest {

    @Test
    fun `computeDisplayInfo landscape 1080p calculates 16-9 mode`() {
        val info = computeDisplayInfo(1920, 1080, 60, isPortrait = false)
        assertEquals(1920, info.width)
        assertEquals(1080, info.height)
        assertEquals(60, info.refreshRate)
        assertEquals("16:9", info.aspectRatioLabel)
        assertTrue(info.recommendedResolutions.contains("1920 × 1080 (Default)"))
        assertTrue(info.recommendedResolutions.contains("1280 × 720"))
    }

    @Test
    fun `computeDisplayInfo portrait 1080p calculates 9-16 mode`() {
        val info = computeDisplayInfo(1920, 1080, 60, isPortrait = true)
        assertEquals(1080, info.width)
        assertEquals(1920, info.height)
        assertEquals(60, info.refreshRate)
        assertEquals("9:16", info.aspectRatioLabel)
        assertTrue(info.recommendedResolutions.contains("1080 × 1920 (Default)"))
        assertTrue(info.recommendedResolutions.contains("720 × 1280"))
    }

    @Test
    fun `computeDisplayInfo landscape 800x1280 calculates 16-10 mode`() {
        val info = computeDisplayInfo(800, 1280, 60, isPortrait = false)
        assertEquals(1280, info.width)
        assertEquals(800, info.height)
        assertEquals(60, info.refreshRate)
        assertEquals("16:10", info.aspectRatioLabel)
        assertTrue(info.recommendedResolutions.contains("1280 × 800 (Default)"))
    }

    @Test
    fun `computeDisplayInfo portrait 800x1280 calculates 10-16 mode`() {
        val info = computeDisplayInfo(800, 1280, 60, isPortrait = true)
        assertEquals(800, info.width)
        assertEquals(1280, info.height)
        assertEquals(60, info.refreshRate)
        assertEquals("10:16", info.aspectRatioLabel)
        assertTrue(info.recommendedResolutions.contains("800 × 1280 (Default)"))
    }

    @Test
    fun `ScreenOrientation fromId parses correctly`() {
        assertEquals(ScreenOrientation.LANDSCAPE, ScreenOrientation.fromId("landscape"))
        assertEquals(ScreenOrientation.PORTRAIT, ScreenOrientation.fromId("portrait"))
        assertEquals(ScreenOrientation.AUTO, ScreenOrientation.fromId("auto"))
        assertEquals(ScreenOrientation.LANDSCAPE, ScreenOrientation.fromId("unknown"))
        assertEquals(ScreenOrientation.LANDSCAPE, ScreenOrientation.fromId(null))
    }

    @Test
    fun `device model names no longer drive TV or orientation detection`() {
        // Without a Context there is no UI mode, feature or sensor to inspect: a phone or
        // tablet, whatever its model string, is not a TV and keeps Auto rotation.
        org.junit.Assert.assertFalse(WormholeIdentity.isTvDevice(null))
        assertTrue(WormholeIdentity.supportsAutoOrientation(null))
    }

    @Test
    fun `default service name is the same on every device`() {
        assertEquals("Wormhole Display", WormholeIdentity.DEFAULT_SERVICE_NAME)
        assertEquals("Wormhole Display", WormholeIdentity.sanitizeServiceName("  "))
        assertEquals("Living Room", WormholeIdentity.sanitizeServiceName(" Living Room "))
    }
}
