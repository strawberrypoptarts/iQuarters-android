package com.guille.spring.iquarters

/** Native-resolution scene; uniform, centered original UI with matching hit coordinates. */
class IqViewport(val width: Int, val height: Int) {
    val scale = minOf(width / 320f, height / 480f).coerceAtLeast(.001f)
    val uiWidth = (320 * scale).toInt()
    val uiHeight = (480 * scale).toInt()
    val left = (width - uiWidth) / 2
    val bottom = (height - uiHeight) / 2
    fun touch(x: Float, y: Float) = V2((x-left)/scale, 480f-(y-bottom)/scale)
    val extraX get() = (width / scale - 320f) / 2f
    val extraY get() = (height / scale - 480f) / 2f
    val verticalExpansion get() = maxOf(1f, (2f/3f)*height/width.coerceAtLeast(1))
}
