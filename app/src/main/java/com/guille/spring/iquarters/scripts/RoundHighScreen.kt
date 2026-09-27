package com.guille.spring.iquarters.scripts

import com.guille.spring.iquarters.Behaviour
import com.guille.spring.iquarters.IqStatic

/**
 * `RoundHighScreen`: the per-round high-score board on `ui_round_high`, which slides in and
 * out on [TriggerIn]/[TriggerOut] (cut from its one clip). [GameOrRoundButtons] waits on
 * [IsScreenSliding]; [mainmenu] draws the scores over it.
 */
class RoundHighScreen : Behaviour() {
    private val stateIdle = 100
    private val stateSlidingIn = 101
    private val stateOnScreen = 102
    private val stateSlidingOut = 103
    /** The ARM `.ctor` stores 100 (`stateIdle`); the decompiled C# has 0. */
    private var state = 100

    override fun start() {
        val anim = animation!!
        if (anim.getClipCount() == 1) {
            anim.addClip(anim.clip, "SlideIn", 0, 30)
            anim.addClip(anim.clip, "SlideOut", 50, 70)
        }
        val shader = "iPhone/Transparent/Vertex Color"
        for (index in 0 until GameManagerScript.numRounds) {
            val path = if (index < 10) "/ui_round_high/high_score_bg_0$index" else "/ui_round_high/high_score_bg_$index"
            find(path)!!.renderer!!.material.shader = shader
        }
        TriggerIn = false
        TriggerOut = false
        IsScreenSliding = false
        state = stateIdle
    }

    override fun update() {
        val anim = animation!!
        if (TriggerIn) {
            anim.play("SlideIn"); TriggerIn = false; IsScreenSliding = true; state = stateSlidingIn
        } else if (TriggerOut) {
            anim.play("SlideOut"); TriggerOut = false; IsScreenSliding = true; state = stateSlidingOut
        } else if (state == stateSlidingIn && !anim.isPlaying("SlideIn")) {
            IsScreenSliding = false; state = stateOnScreen
        } else if (state == stateSlidingOut && !anim.isPlaying("SlideOut")) {
            IsScreenSliding = false; state = stateIdle
        }
    }

    companion object : IqStatic {
        @JvmField var TriggerIn = false
        @JvmField var TriggerOut = false
        @JvmField var IsScreenSliding = false

        override fun reset() {
            TriggerIn = false
            TriggerOut = false
            IsScreenSliding = false
        }
    }
}
