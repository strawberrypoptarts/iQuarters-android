package com.guille.spring.iquarters.scripts

import com.guille.spring.iquarters.Behaviour
import com.guille.spring.iquarters.GObj
import com.guille.spring.iquarters.IqStatic

/**
 * `RoundComplete`: the "round N complete" exciter. [triggerExciterRoundFinished] plays the
 * `rndNN` clip for [roundToDisplay]; [triggerExciterNewRoundHigh] plays `newroundhigh` with
 * [displayScore] spelled out in the two- or three-digit renderers. When the clip ends the
 * digits are hidden and, a frame later, [RoundCompleteObject] is switched off.
 */
class RoundComplete : Behaviour() {
    var RoundCompleteObject: GObj? = null
    var digitsRendererArray: List<GObj?> = emptyList()
    var digitsMaterialArray: IntArray = IntArray(0)
    var digitsTextureArray: IntArray = IntArray(0)

    /** The ARM's ctor gives the states 16/17/18 (the C# reads 10000..10002). */
    private val rfStateIsRunning = 16
    private val rfStateIdle = 17
    private val rfStateEnding = 18
    private var rfStateCurrent = 17

    override fun onBind() {
        RoundCompleteObject = obj("RoundCompleteObject")
        digitsRendererArray = objs("digitsRendererArray")
        digitsMaterialArray = materials("digitsMaterialArray")
        digitsTextureArray = textures("digitsTextureArray")
    }

    fun playAnimation(roundFinished: Int) {
        var r = roundFinished
        animation!!.stop()
        if (r < 0 || r > 14) r = 0
        animation!!.play("rnd" + (r + 1).toString().padStart(2, '0'))
    }

    fun HandleTextures(number: Int) {
        val start: Int
        val count: Int
        if (number < 100) {
            for (index in 0 until 3) digitsRendererArray[index]!!.renderer!!.enabled = false
            digitsRendererArray[3]!!.renderer!!.enabled = true
            digitsRendererArray[4]!!.renderer!!.enabled = true
            start = 3; count = 2
        } else {
            for (index in 0 until 3) digitsRendererArray[index]!!.renderer!!.enabled = true
            digitsRendererArray[3]!!.renderer!!.enabled = false
            digitsRendererArray[4]!!.renderer!!.enabled = false
            start = 0; count = 3
        }
        var divisor = 1
        for (offset in 0 until count) {
            val digit = number / divisor % 10
            setSharedMainTexture(digitsMaterialArray[start + offset], digitsTextureArray[digit])
            divisor *= 10
        }
    }

    fun DisableAllDigitRenders() {
        for (index in 0 until 5) digitsRendererArray[index]!!.renderer!!.enabled = false
    }

    /** `material.mainTexture = t` on a shared material: every instance made from it changes. */
    private fun setSharedMainTexture(material: Int, tex: Int) {
        if (material < 0) throw NullPointerException("digitsMaterialArray")
        val src = world.pack.materials[material]
        for (o in world.objects) o.renderer?.materials?.forEach { if (it.src === src) it.mainTex = tex }
    }

    override fun update() {
        if (triggerExciterRoundFinished) {
            playAnimation(roundToDisplay)
            rfStateCurrent = rfStateIsRunning
            triggerExciterRoundFinished = false
        } else if (triggerExciterNewRoundHigh) {
            animation!!.play("newroundhigh")
            rfStateCurrent = rfStateIsRunning
            triggerExciterNewRoundHigh = false
            HandleTextures(displayScore)
        } else if (rfStateCurrent == rfStateIsRunning && !animation!!.isPlaying) {
            rfStateCurrent = rfStateEnding
            DisableAllDigitRenders()
        } else if (rfStateCurrent == rfStateEnding) {
            rfStateCurrent = rfStateIdle
            RoundCompleteObject!!.setActiveRecursively(false)
        }
    }

    companion object : IqStatic {
        @JvmField var displayScore = 50
        @JvmField var triggerExciterRoundFinished = false
        @JvmField var triggerExciterNewRoundHigh = false
        @JvmField var roundToDisplay = 0

        override fun reset() {
            displayScore = 50
            triggerExciterRoundFinished = false
            triggerExciterNewRoundHigh = false
            roundToDisplay = 0
        }
    }
}
