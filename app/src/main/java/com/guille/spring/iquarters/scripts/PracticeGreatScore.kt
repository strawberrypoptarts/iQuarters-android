package com.guille.spring.iquarters.scripts

import com.guille.spring.iquarters.Behaviour
import com.guille.spring.iquarters.GObj
import com.guille.spring.iquarters.Iq
import com.guille.spring.iquarters.IqStatic

/**
 * `PracticeGreatScore`: the "great score" exciter in practice mode. [triggerGreatScore] spells
 * [displayScore] (clamped to 10..999) in the two- or three-digit renderers under
 * `/ex_great_score` and plays the default clip; a frame after it ends
 * [practiceGreatScoreObject] is switched off.
 */
class PracticeGreatScore : Behaviour() {
    var practiceGreatScoreObject: GObj? = null
    var gsMaterialArray: IntArray = IntArray(0)
    var gsTextureArray: IntArray = IntArray(0)

    /** The ARM's ctor gives the states 16/17/18 (the C# reads 10000..10002). */
    private val gsStateIsRunning = 16
    private val gsStateIdle = 17
    private val gsStateEnding = 18
    private var gsStateCurrent = 17

    override fun onBind() {
        practiceGreatScoreObject = obj("practiceGreatScoreObject")
        gsMaterialArray = materials("gsMaterialArray")
        gsTextureArray = textures("gsTextureArray")
    }

    /** `material.mainTexture = t` on a shared material: every instance made from it changes. */
    private fun setSharedMainTexture(material: Int, tex: Int) {
        if (material < 0) throw NullPointerException("gsMaterialArray")
        val src = world.pack.materials[material]
        for (o in world.objects) o.renderer?.materials?.forEach { if (it.src === src) it.mainTex = tex }
    }

    fun HandleGreatScoreTextures(n: Int) {
        var number = n
        if (number < 10) {
            number = 10
            Iq.log("Round Score is out of range!")
        } else if (number > 999) {
            number = 999
            Iq.log("Round Score is out of range!")
        }
        val renderers = arrayOf(
            find("/ex_great_score/hs_03")!!.renderer!!,
            find("/ex_great_score/hs_02")!!.renderer!!,
            find("/ex_great_score/hs_01")!!.renderer!!,
            find("/ex_great_score/hs_05")!!.renderer!!,
            find("/ex_great_score/hs_04")!!.renderer!!,
        )
        val firstRenderer: Int
        val digitCount: Int
        if (number < 100) {
            for (k in 0 until 3) renderers[k].enabled = false
            renderers[3].enabled = true
            renderers[4].enabled = true
            firstRenderer = 3; digitCount = 2
        } else {
            for (k in 0 until 3) renderers[k].enabled = true
            renderers[3].enabled = false
            renderers[4].enabled = false
            firstRenderer = 0; digitCount = 3
        }
        var divisor = 1
        for (digitIndex in 0 until digitCount) {
            if (digitIndex > 0) divisor *= 10
            val digit = number / divisor % 10
            setSharedMainTexture(gsMaterialArray[firstRenderer + digitIndex], gsTextureArray[digit])
        }
    }

    override fun update() {
        if (triggerGreatScore) {
            HandleGreatScoreTextures(displayScore)
            animation!!.play()
            gsStateCurrent = gsStateIsRunning
            triggerGreatScore = false
        } else if (gsStateCurrent == gsStateIsRunning && !animation!!.isPlaying) {
            gsStateCurrent = gsStateEnding
        } else if (gsStateCurrent == gsStateEnding) {
            practiceGreatScoreObject!!.setActiveRecursively(false)
            gsStateCurrent = gsStateIdle
        }
    }

    companion object : IqStatic {
        @JvmField var displayScore = 10
        @JvmField var triggerGreatScore = false

        override fun reset() {
            displayScore = 10
            triggerGreatScore = false
        }
    }
}
