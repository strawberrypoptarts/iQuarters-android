package com.guille.spring.iquarters.scripts

import com.guille.spring.iquarters.Behaviour
import com.guille.spring.iquarters.GObj
import com.guille.spring.iquarters.IqStatic
import com.guille.spring.iquarters.MeshInst

/**
 * `lightray`: the beam of light that shoots up out of the glass on every made shot. At start both beam meshes'
 * vertex colours become white, alpha 0 where the green channel was lit and 1 elsewhere, and
 * the beam is parked 1000 up. [triggerLightRay] plays its animation at double speed from the
 * quarter's position, 0.12 below it; on round 11 it follows the lazy susan's glass; when the
 * animation ends it is parked and hidden again.
 *
 * [update] follows the AOT ARM (0x2379ac-0x237ff4), not the decompiled C#, which lost the
 * whole trigger branch but the `Play`: every state's speed set to 2.0 (literal at 0x237b38),
 * `transform.position = quarterObject.transform.position` (0x237bdc), then `y -= 0.12`
 * (literal at 0x237c10). Nor does the ARM re-activate [lightRayObject] there; QuarterTrigger
 * already did. Without the move the beam played parked at y 1000, off screen.
 */
class lightray : Behaviour() {
    var lightRayObject: GObj? = null
    var quarterObject: GObj? = null
    var lazySusanGlassObject: GObj? = null
    var thisFilter: GObj? = null
    var thisFilter2: GObj? = null

    private val stateIdle = 100
    private val statePlaying = 101
    private var curState = 100

    override fun onBind() {
        lightRayObject = obj("lightRayObject")
        quarterObject = obj("quarterObject")
        lazySusanGlassObject = obj("lazySusanGlassObject")
        thisFilter = obj("thisFilter")
        thisFilter2 = obj("thisFilter2")
    }

    /**
     * `TintMesh(MeshFilter)`; like Unity's empty `mesh.colors`, a mesh with none throws.
     *
     * The AOT ARM (0x237678-0x237864) starts each vertex at `Color.white` (0x237704) and
     * only sets its alpha. The decompiled C#'s `new Color(0, 0, 0, a)` is wrong: under
     * `Particles/Additive`, whose colour is `_TintColor * primary * texture`, black vertices
     * drew no beam at all.
     */
    fun TintMesh(curFilter: GObj?) {
        val mesh: MeshInst = curFilter!!.meshFilter!!
        val n = mesh.vertices.size / 3
        val original = mesh.colors ?: throw IndexOutOfBoundsException("mesh.colors is empty")
        val colors = ByteArray(n * 4)
        for (i in 0 until n) {
            val g = original[i * 4 + 1].toInt() and 0xff
            colors[i * 4] = 0xff.toByte()
            colors[i * 4 + 1] = 0xff.toByte()
            colors[i * 4 + 2] = 0xff.toByte()
            colors[i * 4 + 3] = if (g > 0) 0 else 0xff.toByte()
        }
        mesh.colors = colors
    }

    fun PositionOffScreen() {
        transform.position = transform.position.copy(y = 1000f)
    }

    override fun start() {
        TintMesh(thisFilter)
        TintMesh(thisFilter2)
        PositionOffScreen()
    }

    override fun update() {
        if (triggerLightRay) {
            for (s in animation!!.allStates) s.speed = 2f
            val q = quarterObject!!.position
            transform.position = q.copy(y = q.y - 0.12f)
            animation!!.play()
            curState = statePlaying
            triggerLightRay = false
        }
        if (curState != statePlaying) return
        if (GameManagerScript.curRound == 11) {
            val g = lazySusanGlassObject!!.position
            transform.position = transform.position.copy(x = g.x, z = g.z)
        }
        if (!animation!!.isPlaying) {
            PositionOffScreen()
            lightRayObject!!.setActiveRecursively(false)
            curState = stateIdle
        }
    }

    companion object : IqStatic {
        @JvmField var triggerLightRay = false

        override fun reset() {
            triggerLightRay = false
        }
    }
}
