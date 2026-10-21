package io.github.swiderekartmax.seconddisplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinchZoomTest {
    private fun PinchZoom.step(cx: Float, cy: Float, panX: Float = 0f, panY: Float = 0f, zoom: Float = 1f) =
        transform(cx, cy, panX, panY, zoom, 2000f, 1200f, 2000f, 1200f)

    @Test fun `zoom around the centre keeps the picture centred`() {
        val z = PinchZoom().step(0f, 0f, zoom = 2f)
        assertEquals(PinchZoom(2f, 0f, 0f), z)
    }

    @Test fun `zoom keeps the point under the fingers in place`() {
        // A point 400 px right of centre stays at 400 px after zooming 2x around it.
        val z = PinchZoom().step(400f, 0f, zoom = 2f)
        assertEquals(2f, z.scale)
        assertEquals(-400f, z.offsetX)
        val picturePoint = (400f - z.offsetX) / z.scale
        assertEquals(400f, picturePoint)
    }

    @Test fun `scale stays between 1x and 5x`() {
        assertEquals(5f, PinchZoom().step(0f, 0f, zoom = 20f).scale)
        assertEquals(1f, PinchZoom(2f).step(0f, 0f, zoom = 0.1f).scale)
    }

    @Test fun `pan cannot reveal space beyond the picture edges`() {
        // At 2x a 2000x1200 picture is 4000x2400: it may move at most 1000 px sideways and 600 px vertically.
        val z = PinchZoom(2f).step(0f, 0f, panX = 5000f, panY = -5000f)
        assertEquals(1000f, z.offsetX)
        assertEquals(-600f, z.offsetY)
    }

    @Test fun `pan does nothing when not zoomed`() {
        val z = PinchZoom().step(0f, 0f, panX = 300f, panY = 300f)
        assertEquals(PinchZoom(), z)
        assertFalse(z.isZoomed)
    }

    @Test fun `zooming out recentres the picture`() {
        val z = PinchZoom(2f, 1000f, 600f).step(0f, 0f, zoom = 0.5f)
        assertEquals(PinchZoom(1f, 0f, 0f), z)
    }

    @Test fun `letterboxed axis stays centred until the picture fills it`() {
        // A 16:9 picture (2000x1125) in a 2000x1200 container: at 1.05x it is 1181 px tall,
        // still shorter than the container, so it cannot move vertically.
        val z = PinchZoom().transform(0f, 0f, 0f, 200f, 1.05f, 2000f, 1125f, 2000f, 1200f)
        assertEquals(0f, z.offsetY)
        assertTrue(z.isZoomed)
    }

    @Test fun `fit matches the aspect ratio inside the container`() {
        assertEquals(Pair(2000, 1125), PinchZoom.fit(16f / 9f, 2000, 1200))
        assertEquals(Pair(1600, 1200), PinchZoom.fit(4f / 3f, 2000, 1200))
        assertEquals(Pair(2000, 1200), PinchZoom.fit(null, 2000, 1200))
    }
}
