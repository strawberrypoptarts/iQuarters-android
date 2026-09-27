package com.guille.spring.iquarters.scripts

import com.guille.spring.iquarters.Behaviour
import com.guille.spring.iquarters.GObj
import com.guille.spring.iquarters.Iq
import com.guille.spring.iquarters.IqStatic
import com.guille.spring.iquarters.Rect
import com.guille.spring.iquarters.Rend

/**
 * `CoinsLeft`: the end-of-game stack of leftover coins. [TriggerAnim] plays `CoinsLeft<N>`
 * (faster above 11 coins) with the coins past N hidden, counting up the two-digit tally as
 * it goes; then the bonus (coins x [bonusMultiplier]) slides in, holds 2s and slides out.
 */
class CoinsLeft : Behaviour() {
    var coinsLeftBonusObect: GObj? = null
    var coinsLeftBarGraphic: GObj? = null
    var stackingSFX = -1
    var scoreSFX = -1
    var UISkin: String? = null
    var coinArray: Array<GObj?> = emptyArray()
    var clDigitsRendererArray: List<Rend?> = emptyList()
    var clDigitsMaterialArray = IntArray(0)
    var clDigitsTextureArray = IntArray(0)

    override fun onBind() {
        coinsLeftBonusObect = obj("coinsLeftBonusObect")
        coinsLeftBarGraphic = obj("coinsLeftBarGraphic")
        stackingSFX = audioClip("stackingSFX")
        scoreSFX = audioClip("scoreSFX")
        UISkin = skin("UISkin")
        coinArray = objs("coinArray").toTypedArray()
        clDigitsRendererArray = objs("clDigitsRendererArray").map { it?.renderer }
        clDigitsMaterialArray = materials("clDigitsMaterialArray")
        clDigitsTextureArray = textures("clDigitsTextureArray")
    }

    /**
     * Traced from the AOT ARM (`CoinsLeft.Start`, 0x264bcc-0x264ebc): [screenRect] is
     * `Rect(coinsLeftTextX, coinsLeftTextY, 80, 30)` (0x264c50-0x264cf4), i.e. (150, 385, 80, 30)
     * with the `.cctor`'s values; a literal scan reads x and y as 0 because they are statics.
     * `Update` then only rewrites its y (0x2652fc). Nothing draws it: `OnGUI` (0x2659bc) only
     * sets `GUI.skin` and refreshes the tally digits.
     */
    override fun start() {
        coinsLeftBarGraphicStartY = coinsLeftBarGraphic!!.localPosition.y
        screenRect = Rect(coinsLeftTextX.toFloat(), coinsLeftTextY.toFloat(), 80f, 30f)
        numCoinsDisplay = 0
        maxCoinsDisplay = 0
        coinArray = arrayOfNulls(maxCoinsLeft + 1)
        for (i in 1..maxCoinsLeft) {
            val name = "quarter_" + (if (i < 11) "0" else "") + (i - 1)
            coinArray[i] = find(name)
        }
    }

    override fun update() {
        val anim = animation!!
        if (clStateCurrent == clStateTrigger) {
            val clipName = "CoinsLeft$numCoinsLeft"
            var speed = 1f
            if (numCoinsLeft > 11) speed += (numCoinsLeft - 12) * 1.5f / (maxCoinsLeft - 12)
            anim[clipName]!!.speed = speed
            anim.play(clipName)
            for (i in numCoinsLeft + 1..maxCoinsLeft) coinArray[i]!!.active = false
            find("/ui_stack/coin_amount/hs_01")!!.renderer!!.enabled = false
            if (!QuarterTrigger.muteF) { audio!!.clip = stackingSFX; audio!!.play() }
            clStateCurrent = clStatePlaying
        } else if (clStateCurrent == clStatePlaying) {
            val barPosition = coinsLeftBarGraphic!!.localPosition
            screenRect = screenRect.copy(y = (coinsLeftTextY - ((barPosition.y - coinsLeftBarGraphicStartY) * scaleFactorY).toInt()).toFloat())
            val st = anim["CoinsLeft$numCoinsLeft"]!!
            numCoinsDisplay = ((st.time / st.length) * numCoinsLeft + 1f).toInt()
            if (numCoinsDisplay > maxCoinsDisplay) {
                maxCoinsDisplay = numCoinsDisplay + 1
                if (maxCoinsDisplay > numCoinsLeft) { audio!!.stop(); maxCoinsDisplay = numCoinsLeft }
            }
            if (!anim.isPlaying) {
                HandleCoinsLeftTextures(maxCoinsDisplay)
                coinsLeftBonusObect!!.setActiveRecursively(true)
                HandleTextures(maxCoinsDisplay * bonusMultiplier)
                coinsLeftBonusObect!!.anim!!.play("SlideIn")
                if (!QuarterTrigger.muteF) { audio!!.clip = scoreSFX; audio!!.play() }
                clStateCurrent = clStateBonusSlideIn
            }
        } else if (clStateCurrent == clStateBonusSlideIn && !coinsLeftBonusObect!!.anim!!.isPlaying) {
            bonusOnScreenStartTime = time
            clStateCurrent = clStateBonusOnScreen
        } else if (clStateCurrent == clStateBonusOnScreen && time > bonusOnScreenStartTime + 2f) {
            coinsLeftBonusObect!!.anim!!.play("SlideOut")
            clStateCurrent = clStateBonusSlideOut
        } else if (clStateCurrent == clStateBonusSlideOut && !coinsLeftBonusObect!!.anim!!.isPlaying) {
            DisableAllDigitRenders()
            coinsLeftBonusObect!!.setActiveRecursively(false)
            clStateCurrent = clStateIdle
        }
    }

