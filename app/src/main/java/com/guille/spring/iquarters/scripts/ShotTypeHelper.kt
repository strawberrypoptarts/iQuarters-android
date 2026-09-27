package com.guille.spring.iquarters.scripts

import com.guille.spring.iquarters.Behaviour
import com.guille.spring.iquarters.GObj
import com.guille.spring.iquarters.IqStatic

/**
 * `ShotTypeHelper`: plays the "flick" or "shake" hint animation when
 * [triggerShotTypeHelper] is raised, then hides the hint once it has finished.
 */
class ShotTypeHelper : Behaviour() {
    var shotTypeHelperObject: GObj? = null

    private val stIdle = 100
    private val stAnimating = 101
    private var curState = 100

    override fun onBind() {
        shotTypeHelperObject = obj("shotTypeHelperObject")
    }

    fun isFlickShot(): Boolean = GameManagerScript.GetCurrentInputType() != GameManagerScript.inputTypeShake

    override fun update() {
        if (curState == stAnimating && animation?.isPlaying != true) {
            shotTypeHelperObject!!.setActiveRecursively(false)
            curState = stIdle
        }
        if (triggerShotTypeHelper) {
            curState = stAnimating
            animation?.play(if (isFlickShot()) "flick" else "shake")
            triggerShotTypeHelper = false
        }
    }

    companion object : IqStatic {
        var triggerShotTypeHelper = false

        override fun reset() {
            triggerShotTypeHelper = false
        }
    }
}
