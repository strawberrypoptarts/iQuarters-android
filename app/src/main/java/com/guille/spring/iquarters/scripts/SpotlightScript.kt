package com.guille.spring.iquarters.scripts

import com.guille.spring.iquarters.Behaviour
import com.guille.spring.iquarters.GObj
import com.guille.spring.iquarters.Quat

/** `SpotlightScript`: the spot tracks the coin, snapping unless [smooth]. */
class SpotlightScript : Behaviour() {
    private var target: GObj? = null
    private var damping = 2f
    private var smooth = false

    override fun onBind() {
        target = obj("target")
        damping = num("damping", 2f)
        smooth = bool("smooth", false)
    }

    override fun lateUpdate() {
        val t = target ?: return
        if (!smooth) transform.lookAt(t)
        else transform.rotation = Quat.slerp(transform.rotation, Quat.lookRotation(t.position - transform.position), deltaTime * damping)
    }
}
