package com.guille.spring.iquarters

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A rigid-body world for iQuarters, standing in for the PhysX 2.8 that Unity iPhone 1.7
 * shipped. Only what one quarter needs: a single dynamic body against static and
 * kinematic colliders, which in iQuarters is every other collider in the scene (the
 * glasses, the launchers and the table are all kinematic or static).
 *
 * The rules that are PhysX's and are kept as such:
 * - **Materials combine by mode, and the higher mode wins** when the two sides differ,
 *   in PhysX's order Average < Minimum < Multiply < Maximum (`NxCombineMode`), which is
 *   not Unity's enum order ([PhysMat]). The quarter's bounce combine is Maximum, so its
 *   0.65 wins against everything.
 * - **Restitution applies only above the bounce threshold**, `PhysicsManager.bounceThreshold`
 *   (3 in iQuarters): a slower approach does not bounce.
 * - **A body sleeps** once its linear and angular speed have stayed under
 *   `sleepVelocity`/`sleepAngularVelocity` for PhysX's default wake-up counter, 20 steps of
 *   0.02s (`NxBodyDesc::wakeUpCounter`).
 * - **Angular speed is clamped** to `maxAngularVelocity` (7).
 * - **Mass properties come from the shapes**, at uniform density scaled to the rigidbody's
 *   mass, as `updateMassFromShapes(0, mass)` does, so the quarter's inertia follows from its
 *   box and cylinder rather than being chosen.
 * - **A non-convex mesh on a moving body does not collide with other non-convex meshes**
 *   (PhysX 2.8 has no mesh-mesh test without pmaps; Unity 2.x documents it). The quarter's
 *   `Cylinder` is such a mesh, so against a glass's shell only its `collide01` box touches.
 *
 * What is not PhysX's and is argued instead: the narrow phase (separating-axis test, then
 * clipping the incident face against the reference face) and the solver (sequential
 * impulses, [iterations] passes, with a penetration bias that keeps
 * `minPenetrationForPenalty` of overlap as slop). These decide how contacts are found and
 * resolved, not where anything is.
 */
