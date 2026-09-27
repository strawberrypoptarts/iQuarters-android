package com.guille.spring.iquarters.scripts

import com.guille.spring.iquarters.Behaviour
import com.guille.spring.iquarters.GObj
import com.guille.spring.iquarters.IqStatic

/**
 * `BackClearButtons`: the Back and Clear buttons of the high-score screens on `ui_back_clear`.
 * [Start] cuts its one clip into `SlideIn`, `BackClick`, `ClearClick` and `SlideOut`; the
 * `Trigger*` flags (set by [mainmenu] and the high-score code) play them.
 */
class BackClearButtons : Behaviour() {
    private var backClearButtonBack: GObj? = null
    private var backClearButtonClear: GObj? = null

    private val stateIdle = 100
    private val stateSlidingIn = 101
    private val stateOnScreen = 102
    private val stateBackClicked = 103
    private val stateSlidingOut = 104
    /** The ARM `.ctor` stores 100 (`stateIdle`); the decompiled C# has 0. */
    private var state = 100

    override fun start() {
        backClearButtonBack = find("/ui_back_clear/button_back_hs")
        backClearButtonClear = find("/ui_back_clear/button_clear_hs")
        val shader = "iPhone/Transparent/Vertex Color"
        backClearButtonBack!!.renderer!!.material.shader = shader
        backClearButtonClear!!.renderer!!.material.shader = shader
        val anim = animation!!
        if (anim.getClipCount() == 1) {
            anim.addClip(anim.clip, "SlideIn", 0, 16)
            anim.addClip(anim.clip, "BackClick", 25, 29)
            anim.addClip(anim.clip, "ClearClick", 40, 45)
            anim.addClip(anim.clip, "SlideOut", 60, 65)
        }
        TriggerIn = false
        TriggerBack = false
        TriggerClear = false
        state = stateIdle
    }

    override fun update() {
        val anim = animation!!
        if (TriggerIn) {
            anim.play("SlideIn"); TriggerIn = false; state = stateSlidingIn
        } else if (TriggerBack) {
            anim.play("BackClick"); TriggerBack = false; state = stateBackClicked
        } else if (TriggerClear) {
            anim.play("ClearClick"); TriggerClear = false
        } else if (state == stateSlidingIn && !anim.isPlaying("SlideIn")) {
            state = stateOnScreen
        } else if (state == stateBackClicked && !anim.isPlaying("BackClick")) {
            anim.play("SlideOut"); state = stateSlidingOut
        } else if (state == stateSlidingOut && !anim.isPlaying("SlideOut")) {
            state = stateIdle
        }
    }

    companion object : IqStatic {
        @JvmField var TriggerIn = false
        @JvmField var TriggerBack = false
        @JvmField var TriggerClear = false

        override fun reset() {
            TriggerIn = false
            TriggerBack = false
            TriggerClear = false
        }
    }
}
