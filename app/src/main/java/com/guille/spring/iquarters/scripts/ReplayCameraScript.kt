package com.guille.spring.iquarters.scripts

import com.guille.spring.iquarters.Behaviour
import com.guille.spring.iquarters.GObj
import com.guille.spring.iquarters.IqStatic
import com.guille.spring.iquarters.Quat

/**
 * `ReplayCameraScript`: Unity's "Smooth Look At", the same code as [spotdirScript] with its
 * own static [replayCamGameObject]. No object in either scene carries it.
 */
class ReplayCameraScript : Behaviour() {
    var target: GObj? = null
    var damping = 2f
    var smooth = true

    override fun onBind() {
        target = obj("target")
        damping = num("damping", 2f)
        smooth = bool("smooth", true)
    }

    override fun lateUpdate() {
        val t = target ?: return
        if (!smooth) {
            transform.lookAt(t)
            return
        }
        val targetRotation = Quat.lookRotation(t.position - transform.position)
        transform.rotation = Quat.slerp(transform.rotation, targetRotation, deltaTime * damping)
    }

    override fun start() {
        replayCamGameObject = gameObject
        rigidbody?.freezeRotation = true
    }

    companion object : IqStatic {
        @JvmStatic var replayCamGameObject: GObj? = null

        override fun reset() { replayCamGameObject = null }
    }
}
