package com.guille.spring.iquarters.scripts

import com.guille.spring.iquarters.Behaviour
import com.guille.spring.iquarters.GObj
import com.guille.spring.iquarters.IqStatic
import com.guille.spring.iquarters.Mathf
import com.guille.spring.iquarters.MeshInst
import com.guille.spring.iquarters.Rend
import kotlin.math.abs

/**
 * `CoinHolder`: the in-game coin holder that slides in from the right with the score (four
 * digits) and coins left (two), each digit a quad whose UVs are shifted along a 1024-wide
 * strip of 64-wide digits. It shows the quarters made this round, flies a new one in
 * (`FlyIn1..3`, cut from the fly-in object's clip), and slides the "coins left" bar out and
 * back. The iPad placement and its fields are dropped.
 */
class CoinHolder : Behaviour() {
    var UISkin: String? = null

    private val stateOffScreen = 10
    private val stateSlidingOn = 20
    private val stateOnScreen = 30
    private val stateSlidingOff = 40
    private var currentHolderState = 10
    private var holderOffScreenX = 0f
    /** The ARM's `.ctor` sets -35; the C# leaves it 0 (which would never slide). */
    private val holderOnScreenDeltaX = -35f
    private val holderSlideTime = 0.3f

    private val clStateOffScreen = 100
    private val clStateSlidingOn = 200
    private val clStateOnScreen = 300
    private val clStateSlidingOff = 400
    private var currentCoinsLeftState = 100
    private val coinsLeftOffScreenX = 0.363887f
    private val coinsLeftOnScreenDeltaX = -0.6f
    private val coinsLeftSlideTime = 0.5f
    private val coinsLeftOnScreenTime = 0.5f

    private var coinsLeftBarObject: GObj? = null
    private var quarterFlyInObject: GObj? = null
    private var quarterFlyInCheckAnim = false
    private var quarterFlyInRenderer: Rend? = null
    private var quarterFlyInAnimName = ""
    private var waitTime = 0f

    /** Each digit quad's mesh with its original UVs (Unity's `mesh.uv` returns a copy). */
    private class Digit(val mesh: MeshInst, val uv: FloatArray)

    private var scoreDigits: Array<Digit> = emptyArray()
    private var coinDigits: Array<Digit> = emptyArray()

    override fun onBind() {
        UISkin = skin("UISkin")
    }

    override fun start() {
        triggerCoinHolderIn = -1
        triggerCoinHolderOut = -1
        triggerCoinHolderOutAll = false
        currentHolderState = stateOffScreen
        holderOffScreenX = transform.localPosition.x
        coinsLeftBarObject = find("/ui_ingame_3coin_hold/coin_holder_top/coins_left")
        val fly = find("/ui_ingame_3coin_root/ui_ingame_3coin")!!
        quarterFlyInObject = fly
        val anim = fly.anim!!
        anim.addClip(anim.clip, "FlyIn1", 0, 20)
        anim.addClip(anim.clip, "FlyIn2", 40, 60)
        anim.addClip(anim.clip, "FlyIn3", 80, 100)
        for (i in 0 until 3)
            find("/ui_ingame_3coin_root/ui_ingame_3coin/quarter_card_0" + (i + 1))!!.renderer!!.enabled = false
        quarterFlyInCheckAnim = false
        quarterFlyInRenderer = null
        quarterFlyInAnimName = ""
        currentCoinsLeftState = clStateOffScreen
        coinTriggered = false
        widthU = digitTextureWidth.toFloat() / mainTextureWidth
        scoreDigits = Array(4) { LoadMesh("/ui_ingame_3coin_hold/coin_holder_base/score_0" + (it + 1)) }
        coinDigits = Array(2) { LoadMesh("/ui_ingame_3coin_hold/coin_holder_base/coins_0" + (it + 1)) }
    }

    fun HandleTextures(scoreIn: Int, coinsLeftIn: Int) {
        val score = scoreIn.coerceIn(0, 9999)
        val coinsLeft = coinsLeftIn.coerceIn(0, 99)
        var divisor = 1000
        for (place in 0 until 4) {
            val digit = score / divisor % 10
            val r = find("/ui_ingame_3coin_hold/coin_holder_base/score_0" + (place + 1))!!.renderer!!
            r.enabled = place == 3 || score >= divisor
            if (r.enabled) DisplayDigit(scoreDigits[place].mesh, scoreDigits[place].uv, digit)
            divisor /= 10
        }
        for (place in 0 until 2) {
            val div = if (place == 0) 10 else 1
            val r = find("/ui_ingame_3coin_hold/coin_holder_base/coins_0" + (place + 1))!!.renderer!!
            r.enabled = place == 1 || coinsLeft >= 10
            if (r.enabled) DisplayDigit(coinDigits[place].mesh, coinDigits[place].uv, coinsLeft / div % 10)
        }
    }

    fun DisplayQuarters(numQuarters: Int) {
        for (i in 0 until 3)
            find("/ui_ingame_3coin_hold/quarter_card_0" + (i + 1))?.renderer?.enabled = i < numQuarters
    }

    fun QuarterFlyIn(quarterIndex: Int) {
        if (quarterIndex < 0 || quarterIndex >= 3) return
        val quarter = find("/ui_ingame_3coin_root/ui_ingame_3coin/quarter_card_0" + (quarterIndex + 1))
        quarterFlyInRenderer = null
        if (quarter != null) {
            quarterFlyInRenderer = quarter.renderer!!
            quarterFlyInRenderer!!.enabled = true
        }
        quarterFlyInAnimName = "FlyIn" + (quarterIndex + 1)
        quarterFlyInObject!!.anim!!.play(quarterFlyInAnimName)
        quarterFlyInCheckAnim = true
    }