class IqPhysics(
    val gravity: V3,
    val bounceThreshold: Float,
    val sleepVelocity: Float,
    val sleepAngularVelocity: Float,
    val maxAngularVelocity: Float,
    val slop: Float,
    val iterations: Int,
) {

    /** A PhysicMaterial, with Unity's combine numbering (0 Average, 1 Multiply, 2 Min, 3 Max). */
    class PhysMat(val dynamicFriction: Float, val staticFriction: Float, val bounciness: Float,
                  val frictionCombine: Int, val bounceCombine: Int) {
        companion object {
            /**
             * What a collider without a material gets. Unity 2.x's default PhysicMaterial:
             * friction 0.6 both ways, no bounce, Average. Argued, not traced; in iQuarters it
             * touches only friction, since the quarter's Maximum bounce combine wins.
             */
            val DEFAULT = PhysMat(0.6f, 0.6f, 0f, 0, 0)

            /** Unity's combine enum mapped to PhysX's priority order. */
            private fun priority(unity: Int) = when (unity) {
                0 -> 0  // Average
                2 -> 1  // Minimum
                1 -> 2  // Multiply
                else -> 3 // Maximum
            }

            fun combine(a: Float, ma: Int, b: Float, mb: Int): Float {
                val mode = if (priority(ma) >= priority(mb)) ma else mb
                return when (mode) {
                    0 -> (a + b) / 2
                    1 -> a * b
                    2 -> min(a, b)
                    else -> max(a, b)
                }
            }
        }
    }

    // -- geometry --------------------------------------------------------------------

    /** A plane `normal . x = d` holding an ordered loop of vertex indices. */
    class Face(val normal: V3, val d: Float, val loop: IntArray)

    /** A convex polyhedron. [edges] are the distinct edge directions, for the SAT's cross axes. */
    class Poly(val verts: Array<V3>, val faces: List<Face>, val edges: List<V3>) {

        fun transformed(pos: V3, rot: Quat): Poly {
            val v = Array(verts.size) { pos + rot.rotate(verts[it]) }
            val f = faces.map {
                val n = rot.rotate(it.normal)
                Face(n, n dot v[it.loop[0]], it.loop)
            }
            return Poly(v, f, edges.map { rot.rotate(it) })
        }

        fun project(axis: V3): FloatArray {
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            for (p in verts) {
                val d = p dot axis
                if (d < lo) lo = d
                if (d > hi) hi = d
            }
            return floatArrayOf(lo, hi)
        }

        fun bounds(): Pair<V3, V3> {
            var lo = V3(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
            var hi = -lo
            for (p in verts) {
                lo = V3(min(lo.x, p.x), min(lo.y, p.y), min(lo.z, p.z))
                hi = V3(max(hi.x, p.x), max(hi.y, p.y), max(hi.z, p.z))
            }
            return lo to hi
        }

        companion object {
            /** A box with [size] about [center], through [m] (which may scale). */
            fun box(m: M4, center: V3, size: V3): Poly {
                val h = size * 0.5f
                val corners = ArrayList<V3>()
                for (i in 0 until 8) {
                    val c = V3(
                        if (i and 1 != 0) h.x else -h.x,
                        if (i and 2 != 0) h.y else -h.y,
                        if (i and 4 != 0) h.z else -h.z,
                    )
                    corners += m.point(center + c)
                }
                val quads = listOf(
                    intArrayOf(0, 2, 6, 4), intArrayOf(1, 5, 7, 3), // -x, +x
                    intArrayOf(0, 4, 5, 1), intArrayOf(2, 3, 7, 6), // -y, +y
                    intArrayOf(0, 1, 3, 2), intArrayOf(4, 6, 7, 5), // -z, +z
                )
                val tris = quads.flatMap { q -> listOf(intArrayOf(q[0], q[1], q[2]), intArrayOf(q[0], q[2], q[3])) }
                return fromConvexTriangles(corners.toTypedArray(), tris)
            }

            /**
             * A convex polyhedron from a closed convex triangle mesh: coplanar triangles
             * merge into one face, and each face's normal is turned away from the centroid,
             * so the mesh's own winding does not matter.
             */
            fun fromConvexTriangles(verts: Array<V3>, tris: List<IntArray>): Poly {
                var c = V3.ZERO
                for (v in verts) c += v
                c *= 1f / verts.size
                class Group(val n: V3, val d: Float, val idx: MutableSet<Int>)
                val groups = ArrayList<Group>()
                val scale = verts.maxOf { (it - c).length() }.coerceAtLeast(1e-6f)
                for (t in tris) {
                    val a = verts[t[0]]; val b = verts[t[1]]; val e = verts[t[2]]
                    var n = ((b - a) cross (e - a))
                    if (n.length() < 1e-9f * scale * scale) continue
                    n = n.normalized()
                    if ((n dot (a - c)) < 0f) n = -n
                    val d = n dot a
                    val g = groups.firstOrNull { (it.n dot n) > 0.9999f && abs(it.d - d) < 1e-4f * scale }
                    if (g != null) g.idx += t.toList() else groups += Group(n, d, t.toMutableSet())
                }
                val faces = groups.map { g -> Face(g.n, g.d, orderLoop(verts, g.idx.toIntArray(), g.n)) }
                return Poly(verts, faces, edgeDirections(verts, faces))
            }

            /** One triangle, as a flat polyhedron with a face on each side. */
            fun triangle(a: V3, b: V3, c: V3): Poly? {
                val n0 = (b - a) cross (c - a)
                if (n0.lengthSq() < 1e-20f) return null
                val n = n0.normalized()
                val verts = arrayOf(a, b, c)
                val faces = listOf(Face(n, n dot a, intArrayOf(0, 1, 2)), Face(-n, -(n dot a), intArrayOf(0, 2, 1)))
                return Poly(verts, faces, listOf((b - a).normalized(), (c - b).normalized(), (a - c).normalized()))
            }

            /**
             * The face's outline, counter-clockwise about [n]: the 2D convex hull of its
             * points (monotone chain), which drops a fan's centre vertex and any point lying
             * along an edge, so the loop is always a convex polygon the clipper can use.
             */
            private fun orderLoop(verts: Array<V3>, idx: IntArray, n: V3): IntArray {
                val u = (if (abs(n.x) < 0.9f) V3.RIGHT else V3.UP).let { (it cross n).normalized() }
                val w = n cross u
                val pts = idx.distinct().sortedWith(compareBy({ verts[it] dot u }, { verts[it] dot w }))
                fun turn(o: Int, a: Int, b: Int): Float {
                    val oa = verts[a] - verts[o]
                    val ob = verts[b] - verts[o]
                    return (oa dot u) * (ob dot w) - (oa dot w) * (ob dot u)
                }
                val eps = 1e-12f
                val hull = ArrayList<Int>()
                for (pass in 0 until 2) {
                    val start = hull.size
                    for (i in if (pass == 0) pts else pts.asReversed()) {
                        while (hull.size >= start + 2 && turn(hull[hull.size - 2], hull[hull.size - 1], i) <= eps) hull.removeAt(hull.size - 1)
                        hull += i
                    }
                    hull.removeAt(hull.size - 1)
                }
                return hull.toIntArray()
            }

            private fun edgeDirections(verts: Array<V3>, faces: List<Face>): List<V3> {
                val out = ArrayList<V3>()
                for (f in faces) for (i in f.loop.indices) {
                    val e = (verts[f.loop[(i + 1) % f.loop.size]] - verts[f.loop[i]]).normalized()
                    if (e.lengthSq() == 0f) continue
                    if (out.none { abs(it dot e) > 0.9999f }) out += e
                }
                return out
            }
        }
    }

    // -- scene -----------------------------------------------------------------------

    /**
     * A collider that no force moves: static, or kinematic and moved by animation or script.
     * [local] is its geometry in its own rigid frame (any scale baked in); [setPose] places
     * it, and a pose given with a time step also gives it the velocity that move implies,
     * which is what a contact with a moving kinematic actor feels in PhysX. [nonConvexMesh]
     * marks a Unity MeshCollider without `convex`. [id] is whatever the game needs back from
     * a contact (for iQuarters, the collider's index in [IqColliders]).
     */
    class StaticCollider(val id: Int, val name: String, val material: PhysMat, val nonConvexMesh: Boolean,
                         val local: List<Poly>) {
        var enabled = true
        var pieces: List<Poly> = local
            private set
        var boundsLo: Array<V3> = emptyArray()
            private set
        var boundsHi: Array<V3> = emptyArray()
            private set
        var coarseLo = V3.ZERO
            private set
        var coarseHi = V3.ZERO
            private set
        fun overlaps(lo: V3, hi: V3) = !(hi.x < coarseLo.x || lo.x > coarseHi.x || hi.y < coarseLo.y || lo.y > coarseHi.y || hi.z < coarseLo.z || lo.z > coarseHi.z)
        var position = V3.ZERO
            private set
        var rotation = Quat.IDENTITY
            private set
        var linearVelocity = V3.ZERO
        var angularVelocity = V3.ZERO
        private var placed = false

        init { setPose(V3.ZERO, Quat.IDENTITY, null) }

        /** Place it; with [dt], also take the velocity of the move from the last pose. */
        fun setPose(p: V3, q: Quat, dt: Float?) {
            if (placed && p == position && q == rotation) {
                linearVelocity = V3.ZERO
                angularVelocity = V3.ZERO
                return
            }
            if (dt != null && placed && dt > 0f) {
                linearVelocity = (p - position) * (1f / dt)
                var dq = q * rotation.conjugate()
                if (dq.w < 0f) dq = Quat(-dq.x, -dq.y, -dq.z, -dq.w)
                val sinHalf = V3(dq.x, dq.y, dq.z).length()
                val angle = 2f * kotlin.math.atan2(sinHalf, dq.w)
                angularVelocity = if (sinHalf > 1e-7f) V3(dq.x, dq.y, dq.z) * (angle / sinHalf / dt) else V3.ZERO
            } else {
                linearVelocity = V3.ZERO
                angularVelocity = V3.ZERO
            }
            position = p
            rotation = q
            placed = true
            pieces = if (p == V3.ZERO && q == Quat.IDENTITY) local else local.map { it.transformed(p, q) }
            val b = pieces.map { it.bounds() }
            boundsLo = Array(b.size) { b[it].first }
            boundsHi = Array(b.size) { b[it].second }
            coarseLo = V3(boundsLo.minOfOrNull { it.x } ?: 0f,boundsLo.minOfOrNull { it.y } ?: 0f,boundsLo.minOfOrNull { it.z } ?: 0f)
            coarseHi = V3(boundsHi.maxOfOrNull { it.x } ?: 0f,boundsHi.maxOfOrNull { it.y } ?: 0f,boundsHi.maxOfOrNull { it.z } ?: 0f)
        }

        val moving get() = linearVelocity != V3.ZERO || angularVelocity != V3.ZERO

        fun surfaceVelocity(pt: V3) = linearVelocity + (angularVelocity cross (pt - position))
    }

    /** One of the body's shapes, in the body's frame (the actor pose, scale baked in). */
    class BodyShape(val id: Int, val name: String, val material: PhysMat, val nonConvexMesh: Boolean, val local: Poly)

    class Body(val mass: Float, val inertia: M3, val shapes: List<BodyShape>, var drag: Float, var angularDrag: Float) {
        var position = V3.ZERO
        var rotation = Quat.IDENTITY
        var velocity = V3.ZERO
        var angularVelocity = V3.ZERO
        var isKinematic = false
        var useGravity = true
        var sleeping = false
        internal var wakeCounter = WAKE_COUNTER
        internal var torque = V3.ZERO
        private val invInertia = invert(inertia)

        fun wakeUp() {
            sleeping = false
            wakeCounter = WAKE_COUNTER
        }

        /** `AddTorque(t)` in ForceMode.Force: integrated over the next step. */
        fun addTorque(t: V3) {
            torque += t
        }

        fun invInertiaWorld(v: V3): V3 = rotation.rotate(invInertia.times(rotation.conjugate().rotate(v)))

        companion object {
            /** PhysX 2.8's default `NxBodyDesc::wakeUpCounter`, 20 steps of 0.02s. */
            const val WAKE_COUNTER = 20f * 0.02f

            private fun invert(m: M3): M3 {
                val a = m.m
                val c0 = a[4] * a[8] - a[5] * a[7]
                val c1 = a[5] * a[6] - a[3] * a[8]
                val c2 = a[3] * a[7] - a[4] * a[6]
                val det = a[0] * c0 + a[1] * c1 + a[2] * c2
                val id = 1f / det
                return M3(floatArrayOf(
                    c0 * id, (a[2] * a[7] - a[1] * a[8]) * id, (a[1] * a[5] - a[2] * a[4]) * id,
                    c1 * id, (a[0] * a[8] - a[2] * a[6]) * id, (a[2] * a[3] - a[0] * a[5]) * id,
                    c2 * id, (a[1] * a[6] - a[0] * a[7]) * id, (a[0] * a[4] - a[1] * a[3]) * id,
                ))
            }

            /**
             * Volume, inertia (about the origin, density 1) and first moment of a closed
             * convex polyhedron, by summing the signed tetrahedra each face triangle makes
             * with the origin.
             */
            fun massProperties(p: Poly): Triple<Float, FloatArray, V3> {
                var vol = 0.0
                var moment = V3.ZERO
                val cov = DoubleArray(9)
                for (f in p.faces) {
                    val a = p.verts[f.loop[0]]
                    for (i in 1 until f.loop.size - 1) {
                        var b = p.verts[f.loop[i]]
                        var c = p.verts[f.loop[i + 1]]
                        if ((((b - a) cross (c - a)) dot f.normal) < 0f) { val t = b; b = c; c = t }
                        val det = (a dot (b cross c)).toDouble()
                        vol += det / 6.0
                        moment += (a + b + c) * (det.toFloat() / 24f)
                        val s = arrayOf(a, b, c)
                        val sum = doubleArrayOf((a.x + b.x + c.x).toDouble(), (a.y + b.y + c.y).toDouble(), (a.z + b.z + c.z).toDouble())
                        for (r in 0 until 3) for (k in 0 until 3) {
                            var acc = sum[r] * sum[k]
                            for (v in s) acc += comp(v, r).toDouble() * comp(v, k)
                            cov[r * 3 + k] += det / 120.0 * acc
                        }
                    }
                }
                val tr = cov[0] + cov[4] + cov[8]
                val inertia = FloatArray(9) { i -> ((if (i % 4 == 0) tr else 0.0) - cov[i]).toFloat() }
                return Triple(vol.toFloat(), inertia, moment)
            }

            private fun comp(v: V3, i: Int) = when (i) { 0 -> v.x; 1 -> v.y; else -> v.z }

            /**
             * A body whose mass is spread uniformly over [shapes] and scaled to [mass]. The
             * centre of mass is taken as the actor origin, which holds for the quarter; a
             * body where it does not is refused rather than simulated about the wrong point.
             */
            fun fromShapes(mass: Float, shapes: List<BodyShape>, drag: Float, angularDrag: Float): Body {
                var vol = 0f
                val inertia = FloatArray(9)
                var moment = V3.ZERO
                for (s in shapes) {
                    val (v, i, m) = massProperties(s.local)
                    vol += v
                    for (k in 0 until 9) inertia[k] += i[k]
                    moment += m
                }
                val com = moment * (1f / vol)
                require(com.length() < 1e-3f) { "centre of mass $com is off the actor origin" }
                val rho = mass / vol
                return Body(mass, M3(FloatArray(9) { inertia[it] * rho }), shapes, drag, angularDrag)
            }
        }
    }

    /** A contact as Unity reports it to the body: the normal points from [otherId] into the body. */
    class Contact(val point: V3, val normal: V3, val depth: Float, val shapeId: Int, val other: StaticCollider) {
        val otherId get() = other.id
        val otherName get() = other.name
    }

    val statics = ArrayList<StaticCollider>()

    // -- stepping --------------------------------------------------------------------

    private class Row(
        val c: Contact, val r: V3, val n: V3, val t1: V3, val t2: V3,
        val kn: Float, val kt1: Float, val kt2: Float, val target: Float, val mu: Float,
        /** The other side's surface velocity at the contact. */
        val vs: V3,
    ) {
        var pn = 0f
        var pt1 = 0f
        var pt2 = 0f
    }

    /** One `Physics.Simulate(dt)`; returns the contacts the step found (its OnCollisionStay). */
    fun step(body: Body, dt: Float): List<Contact> {
        if (body.sleeping && !body.isKinematic && movingNear(body)) body.wakeUp()
        if (body.isKinematic || body.sleeping) {
            body.torque = V3.ZERO
            return emptyList()
        }
        if (body.useGravity) body.velocity += gravity * dt
        body.angularVelocity += body.invInertiaWorld(body.torque) * dt
        body.torque = V3.ZERO
        body.velocity *= max(0f, 1f - body.drag * dt)
        body.angularVelocity *= max(0f, 1f - body.angularDrag * dt)

        val contacts = detect(body)
        val rows = contacts.map { row(body, it, dt) }
        repeat(iterations) {
            for (row in rows) solve(body, row)
        }

        val w = body.angularVelocity
        if (w.length() > maxAngularVelocity) body.angularVelocity = w.normalized() * maxAngularVelocity
        body.position += body.velocity * dt
        val om = body.angularVelocity
        val q = body.rotation
        val dq = Quat(om.x, om.y, om.z, 0f) * q
        body.rotation = Quat(q.x + 0.5f * dt * dq.x, q.y + 0.5f * dt * dq.y, q.z + 0.5f * dt * dq.z, q.w + 0.5f * dt * dq.w).normalized()

        if (body.velocity.lengthSq() < sleepVelocity * sleepVelocity &&
            body.angularVelocity.lengthSq() < sleepAngularVelocity * sleepAngularVelocity
        ) {
            body.wakeCounter -= dt
            if (body.wakeCounter <= 0f) {
                body.sleeping = true
                body.velocity = V3.ZERO
                body.angularVelocity = V3.ZERO
            }
        } else {
            body.wakeCounter = Body.WAKE_COUNTER
        }
        return contacts
    }

    /** A kinematic collider moving into a sleeping body wakes it, as a PhysX actor move does. */
    private fun movingNear(body: Body): Boolean {
        for (shape in body.shapes) {
            val (lo, hi) = shape.local.transformed(body.position, body.rotation).bounds()
            for (s in statics) {
                if (!s.enabled || !s.moving || !s.overlaps(lo,hi)) continue
                for (i in s.pieces.indices) {
                    val a = s.boundsLo[i]; val b = s.boundsHi[i]
                    if (hi.x < a.x || lo.x > b.x || hi.y < a.y || lo.y > b.y || hi.z < a.z || lo.z > b.z) continue
                    return true
                }
            }
        }
        return false
    }

    private fun row(body: Body, c: Contact, dt: Float): Row {
        val n = c.normal
        val r = c.point - body.position
        val t1 = (if (abs(n.x) < 0.9f) V3.RIGHT else V3.UP).let { (it cross n).normalized() }
        val t2 = n cross t1
        fun k(d: V3): Float {
            val rd = r cross d
            return 1f / (1f / body.mass + (body.invInertiaWorld(rd) cross r dot d))
        }
        val shape = body.shapes.first { it.id == c.shapeId }.material
        val other = c.other.material
        val e = PhysMat.combine(shape.bounciness, shape.bounceCombine, other.bounciness, other.bounceCombine)
        val mu = PhysMat.combine(shape.dynamicFriction, shape.frictionCombine, other.dynamicFriction, other.frictionCombine)
        val vs = c.other.surfaceVelocity(c.point)
        val vn = (relVel(body, r) - vs) dot n
        var target = if (-vn > bounceThreshold) -e * vn else 0f
        val bias = BAUMGARTE / dt * max(0f, c.depth - slop)
        target = max(target, bias)
        return Row(c, r, n, t1, t2, k(n), k(t1), k(t2), target, mu, vs)
    }

    private fun relVel(body: Body, r: V3) = body.velocity + (body.angularVelocity cross r)

    private fun applyImpulse(body: Body, r: V3, p: V3) {
        body.velocity += p * (1f / body.mass)
        body.angularVelocity += body.invInertiaWorld(r cross p)
    }

    private fun solve(body: Body, row: Row) {
        val vn = (relVel(body, row.r) - row.vs) dot row.n
        var dp = row.kn * (row.target - vn)
        val old = row.pn
        row.pn = max(0f, old + dp)
        dp = row.pn - old
        applyImpulse(body, row.r, row.n * dp)

        val limit = row.mu * row.pn
        val v = relVel(body, row.r) - row.vs
        var a1 = row.pt1 - row.kt1 * (v dot row.t1)
        var a2 = row.pt2 - row.kt2 * (v dot row.t2)
        val len = sqrt(a1 * a1 + a2 * a2)
        if (len > limit && len > 0f) {
            a1 *= limit / len; a2 *= limit / len
        }
        val d1 = a1 - row.pt1
        val d2 = a2 - row.pt2
        row.pt1 = a1; row.pt2 = a2
        applyImpulse(body, row.r, row.t1 * d1 + row.t2 * d2)
    }

    // -- narrow phase ----------------------------------------------------------------

    fun detect(body: Body): List<Contact> {
        val out = ArrayList<Contact>()
        for (shape in body.shapes) {
            val world = shape.local.transformed(body.position, body.rotation)
            val (lo, hi) = world.bounds()
            for (s in statics) {
                if (!s.enabled || !s.overlaps(lo,hi)) continue
                if (shape.nonConvexMesh && s.nonConvexMesh) continue
                for (i in s.pieces.indices) {
                    val a = s.boundsLo[i]; val b = s.boundsHi[i]
                    if (hi.x < a.x || lo.x > b.x || hi.y < a.y || lo.y > b.y || hi.z < a.z || lo.z > b.z) continue
                    val m = collide(world, s.pieces[i]) ?: continue
                    for ((p, depth) in m.second) out += Contact(p, -m.first, depth, shape.id, s)
                }
            }
        }
        return out
    }

    /**
     * Separating-axis test of [a] against [b]. Returns the axis pointing from a to b and
     * the contact points (on a, with their depth), or null if they are apart.
     */
    fun collide(a: Poly, b: Poly): Pair<V3, List<Pair<V3, Float>>>? {
        var best = Float.MAX_VALUE
        var axis = V3.ZERO
        fun test(n: V3, bias: Float): Boolean {
            val pa = a.project(n)
            val pb = b.project(n)
            val d1 = pa[1] - pb[0]
            val d2 = pb[1] - pa[0]
            if (d1 < 0f || d2 < 0f) return false
            val o = min(d1, d2)
            if (o * bias < best) {
                best = o * bias
                axis = if (d1 < d2) n else -n
            }
            return true
        }
        for (f in b.faces) if (!test(f.normal, 1f)) return null
        for (f in a.faces) if (!test(f.normal, 1.0005f)) return null
        for (ea in a.edges) for (eb in b.edges) {
            val c = ea cross eb
            if (c.lengthSq() < 1e-8f) continue
            if (!test(c.normalized(), 1.05f)) return null
        }
        if (axis == V3.ZERO) return null
        return axis to contactPoints(a, b, axis, best)
    }

    private fun contactPoints(a: Poly, b: Poly, n: V3, depth: Float): List<Pair<V3, Float>> {
        // Reference face: whichever side has a face most nearly facing the other.
        val fb = b.faces.maxBy { it.normal dot -n }
        val fa = a.faces.maxBy { it.normal dot n }
        val points = ArrayList<Pair<V3, Float>>()
        if ((fb.normal dot -n) >= (fa.normal dot n) - 1e-3f) {
            val inc = a.faces.minBy { it.normal dot fb.normal }
            for (p in clip(inc.loop.map { a.verts[it] }, b, fb)) {
                val d = fb.d - (fb.normal dot p)
                if (d >= 0f) points += p to d
            }
        } else {
            val inc = b.faces.minBy { it.normal dot fa.normal }
            for (p in clip(inc.loop.map { b.verts[it] }, a, fa)) {
                val d = fa.d - (fa.normal dot p)
                if (d >= 0f) points += (p + fa.normal * d) to d
            }
        }
        if (points.isEmpty()) {
            // Edge against edge, or a clip that left nothing: the deepest vertex of a.
            val p = a.verts.maxBy { it dot n }
            points += p to depth
        }
        return reduce(points)
    }

    private fun clip(poly: List<V3>, owner: Poly, ref: Face): List<V3> {
        var pts = poly
        val loop = ref.loop.map { owner.verts[it] }
        var c = V3.ZERO
        for (p in loop) c += p
        c *= 1f / loop.size
        for (i in loop.indices) {
            val p0 = loop[i]
            val p1 = loop[(i + 1) % loop.size]
            var s = ((p1 - p0) cross ref.normal).normalized()
            if ((s dot (c - p0)) > 0f) s = -s
            val d = s dot p0
            val next = ArrayList<V3>()
            for (k in pts.indices) {
                val u = pts[k]
                val v = pts[(k + 1) % pts.size]
                val du = (s dot u) - d
                val dv = (s dot v) - d
                if (du <= 0f) next += u
                if ((du < 0f && dv > 0f) || (du > 0f && dv < 0f)) next += u + (v - u) * (du / (du - dv))
            }
            pts = next
            if (pts.isEmpty()) break
        }
        return pts
    }

    /** At most four points: the deepest, the farthest from it, then the two widening the patch most. */
    private fun reduce(points: List<Pair<V3, Float>>): List<Pair<V3, Float>> {
        if (points.size <= 4) return points
        val first = points.maxBy { it.second }
        val second = points.maxBy { (it.first - first.first).lengthSq() }
        val axis = second.first - first.first
        val third = points.maxBy { ((it.first - first.first) cross axis).lengthSq() }
        val n = (axis cross (third.first - first.first))
        val fourth = points.maxBy { -(((it.first - first.first) cross axis) dot n) }
        return listOf(first, second, third, fourth).distinct()
    }

    companion object {
        /** Fraction of the penetration beyond the slop corrected per step. */
        const val BAUMGARTE = 0.2f
    }
}
