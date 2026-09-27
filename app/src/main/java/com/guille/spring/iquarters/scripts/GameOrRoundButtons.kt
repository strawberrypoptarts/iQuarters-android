package com.guille.spring.iquarters.scripts

import com.guille.spring.iquarters.Behaviour
import com.guille.spring.iquarters.GObj
import com.guille.spring.iquarters.IqStatic

/**
 * `GameOrRoundButtons`: the Game / Round tab pair above the high-score boards on
 * `ui_button_high_round`. [gameButtonSelected] says which table is showing; [mainmenu],
 * [HiScoreScript] and [InGameHiScore] read it. The selected tab wears its "on" texture.
 */
class GameOrRoundButtons : Behaviour() {
    var gameButtonTextureOff = -1
    var gameButtonTextureOn = -1
    var roundButtonTextureOff = -1
    var roundButtonTextureOn = -1

    private var roundOrGameButtonsGame: GObj? = null
    private var roundOrGameButtonsRound: GObj? = null

    private val stateIdle = 100
    private val stateSlidingIn = 101
    private val stateOnScreen = 102
    private val stateGameInRoundOut = 103
    private val stateGameOutRoundIn = 104
    private val stateSlidingOut = 105
    /** The ARM `.ctor` stores 100 (`stateIdle`); the decompiled C# has 0. */
    private var state = 100

    override fun onBind() {
        gameButtonTextureOff = texture("gameButtonTextureOff")
        gameButtonTextureOn = texture("gameButtonTextureOn")
        roundButtonTextureOff = texture("roundButtonTextureOff")
        roundButtonTextureOn = texture("roundButtonTextureOn")
    }

    override fun start() {
        roundOrGameButtonsGame = find("/ui_button_high_round/button_highscore")
        roundOrGameButtonsRound = find("/ui_button_high_round/button_roundhigh")
        val shader = "iPhone/Transparent/Vertex Color"
        roundOrGameButtonsGame!!.renderer!!.material.shader = shader
        roundOrGameButtonsRound!!.renderer!!.material.shader = shader
        val anim = animation!!
        if (anim.getClipCount() == 1) {
            anim.addClip(anim.clip, "SlideIn", 0, 16)
            anim.addClip(anim.clip, "GameClick", 30, 34)
            anim.addClip(anim.clip, "RoundClick", 45, 49)
            anim.addClip(anim.clip, "SlideOut", 70, 83)
        }
        TriggerIn = false
        TriggerOut = false
        TriggerButtonGame = false
        TriggerButtonRound = false
        gameButtonSelected = true
        state = stateIdle
    }

    override fun update() {
        val anim = animation!!
        if (TriggerIn) {
            anim.play("SlideIn"); TriggerIn = false; gameButtonSelected = true
            HandleButtonTextures(); state = stateSlidingIn
        } else if (TriggerOut) {
            anim.play("SlideOut"); TriggerOut = false; state = stateSlidingOut
        } else if (TriggerButtonGame) {
            val wasSelected = gameButtonSelected
            gameButtonSelected = true; HandleButtonTextures()
            if (!wasSelected) state = stateGameInRoundOut
            TriggerButtonGame = false
        } else if (TriggerButtonRound) {
            val wasSelected = gameButtonSelected
            gameButtonSelected = false; HandleButtonTextures()
            if (wasSelected) state = stateGameOutRoundIn
            TriggerButtonRound = false
        } else if (state == stateSlidingIn && !anim.isPlaying("SlideIn")) {
            state = stateOnScreen
        } else if ((state == stateGameInRoundOut || state == stateGameOutRoundIn) && !RoundHighScreen.IsScreenSliding) {
            state = stateOnScreen
        } else if (state == stateSlidingOut && !anim.isPlaying("SlideOut")) {
            state = stateIdle
        }
    }

    fun HandleButtonTextures() {
        val game = roundOrGameButtonsGame!!.renderer!!.material
        val round = roundOrGameButtonsRound!!.renderer!!.material
        if (gameButtonSelected) {
            game.mainTex = gameButtonTextureOn
            round.mainTex = roundButtonTextureOff
        } else {
            game.mainTex = gameButtonTextureOff
            round.mainTex = roundButtonTextureOn
        }
    }

    companion object : IqStatic {
        @JvmField var TriggerIn = false
        @JvmField var TriggerOut = false
        @JvmField var TriggerButtonGame = false
        @JvmField var TriggerButtonRound = false
        @JvmField var gameButtonSelected = true

        override fun reset() {
            TriggerIn = false
            TriggerOut = false
            TriggerButtonGame = false
            TriggerButtonRound = false
            gameButtonSelected = true
        }
    }
}
