package com.guille.spring.iquarters.scripts

import com.guille.spring.iquarters.Behaviour
import com.guille.spring.iquarters.GuiLabel
import com.guille.spring.iquarters.GObj
import com.guille.spring.iquarters.IqStatic
import com.guille.spring.iquarters.Rect

/**
 * `InGameHiScore`: the end-of-game high-score board in `level0`. [triggerHiScores] starts it:
 * a trophy and "new high score" first if the player took the top row (`HiScoreScript.guiIndex`
 * 0), then the bars slide in, the table is drawn until Done, and the bars slide out and raise
 * [cancelHiScores]. The iPad rects are dropped.
 */
class InGameHiScore : Behaviour() {
    var newHighScoreObject: GObj? = null
    var InGameHSObject: GObj? = null
    var TrophyObject: GObj? = null
    var enableButtonView = false
    var dummySkin: String? = null
    var fontSkin: String? = null

    private val hsStateIdle = 100
    private val hsStateTrophy = 101
    private val hsStateSlideIn = 102
    private val hsStateWait = 103
    private val hsStateSlideOut = 104
    private val hsStateTurnOff = 105
    private var hsStateCurrent = 100

    override fun onBind() {
        newHighScoreObject = obj("newHighScoreObject")
        InGameHSObject = obj("InGameHSObject")
        TrophyObject = obj("TrophyObject")
        enableButtonView = bool("enableButtonView", false)
        dummySkin = skin("dummySkin")
        fontSkin = skin("fontSkin")
    }

    /**
     * Traced from the AOT ARM (`InGameHiScore.ScoresText`, 0x24af84-0x24bc2c). The locals are
     * set once in the prologue (0x24b0a8-0x24b0dc): x -130/50/100, row y 87 + 34 * i, width 160,
     * height 30 (the iPad's y base of 91 is dropped), which is why a literal scan reads the three
     * rects as computed. Per entry, under `GUI.skin = fontSkin`:
     *  - `"" + (i + 1)` at `(-130, y, 160, 30)`, `UpperRight` (0x24b658);
     *  - the name at `(50, y, 160, 30)` in the skin's own alignment (restored at 0x24b7f0), cut to
     *    its first 8 characters plus `"..."` when longer than 8 (0x24b7fc), then `+ " "`;
     *  - `score + " "` (a boxed float) at `(100, y, 160, 30)`, `UpperRight` (0x24b9bc).
     *
     * The row at `HiScoreScript.guiIndex` is drawn with the label style's `normal.textColor`
     * given r = 255, g = 255, b = 0 (yellow, alpha kept; 0x24b26c-0x24b640) and restored after
     * the row (0x24bbbc).
     */
    fun ScoresText() {
        val guiSkin = fontSkin ?: "fontSkin"
        val scores = HiScoreScript.GetArray()
        for (i in scores.indices) {
            val entry = scores[i]
            val y = 87f + i * 34f
            val c = if (i == HiScoreScript.guiIndex) GuiLabel.YELLOW else null
            guiLabel(Rect(-130f, y, 160f, 30f), (i + 1).toString(), guiSkin, right = true, color = c)
            val name = if (entry.name.length > 8) entry.name.substring(0, 8) + "..." else entry.name
            guiLabel(Rect(50f, y, 160f, 30f), "$name ", guiSkin, color = c)
            guiLabel(Rect(100f, y, 160f, 30f), fmtScore(entry.score) + " ", guiSkin, right = true, color = c)
        }
    }

    /** `float.ToString()` for the scores this game keeps, which are whole. */
    private fun fmtScore(v: Float): String = if (v == Math.floor(v.toDouble()).toFloat()) v.toInt().toString() else v.toString()

    /**
     * Traced from the AOT ARM (`InGameHiScore.OnGUI`, 0x24bc2c-0x24be54): in `hsStateWait`,
     * [ScoresText], `GUI.skin = dummySkin` unless [enableButtonView], and an invisible Done
     * button at `Rect(0, 430, 160, 40)` (0x24bd44). The iPad `GetiPadRect` branch is dropped.
     */
    override fun onGUI() {
        if (hsStateCurrent != hsStateWait) return
        ScoresText()
        val doneRect = Rect(0f, 430f, 160f, 40f)
        if (guiButton(doneRect)) {
            AnnouncerScript.triggerClickSound = true
            animation!!.play("doneclick")
            animation!!.playQueued("barsout")
            hsStateCurrent = hsStateSlideOut
        }
    }

    override fun update() {
        if (triggerHiScores) {
            triggerHiScores = false
            if (HiScoreScript.guiIndex == 0) {
                TrophyObject!!.setActiveRecursively(true)
                TrophyObject!!.anim!!.play()
                newHighScoreObject!!.setActiveRecursively(true)
                newHighScoreObject!!.anim!!.play()
                hsStateCurrent = hsStateTrophy
            } else {
                animation!!.play("barsin")
                hsStateCurrent = hsStateSlideIn
            }
        }
        if (hsStateCurrent == hsStateTrophy && !TrophyObject!!.anim!!.isPlaying && !newHighScoreObject!!.anim!!.isPlaying) {
            TrophyObject!!.setActiveRecursively(false)
            newHighScoreObject!!.setActiveRecursively(false)
            animation!!.play("barsin")
            hsStateCurrent = hsStateSlideIn
        } else if (hsStateCurrent == hsStateSlideIn && !animation!!.isPlaying) {
            hsStateCurrent = hsStateWait
        } else if (hsStateCurrent == hsStateSlideOut && !animation!!.isPlaying) {
            cancelHiScores = true
            hsStateCurrent = hsStateTurnOff
        } else if (hsStateCurrent == hsStateTurnOff) {
            InGameHSObject!!.setActiveRecursively(false)
            hsStateCurrent = hsStateIdle
        }
    }

    companion object : IqStatic {
        @JvmField var triggerHiScores = false
        @JvmField var cancelHiScores = false

        override fun reset() {
            triggerHiScores = false
            cancelHiScores = false
        }
    }
}
