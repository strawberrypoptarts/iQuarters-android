package com.guille.spring.iquarters.scripts

import com.guille.spring.iquarters.Behaviour
import com.guille.spring.iquarters.GObj
import kotlin.math.abs
import kotlin.math.hypot

/** `ShadowQuarterScript`: the coin's blob shadow follows it on the table and grows with height. */
class ShadowQuarterScript : Behaviour() {
    private var thisObject: GObj? = null
    private var quarterObject: GObj? = null
    private var baseScale = 0.1f
    private var shadowScaleFactor = 0.02f
    private var tableDistFromCenterX = 0f
    private var tableDistFromCenterZ = 0f
    private val minZposition = -3.5f
    private var radiusThresh = 3f
    private var lazySusanHeight = 0.17f
    private val lazySusanRound = 11

    override fun onBind() {
        thisObject = obj("ThisObject")
        quarterObject = obj("QuarterObject")
        baseScale = num("baseScale", 0.1f)
        shadowScaleFactor = num("shadowScaleFactor", 0.02f)
        tableDistFromCenterX = num("tableDistFromCenterX")
        tableDistFromCenterZ = num("tableDistFromCenterZ")
        radiusThresh = num("radiusThresh", 3f)
        lazySusanHeight = num("lazySusanHeight", 0.17f)
    }

    override fun update() {
        val self = thisObject ?: return
        val q = (quarterObject ?: return).position
        var x = q.x
        var z = maxOf(q.z, minZposition)
        var y = if (q.y < 0.1f) -10f else 0.05f
        if (GameManagerScript.curRound == lazySusanRound) {
            find("/lazy_susan_00")?.let { ls ->
                if (hypot(ls.position.x - x, ls.position.z - z) < radiusThresh) y += lazySusanHeight
            }
        }
        self.position = com.guille.spring.iquarters.V3(x, y, z)
        var k = if (q.y < 0f) baseScale else baseScale + q.y * shadowScaleFactor
        if (abs(x) > tableDistFromCenterX || abs(z) > tableDistFromCenterZ) k = 0f
        self.localScale = self.localScale.copy(x = k, z = k)
    }
}
