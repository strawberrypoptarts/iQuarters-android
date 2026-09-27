package com.guille.spring.iquarters.scripts

import com.guille.spring.iquarters.Behaviour
import com.guille.spring.iquarters.GObj
import com.guille.spring.iquarters.Mathf
import com.guille.spring.iquarters.Quat
import com.guille.spring.iquarters.V3

/**
 * `MainCameraScript`: the game camera eases between its two shot-angle poses, then turns
 * toward the coin (lifted by [yOffset]) at [damping] per second. Checked against the ARM
 * in phase 2; the iPad field-of-view change is dropped.
 */
class MainCameraScript : Behaviour() {
    private var target: GObj? = null
    private var damping = 2f
    private var smooth = true
    private var yOffset = 0f
    private var currentT = 0f
    private var startPosition = V3.ZERO

    override fun onBind() {
        target = obj("target")
        damping = num("damping", 2f)
        smooth = bool("smooth", true)
        yOffset = num("yOffset", 0f)
    }

    override fun start() {
        startPosition = transform.position
    }

    override fun lateUpdate() {
        val target = target ?: return
        val targetT = (GameManagerScript.shotAngle - GameManagerScript.defaultShotAngle) /
            (QuarterTrigger.maxShotAngle - GameManagerScript.defaultShotAngle)
        currentT += (targetT - currentT) * 0.2f
        // Mathf.Lerp clamps t, so the poses stop at the ends of the range.
        transform.position = startPosition + V3(0f, Mathf.lerp(-0.007f, 0.007f, currentT), Mathf.lerp(-0.05f, 0.05f, currentT))
        if (!smooth) { transform.lookAt(target); return }
        val d = target.position - transform.position
        val dir = V3(d.x, maxOf(d.y + yOffset, -5f), d.z)
        val blended = Quat.slerp(transform.rotation, Quat.lookRotation(dir), deltaTime * damping)
        // Slerp can introduce roll even between upright poses. Rebuild with world up.
        transform.rotation = Quat.lookRotation(blended.rotate(V3(0f, 0f, 1f)))
    }
}
