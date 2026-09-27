package com.guille.spring.iquarters.scripts

import com.guille.spring.iquarters.Behaviour
import com.guille.spring.iquarters.GObj
import com.guille.spring.iquarters.Mathf
import com.guille.spring.iquarters.Quat
import com.guille.spring.iquarters.V3

/**
 * `IntroCamScript`: Unity's "Smooth Follow" on the intro camera. It sits [distance] behind
 * [target] along the target's heading and [height] above it, easing both the heading and the
 * height, and looks at the target.
 */
class IntroCamScript : Behaviour() {
    var target: GObj? = null
    var distance = 10f
    var height = 5f
    var heightDamping = 2f
    var rotationDamping = 3f

    override fun onBind() {
        target = obj("target")
        distance = num("distance", 10f)
        height = num("height", 5f)
        heightDamping = num("heightDamping", 2f)
        rotationDamping = num("rotationDamping", 3f)
    }

    override fun lateUpdate() {
        val t = target ?: return
        val wantedRotationAngle = t.eulerAngles.y
        val wantedHeight = t.position.y + height
        var currentRotationAngle = transform.eulerAngles.y
        var currentHeight = transform.position.y
        currentRotationAngle = Mathf.lerpAngle(currentRotationAngle, wantedRotationAngle, rotationDamping * deltaTime)
        currentHeight = Mathf.lerp(currentHeight, wantedHeight, heightDamping * deltaTime)
        val currentRotation = Quat.euler(V3(0f, currentRotationAngle, 0f))
        transform.position = t.position
        transform.position = transform.position - currentRotation.rotate(V3.FORWARD) * distance
        transform.position = transform.position.copy(y = currentHeight)
        transform.lookAt(t)
    }
}