    /** Draws no label: it only sets `GUI.skin` and refreshes the tally digits. */
    override fun onGUI() {
        if (clStateCurrent == clStatePlaying) HandleCoinsLeftTextures(maxCoinsDisplay)
    }

    /** `Material.mainTexture = t` on a shared material: every instance made from it changes. */
    private fun setSharedMainTex(material: Int, tex: Int) {
        if (material < 0) throw NullPointerException("clDigitsMaterialArray")
        val src = world.pack.materials[material]
        for (o in world.objects) o.renderer?.materials?.forEach { if (it.src === src) it.mainTex = tex }
    }

    /** Verbatim: the tens come from [numCoins], the ones from [maxCoinsDisplay]. */
    fun HandleCoinsLeftTextures(numCoins: Int) {
        val tens = numCoins / 10
        val tensObject = find("/ui_stack/coin_amount/hs_01")!!
        if (tens != 0) {
            tensObject.renderer!!.enabled = true
            tensObject.renderer!!.material.mainTex = clDigitsTextureArray[tens]
        }
        find("/ui_stack/coin_amount/hs_02")!!.renderer!!.material.mainTex = clDigitsTextureArray[maxCoinsDisplay % 10]
    }

    fun HandleTextures(number: Int) {
        if (number < 0 || number > 999) {
            Iq.log("stack bonus is out of range!    $number")
            return
        }
        val start: Int
        val count: Int
        if (number < 10) { start = 1; count = 1 }
        else if (number < 100) { start = 3; count = 2 }
        else { start = 0; count = 3 }
        DisableAllDigitRenders()
        var divisor = 1
        for (offset in 0 until count) {
            val r = start + offset
            clDigitsRendererArray[r]!!.enabled = true
            setSharedMainTex(clDigitsMaterialArray[r], clDigitsTextureArray[number / divisor % 10])
            divisor *= 10
        }
    }

    fun DisableAllDigitRenders() {
        for (i in 0 until 5) clDigitsRendererArray[i]!!.enabled = false
    }

    companion object : IqStatic {
        const val bonusMultiplier = 5
        const val maxCoinsLeft = 40
        const val clStateIdle = 100
        const val clStateTrigger = 101
        const val clStatePlaying = 102
        const val clStateBonusSlideIn = 103
        const val clStateBonusOnScreen = 104
        const val clStateBonusSlideOut = 105
        const val coinsLeftTextX = 150
        const val coinsLeftTextY = 385
        const val scaleFactorY = 79f

        @JvmField var numCoinsLeft = 0
        @JvmField var numCoinsDisplay = 0
        @JvmField var maxCoinsDisplay = 0

        /**
         * The ARM's `.cctor` starts this at 105 (bonus sliding out), not the C#'s 100, so the
         * first frame clears the bonus digits and deactivates the bonus object.
         */
        @JvmField var clStateCurrent = clStateBonusSlideOut
        @JvmField var coinsLeftBarGraphicStartY = 0f
        @JvmField var screenRect = Rect(0f, 0f, 0f, 0f)
        @JvmField var bonusOnScreenStartTime = 0f

        fun TriggerAnim(numCoins: Int) {
            numCoinsDisplay = 0
            maxCoinsDisplay = 0
            numCoinsLeft = numCoins
            if (numCoinsLeft in 1..maxCoinsLeft) clStateCurrent = clStateTrigger
        }

        fun IsDonePlaying() = clStateCurrent == clStateIdle

        override fun reset() {
            numCoinsLeft = 0
            numCoinsDisplay = 0
            maxCoinsDisplay = 0
            clStateCurrent = clStateBonusSlideOut
            coinsLeftBarGraphicStartY = 0f
            screenRect = Rect(0f, 0f, 0f, 0f)
            bonusOnScreenStartTime = 0f
        }
    }
}
