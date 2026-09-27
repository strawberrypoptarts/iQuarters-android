package com.guille.spring.iquarters.scripts

import com.guille.spring.iquarters.Behaviour
import com.guille.spring.iquarters.IqStatic

/**
 * `Logo`: the iQuarters logo over the high-score screens on `ui_quarter_logo_score`, which
 * slides in and out on [TriggerIn]/[TriggerOut] (cut from its one clip).
 */
class Logo : Behaviour() {
    private val stateIdle = 100
    private val stateSlidingIn = 101
    private val stateOnScreen = 102
    private val stateSlidingOut = 103
    /** The ARM `.ctor` stores 100 (`stateIdle`); the decompiled C# has 0. */
    private var state = 100

    override fun start() {
        val anim = animation!!
        if (anim.getClipCount() == 1) {
            anim.addClip(anim.clip, "SlideIn", 0, 15)
            anim.addClip(anim.clip, "SlideOut", 55, 65)
        }
        val shader = "iPhone/Transparent/Vertex Color"
        find("/ui_quarter_logo_score/logo_bg_q_00")!!.renderer!!.material.shader = shader
        find("/ui_quarter_logo_score/logo_quarters_00")!!.renderer!!.material.shader = shader
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
