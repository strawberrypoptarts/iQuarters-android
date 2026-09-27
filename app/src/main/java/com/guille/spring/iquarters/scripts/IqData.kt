package com.guille.spring.iquarters.scripts

import com.guille.spring.iquarters.Col
import com.guille.spring.iquarters.GObj

/**
 * The game's plain data classes. Initial values are the ARM `.ctor`'s, which agree with the
 * decompiled source for these four.
 */
class PlayerInfoClass {
    @JvmField var nameEnteredFlag = false
    @JvmField var playerName = "plyr"
    @JvmField var score = 0
    @JvmField var roundScore = 0
    @JvmField var shotsLeft = 40
    @JvmField var streak = 0
    @JvmField var maxStreak = 0
    @JvmField var roundStreak = 0
    @JvmField var playedThisRound = false
    @JvmField var gameOver = false
    @JvmField var inputType = 1
    @JvmField var maxRicochet = 0
}

/** A high-score table row (`Entry`). */
class Entry(@JvmField var score: Float = 0f, @JvmField var name: String = "")

class ColliderInfoClass {
    @JvmField var m_Collider: Col? = null
    @JvmField var m_time = 0f
}

class ColliderGameObjectClass {
    @JvmField var m_GameObject: GObj? = null
    @JvmField var m_time = 0f
}
