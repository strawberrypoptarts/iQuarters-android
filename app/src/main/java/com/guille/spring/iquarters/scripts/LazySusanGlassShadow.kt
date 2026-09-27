package com.guille.spring.iquarters.scripts

import com.guille.spring.iquarters.Behaviour
import com.guille.spring.iquarters.GObj

/**
 * `LazySusanGlassShadow`: while the lazy susan is out, the glass shadow follows the glass on
 * it, 0.23 above its origin and flattened to 0.17 across. Nothing hides it again when the
 * lazy susan goes; the ARM never sets the renderer back off either.
 */
class LazySusanGlassShadow : Behaviour() {
    var lazySusanObject: GObj? = null
    var lazySusanGlassObject: GObj? = null
    var glassShadowObject: GObj? = null

    override fun onBind() {
        lazySusanObject = obj("lazySusanObject")
        lazySusanGlassObject = obj("lazySusanGlassObject")
        glassShadowObject = obj("glassShadowObject")
    }

    override fun update() {
        if (!IsLazySusanActive()) return
        val shadow = glassShadowObject!!
        shadow.renderer!!.enabled = true
        val p = lazySusanGlassObject!!.position
        shadow.position = p.copy(y = p.y + 0.23f)
        // The ARM adds 0.15f and 0.02f in double (the source's 0.17 literal is folded away).
        val s = (0.15f.toDouble() + 0.02f.toDouble()).toFloat()
        shadow.localScale = shadow.localScale.copy(x = s, z = s)
    }

    fun IsLazySusanActive(): Boolean = lazySusanObject!!.active
}
