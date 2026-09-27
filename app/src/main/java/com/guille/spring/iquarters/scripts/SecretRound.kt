package com.guille.spring.iquarters.scripts

import com.guille.spring.iquarters.Behaviour
import com.guille.spring.iquarters.GObj
import com.guille.spring.iquarters.IqStatic

/**
 * `SecretRound`: the secret-round exciter. [TriggerSecretRoundIntro] shows "round" alone,
 * [TriggerSecretRoundOutro] shows "round complete"; either plays the default clip, and when it
 * ends [secretRoundObject] is switched off.
 */
class SecretRound : Behaviour() {
    var secretRoundObject: GObj? = null

    private val stateIdle = 100
    private val stateOnscreen = 101
    private var state = 100
    private var roundCenterObject: GObj? = null
    private var completeObject: GObj? = null
    private var roundObject: GObj? = null

    override fun onBind() {
        secretRoundObject = obj("secretRoundObject")
    }

    override fun start() {
        roundCenterObject = find("/ex_secret_round/Plane01/round_center")
        completeObject = find("/ex_secret_round/Plane01/complete")
        roundObject = find("/ex_secret_round/Plane01/round_01")
        secretRoundObject!!.setActiveRecursively(false)
    }

    override fun update() {
        if (TriggerSecretRoundIntro) {
            TriggerSecretRoundIntro = false
            roundCenterObject!!.renderer!!.enabled = false
            completeObject!!.renderer!!.enabled = false
            roundObject!!.renderer!!.enabled = true
            animation!!.play()
            state = stateOnscreen
        } else if (TriggerSecretRoundOutro) {
            TriggerSecretRoundOutro = false
            roundCenterObject!!.renderer!!.enabled = true
            completeObject!!.renderer!!.enabled = true
            roundObject!!.renderer!!.enabled = false
            animation!!.play()
            state = stateOnscreen
        } else if (state == stateOnscreen && !animation!!.isPlaying) {
            secretRoundObject!!.setActiveRecursively(false)
            state = stateIdle
        }
    }

    companion object : IqStatic {
        @JvmField var TriggerSecretRoundIntro = false
        @JvmField var TriggerSecretRoundOutro = false

        override fun reset() {
            TriggerSecretRoundIntro = false
            TriggerSecretRoundOutro = false
        }
    }
}
