package com.guille.spring.iquarters.scripts

import com.guille.spring.iquarters.Behaviour
import com.guille.spring.iquarters.GObj
import com.guille.spring.iquarters.IqStatic

/**
 * `GameOver`: the end-of-game exciter. [triggerExciterOutOfShots] plays `outofshots`,
 * [triggerExciterGameComplete] plays `gamecomplete`; a frame after the clip ends
 * [GameOverExciterObject] is switched off.
 */
class GameOver : Behaviour() {
    var GameOverExciterObject: GObj? = null

    /** The ARM's ctor gives the states 16/17/18 (the C# reads 10000..10002). */
    private val rfStateIsRunning = 16
    private val rfStateIdle = 17
    private val rfStateEnding = 18
    private var rfStateCurrent = 17

    override fun onBind() {
        GameOverExciterObject = obj("GameOverExciterObject")
    }

    override fun update() {
        if (triggerExciterOutOfShots) {
            animation!!.play("outofshots")
            rfStateCurrent = rfStateIsRunning
            triggerExciterOutOfShots = false
        } else if (triggerExciterGameComplete) {
            animation!!.play("gamecomplete")
            rfStateCurrent = rfStateIsRunning
            triggerExciterGameComplete = false
        } else if (rfStateCurrent == rfStateIsRunning && !animation!!.isPlaying) {
            rfStateCurrent = rfStateEnding
        } else if (rfStateCurrent == rfStateEnding) {
            rfStateCurrent = rfStateIdle
            GameOverExciterObject!!.setActiveRecursively(false)
        }
    }

    companion object : IqStatic {
        @JvmField var triggerExciterOutOfShots = false
        @JvmField var triggerExciterGameComplete = false
        @JvmField var roundToDisplay = 0

        override fun reset() {
            triggerExciterOutOfShots = false
            triggerExciterGameComplete = false
            roundToDisplay = 0
        }
    }
}
