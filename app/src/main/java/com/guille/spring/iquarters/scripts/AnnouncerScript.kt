package com.guille.spring.iquarters.scripts

import com.guille.spring.iquarters.Behaviour
import com.guille.spring.iquarters.IqStatic

/**
 * `AnnouncerScript`: the one-shot UI and voice sounds (click, unlock, perfect round in
 * practice, off the table), each raised by a static flag and cleared the frame it is seen.
 * [triggerPlayerVO] is only ever cleared: the player call-outs play nothing in this build.
 */
class AnnouncerScript : Behaviour() {
    var floorSound = -1
    var perfectRoundVO = -1
    var clickSound = -1
    var unlockSound = -1

    override fun onBind() {
        floorSound = audioClip("floorSound")
        perfectRoundVO = audioClip("perfectRoundVO")
        clickSound = audioClip("clickSound")
        unlockSound = audioClip("unlockSound")
    }

    private fun play(clip: Int) {
        audio?.clip = clip
        audio?.play()
    }

    override fun update() {
        if (triggerClickSound) {
            if (!QuarterTrigger.muteF) play(clickSound)
            triggerClickSound = false
        }
        if (triggerUnlockSound) {
            if (!QuarterTrigger.muteF) play(unlockSound)
            triggerUnlockSound = false
        }
        if (triggerPerfectRoundVO) {
            if (!QuarterTrigger.muteF && mainmenu.feGameType == mainmenu.gtPractice) play(perfectRoundVO)
            triggerPerfectRoundVO = false
        }
        if (triggerOffTableVO) {
            if (!QuarterTrigger.muteF) play(floorSound)
            triggerOffTableVO = false
        }
        if (triggerPlayerVO != 0) triggerPlayerVO = 0
    }

    companion object : IqStatic {
        @JvmField var triggerOffTableVO = false
        @JvmField var triggerPlayerVO = 0
        @JvmField var triggerPerfectRoundVO = false
        @JvmField var triggerClickSound = false
        @JvmField var triggerUnlockSound = false

        override fun reset() {
            triggerOffTableVO = false
            triggerPlayerVO = 0
            triggerPerfectRoundVO = false
            triggerClickSound = false
            triggerUnlockSound = false
        }
    }
}
