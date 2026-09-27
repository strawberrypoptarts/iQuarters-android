package com.guille.spring.iquarters

import kotlin.math.abs

/**
 * What lets the game draw at the display's rate without running faster. The game steps at
 * [IqRuntime.FRAME_DT], as Unity iPhone did (its scripts count frames and the flick reads a
 * frame's touch delta, so stepping it faster would change how it plays). After each step the
 * renderer [capture]s every object's local transform and camera field of view. Each drawn
 * frame then shows the state [alpha] of the way from the step before the last one to the
 * last one. That is one step behind, at most 33ms. Motion that is really a cut is snapped
 * rather than smeared across a step: a move of more than [SNAP_DISTANCE] (the coin moves at
 * most ~0.9 units a step in flight; a reset moves it 3 to 17), a turn of more than 120
 * degrees, or a new level.
 *
 * Only transforms and field of view are interpolated. Everything else a frame shows (what
 * is active, materials, texture offsets, script-modified meshes, `GUI` labels) is the last
 * step's, as it was on the device.
 */
class IqInterp {
    private var world: IqWorld? = null
    private var p0 = emptyArray<V3>()
    private var r0 = emptyArray<Quat>()
    private var s0 = emptyArray<V3>()
    private var f0 = FloatArray(0)
    private var p1 = emptyArray<V3>()
    private var r1 = emptyArray<Quat>()
    private var s1 = emptyArray<V3>()
    private var f1 = FloatArray(0)
    private var cache = arrayOfNulls<M4>(0)
    private var alpha = 1f

    /** Call after every game step. */
    fun capture(w: IqWorld) {
        val objs = w.objects
        if (w !== world || objs.size != p1.size) {
            world = w
            p1 = Array(objs.size) { objs[it].localPosition }
            r1 = Array(objs.size) { objs[it].localRotation }
            s1 = Array(objs.size) { objs[it].localScale }
            f1 = FloatArray(objs.size) { objs[it].camera?.fieldOfView ?: 0f }
            p0 = p1.copyOf(); r0 = r1.copyOf(); s0 = s1.copyOf(); f0 = f1.copyOf()
            cache = arrayOfNulls(objs.size)
            return
        }
        val tp = p0; p0 = p1; p1 = tp
        val tr = r0; r0 = r1; r1 = tr
        val ts = s0; s0 = s1; s1 = ts
        val tf = f0; f0 = f1; f1 = tf
        for (i in objs.indices) {
            val o = objs[i]
            p1[i] = o.localPosition
            r1[i] = o.localRotation
            s1[i] = o.localScale
            f1[i] = o.camera?.fieldOfView ?: 0f
        }
    }

    /** Start a drawn frame [a] of the way from the previous step to the last one. */
    fun begin(a: Float) {
        alpha = a.coerceIn(0f, 1f)
        cache.fill(null)
    }

    private fun ready(go: GObj) = go.world === world && go.index < p1.size

    /** The interpolated `localToWorldMatrix`. */
    fun worldMatrix(go: GObj): M4 {
        if (!ready(go)) return go.worldMatrix()
        cache[go.index]?.let { return it }
        val i = go.index
        val local = if (alpha >= 1f) M4.trs(p1[i], r1[i], s1[i]) else {
            val dp = p1[i] - p0[i]
            val cut = dp.lengthSq() > SNAP_DISTANCE * SNAP_DISTANCE || abs(r0[i].dot(r1[i])) < SNAP_COS_HALF
            if (cut) M4.trs(p1[i], r1[i], s1[i])
            else M4.trs(p0[i] + dp * alpha, Quat.slerp(r0[i], r1[i], alpha), s0[i] + (s1[i] - s0[i]) * alpha)
        }
        val m = go.parent?.let { worldMatrix(it) * local } ?: local
        cache[i] = m
        return m
    }

    fun fieldOfView(go: GObj): Float {
        val cam = go.camera!!
        if (!ready(go)) return cam.fieldOfView
        return f0[go.index] + (f1[go.index] - f0[go.index]) * alpha
    }

    companion object {
        const val SNAP_DISTANCE = 2.5f
        /** cos(120°/2): a quaternion dot below this is a turn of more than 120 degrees. */
        const val SNAP_COS_HALF = 0.5f
    }
}