    override fun update() {
        if (triggerCoinHolderIn >= 0) {
            HandleTextures(GameManagerScript.GetCurrentScore(), GameManagerScript.GetCurrentShotsLeft())
            DisplayQuarters(triggerCoinHolderIn)
            triggerCoinHolderIn = -1
            currentHolderState = stateSlidingOn
        } else if (triggerCoinHolderOut >= 0 || triggerCoinHolderOutAll) {
            quarterFlyInRenderer?.let { it.enabled = false; quarterFlyInRenderer = null }
            triggerCoinHolderOut = -1
            triggerCoinHolderOutAll = false
            currentHolderState = stateSlidingOff
        } else if (triggerCoin01) { QuarterFlyIn(0); triggerCoin01 = false }
        else if (triggerCoin02) { QuarterFlyIn(1); triggerCoin02 = false }
        else if (triggerCoin03) { QuarterFlyIn(2); triggerCoin03 = false }
        else if (triggerCoinsLeftAnim) {
            triggerCoinsLeftAnim = false
            currentCoinsLeftState = clStateSlidingOn
        }

        if (quarterFlyInCheckAnim && !quarterFlyInObject!!.anim!!.isPlaying(quarterFlyInAnimName)) {
            quarterFlyInRenderer?.let { it.enabled = false; quarterFlyInRenderer = null }
            DisplayQuarters(GameManagerScript.curMadeShotsThisRound + if (QuarterTrigger.glassMultiplierTriggered) 1 else 0)
            quarterFlyInCheckAnim = false
        }

        var holderTarget = holderOffScreenX
        if (currentHolderState == stateSlidingOn || currentHolderState == stateOnScreen) holderTarget += holderOnScreenDeltaX
        if (currentHolderState == stateSlidingOn || currentHolderState == stateSlidingOff) {
            val p = transform.localPosition
            val x = Mathf.moveTowards(p.x, holderTarget, abs(holderOnScreenDeltaX) / holderSlideTime * deltaTime)
            transform.localPosition = p.copy(x = x)
            if (x == holderTarget) currentHolderState = if (currentHolderState == stateSlidingOn) stateOnScreen else stateOffScreen
        }

        var coinsTarget = coinsLeftOffScreenX
        if (currentCoinsLeftState == clStateSlidingOn || currentCoinsLeftState == clStateOnScreen) coinsTarget += coinsLeftOnScreenDeltaX
        if (currentCoinsLeftState == clStateSlidingOn || currentCoinsLeftState == clStateSlidingOff) {
            val bar = coinsLeftBarObject!!
            val p = bar.localPosition
            val x = Mathf.moveTowards(p.x, coinsTarget, abs(coinsLeftOnScreenDeltaX) / coinsLeftSlideTime * deltaTime)
            bar.localPosition = p.copy(x = x)
            if (x == coinsTarget) {
                currentCoinsLeftState = if (currentCoinsLeftState == clStateSlidingOn) clStateOnScreen else clStateOffScreen
                waitTime = time
            }
        } else if (currentCoinsLeftState == clStateOnScreen && time - waitTime > coinsLeftOnScreenTime) {
            currentCoinsLeftState = clStateSlidingOff
        }
    }

    private fun LoadMesh(path: String): Digit {
        val mesh = find(path)!!.meshFilter!!
        return Digit(mesh, mesh.uv!!.copyOf())
    }

    companion object : IqStatic {
        @JvmField var triggerCoinHolderIn = -1
        @JvmField var triggerCoinHolderOut = -1
        @JvmField var triggerCoin01 = false
        @JvmField var triggerCoin02 = false
        @JvmField var triggerCoin03 = false
        @JvmField var triggerCoin04 = false
        @JvmField var triggerCoinsLeftIn = false
        @JvmField var triggerCoinHolderOutAll = false
        @JvmField var triggerCoinsLeftAnim = false
        @JvmField var coinTriggered = false
        const val mainTextureWidth = 1024
        const val mainTextureHeight = 256
        const val digitTextureWidth = 64
        const val digitTextureHeight = 64
        @JvmField var widthU = 0f

        /**
         * Rewrites [mesh]'s UVs as [origUv] shifted right by [digit] digit widths. The C#
         * also clears and re-assigns the vertices and triangles unchanged; only the UVs matter.
         */
        fun DisplayDigit(mesh: MeshInst, origUv: FloatArray, digit: Int) {
            val uv = origUv.copyOf()
            for (i in uv.indices step 2) uv[i] += widthU * digit
            mesh.uv = uv
        }

        fun TriggerCoinIn(coin: Int) {
            when (coin) {
                0 -> triggerCoin01 = true
                1 -> triggerCoin02 = true
                2 -> triggerCoin03 = true
                3 -> triggerCoin04 = true
            }
            if (coin in 0..3) coinTriggered = true
        }

        override fun reset() {
            triggerCoinHolderIn = -1
            triggerCoinHolderOut = -1
            triggerCoin01 = false
            triggerCoin02 = false
            triggerCoin03 = false
            triggerCoin04 = false
            triggerCoinsLeftIn = false
            triggerCoinHolderOutAll = false
            triggerCoinsLeftAnim = false
            coinTriggered = false
            widthU = 0f
        }
    }
}
