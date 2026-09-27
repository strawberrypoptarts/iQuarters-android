package com.guille.spring.iquarters.scripts

import com.guille.spring.iquarters.Behaviour
import com.guille.spring.iquarters.GObj
import com.guille.spring.iquarters.IqCollision
import com.guille.spring.iquarters.RigidbodyComp
import com.guille.spring.iquarters.V3

/**
 * `LighterScript`: the lighters, [LauncherScript] with its contact angle (0.707) and its two
 * extra seconds of shot clock fixed in the code. It also keeps the last contact's normal y in
 * [contNorm], which nothing reads.
 */
class LighterScript : Behaviour() {
    var curBody: RigidbodyComp? = null
    var contNorm = 0f
    var xVel = 2.5f
    var yVel = 14f
    var zVel = 2.5f
    var hingeObject: GObj? = null
    private val stateIdle = 100
    private val stateTriggered = 101
    /** 100 (`stateIdle`) in the ARM `.ctor`; the decompiled source leaves it 0. `Start` sets it anyway. */
    private var state = 100

    override fun onBind() {
        contNorm = num("contNorm", 0f)
        xVel = num("xVel", 2.5f)
        yVel = num("yVel", 14f)
        zVel = num("zVel", 2.5f)
        hingeObject = obj("hingeObject")
    }

    override fun onCollisionEnter(c: IqCollision) {
        for (contact in c.contacts) {
            contNorm = contact.normal.y
            if (contact.normal.y >= -0.707f) continue
            val body = contact.otherCollider.attachedRigidbody!!
            curBody = body
            // The ARM sets the three components one get/set at a time; the result is the same.
            body.velocity = V3(xVel, yVel, zVel)
            animation!!.play()
            if (audio != null && !QuarterTrigger.muteF) audio!!.play()
            QuarterTrigger.shotTime += 2f
            state = stateTriggered
            return
        }
    }

    override fun start() {
        state = stateIdle
    }

    override fun update() {
        if (state == stateTriggered && !animation!!.isPlaying) {
            hingeObject?.let { h ->
                // `localEulerAngles = Vector3.zero` as the ARM does it: z, then y, then x,
                // each a separate get/set round trip.
                h.localEulerAngles = h.localEulerAngles.copy(z = 0f)
                h.localEulerAngles = h.localEulerAngles.copy(y = 0f)
                h.localEulerAngles = h.localEulerAngles.copy(x = 0f)
            }
            state = stateIdle
        }
    }
}
