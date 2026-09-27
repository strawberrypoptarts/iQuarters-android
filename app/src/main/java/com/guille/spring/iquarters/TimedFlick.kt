package com.guille.spring.iquarters

import kotlin.math.pow

data class FlickShot(val power: Float, val aim: Float)

/** Port of the shipped C# TimedFlickGesture: actual event time, reference 60 Hz. */
class TimedFlick {
    private var tracking = false
    private var fired = false
    private var previous = 0.0
    private var duration = 0.0
    private var dx = 0f
    private var dy = 0f
    fun cancel() { tracking = false; fired = false; duration = 0.0; dx = 0f; dy = 0f }
    fun begin(time: Double) { cancel(); if (time.isFinite()) { previous = time; tracking = true } }
    private fun evaluate(x: Float, y: Float): FlickShot? {
        if (!tracking || fired || !x.isFinite() || !y.isFinite() || y <= 0) return null
        val up = minOf(y, 360f)
        val power = (up * .02f / 1.8f).pow(.1f)
        val aim = 4f * x / up
        if (power <= .9f || !aim.isFinite()) return null
        fired = true
        return FlickShot(power, aim)
    }
    fun sample(x: Float, y: Float, time: Double): FlickShot? {
        if (!tracking || !time.isFinite() || time <= previous || !x.isFinite() || !y.isFinite()) return null
        val dt = time - previous; previous = time
        if (duration + dt < WINDOW - 1e-9) { dx += x; dy += y; duration += dt; return null }
        val first = maxOf(0.0, WINDOW - duration)
        dx += x * (first / dt).toFloat(); dy += y * (first / dt).toFloat()
        val shot = evaluate(dx, dy); dx = 0f; dy = 0f; duration = 0.0
        if (shot != null) return shot
        var left = maxOf(0.0, dt - first)
        if (left >= WINDOW) {
            evaluate(x * (WINDOW / dt).toFloat(), y * (WINDOW / dt).toFloat())?.let { return it }
            left %= WINDOW
        }
        dx = x * (left / dt).toFloat(); dy = y * (left / dt).toFloat(); duration = left
        return null
    }
    fun end(): FlickShot? {
        val shot = if (tracking && duration > 1e-9) evaluate(dx * (WINDOW / duration).toFloat(), dy * (WINDOW / duration).toFloat()) else null
        cancel(); return shot
    }
    companion object { const val WINDOW = 1.0 / 60.0 }
}
