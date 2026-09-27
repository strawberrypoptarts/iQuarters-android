package com.guille.spring.iquarters

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The little vector algebra the iQuarters port needs, in Unity's own conventions:
 * left-handed, Y up, Z forward, quaternions as (x, y, z, w). Everything is `Float`,
 * because Unity and PhysX 2.8 were.
 *
 * Handedness only matters where a rotation is *named* (Unity's `LookRotation`) and
 * at the one place the renderer hands a matrix to GL, which flips Z. Cross products,
 * quaternion products and rigid-body dynamics are the same formulas in either.
 */
data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = V3(x * s, y * s, z * s)
    operator fun unaryMinus() = V3(-x, -y, -z)
    infix fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun mul(o: V3) = V3(x * o.x, y * o.y, z * o.z)
    fun lengthSq() = x * x + y * y + z * z
    fun length() = sqrt(lengthSq())
    fun normalized(): V3 {
        val l = length()
        return if (l > 1e-12f) this * (1f / l) else ZERO
    }

    companion object {
        val ZERO = V3(0f, 0f, 0f)
        val UP = V3(0f, 1f, 0f)
        val RIGHT = V3(1f, 0f, 0f)
        val FORWARD = V3(0f, 0f, 1f)
    }
}

data class Quat(val x: Float, val y: Float, val z: Float, val w: Float) {
    operator fun times(q: Quat) = Quat(
        w * q.x + x * q.w + y * q.z - z * q.y,
        w * q.y - x * q.z + y * q.w + z * q.x,
        w * q.z + x * q.y - y * q.x + z * q.w,
        w * q.w - x * q.x - y * q.y - z * q.z,
    )

    fun conjugate() = Quat(-x, -y, -z, w)

    fun rotate(v: V3): V3 {
        val u = V3(x, y, z)
        val t = (u cross v) * 2f
        return v + t * w + (u cross t)
    }

    fun normalized(): Quat {
        val l = sqrt(x * x + y * y + z * z + w * w)
        return if (l > 1e-12f) Quat(x / l, y / l, z / l, w / l) else IDENTITY
    }

    fun dot(q: Quat) = x * q.x + y * q.y + z * q.z + w * q.w

    /**
     * `Quaternion.eulerAngles`, degrees in [0, 360): the inverse of [euler], reading
     * `R = Ry·Rx·Rz` back as x = asin(−R₁₂), y = atan2(R₀₂, R₂₂), z = atan2(R₁₀, R₁₁).
     */
    fun eulerAngles(): V3 {
        val r12 = 2 * (y * z - w * x)
        val ex = asin((-r12).coerceIn(-1f, 1f))
        val ey: Float
        val ez: Float
        if (abs(r12) < 0.99999f) {
            ey = atan2(2 * (x * z + w * y), 1 - 2 * (x * x + y * y))
            ez = atan2(2 * (x * y + w * z), 1 - 2 * (x * x + z * z))
        } else {
            ey = atan2(-2 * (x * z - w * y), 1 - 2 * (y * y + z * z))
            ez = 0f
        }
        fun deg(r: Float): Float { val d = Math.toDegrees(r.toDouble()).toFloat(); return if (d < 0f) d + 360f else if (d >= 360f) d - 360f else d }
        return V3(deg(ex), deg(ey), deg(ez))
    }

