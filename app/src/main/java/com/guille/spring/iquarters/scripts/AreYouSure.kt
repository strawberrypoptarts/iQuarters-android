package com.guille.spring.iquarters.scripts

import com.guille.spring.iquarters.Behaviour
import com.guille.spring.iquarters.GObj
import com.guille.spring.iquarters.IqStatic
import com.guille.spring.iquarters.Rect

/**
 * `AreYouSure`: the yes/no pop-up. [TriggerAreYouSureMessage] raises it; a click plays
 * `YesClick` or `NoClick`, and once that ends the pop-up deactivates itself and leaves the
 * answer in [returnState]. The iPad rects are dropped.
 */
class AreYouSure : Behaviour() {
    var thisObject: GObj? = null
    var enableButtonView = false
    var dummySkin: String? = null
    var fontSkin: String? = null

    private val popupStateIdle = 100
    private val popupStateRunning = 101
    private val popupStateWaitToEndYes = 102
    private val popupStateWaitToEndNo = 103
    /** The ARM `.ctor` stores 100 (`popupStateIdle`); the decompiled C# has 0. */
    private var curState = 100

    override fun onBind() {
        thisObject = obj("thisObject")
        enableButtonView = bool("enableButtonView", false)
        dummySkin = skin("dummySkin")
        fontSkin = skin("fontSkin")
    }

    /**
     * ARM `AreYouSure::CheckButtons` (0x23b54c): under [dummySkin] unless [enableButtonView]
     * (0x23b588), yes at (60,259,90,43) (0x23b5a8) then no at (179,259,90,43) — the no rect's
     * ints are stored to locals at 0x23b5cc and read back for the second `Rect` (0x23b740).
     * Two separate `if`s; the iPad `GetiPadRect` calls are dropped.
     */
    fun CheckButtons() {
        if (guiButton(Rect(60f, 259f, 90f, 43f))) {
            animation!!.play("YesClick"); curState = popupStateWaitToEndYes
        }
        if (guiButton(Rect(179f, 259f, 90f, 43f))) {
            animation!!.play("NoClick"); curState = popupStateWaitToEndNo
        }
    }

    /** ARM `AreYouSure::OnGUI` (0x23b89c): the trigger, then an if/else-if on [curState]. */
    override fun onGUI() {
        if (triggerAreYouSure) {
            curState = popupStateRunning
            triggerAreYouSure = false
        }
        if (curState == popupStateRunning) {
            CheckButtons()
        } else if (curState == popupStateWaitToEndYes && !animation!!.isPlaying) {
            thisObject!!.setActiveRecursively(false); returnState = stateAnswerYes
        } else if (curState == popupStateWaitToEndNo && !animation!!.isPlaying) {
            thisObject!!.setActiveRecursively(false); returnState = stateAnswerNo
        }
    }

    companion object : IqStatic {
        @JvmField var triggerAreYouSure = false
        const val stateWaitForAnswer = 0
        const val stateAnswerYes = 2
        const val stateAnswerNo = 4
        /** The ARM `.cctor` stores 4 (`stateAnswerNo`); the decompiled C# leaves it 0. */
        @JvmField var returnState = 4

        override fun reset() {
            triggerAreYouSure = false
            returnState = 4
        }

        @JvmStatic fun TriggerAreYouSureMessage() {
            returnState = stateWaitForAnswer
            triggerAreYouSure = true
        }
    }
}
