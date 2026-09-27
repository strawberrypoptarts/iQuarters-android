package com.guille.spring.iquarters.scripts

import com.guille.spring.iquarters.Behaviour
import com.guille.spring.iquarters.GObj
import com.guille.spring.iquarters.IqStatic
import com.guille.spring.iquarters.Rect

/**
 * `PracticeUI`: the practice-mode round picker. [triggerPracticeUI] slides it in on
 * [curLevelSelected]; left and right step the round (wrapping, and raising
 * [triggerLevelSwitch] once their animations settle so `QuarterTrigger` moves the table),
 * a padlock shows over locked rounds, go raises [selectLevelDone] and quit raises
 * [cancelPracticeScreen]. The iPad rects are dropped.
 */
class PracticeUI : Behaviour() {
    var lockUIObject: GObj? = null
    var PracticeUIObject: GObj? = null
    var enableButtonView = false
    var dummySkin: String? = null
    var fontSkin: String? = null
    var fontSkin_iPad: String? = null

    // The ARM's ctor values; the C# numbers these 1000..1004. Only their distinctness matters.
    private val practiceStateNone = 1000
    private val practiceStateSlideIn = 233
    private val practiceStateWait = 234
    private val practiceStateSlideOut = 235
    private val practiceStateSwitchClicked = 1004
    private var currentState = 1000
    private var lockDisplayedState = false
    private var roundScore = 0
    private var firstTimeFlag = false

    override fun onBind() {
        lockUIObject = obj("lockUIObject")
        PracticeUIObject = obj("PracticeUIObject")
        enableButtonView = bool("enableButtonView", false)
        dummySkin = skin("dummySkin")
        fontSkin = skin("fontSkin")
        fontSkin_iPad = skin("fontSkin_iPad")
    }

    override fun start() {
        firstTimeFlag = true
    }

    override fun update() {
        val anim = animation!!
        if (currentState == practiceStateSlideIn && !anim.isPlaying) {
            currentState = practiceStateWait
        } else if (currentState == practiceStateSlideOut && !anim.isPlaying) {
            if (cancelPracticeScreen) QuarterTrigger.state = QuarterTrigger.stateGameOver
            PracticeUIObject!!.setActiveRecursively(false)
            lockUIObject!!.setActiveRecursively(false)
            currentState = practiceStateNone
        } else if (currentState == practiceStateSwitchClicked && !anim.isPlaying &&
            !lockUIObject!!.anim!!.isPlaying("lockin") && !lockUIObject!!.anim!!.isPlaying("lockout")
        ) {
            triggerLevelSwitch = true
            currentState = practiceStateWait
        }

        if (triggerPracticeUI) {
            roundScore = HiScoreRoundScript.GetScore(curLevelSelected)
            anim.play("slidein")
            currentState = practiceStateSlideIn
            triggerPracticeUI = false
            lockDisplayedState = curLevelSelected >= HiScoreScript.lockedRoundStartIndex
            cancelPracticeScreen = false
            lockUIObject!!.setActiveRecursively(true)
            ChecktoTriggerLockAnimation()
        }

        if (triggerDelayedLockAnim) {
            triggerDelayedLockAnim = false
            val lockObject = find("/ui_practice_lock/lock")
            if (triggerDelayedLockIn) {
                triggerDelayedLockIn = false
                if (firstTimeFlag) {
                    lockUIObject!!.anim!!.play("lockin")
                    firstTimeFlag = false
                } else {
                    lockObject!!.renderer!!.enabled = true
                }
            } else if (triggerDelayedLockOut) {
                triggerDelayedLockOut = false
                lockObject!!.renderer!!.enabled = false
            }
        }
    }

    fun ChecktoTriggerLockAnimation() {
        val shouldLock = curLevelSelected >= HiScoreScript.lockedRoundStartIndex
        if (!lockDisplayedState && shouldLock) {
            triggerDelayedLockIn = true
            lockDisplayedState = true
        } else if (lockDisplayedState && !shouldLock) {
            triggerDelayedLockOut = true
            lockDisplayedState = false
        }
    }

    fun IsGoButtonDisabled(): Boolean = curLevelSelected >= HiScoreScript.lockedRoundStartIndex

    /**
     * Transcribed from the ARM (`PracticeUI::OnGUI`, 0x272d9c-0x2738d4); the iPad
     * `fontSkin_iPad` / `GetiPadRect` branches are dropped. The hi-score label shows in both
     * wait and switch-clicked (0x272dd8) under [fontSkin] at (90,46,180,40) (0x272fb0), in
     * the skin's own alignment (nothing sets one). The buttons exist only in wait (0x273020),
     * under [dummySkin] unless [enableButtonView] (0x273048), and each is its own `if` in
     * this order: go (94,323,138,96) skipped while [IsGoButtonDisabled] (0x273070, 0x273134),
     * left (12,335,74,74) (0x273304), right (240,335,74,74) (0x273548), quit
     * (109,440,105,40) (0x273788).
     */
    override fun onGUI() {
        if (currentState == practiceStateWait || currentState == practiceStateSwitchClicked) {
            guiLabel(Rect(90f, 46f, 180f, 40f), "Hi Score  $roundScore", fontSkin ?: "fontSkin")
        }
        if (currentState != practiceStateWait) return

        val anim = animation!!
        if (!IsGoButtonDisabled() && guiButton(Rect(94f, 323f, 138f, 96f))) {
            selectLevelDone = true
            AnnouncerScript.triggerClickSound = true
            anim.play("goclick")
            anim.playQueued("slideout")
            currentState = practiceStateSlideOut
        }
        if (guiButton(Rect(12f, 335f, 74f, 74f))) {
            curLevelSelected--
            if (curLevelSelected < 0) curLevelSelected = GameManagerScript.numRounds - 1
            currentState = practiceStateSwitchClicked
            roundScore = HiScoreRoundScript.GetScore(curLevelSelected)
            AnnouncerScript.triggerClickSound = true
            anim.play("leftclick")
            ChecktoTriggerLockAnimation()
        }
        if (guiButton(Rect(240f, 335f, 74f, 74f))) {
            curLevelSelected++
            if (curLevelSelected >= GameManagerScript.numRounds) curLevelSelected = 0
            currentState = practiceStateSwitchClicked
            roundScore = HiScoreRoundScript.GetScore(curLevelSelected)
            AnnouncerScript.triggerClickSound = true
            anim.play("rightclick")
            ChecktoTriggerLockAnimation()
        }
        if (guiButton(Rect(109f, 440f, 105f, 40f))) {
            lockUIObject!!.setActiveRecursively(false)
            cancelPracticeScreen = true
            AnnouncerScript.triggerClickSound = true
            anim.play("quitclick")
            anim.playQueued("slideout")
            currentState = practiceStateSlideOut
        }
    }

    companion object : IqStatic {
        @JvmField var triggerPracticeUI = false
        @JvmField var triggerLevelSwitch = false
        @JvmField var curLevelSelected = 0
        @JvmField var selectLevelDone = false
        @JvmField var triggerDelayedLockAnim = false
        @JvmField var triggerDelayedLockIn = false
        @JvmField var triggerDelayedLockOut = false
        @JvmField var cancelPracticeScreen = false

        override fun reset() {
            triggerPracticeUI = false
            triggerLevelSwitch = false
            curLevelSelected = 0
            selectLevelDone = false
            triggerDelayedLockAnim = false
            triggerDelayedLockIn = false
            triggerDelayedLockOut = false
            cancelPracticeScreen = false
        }
    }
}