    companion object {
        val IDENTITY = Quat(0f, 0f, 0f, 1f)

        /** `Quaternion.Euler(x, y, z)`, degrees: z first, then x, then y, all about fixed axes. */
        fun euler(e: V3): Quat {
            val r = Math.PI.toFloat() / 180f
            return axisAngle(V3.UP, e.y * r) * axisAngle(V3.RIGHT, e.x * r) * axisAngle(V3.FORWARD, e.z * r)
        }

        fun axisAngle(axis: V3, radians: Float): Quat {
            val a = axis.normalized()
            val s = sin(radians / 2)
            return Quat(a.x * s, a.y * s, a.z * s, cos(radians / 2))
        }

        /** Rotation taking the columns (right, up, forward) to the basis vectors. */
        fun fromBasis(r: V3, u: V3, f: V3): Quat {
            val trace = r.x + u.y + f.z
            return if (trace > 0f) {
                val s = sqrt(trace + 1f) * 2f
                Quat((u.z - f.y) / s, (f.x - r.z) / s, (r.y - u.x) / s, 0.25f * s)
            } else if (r.x > u.y && r.x > f.z) {
                val s = sqrt(1f + r.x - u.y - f.z) * 2f
                Quat(0.25f * s, (u.x + r.y) / s, (f.x + r.z) / s, (u.z - f.y) / s)
            } else if (u.y > f.z) {
                val s = sqrt(1f + u.y - r.x - f.z) * 2f
                Quat((u.x + r.y) / s, 0.25f * s, (f.y + u.z) / s, (f.x - r.z) / s)
            } else {
                val s = sqrt(1f + f.z - r.x - u.y) * 2f
                Quat((f.x + r.z) / s, (f.y + u.z) / s, 0.25f * s, (r.y - u.x) / s)
            }.normalized()
        }

        /**
         * `Quaternion.LookRotation(forward)`: +Z along [forward], +Y as close to world
         * up as that allows. Left-handed, so right = up x forward.
         */
        fun lookRotation(forward: V3, up: V3 = V3.UP): Quat {
            val f = forward.normalized()
            if (f.lengthSq() == 0f) return IDENTITY
            var r = (up cross f)
            if (r.lengthSq() < 1e-12f) r = V3.RIGHT
            r = r.normalized()
            val u = f cross r
            return fromBasis(r, u, f)
        }

        /** `Quaternion.Slerp`: t clamped to [0, 1], along the shorter arc. */
        fun slerp(a: Quat, b: Quat, tIn: Float): Quat {
            val t = tIn.coerceIn(0f, 1f)
            var d = a.dot(b)
            var bb = b
            if (d < 0f) {
                d = -d; bb = Quat(-b.x, -b.y, -b.z, -b.w)
            }
            if (d > 0.9995f) {
                return Quat(
                    a.x + (bb.x - a.x) * t, a.y + (bb.y - a.y) * t,
                    a.z + (bb.z - a.z) * t, a.w + (bb.w - a.w) * t,
                ).normalized()
            }
            val th = acos(d)
            val s = sin(th)
            val wa = sin((1 - t) * th) / s
            val wb = sin(t * th) / s
            return Quat(a.x * wa + bb.x * wb, a.y * wa + bb.y * wb, a.z * wa + bb.z * wb, a.w * wa + bb.w * wb)
        }
    }
}

/** 3x3 matrix, row-major, for inertia tensors. */
class M3(val m: FloatArray = FloatArray(9)) {
    operator fun get(r: Int, c: Int) = m[r * 3 + c]
    fun times(v: V3) = V3(
        m[0] * v.x + m[1] * v.y + m[2] * v.z,
        m[3] * v.x + m[4] * v.y + m[5] * v.z,
        m[6] * v.x + m[7] * v.y + m[8] * v.z,
    )

    companion object {
        fun diag(d: V3) = M3(floatArrayOf(d.x, 0f, 0f, 0f, d.y, 0f, 0f, 0f, d.z))
    }
}

/**
 * Affine 4x4, column-major like GL, holding Unity's TRS products. `m[12..14]` is the
 * translation.
 */
class M4(val m: FloatArray = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }) {

    operator fun times(o: M4): M4 {
        val r = FloatArray(16)
        for (c in 0 until 4) for (row in 0 until 4) {
            var s = 0f
            for (k in 0 until 4) s += m[k * 4 + row] * o.m[c * 4 + k]
            r[c * 4 + row] = s
        }
        return M4(r)
    }

    fun point(v: V3) = V3(
        m[0] * v.x + m[4] * v.y + m[8] * v.z + m[12],
        m[1] * v.x + m[5] * v.y + m[9] * v.z + m[13],
        m[2] * v.x + m[6] * v.y + m[10] * v.z + m[14],
    )

    fun vector(v: V3) = V3(
        m[0] * v.x + m[4] * v.y + m[8] * v.z,
        m[1] * v.x + m[5] * v.y + m[9] * v.z,
        m[2] * v.x + m[6] * v.y + m[10] * v.z,
    )

