package com.guille.spring.iquarters.scripts

import com.guille.spring.iquarters.Behaviour
import com.guille.spring.iquarters.IqStatic

/**
 * `UIPlayer`: the "Player N" banner. Each trigger plays `player<N>`, `player<N>in` or
 * `player<N>out` for the current player (1-based), one trigger per frame.
 */
class UIPlayer : Behaviour() {
    override fun update() {
        if (triggerPlayer1) {
            animation?.play(PlayerAnimationName(""))
            triggerPlayer1 = false
        } else if (triggerPlayer1In) {
            animation?.play(PlayerAnimationName("in"))
            triggerPlayer1In = false
        } else if (triggerPlayer1Out) {
            animation?.play(PlayerAnimationName("out"))
            triggerPlayer1Out = false
        }
    }

    companion object : IqStatic {
        @JvmField var triggerPlayer1 = false
        @JvmField var triggerPlayer1In = false
        @JvmField var triggerPlayer1Out = false

        private fun PlayerAnimationName(suffix: String) = "player" + (GameManagerScript.curPlayer + 1) + suffix

        override fun reset() {
            triggerPlayer1 = false
            triggerPlayer1In = false
            triggerPlayer1Out = false
        }
    }
}
