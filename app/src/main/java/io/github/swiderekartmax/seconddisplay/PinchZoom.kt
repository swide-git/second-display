package io.github.swiderekartmax.seconddisplay

/**
 * Pinch-to-zoom state for the mirrored picture. Purely local to the receiver: the sender keeps
 * streaming the full frame and is never told about the zoom.
 *
 * [offsetX] / [offsetY] move the centre of the scaled picture away from the centre of the
 * container, in pixels. All coordinates passed in are relative to the container's centre.
 */
data class PinchZoom(val scale: Float = 1f, val offsetX: Float = 0f, val offsetY: Float = 0f) {
    val isZoomed: Boolean get() = scale > 1f

    /**
     * Applies one step of a transform gesture: zooms by [zoom] around [centroidX]/[centroidY],
     * then pans by [panX]/[panY]. [contentWidth]/[contentHeight] is the unzoomed picture size;
     * the result keeps the picture covering the container wherever it is larger than it.
     */
    fun transform(
        centroidX: Float,
        centroidY: Float,
        panX: Float,
        panY: Float,
        zoom: Float,
        contentWidth: Float,
        contentHeight: Float,
        containerWidth: Float,
        containerHeight: Float
    ): PinchZoom {
        val newScale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
        val factor = newScale / scale
        // Keep the picture point under the fingers in place while the scale changes.
        val x = centroidX - (centroidX - offsetX) * factor + panX
        val y = centroidY - (centroidY - offsetY) * factor + panY
        return PinchZoom(newScale, x, y).clamped(contentWidth, contentHeight, containerWidth, containerHeight)
    }

    fun clamped(contentWidth: Float, contentHeight: Float, containerWidth: Float, containerHeight: Float): PinchZoom {
        val maxX = ((contentWidth * scale - containerWidth) / 2f).coerceAtLeast(0f)
        val maxY = ((contentHeight * scale - containerHeight) / 2f).coerceAtLeast(0f)
        return copy(offsetX = offsetX.coerceIn(-maxX, maxX), offsetY = offsetY.coerceIn(-maxY, maxY))
    }

    companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 5f

        /**
         * Size of the unzoomed picture: [aspectRatio] (width / height) fitted inside the
         * container, or the whole container when the ratio is unknown.
         */
        fun fit(aspectRatio: Float?, containerWidth: Int, containerHeight: Int): Pair<Int, Int> {
            if (aspectRatio == null || aspectRatio <= 0f || containerHeight <= 0) {
                return Pair(containerWidth, containerHeight)
            }
            return if (containerWidth.toFloat() / containerHeight > aspectRatio) {
                Pair((containerHeight * aspectRatio).toInt(), containerHeight)
            } else {
                Pair(containerWidth, (containerWidth / aspectRatio).toInt())
            }
        }
    }
}
