package com.guille.spring.render

/**
 * Upscale arithmetic for the offscreen framebuffer.
 *
 * Pure math, unit-testable without Android.
 */
object ScalingMath {

    data class Viewport(
        /** Physical pixels per framebuffer pixel. Whole unless [integer] is false. */
        val scale: Float,
        val offsetX: Int,
        val offsetY: Int,
        /** The panel's physical size: the framebuffer times [scale], always whole. */
        val width: Int,
        val height: Int,
    ) {
        /** True when every framebuffer pixel is a whole block, so a nearest blit is exact. */
        val integer: Boolean get() = scale == Math.floor(scale.toDouble()).toFloat()
    }

    /**
     * Compute the upscale factor and letterbox offsets.
     *
     * The factor is chosen in **points**, not framebuffer pixels: both stages
     * are a 320x480-point screen, and a @2x framebuffer ([pixelsPerPoint] 2)
     * is the same screen at twice the density. So
     *
     *   pointScale = minOf(physicalWidth / pointsWide, physicalHeight / pointsHigh)
     *   scale      = pointScale / pixelsPerPoint
     *
     * and both stages come out the same physical size on any display. For
     * 1.1.1 that is the old integer rule unchanged. For iOS 4 it is the old
     * rule whenever pointScale is even (the 720-wide test phone: 2 / 2 = 1,
     * byte-exact); on an odd pointScale (a 1080-wide phone: 3 / 2 = 1.5) the
     * framebuffer cannot be blown up in whole blocks, and the integer rule
     * would drop it to 1x, 640px in a 1080px screen. Such a viewport says so
     * through [Viewport.integer], and the view filters the blit.
     *
     * @throws IllegalArgumentException if the physical screen is smaller than the
     * virtual canvas in either axis — we never silently clamp to scale 1.
     */
    fun compute(
        physicalWidth: Int,
        physicalHeight: Int,
        virtualWidth: Int,
        virtualHeight: Int,
        pixelsPerPoint: Int = 1,
    ): Viewport {
        require(physicalWidth > 0 && physicalHeight > 0) { "Physical size must be positive" }
        require(virtualWidth > 0 && virtualHeight > 0) { "Virtual size must be positive" }
        require(pixelsPerPoint > 0 && virtualWidth % pixelsPerPoint == 0 && virtualHeight % pixelsPerPoint == 0) {
            "Virtual size ${virtualWidth}x$virtualHeight is not a whole number of points at $pixelsPerPoint px/pt"
        }

        val pointsW = virtualWidth / pixelsPerPoint
        val pointsH = virtualHeight / pixelsPerPoint
        val pointScale = minOf(physicalWidth / pointsW, physicalHeight / pointsH)
        require(pointScale >= pixelsPerPoint) {
            "Physical screen smaller than virtual canvas at this display mode: " +
                "${physicalWidth}x${physicalHeight} <= ${virtualWidth}x${virtualHeight}"
        }

        val w = pointsW * pointScale
        val h = pointsH * pointScale
        return Viewport(
            scale = pointScale.toFloat() / pixelsPerPoint,
            offsetX = (physicalWidth - w) / 2,
            offsetY = (physicalHeight - h) / 2,
            width = w,
            height = h,
        )
    }

    /** Invert a physical (raw view) coordinate back to virtual canvas space. */
    fun toVirtual(view: Viewport, physicalX: Float, physicalY: Float): Pair<Float, Float> =
        ((physicalX - view.offsetX) / view.scale) to ((physicalY - view.offsetY) / view.scale)

    /** The physical rect (inset by the letterbox) that the full virtual canvas maps onto. */
    fun physicalRect(view: Viewport): IntArray =
        intArrayOf(view.offsetX, view.offsetY, view.offsetX + view.width, view.offsetY + view.height)
}
