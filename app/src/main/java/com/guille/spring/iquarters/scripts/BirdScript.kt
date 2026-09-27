package com.guille.spring.iquarters.scripts

import com.guille.spring.iquarters.Anim
import com.guille.spring.iquarters.Behaviour
import com.guille.spring.iquarters.IqStatic

/**
 * `BirdScript`: the looping table obstacles (the drinking bird, the lazy susan, the
 * pendulum, the helicopter, the biplane). Their animations play on their own; this script
 * only makes a replay see them where the shot did, driven by `QuarterTrigger.birdAnimState`,
 * which `QuarterTrigger` raises five fixed steps before a launch:
 *
 * - **1** (a live shot): read every state's time, speed and normalized speed into the
 *   statics, hand them to `ReplayController.SaveAnimationData`, and drop the flag to 0.
 * - **2** (a replay): take the saved values (from the Hall of Fame slot being replayed, if
 *   it is one), write them into every state, and move the flag on to 3.
 * - **3**: copy the states into the debug `l*` fields, and drop the flag to 0.
 *
 * That is the ARM (`BirdScript::FixedUpdate`), not the decompiled source, which misses all
 * three flag writes and so has 1 and 2 run on every step (which would freeze the obstacle
 * through a replay) and has 3 rewind the states instead. Because the flag is dropped by the
 * first `BirdScript` to run, only one obstacle is ever captured; the round only ever has one
 * out.
 */
class BirdScript : Behaviour() {
    var ltime = 0f
    var lspeed = 0f
    var lnormalizedSpeed = 0f
    var lweight = 0f
    var lwrapMode = 0
    var lntime = 0f
    var llength = 0f
    var llayer = 0
    var lclip = 0f
    var lblendMode = 0

    override fun onBind() {
        ltime = num("ltime", 0f)
        lspeed = num("lspeed", 0f)
        lnormalizedSpeed = num("lnormalizedSpeed", 0f)
        lweight = num("lweight", 0f)
        lwrapMode = int("lwrapMode", 0)
        lntime = num("lntime", 0f)
        llength = num("llength", 0f)
        llayer = int("llayer", 0)
        lclip = num("lclip", 0f)
        lblendMode = int("lblendMode", 0)
    }

    override fun start() {
        anim = gameObject.anim
    }

    override fun onDisable() {
        anim = null
    }

    /** `foreach (AnimationState s in animation)`: every state, in the component's order. */
    private fun states(): List<Anim.State> {
        val a = animation!!
        val src = gameObject.src.animation ?: return emptyList()
        val clips = world.pack.clips
        val names = LinkedHashSet<String>()
        for (ci in src.clips) if (ci >= 0) names += clips[ci].name
        if (src.clip >= 0) names += clips[src.clip].name
        return names.mapNotNull { a[it] }
    }

    override fun fixedUpdate() {
        when (QuarterTrigger.birdAnimState) {
            1 -> {
                for (s in states()) {
                    BirdScript.time = s.time
                    BirdScript.speed = s.speed
                    BirdScript.normalizedSpeed = s.normalizedSpeed()
                    ReplayController.SaveAnimationData(0, BirdScript.time, BirdScript.speed, BirdScript.normalizedSpeed)
                }
                QuarterTrigger.birdAnimState = 0
            }
            2 -> {
                if (ReplayController.hallOfFameReplay) {
                    val index = ReplayController.replayDataCurIndex
                    BirdScript.time = ReplayController.storeTime!![index]
                    BirdScript.speed = ReplayController.storeSpeed!![index]
                    BirdScript.normalizedSpeed = ReplayController.storenormalizedSpeed!![index]
                }
                for (s in states()) {
                    s.time = BirdScript.time
                    s.speed = BirdScript.speed
                    s.setNormalizedSpeed(BirdScript.normalizedSpeed)
                }
                QuarterTrigger.birdAnimState = 3
            }
            3 -> {
                for (s in states()) {
                    ltime = s.time
                    lspeed = s.speed
                    // The ARM reads normalizedSpeed here into a local, never into lnormalizedSpeed.
                    lweight = s.weight
                    lwrapMode = s.wrapMode
                    lntime = s.normalizedTime
                    llength = s.length
                    llayer = 0 // The engine has no layers; every state is on layer 0.
                    lblendMode = 0 // AnimationBlendMode.Blend; the engine has no additive states.
                }
                QuarterTrigger.birdAnimState = 0
            }
        }
    }

    companion object : IqStatic {
        /** Shadows Unity's `Time.time` inside the class, as the C# static does. */
        var time = 0f
        var speed = 0f
        var normalizedSpeed = 0f
        var anim: Anim? = null

        fun AnimationPresent(): Boolean = false
        fun RewindAnim() {}
        fun ChecktoRewindAnim() {}
        fun SaveAnimationData() {}
        fun LoadAnimationData() {}

        /** `AnimationState.normalizedSpeed`: speed per clip length. */
        private fun Anim.State.normalizedSpeed() = if (length > 0f) speed / length else 0f
        private fun Anim.State.setNormalizedSpeed(v: Float) { speed = v * length }

        override fun reset() {
            time = 0f
            speed = 0f
            normalizedSpeed = 0f
            anim = null
        }
    }
}
