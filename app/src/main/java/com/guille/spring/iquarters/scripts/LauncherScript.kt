package com.guille.spring.iquarters.scripts

import com.guille.spring.iquarters.Behaviour
import com.guille.spring.iquarters.GObj
import com.guille.spring.iquarters.IqCollision
import com.guille.spring.iquarters.RigidbodyComp
import com.guille.spring.iquarters.V3

/**
 * `LauncherScript`: the catapult obstacles (the cellphones, the hula girl, the bobblehead,
 * the ballista). A coin landing on top — a contact normal pointing down into the launcher by
 * more than [contactAngle] — is thrown at ([xVel], [yVel], [zVel]); the launcher plays its
 * animation and its sound, and the shot clock gets [shotTimeAdd] more seconds. When the
 * animation ends the hinge is set back flat.
 */
class LauncherScript : Behaviour() {
    private var curBody: RigidbodyComp? = null
    var xVel = -2.5f
    var yVel = 14f
    var zVel = 2.5f
    var hingeObject: GObj? = null
    var contactAngle = 0.707f
    var shotTimeAdd = 2f
    private val stateIdle = 100
    private val stateTriggered = 101
    /** 100 (`stateIdle`) in the ARM `.ctor`; the decompiled source leaves it 0. `Start` sets it anyway. */
    private var state = 100

    override fun onBind() {
        xVel = num("xVel", -2.5f)
        yVel = num("yVel", 14f)
        zVel = num("zVel", 2.5f)
        hingeObject = obj("hingeObject")
        contactAngle = num("contactAngle", 0.707f)
        shotTimeAdd = num("shotTimeAdd", 2f)
    }

    override fun onCollisionEnter(c: IqCollision) {
        for (contact in c.contacts) {
            if (contact.normal.y >= -contactAngle) continue
            val body = contact.otherCollider.attachedRigidbody!!
            curBody = body
            // The ARM sets the three components one get/set at a time; the result is the same.
            body.velocity = V3(xVel, yVel, zVel)
            animation!!.play()
            if (audio != null && !QuarterTrigger.muteF) audio!!.play()
            QuarterTrigger.shotTime += shotTimeAdd
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