    val translation get() = V3(m[12], m[13], m[14])

    /** Determinant of the upper 3x3; negative means the matrix mirrors. */
    fun det3(): Float =
        m[0] * (m[5] * m[10] - m[9] * m[6]) - m[4] * (m[1] * m[10] - m[9] * m[2]) + m[8] * (m[1] * m[6] - m[5] * m[2])

    /** Inverse of a rigid (rotation + translation) matrix. */
    fun rigidInverse(): M4 {
        val r = FloatArray(16)
        for (i in 0 until 3) for (j in 0 until 3) r[j * 4 + i] = m[i * 4 + j]
        val t = translation
        r[12] = -(r[0] * t.x + r[4] * t.y + r[8] * t.z)
        r[13] = -(r[1] * t.x + r[5] * t.y + r[9] * t.z)
        r[14] = -(r[2] * t.x + r[6] * t.y + r[10] * t.z)
        r[15] = 1f
        return M4(r)
    }

    /** General affine inverse (the bottom row is taken as 0 0 0 1). */
    fun inverse(): M4 {
        val a = m
        val c00 = a[5] * a[10] - a[9] * a[6]
        val c01 = a[8] * a[6] - a[4] * a[10]
        val c02 = a[4] * a[9] - a[8] * a[5]
        val det = a[0] * c00 + a[1] * c01 + a[2] * c02
        if (abs(det) < 1e-20f) return M4()
        val id = 1f / det
        val r = FloatArray(16)
        r[0] = c00 * id; r[4] = c01 * id; r[8] = c02 * id
        r[1] = (a[9] * a[2] - a[1] * a[10]) * id
        r[5] = (a[0] * a[10] - a[8] * a[2]) * id
        r[9] = (a[8] * a[1] - a[0] * a[9]) * id
        r[2] = (a[1] * a[6] - a[5] * a[2]) * id
        r[6] = (a[4] * a[2] - a[0] * a[6]) * id
        r[10] = (a[0] * a[5] - a[4] * a[1]) * id
        val t = translation
        r[12] = -(r[0] * t.x + r[4] * t.y + r[8] * t.z)
        r[13] = -(r[1] * t.x + r[5] * t.y + r[9] * t.z)
        r[14] = -(r[2] * t.x + r[6] * t.y + r[10] * t.z)
        r[15] = 1f
        return M4(r)
    }

    /** The rotation part, with any scale divided out of each column. */
    fun rotation(): Quat {
        val r = V3(m[0], m[1], m[2]).normalized()
        val u = V3(m[4], m[5], m[6]).normalized()
        val f = V3(m[8], m[9], m[10]).normalized()
        return Quat.fromBasis(r, u, f)
    }

    /** Column lengths: Unity's `lossyScale`, sign lost. */
    fun lossyScale() = V3(V3(m[0], m[1], m[2]).length(), V3(m[4], m[5], m[6]).length(), V3(m[8], m[9], m[10]).length())

    companion object {
        fun trs(t: V3, q: Quat, s: V3): M4 {
            val x = q.x; val y = q.y; val z = q.z; val w = q.w
            val m = FloatArray(16)
            m[0] = (1 - 2 * (y * y + z * z)) * s.x
            m[1] = (2 * (x * y + w * z)) * s.x
            m[2] = (2 * (x * z - w * y)) * s.x
            m[4] = (2 * (x * y - w * z)) * s.y
            m[5] = (1 - 2 * (x * x + z * z)) * s.y
            m[6] = (2 * (y * z + w * x)) * s.y
            m[8] = (2 * (x * z + w * y)) * s.z
            m[9] = (2 * (y * z - w * x)) * s.z
            m[10] = (1 - 2 * (x * x + y * y)) * s.z
            m[12] = t.x; m[13] = t.y; m[14] = t.z; m[15] = 1f
            return M4(m)
        }

        fun scale(s: V3) = trs(V3.ZERO, Quat.IDENTITY, s)
    }
}

internal fun approxZero(v: Float) = abs(v) < 1e-6f
