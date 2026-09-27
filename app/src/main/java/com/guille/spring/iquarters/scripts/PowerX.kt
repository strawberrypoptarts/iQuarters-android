package com.guille.spring.iquarters.scripts

import com.guille.spring.iquarters.Behaviour
import com.guille.spring.iquarters.GObj
import com.guille.spring.iquarters.IqRuntime
import com.guille.spring.iquarters.IqStatic
import kotlin.math.tan

/**
 * `PowerX`: the "2x/3x/4x" power-up burst over the quarter. A trigger picks the texture,
 * puts the object at the quarter's viewport position and plays its clip; while it plays the
 * shared `pUpMaterial` takes [startColor] with alpha from the scale controller's x scale - 1,
 * and when it ends the material goes clear and [PowerXObject] is deactivated.
 */
class PowerX : Behaviour() {
    var scaleX = 0f
    var scaleY = 0f
    var quarterObject: GObj? = null
    var PowerXObject: GObj? = null
    var scaleControllerObject: GObj? = null
    var pUpMaterial = -1
    var power1UpTexture = -1
    var power2UpTexture = -1
    var power3UpTexture = -1
    var power4UpTexture = -1
    var startColor = floatArrayOf(0f, 0f, 0f, 0f)
    var startPositionX = 0.5f
    var startPositionY = 0.5f

    private val puStateIdle = 100
    private val puStateInit = 101
    private val puStateAnimating = 102
    private var puStateCurrent = 100

    override fun onBind() {
        scaleX = num("scaleX", 0f)
        scaleY = num("scaleY", 0f)
        quarterObject = obj("quarterObject")
        PowerXObject = obj("PowerXObject")
        scaleControllerObject = obj("scaleControllerObject")
        pUpMaterial = material("pUpMaterial")
        power1UpTexture = texture("power1UpTexture")
        power2UpTexture = texture("power2UpTexture")
        power3UpTexture = texture("power3UpTexture")
        power4UpTexture = texture("power4UpTexture")
        // The engine reads a serialized vector as three floats; alpha is rewritten every frame.
        val c = vec("startColor")
        startColor = floatArrayOf(c.x, c.y, c.z, 0f)
        startPositionX = num("startPositionX", 0.5f)
        startPositionY = num("startPositionY", 0.5f)
    }

    /** Every material instance made from the shared `pUpMaterial`. */
    private inline fun eachPUp(block: (com.guille.spring.iquarters.MatInst) -> Unit) {
        if (pUpMaterial < 0) throw NullPointerException("pUpMaterial")
        val src = world.pack.materials[pUpMaterial]
        for (o in world.objects) o.renderer?.materials?.forEach { if (it.src === src) block(it) }
    }

    private fun setColor(c: FloatArray) = eachPUp { it.color = c.copyOf() }

    override fun start() {
        setColor(CLEAR)
    }

    /**
     * `Camera.allCameras[0].WorldToViewportPoint(quarter)`. The engine has no such call, so
     * it is Unity's projection written out: the camera's rigid view (scale ignored, looking
     * down local +z), the vertical fov and the 320x480 screen through the camera's viewport rect.
     */
    fun GetStartPosition() {
        startPositionX = 0.5f
        startPositionY = 0.5f
        val cams = allCameras()
        if (cams.isNotEmpty()) {
            val camObj = cams[0]
            val cam = camObj.camera!!
            val p = camObj.rotation.conjugate().rotate(quarterObject!!.position - camObj.position)
            val vp = cam.src.viewport
            val aspect = if (vp.size >= 4 && vp[3] != 0f)
                (IqRuntime.SCREEN_W * vp[2]) / (IqRuntime.SCREEN_H * vp[3]) else IqRuntime.SCREEN_W / IqRuntime.SCREEN_H
            val halfH = if (cam.src.orthographic) cam.src.orthoSize
            else p.z * tan(Math.toRadians(cam.fieldOfView / 2.0).toFloat())
            if (halfH != 0f) {
                startPositionX = 0.5f + 0.5f * p.x / (halfH * aspect)
                startPositionY = 0.5f + 0.5f * p.y / halfH
            }
        }
    }

    fun SetTexture(idx: Int) {
        val tex = when (idx) {
            2 -> power2UpTexture
            3 -> power3UpTexture
            4 -> power4UpTexture
            else -> power1UpTexture
        }
        eachPUp { it.mainTex = tex }
    }

    override fun update() {
        if (triggerPowerUp) {
            GetStartPosition()
            SetTexture(pUpIndex)
            setColor(CLEAR)
            animation?.play()
            puStateCurrent = puStateInit
            triggerPowerUp = false
            return
        }
        if (puStateCurrent == puStateInit) {
            transform.position = transform.position.copy(x = startPositionX, y = startPositionY)
            puStateCurrent = puStateAnimating
        } else if (puStateCurrent == puStateAnimating) {
            val s = scaleControllerObject!!.localScale
            startColor = startColor.copyOf().also { it[3] = s.x - 1f }
            setColor(startColor)
            scaleX = s.x
            scaleY = s.y
            if (animation?.isPlaying != true) {
                setColor(CLEAR)
                PowerXObject!!.setActiveRecursively(false)
                puStateCurrent = puStateIdle
            }
        }
    }

    companion object : IqStatic {
        private val CLEAR = floatArrayOf(0f, 0f, 0f, 0f)

        @JvmField var triggerStatscreen = false
        @JvmField var pUpIndex = 1
        @JvmField var triggerPowerUp = false

        fun DisplayPowerUp(powerUp: Int) {
            pUpIndex = powerUp
            triggerPowerUp = true
        }

        override fun reset() {
            triggerStatscreen = false
            pUpIndex = 1
            triggerPowerUp = false
        }
    }
}
