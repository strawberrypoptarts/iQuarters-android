package com.guille.spring.iquarters.scripts

import com.guille.spring.iquarters.Behaviour
import com.guille.spring.iquarters.IqStatic

/**
 * `CrowdScript`: the applause. [playApplauseSound] 1 plays `applause1` on the source at half
 * volume, 2 one-shots it at half volume, 3 one-shots `applause3` at full; read in `FixedUpdate`.
 */
class CrowdScript : Behaviour() {
    var applause1 = -1
    var applause3 = -1

    override fun onBind() {
        applause1 = audioClip("applause1")
        applause3 = audioClip("applause3")
    }

    fun ApplauseSound(sound: Int) {
        if (QuarterTrigger.muteF) return
        val a = audio!!
        when (sound) {
            1 -> { a.volume = 0.5f; a.clip = applause1; a.play() }
            2 -> { a.volume = 0.5f; a.playOneShot(applause1) }
            3 -> { a.volume = 1f; a.playOneShot(applause3) }
        }
    }

    override fun fixedUpdate() {
        if (playApplauseSound != 0) {
            ApplauseSound(playApplauseSound)
            playApplauseSound = 0
        }
    }

    companion object : IqStatic {
        @JvmField var playApplauseSound = 0

        override fun reset() { playApplauseSound = 0 }
    }
}
