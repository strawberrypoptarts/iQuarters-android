package com.guille.spring.iquarters.scripts

import com.guille.spring.iquarters.Behaviour
import com.guille.spring.iquarters.GObj
import com.guille.spring.iquarters.IqStatic
import com.guille.spring.iquarters.Rect

/**
 * `RicochetExciter`: the ricochet holder that slides in during a shot, lights one coin per
 * bank (`TriggerCoinX`), flashes the ricochet bonus (`TriggerScore`), and slides out. While
 * the shot is in the air and the holder is coming in or on screen, a tap anywhere skips to the
 * rack-up: `OnGUI` (ARM 0x27af34) makes the whole screen, (0,0,320,480), one invisible button.
 *
 * The iPad rect branch is dropped.
 */
class RicochetExciter : Behaviour() {
    var ricochetParentObject: GObj? = null
    var ricochetScoreObject: GObj? = null
    var digitsTextureArray = IntArray(0)
    var dummySkin: String? = null

    private val stateIdle = 100
    private val stateHolderIn = 101
    private val stateOnScreen = 102
    private val stateOnScreenWithScore = 103
    private val stateHolderOut = 104
    private var state = 100
    private val maxCoins = 9
    private var holderObject: GObj? = null

    override fun onBind() {
        ricochetParentObject = obj("ricochetParentObject")
        ricochetScoreObject = obj("ricochetScoreObject")
        digitsTextureArray = textures("digitsTextureArray")
        dummySkin = skin("dummySkin")
    }

    override fun start() {
        val holder = find("/RicochetParent/ui_richochet_holder")!!
        holderObject = holder
        val ha = holder.anim!!
        if (ha.getClipCount() == 1) {
            ha.addClip(ha.clip, "SlideIn", 0, 8)
            ha.addClip(ha.clip, "SlideOut", 15, 20)
        }
        ricochetParentObject!!.setActiveRecursively(false)
        val score = ricochetScoreObject!!
        val sa = score.anim!!
        if (sa.getClipCount() == 1) sa.addClip(sa.clip, "OnOff", 0, 45)
        score.setActiveRecursively(false)
    }

    fun DisableInstantReplayExciter() {
        find("/exciter_instant_replay")?.setActiveRecursively(false)
    }

    private fun hideCoins() {
        for (i in 0 until maxCoins) find("/RicochetParent/ui_richochet_coin0$i")?.setActiveRecursively(false)
    }

    override fun update() {
        val holder = holderObject!!
        if (TriggerOn && QuarterTrigger.skipToRackupState == 0) {
            TriggerOn = false
            hideCoins()
            DisableInstantReplayExciter()
            holder.anim?.play("SlideIn")
            state = stateHolderIn
        }

        when (state) {
            stateIdle -> return
            stateHolderIn -> {
                if (holder.anim?.isPlaying("SlideIn") != true) state = stateOnScreen
            }
            stateOnScreen -> {
                if (TriggerCoinX != 0 && QuarterTrigger.skipToRackupState == 0) {
                    if (TriggerCoinX in 1..maxCoins) {
                        DisableInstantReplayExciter()
                        ricochetParentObject!!.setActiveRecursively(true)
                        for (i in 0 until maxCoins) {
                            // Unanchored, as in the original.
                            val coin = find("ui_richochet_coin0$i")!!
                            coin.setActiveRecursively(i < TriggerCoinX - 1)
                            if (i == TriggerCoinX - 1) coin.anim?.play()
                        }
                        if (!QuarterTrigger.muteF) audio?.play()
                    }
                    TriggerCoinX = 0
                }
                if (TriggerScore != 0 && QuarterTrigger.skipToRackupState == 0) {
                    DisableInstantReplayExciter()
                    TriggerScore %= 10
                    val score = ricochetScoreObject!!
                    score.setActiveRecursively(true)
                    find("/ui_richochet_score/center_marker")!!.active = false
                    find("/ui_richochet_score/richochet_score/number_parent/rs_01")!!.renderer!!.material.mainTex =
                        digitsTextureArray[TriggerScore]
                    find("/ui_richochet_score/richochet_score/number_parent/rs_02")!!.renderer!!.material.mainTex =
                        digitsTextureArray[0]
                    score.anim?.play("OnOff")
                    TriggerScore = 0
                    state = stateOnScreenWithScore
                }
            }
            stateOnScreenWithScore -> {
                if (ricochetScoreObject!!.anim?.isPlaying("OnOff") != true) {
                    hideCoins()
                    holder.anim?.play("SlideOut")
                    state = stateHolderOut
                }
            }
            stateHolderOut -> {
                if (holder.anim?.isPlaying("SlideOut") != true) {
                    ricochetParentObject!!.setActiveRecursively(false)
                    ricochetScoreObject!!.setActiveRecursively(false)
                    DisableInstantReplayExciter()
                    state = stateIdle
                }
            }
        }
    }

    override fun onGUI() {
        if (QuarterTrigger.state == QuarterTrigger.stateShotInAir && (state == stateHolderIn || state == stateOnScreen)) {
            if (guiButton(Rect(0f, 0f, 320f, 480f))) {
                ricochetParentObject!!.setActiveRecursively(false)
                ricochetScoreObject!!.setActiveRecursively(false)
                DisableInstantReplayExciter()
                state = stateIdle
                if (QuarterTrigger.skipToRackupState == 0) QuarterTrigger.skipToRackupState = 1
            }
        }
    }

    companion object : IqStatic {
        var numRicochets = 0
        var TriggerOn = false
        var TriggerCoinX = 0
        var TriggerScore = 0

        override fun reset() {
            numRicochets = 0
            TriggerOn = false
            TriggerCoinX = 0
            TriggerScore = 0
        }
    }
}
