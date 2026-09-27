package com.guille.spring.iquarters.scripts

import com.guille.spring.iquarters.Behaviour
import com.guille.spring.iquarters.GObj
import com.guille.spring.iquarters.IqRuntime
import com.guille.spring.iquarters.IqStatic
import com.guille.spring.iquarters.Rect

/**
 * `StatsScreen`: the end-of-game stats board. [triggerStatscreen] slides in the variant for
 * the player count (`ui_in_1`..`_4`) and shows the finger markers; while it waits, each
 * player's score, best streak, best ricochet and shots left are drawn in a column; done slides
 * it out, then "Loading..." is drawn and [cancelStatscreen] raised. The iPad rects are dropped.
 */
class StatsScreen : Behaviour() {
    var StatsScreenObject: GObj? = null
    var enableButtonView = false
    var dummySkin: String? = null
    var fontSkin: String? = null
    var fontSkin_iPad: String? = null

    private val ssStateIdle = 100
    private val ssStateSlideIn = 101
    private val ssStateWait = 102
    private val ssStateSlideOut = 103
    private var ssStateCurrent = 100
    private var displayLoadingFlag = false
    private var slidingOutTimer = 0f

    override fun onBind() {
        StatsScreenObject = obj("StatsScreenObject")
        enableButtonView = bool("enableButtonView", false)
        dummySkin = skin("dummySkin")
        fontSkin = skin("fontSkin")
        fontSkin_iPad = skin("fontSkin_iPad")
    }

    override fun start() {
        cancelStatscreen = false
    }

    /**
     * Traced from the AOT ARM (`StatsScreen.StatsText`, 0x262a80-0x2633fc). Under
     * `GUI.skin = fontSkin`, the label style is set to `UpperRight` (0x262b30) and restored to
     * its own alignment after the loop (0x2633d8). The column x starts at 132 and is pulled
     * left by 32 per extra player (0x262d20-0x262df0; only for 1..4 players, and the iPad's
     * 127/57/30 variant is dropped); the pitch is 64. Each column is four `"" + value` labels,
     * 80x30, at y 115, 149, 183, 217 (115 + 34 * row, precomputed at 0x262b4c-0x262bb0): score,
     * best streak, best ricochet, shots left. Every value is a boxed int.
     */
    fun StatsText() {
        val guiSkin = fontSkin ?: "fontSkin"
        val tot = GameManagerScript.totPlayers
        var startX = 132f
        val spacing = 64f
        if (tot in 1..4) startX -= 32f * (tot - 1)
        for (i in 0 until tot) {
            val x = startX + i * spacing
            val values = arrayOf(
                GameManagerScript.GetPlayerScore(i).toString(),
                GameManagerScript.GetPlayerMaxStreak(i).toString(),
                GameManagerScript.GetPlayerMaxRicochet(i).toString(),
                GameManagerScript.GetPlayerShotsLeft(i).toString(),
            )
            for (row in values.indices) {
                guiLabel(Rect(x, 115f + row * 34f, 80f, 30f), values[row], guiSkin, right = true)
            }
        }
    }

    private fun suffix(): String {
        val tot = GameManagerScript.totPlayers
        return if (tot in 1..4) tot.toString() else "1"
    }

    /**
     * Traced from the AOT ARM (`StatsScreen.OnGUI`, 0x2633fc-0x2638b4). While
     * [displayLoadingFlag], `GUI.skin = fontSkin` and "Loading..." at
     * `Rect(6, Screen.height - 32, 120, 32)` in the skin's own alignment (0x26344c). Then, in
     * `ssStateWait`, [StatsText] and an invisible Done button at `Rect(0, 430, 160, 40)`
     * (0x263654) under `dummySkin` unless [enableButtonView]. Done sets
     * `slidingOutTimer = time + 0.4` (the float literal at 0x263860; the port had 0.25). The
     * iPad `GetiPadRect` branches are dropped.
     */
    override fun onGUI() {
        if (displayLoadingFlag) {
            guiLabel(Rect(6f, IqRuntime.SCREEN_H - 32f, 120f, 32f), "Loading...", fontSkin ?: "fontSkin")
        }
        if (ssStateCurrent != ssStateWait) return
        StatsText()
        if (guiButton(Rect(0f, 430f, 160f, 40f))) {
            val s = suffix()
            animation!!.play("done_click_$s")
            animation!!.playQueued("ui_out_$s")
            slidingOutTimer = time + 0.4f
            AnnouncerScript.triggerClickSound = true
            ssStateCurrent = ssStateSlideOut
        }
    }

    fun DisplayFingers(flag: Boolean) {
        val names = arrayOf("stats_score_", "stats_streak_", "stats_richochet_", "stats_coins_left_")
        for (name in names) {
            for (player in 'a'..'d') {
                find("/ui_stats/$name$player")?.let { it.renderer!!.enabled = flag }
            }
        }
    }

    override fun update() {
        if (triggerStatscreen) {
            DisplayFingers(true)
            animation!!.play("ui_in_" + suffix())
            triggerStatscreen = false
            ssStateCurrent = ssStateSlideIn
        }
        if (ssStateCurrent == ssStateSlideIn && !animation!!.isPlaying) {
            ssStateCurrent = ssStateWait
        } else if (ssStateCurrent == ssStateSlideOut) {
            if (time > slidingOutTimer) DisplayFingers(false)
            if (!animation!!.isPlaying) {
                displayLoadingFlag = true
                cancelStatscreen = true
                ssStateCurrent = ssStateIdle
            }
        }
    }

    companion object : IqStatic {
        @JvmField var triggerStatscreen = false
        @JvmField var cancelStatscreen = false

        override fun reset() {
            triggerStatscreen = false
            cancelStatscreen = false
        }
    }
}
