package com.guille.spring.iquarters.scripts

import com.guille.spring.iquarters.Behaviour
import com.guille.spring.iquarters.GObj
import com.guille.spring.iquarters.Rend
import com.guille.spring.iquarters.Rect

/**
 * `AngleAdjustScript`: while the angle is being set, shows the angle gauge and stretches
 * [adjustObject] between its min and max scale by where `shotAngle` sits in its range. The
 * debug up/down buttons exist only with [displayButtons].
 *
 * The iPad rect branch is dropped.
 */
class AngleAdjustScript : Behaviour() {
    var displayButtons = false
    var adjustObject: GObj? = null
    var adjustRenderer: Rend? = null
    var minScaleY = 10f
    var maxScaleY = -10f
    var minScaleZ = -10f
    var maxScaleZ = 10f

    private var InitialScaleY = 0f
    private var InitialScaleZ = 0f

    override fun onBind() {
        displayButtons = bool("displayButtons", false)
        adjustObject = obj("adjustObject")
        adjustRenderer = obj("adjustRenderer")?.renderer
        minScaleY = num("minScaleY", 10f)
        maxScaleY = num("maxScaleY", -10f)
        minScaleZ = num("minScaleZ", -10f)
        maxScaleZ = num("maxScaleZ", 10f)
    }

    override fun start() {
        val s = adjustObject!!.localScale
        InitialScaleY = s.y
        InitialScaleZ = s.z
    }

    override fun update() {
        if (QuarterTrigger.state != QuarterTrigger.stateAngleInput) {
            adjustRenderer!!.enabled = false
            return
        }
        adjustRenderer!!.enabled = true
        val angleRange = QuarterTrigger.maxShotAngle - QuarterTrigger.minShotAngle
        if (angleRange <= 0f) return
        val ratio = 1f - (GameManagerScript.shotAngle - QuarterTrigger.minShotAngle) / angleRange
        val o = adjustObject!!
        o.localScale = o.localScale.copy(
            y = InitialScaleY + minScaleY * (1f - ratio) + maxScaleY * ratio,
            z = InitialScaleZ + minScaleZ * (1f - ratio) + maxScaleZ * ratio,
        )
    }

    /**
     * Traced from the AOT ARM (`AngleAdjustScript.OnGUI`, 0x264514-0x264994): with
     * [displayButtons] and `stateAngleInput`, `GUI.skin = null` (0x2645a0), then a "Down" button
     * at `Rect(10, 440, 64, 32)` and an "Up" button at `Rect(240, 440, 64, 32)`, each its own
     * `if`. Each steps `shotAngle` by 1 and then clamps: `if (shotAngle < min) shotAngle = min`
     * (0x26474c), `if (max < shotAngle) shotAngle = max` (0x26493c). The iPad `GetiPadRect`
     * branches are dropped. The captions are drawn by UnityGUI's default skin; the engine's
     * `guiButton` takes no caption, so they are not drawn here (a debug-only control).
     */
    override fun onGUI() {
        if (!displayButtons || QuarterTrigger.state != QuarterTrigger.stateAngleInput) return
        val down = Rect(10f, 440f, 64f, 32f)
        val up = Rect(240f, 440f, 64f, 32f)
        if (guiButton(down)) {
            GameManagerScript.shotAngle -= 1f
            if (GameManagerScript.shotAngle < QuarterTrigger.minShotAngle)
                GameManagerScript.shotAngle = QuarterTrigger.minShotAngle
        }
        if (guiButton(up)) {
            GameManagerScript.shotAngle += 1f
            if (QuarterTrigger.maxShotAngle < GameManagerScript.shotAngle)
                GameManagerScript.shotAngle = QuarterTrigger.maxShotAngle
        }
    }
}
