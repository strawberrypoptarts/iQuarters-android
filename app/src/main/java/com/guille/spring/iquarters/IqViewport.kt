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
    // Off-screen animation poses must stay outside their original stage even when
    // the scene camera expands to fill a modern display. HUD stages move with anchors.
    enum class Anchor(val x: Int, val y: Int) {
        CENTER(0, 0), TOP_LEFT(-1, 1), TOP_RIGHT(1, 1),
        BOTTOM_RIGHT(1, -1), TOP(0, 1), BOTTOM(0, -1)
    }
    data class Clip(val left: Int, val bottom: Int, val width: Int, val height: Int)
    private val clips = Anchor.values().associateWith {
        Clip(left + it.x * left, bottom + it.y * bottom, uiWidth, uiHeight)
    }
    fun clip(anchor: Anchor = Anchor.CENTER): Clip = clips.getValue(anchor)
    companion object {
        fun anchor(name: String): Anchor? = when (name) {
            "ui_ingame_3coin_hold", "ui_ingame_3coin_root" -> Anchor.TOP_LEFT
            "ui_ingame_angle_root", "ex_round_mon" -> Anchor.TOP_RIGHT
            "UI_pause" -> Anchor.BOTTOM_RIGHT
            "UI_ingame_player", "exciter_in_a_row" -> Anchor.TOP
            "RicochetParent", "ui_richochet_score" -> Anchor.BOTTOM
            else -> null
        }
        fun fullScreenBackground(name: String) = name == "BackDrop" || name == "backdrop"
    }
    val verticalExpansion get() = maxOf(1f, (2f/3f)*height/width.coerceAtLeast(1))
}
