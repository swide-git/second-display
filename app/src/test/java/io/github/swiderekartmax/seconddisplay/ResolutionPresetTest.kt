package io.github.swiderekartmax.seconddisplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ResolutionPresetTest {
    @Test fun `native keeps the exact panel size`() {
        assertEquals(Pair(2000, 1200), ResolutionPreset.NATIVE.size(2000, 1200))
        assertEquals(Pair(1281, 801), ResolutionPreset.NATIVE.size(1281, 801))
    }

    @Test fun `fixed presets follow the panel orientation`() {
        assertEquals(Pair(1920, 1080), ResolutionPreset.FHD.size(2000, 1200))
        assertEquals(Pair(1080, 1920), ResolutionPreset.FHD.size(1200, 2000))
    }

    @Test fun `percent presets scale and round down to multiples of 8`() {
        assertEquals(Pair(1600, 960), ResolutionPreset.P80.size(2000, 1200))
        assertEquals(Pair(1280, 768), ResolutionPreset.P64.size(2000, 1200))
        assertEquals(Pair(1000, 600), ResolutionPreset.P50.size(2000, 1200))
        assertEquals(Pair(1024, 640), ResolutionPreset.P80.size(1280, 800))
    }

    @Test fun `labels use the advertised orientation`() {
        assertEquals("1920 × 1080 (16:9)", ResolutionPreset.FHD.label(2000, 1200, portrait = false))
        assertEquals("1080 × 1920 (16:9)", ResolutionPreset.FHD.label(2000, 1200, portrait = true))
    }

    @Test fun `available lists native first then by pixel count`() {
        assertEquals(
            listOf(
                ResolutionPreset.NATIVE, ResolutionPreset.WUXGA, ResolutionPreset.FHD, ResolutionPreset.WSXGA_PLUS,
                ResolutionPreset.P80, ResolutionPreset.HD_PLUS, ResolutionPreset.WXGA_PLUS, ResolutionPreset.P64,
                ResolutionPreset.HD, ResolutionPreset.P50
            ),
            ResolutionPreset.available(2000, 1200)
        )
    }

    @Test fun `presets larger than the panel are not offered`() {
        val panel1280x800 = ResolutionPreset.available(1280, 800)
        assertFalse(ResolutionPreset.FHD in panel1280x800)
        assertFalse(ResolutionPreset.WXGA_PLUS in panel1280x800)
        // 1280x720 (921k px) outranks 80% = 1024x640 (655k px).
        assertEquals(listOf(ResolutionPreset.NATIVE, ResolutionPreset.HD, ResolutionPreset.P80, ResolutionPreset.P64, ResolutionPreset.P50), panel1280x800)
    }

    @Test fun `unknown or missing id falls back to native`() {
        assertEquals(ResolutionPreset.NATIVE, ResolutionPreset.fromId(null))
        assertEquals(ResolutionPreset.NATIVE, ResolutionPreset.fromId("4k"))
        assertEquals(ResolutionPreset.FHD, ResolutionPreset.fromId("1080p"))
    }
}
