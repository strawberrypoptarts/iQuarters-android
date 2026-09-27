package com.guille.spring.iquarters.scripts

import com.guille.spring.iquarters.Behaviour
import com.guille.spring.iquarters.IqStatic

/**
 * `GameHighScreen`: the game high-score board on `ui_high_high`, which slides in and out on
 * [TriggerIn]/[TriggerOut] (cut from its one clip) and shows the odd finger row
 * (`high_score_bg_08`) only while it is on screen. [mainmenu] draws the entries over it.
 */
class GameHighScreen : Behaviour() {
    private val stateIdle = 100
    private val stateSlidingIn = 101
    private val stateOnScreen = 102
    private val stateSlidingOut = 103
    /** The ARM `.ctor` stores 100 (`stateIdle`); the decompiled C# has 0. */
    private var state = 100

    override fun start() {
        val anim = animation!!
        if (anim.getClipCount() == 1) {
            anim.addClip(anim.clip, "SlideIn", 0, 22)
            anim.addClip(anim.clip, "SlideOut", 50, 66)
        }
        val shader = "iPhone/Transparent/Vertex Color"
        for (index in 0 until HiScoreScript.maxEntryCount)
            find("/ui_high_high/high_score_bg_0$index")!!.renderer!!.material.shader = shader
        TriggerIn = false
        TriggerOut = false
        IsScreenSliding = false
        state = stateIdle
    }

    fun DisplayOddFinger(flag: Boolean) {
        find("/ui_high_high/high_score_bg_08")!!.renderer!!.enabled = flag
    }

    override fun update() {
        val anim = animation!!
        if (TriggerIn) {
            DisplayOddFinger(true)
            anim.play("SlideIn"); TriggerIn = false; IsScreenSliding = true; state = stateSlidingIn
        } else if (TriggerOut) {
            anim.play("SlideOut"); TriggerOut = false; IsScreenSliding = true; state = stateSlidingOut
        } else if (state == stateSlidingIn && !anim.isPlaying("SlideIn")) {
            IsScreenSliding = false; state = stateOnScreen
        } else if (state == stateSlidingOut && !anim.isPlaying("SlideOut")) {
            DisplayOddFinger(false)
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
