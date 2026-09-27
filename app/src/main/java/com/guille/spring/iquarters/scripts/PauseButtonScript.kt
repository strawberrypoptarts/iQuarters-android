package com.guille.spring.iquarters.scripts

import com.guille.spring.iquarters.Behaviour
import com.guille.spring.iquarters.GObj
import com.guille.spring.iquarters.Rect

/**
 * `PauseButtonScript`: the in-game pause button. It shows only while `QuarterTrigger` waits
 * for a shot; a press plays its animation, and when that ends the HUD slides out, the pause
 * menu is switched on and `PauseMenu.triggerAnimIn` raised. Its `Awake` loads the replay
 * data. `OnGUI` is transcribed from the AOT ARM (0x269c6c); the iPad placement is dropped.
 */
class PauseButtonScript : Behaviour() {
    var debugState = -1
    var pauseButtonRenderer: GObj? = null
    var enableButtonView = false
    var dummySkin: String? = null
    var pauseMenuObject: GObj? = null

    private val waitForAnimState = 100
    private val idleState = 101
    /** The ARM's ctor sets 101 (the C# leaves it 0); `Start` sets it to idle either way. */
    private var currentState = 101
    private var oneTimeFlag = false

    override fun onBind() {
        debugState = int("debugState", -1)
        pauseButtonRenderer = obj("pauseButtonRenderer")
        enableButtonView = bool("enableButtonView", false)
        dummySkin = skin("dummySkin")
        pauseMenuObject = obj("pauseMenuObject")
    }

    override fun awake() {
        if (!oneTimeFlag) {
            oneTimeFlag = true
            ReplayController.InitReplayData()
            ReplayController.LoadAllReplays()
        }
    }

    override fun start() {
        currentState = idleState
    }

    override fun onGUI() {
        if (QuarterTrigger.state != QuarterTrigger.stateWaitForShot) {
            currentState = idleState
            pauseButtonRenderer!!.renderer!!.enabled = false
            return
        }
        if (currentState == idleState) {
            pauseButtonRenderer!!.renderer!!.enabled = true
            // ARM 0x269d14: built from ints (260, 420, 60, 60), the bottom-right corner; the
            // `allrects` entry of zeros is the zeroed result local, not this rect.
            if (guiButton(Rect(260f, 420f, 60f, 60f))) {
                animation!!.play()
                AnnouncerScript.triggerClickSound = true
                currentState = waitForAnimState
                GameManagerScript.ResetUserSecretCodes()
            }
        } else if (currentState == waitForAnimState && !animation!!.isPlaying) {
            QuarterTrigger.state = QuarterTrigger.stateOptionsMenu
            CoinHolder.triggerCoinHolderOut = GameManagerScript.curMadeShotsThisRound
            InGameAngleIcon.triggerOffScreen = true
            RoundIndicator.TriggerSlideOut = true
            pauseMenuObject!!.setActiveRecursively(true)
            PauseMenu.triggerAnimIn = true
            debugState++
        }
    }

    fun CheckToDisableHOFButton() {}
}
