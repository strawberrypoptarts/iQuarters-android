package com.guille.spring.iquarters.scripts

import com.guille.spring.iquarters.Behaviour
import com.guille.spring.iquarters.GObj
import com.guille.spring.iquarters.IqStatic
import com.guille.spring.iquarters.V3

/**
 * `GlassScaleController`: the glass "pop" on a perfect round. [triggerGlassScale] finds the
 * renderer under [inThisGlassObject] (the glass the quarter landed in, set by
 * `QuarterTrigger`), plays this object's scale animation, and copies its x scale, damped to
 * three quarters, onto the glass's x and y until the animation ends.
 */
class GlassScaleController : Behaviour() {
    private var inThisGlassObjectCopy: GObj? = null
    private val stateIdle = 100
    private val stateScaleUp = 101
    @Suppress("unused") private val stateScaleDown = 102
    private var stateCurrent = 100
    private var originalScale = V3.ZERO
    @Suppress("unused") private val maxScale = 1.2f
    private var currentScale = 1f
    @Suppress("unused") private val scaleRate = 0.06f

    override fun start() {
        // `foreach (AnimationState s in animation)`: the engine does not enumerate states, so
        // this takes the default clip's, which is the only one this object carries.
        val anim = animation ?: return
        val s = anim.clip?.let { anim[it] } ?: return
        s.speed = s.length * 1.5f
    }

    override fun update() {
        if (triggerGlassScale) {
            val glass = inThisGlassObject
            if (glass != null && glass.tag != TAG_IGNORE_GLASS_EFFECT) {
                val childRenderer = glass.rendererInChildren()
                if (childRenderer != null) {
                    inThisGlassObjectCopy = childRenderer.go
                    originalScale = childRenderer.go.localScale
                    currentScale = 1f
                    stateCurrent = stateScaleUp
                    animation!!.play()
                }
            }
            triggerGlassScale = false
        }

        if (stateCurrent == stateScaleUp) {
            val copy = inThisGlassObjectCopy!!
            val factor = (transform.localScale.x - 1f) * 0.75f + 1f
            copy.localScale = copy.localScale.copy(x = originalScale.x * factor)
            copy.localScale = copy.localScale.copy(y = originalScale.y * factor)
            if (!animation!!.isPlaying) {
                copy.localScale = originalScale
                stateCurrent = stateIdle
            }
        }
    }

    companion object : IqStatic {
        /**
         * The tag "IgnoreGlassEffect" as the pack stores it. mainData's TagManager lists the
         * user tags "", "Water", "IgnoreGlassEffect" (user tags number from 20000), and
         * 20002 is on exactly `lazy_susan_00` and `beer_yard_00` in level0.
         */
        const val TAG_IGNORE_GLASS_EFFECT = 20002

        @JvmField var triggerGlassScale = false
        @JvmField var inThisGlassObject: GObj? = null

        override fun reset() {
            triggerGlassScale = false
            inThisGlassObject = null
        }
    }
}
