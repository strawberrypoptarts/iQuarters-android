package com.guille.spring.iquarters.scripts

import com.guille.spring.iquarters.Behaviour
import com.guille.spring.iquarters.GObj
import com.guille.spring.iquarters.Iq
import com.guille.spring.iquarters.IqStatic
import com.guille.spring.iquarters.Rend

/**
 * `Streak`: the "N in a row" exciter. A trigger shows the count on one or two digit quads
 * (right-hand single digit under 10, otherwise ones and tens) and plays the exciter's default
 * clip; when it ends the digits go off and the exciter is deactivated.
 */
class Streak : Behaviour() {
    var streakExciterObject: GObj? = null
    var streakDigitsRendererArray: List<Rend?> = emptyList()
    var streakDigitsMaterialArray = IntArray(0)
    var streakDigitsTextureArray = IntArray(0)

    override fun onBind() {
        streakExciterObject = obj("streakExciterObject")
        streakDigitsRendererArray = objs("streakDigitsRendererArray").map { it?.renderer }
        streakDigitsMaterialArray = materials("streakDigitsMaterialArray")
        streakDigitsTextureArray = textures("streakDigitsTextureArray")
    }

    override fun update() {
        if (stateCurrent == stateTrigger) {
            // The C# also builds an unused "player<min(count, 4)>" name here.
            HandleTextures(streakCount)
            streakExciterObject!!.anim!!.play()
            stateCurrent = stateOnscreen
        } else if (stateCurrent == stateOnscreen && !streakExciterObject!!.anim!!.isPlaying) {
            DisableAllDigitRenders()
            streakExciterObject!!.setActiveRecursively(false)
            stateCurrent = stateIdle
        }
    }

    /** `Material.mainTexture = t` on a shared material: every instance made from it changes. */
    private fun setSharedMainTex(material: Int, tex: Int) {
        if (material < 0) throw NullPointerException("streakDigitsMaterialArray")
        val src = world.pack.materials[material]
        for (o in world.objects) o.renderer?.materials?.forEach { if (it.src === src) it.mainTex = tex }
    }

    fun HandleTextures(number: Int) {
        if (number < 0 || number > 99) {
            Iq.log("streak count is out of range!    $number")
            return
        }
        if (number < 10) {
            streakDigitsRendererArray[0]!!.enabled = false
            streakDigitsRendererArray[1]!!.enabled = false
            streakDigitsRendererArray[2]!!.enabled = true
            setSharedMainTex(streakDigitsMaterialArray[2], streakDigitsTextureArray[number % 10])
        } else {
            streakDigitsRendererArray[0]!!.enabled = true
            streakDigitsRendererArray[1]!!.enabled = true
            streakDigitsRendererArray[2]!!.enabled = false
            setSharedMainTex(streakDigitsMaterialArray[0], streakDigitsTextureArray[number % 10])
            setSharedMainTex(streakDigitsMaterialArray[1], streakDigitsTextureArray[number / 10])
        }
    }

    fun DisableAllDigitRenders() {
        for (i in 0 until maxDigits) streakDigitsRendererArray[i]!!.enabled = false
    }

    companion object : IqStatic {
        const val maxDigits = 3
        const val stateIdle = 100
        const val stateTrigger = 101
        const val stateOnscreen = 102

        /**
         * The ARM's `.cctor` starts this at 102 (on screen), not the C#'s 100, so the first
         * frame of the first game hides the digits and deactivates the exciter.
         */
        @JvmField var stateCurrent = stateOnscreen
        @JvmField var streakCount = 0

        override fun reset() {
            stateCurrent = stateOnscreen
            streakCount = 0
        }
    }
}
