package com.guille.spring.iquarters.scripts

import com.guille.spring.iquarters.Behaviour
import com.guille.spring.iquarters.GObj
import com.guille.spring.iquarters.IqStatic

/**
 * `RoundIndicator`: the round monitor (`/ex_round_mon`). Start cuts its one clip into
 * `SlideIn` (frames 0-12) and `SlideOut` (50-55). [TriggerSlideIn] shows only the current
 * round's number (or `secret` on the secret round), which rides the move marker while it
 * slides and spins about y while on screen; [TriggerSlideOut] slides it off and deactivates
 * [roundIndicatorObject]. The iPad placement is dropped.
 */
class RoundIndicator : Behaviour() {
    var roundIndicatorObject: GObj? = null

    private var animObject: GObj? = null
    private var markerObject: GObj? = null
    private var markerDefaultPosX = 0f
    private var roundLabelObject: GObj? = null
    private var roundLabelDefaultPosX = 0f
    private var curRoundNumberObject: GObj? = null
    private val maxRoundNumbers = 15
    private val stateIdle = 100
    private val stateSlideIn = 101
    private val stateOnScreen = 102
    private val stateSlideOut = 103
    private var state = 100

    override fun onBind() {
        roundIndicatorObject = obj("roundIndicatorObject")
    }

    override fun start() {
        val a = find("/ex_round_mon")!!
        animObject = a
        val anim = a.anim!!
        anim.playAutomatically = false
        if (anim.getClipCount() == 1) {
            anim.addClip(anim.clip, "SlideIn", 0, 12)
            anim.addClip(anim.clip, "SlideOut", 50, 55)
        }
        markerObject = find("/ex_round_mon/round_move_marker")
        markerDefaultPosX = markerObject!!.position.x
        roundLabelObject = find("/ex_round_mon/round_graphic")
        roundLabelDefaultPosX = roundLabelObject!!.position.x
        roundIndicatorObject!!.setActiveRecursively(false)
        state = stateIdle
    }

    fun UpdateCurRoundNumberPosition() {
        val marker = markerObject!!.position
        curRoundNumberObject!!.position = marker
        roundLabelObject!!.position = roundLabelObject!!.position.copy(x = marker.x)
    }

    fun UpdateCurRoundNumberRotation() {
        val o = curRoundNumberObject!!
        var y = o.localEulerAngles.y - deltaTime * 100f
        // Verbatim; `< 360` is always true here, and +360 is the same rotation.
        if (y < 360f) y += 360f
        o.localEulerAngles = o.localEulerAngles.copy(y = y)
    }

    override fun update() {
        val anim = animObject!!.anim!!
        if (TriggerSlideIn) {
            TriggerSlideIn = false
            markerObject!!.renderer!!.enabled = false
            anim.play("SlideIn")
            CurrentRound = GameManagerScript.curRound
            for (i in 0 until maxRoundNumbers) {
                val path = when {
                    i == GameManagerScript.secretRoundNumber -> "/ex_round_mon/secret"
                    i < 9 -> "/ex_round_mon/0" + (i + 1)
                    else -> "/ex_round_mon/" + (i + 1)
                }
                val numberObject = find(path)!!
                val current = i == CurrentRound
                numberObject.active = current
                if (current) {
                    curRoundNumberObject = numberObject
                    numberObject.localEulerAngles = numberObject.localEulerAngles.copy(y = 0f)
                }
            }
            state = stateSlideIn
        } else if (state == stateSlideIn) {
            UpdateCurRoundNumberPosition()
            if (TriggerSlideOut) {
                TriggerSlideOut = false
                anim.play("SlideOut")
                state = stateSlideOut
            } else if (!anim.isPlaying("SlideIn")) state = stateOnScreen
        } else if (state == stateOnScreen) {
            UpdateCurRoundNumberRotation()
            if (TriggerSlideOut) {
                TriggerSlideOut = false
                anim.play("SlideOut")
                state = stateSlideOut
            }
        } else if (state == stateSlideOut) {
            UpdateCurRoundNumberPosition()
            if (!anim.isPlaying("SlideOut")) {
                roundIndicatorObject!!.setActiveRecursively(false)
                state = stateIdle
            }
        }
    }

    companion object : IqStatic {
        @JvmField var TriggerSlideIn = false
        @JvmField var TriggerSlideOut = false
        @JvmField var CurrentRound = 1

        override fun reset() {
            TriggerSlideIn = false
            TriggerSlideOut = false
            CurrentRound = 1
        }
    }
}
