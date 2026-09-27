package com.guille.spring.iquarters.scripts

import com.guille.spring.iquarters.Behaviour
import com.guille.spring.iquarters.GObj
import com.guille.spring.iquarters.IqStatic
import com.guille.spring.iquarters.Quat

/**
 * `spotdirScript`: Unity's "Smooth Look At" on the level's Spotlight. It turns toward
 * [target] (unassigned in the scene, so it does nothing) and, in `Start`, claims the static
 * [replayCamGameObject].
 */
class spotdirScript : Behaviour() {
    var target: GObj? = null
    /** 2 in the ARM `.ctor`; the decompiled source leaves it 0. */
    var damping = 2f
    var smooth = false

    override fun onBind() {
        target = obj("target")
        damping = num("damping", 2f)
        smooth = bool("smooth", false)
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
