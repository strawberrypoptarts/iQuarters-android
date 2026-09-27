package com.guille.spring.iquarters

import kotlin.math.cos
import kotlin.math.sin

/**
 * The scene's colliders as [IqPhysics] sees them, and the collision messages back.
 *
 * Every non-kinematic Rigidbody becomes a dynamic [IqPhysics.Body] built from the colliders
 * at and under it (in iQuarters that is the quarter alone). Every other non-trigger collider
 * becomes an [IqPhysics.StaticCollider] that follows its GameObject each step, so the
 * animated ones (the lazy susan, the pendulum, the drinking bird) push the coin with the
 * velocity of their move. A collider counts only while its own GameObject is active, which
 * is Unity 2.x's rule.
 *
 * Messages go the way Unity sends them: `OnCollisionEnter` on the first step a pair touches,
 * `OnCollisionStay` on each later one, to the scripts on the body's GameObject (with the
 * other collider) and to those on the collider's GameObject (with the body's).
 */
internal object IqColliders {

    class Link(val col: Col, val phys: IqPhysics.StaticCollider)

    class Dyn(val go: GObj, val rb: RigidbodyComp, val body: IqPhysics.Body, val shapes: Map<Int, Col>) {
        var touching = HashSet<Long>()
    }

    private val links = HashMap<IqWorld, List<Link>>()
    private val dyns = HashMap<IqWorld, List<Dyn>>()

    fun build(world: IqWorld) {
        // One level is loaded at a time; a new world replaces whatever came before.
        links.clear()
        dyns.clear()
        val pack = world.pack
        val bodies = world.objects.filter { it.rigidbody != null && !it.src.rigidbody!!.isKinematic }
        val dynList = ArrayList<Dyn>()
        val underBody = HashSet<GObj>()
        for (go in bodies) {
            val shapes = ArrayList<IqPhysics.BodyShape>()
            val byId = HashMap<Int, Col>()
            val rootInv = M4.trs(go.position, go.rotation, V3(1f, 1f, 1f)).rigidInverse()
            fun visit(o: GObj) {
                if (o !== go && o.rigidbody != null) return
                underBody += o
                if (o.active) for (c in o.colliders) {
                    if (c.src.isTrigger) continue
                    val m = rootInv * o.worldMatrix()
                    val id = byId.size
                    byId[id] = c
                    shapes += IqPhysics.BodyShape(id, o.name, physMat(pack, c.src.physMaterial),
                        c.src.kind == IqPack.Collider.MESH && !c.src.convex, convexPoly(pack, c.src, m))
                }
                for (ch in o.children) visit(ch)
            }
            visit(go)
            if (shapes.isEmpty()) continue
            val rb = go.rigidbody!!
            val body = IqPhysics.Body.fromShapes(rb.mass, shapes, rb.src.drag, rb.src.angularDrag)
            body.useGravity = rb.src.useGravity
            body.position = go.position
            body.rotation = go.rotation
            rb.body = body
            dynList += Dyn(go, rb, body, byId)
        }
        val linkList = ArrayList<Link>()
        for (o in world.objects) {
            if (o in underBody) continue
            for (c in o.colliders) {
                if (c.src.isTrigger) continue
                val w = o.worldMatrix()
                val pos = w.translation
                val rot = w.rotation()
                // Geometry in the rigid frame: the world matrix with its rigid part taken out.
                val local = M4.trs(pos, rot, V3(1f, 1f, 1f)).rigidInverse() * w
                val pieces = when (c.src.kind) {
                    IqPack.Collider.MESH -> {
                        val mesh = pack.meshes[c.src.mesh]
                        if (c.src.convex) listOf(convexMeshPoly(mesh, local)) else meshTriangles(mesh, local)
                    }
                    else -> listOf(convexPoly(pack, c.src, local))
                }
                val sc = IqPhysics.StaticCollider(linkList.size, o.name, physMat(pack, c.src.physMaterial),
                    c.src.kind == IqPack.Collider.MESH && !c.src.convex, pieces)
                sc.setPose(pos, rot, null)
                sc.enabled = o.active && c.enabled
                linkList += Link(c, sc)
            }
        }
        world.physics.statics.clear()
        world.physics.statics += linkList.map { it.phys }
        links[world] = linkList
        dyns[world] = dynList
    }

    fun step(world: IqWorld, dt: Float) {
        val ls = links[world] ?: return
        for (l in ls) {
            val go = l.col.go
            l.phys.enabled = go.active && l.col.enabled
            if (!l.phys.enabled) continue
            val w = go.worldMatrix()
            l.phys.setPose(w.translation, w.rotation(), dt)
        }
        for (d in dyns[world] ?: emptyList()) {
            val contacts = world.physics.step(d.body, dt)
            d.rb.syncTransform()
            dispatch(world, d, ls, contacts)
        }
    }

    private fun dispatch(world: IqWorld, d: Dyn, ls: List<Link>, contacts: List<IqPhysics.Contact>) {
        val groups = LinkedHashMap<Long, MutableList<IqPhysics.Contact>>()
        for (c in contacts) groups.getOrPut(key(c.shapeId, c.otherId)) { ArrayList() } += c
        val now = HashSet(groups.keys)
        for ((k, cs) in groups) {
            val entered = k !in d.touching
            val shapeCol = d.shapes.getValue(cs[0].shapeId)
            val other = ls[cs[0].otherId].col
            val rel = d.body.velocity - cs[0].other.surfaceVelocity(cs[0].point)
            val mine = IqCollision(other, cs.map { IqContact(it.point, it.normal, shapeCol, other, -it.depth) }, rel)
            val theirs = IqCollision(shapeCol, cs.map { IqContact(it.point, -it.normal, other, shapeCol, -it.depth) }, -rel)
            send(world, d.go, mine, entered)
            if (other.go !== d.go) send(world, other.go, theirs, entered)
            // A compound collider's rigidbody hears about it too.
            other.attachedRigidbody?.go?.let { if (it !== other.go && it !== d.go) send(world, it, theirs, entered) }
        }
        d.touching = now
    }

    private fun send(world: IqWorld, go: GObj, c: IqCollision, entered: Boolean) {
        if (!go.active) return
        for (s in go.scripts) {
            if (!s.enabled || !s.awoken) continue
            world.guard(s) { if (entered) s.onCollisionEnter(c) else s.onCollisionStay(c) }
        }
    }

    private fun key(a: Int, b: Int) = (a.toLong() shl 32) or (b.toLong() and 0xffffffffL)

    fun physMat(pack: IqPack, i: Int): IqPhysics.PhysMat =
        if (i < 0) IqPhysics.PhysMat.DEFAULT
        else pack.physMaterials[i].let {
            IqPhysics.PhysMat(it.dynamicFriction, it.staticFriction, it.bounciness, it.frictionCombine, it.bounceCombine)
        }

    /** A box, sphere, capsule or convex mesh collider as one polyhedron under [m]. */
    fun convexPoly(pack: IqPack, c: IqPack.Collider, m: M4): IqPhysics.Poly = when (c.kind) {
        IqPack.Collider.BOX -> IqPhysics.Poly.box(m, c.center, c.a)
        IqPack.Collider.MESH -> convexMeshPoly(pack.meshes[c.mesh], m)
        IqPack.Collider.SPHERE -> roundPoly(m, c.center, c.a.x, 0f, 1)
        IqPack.Collider.CAPSULE -> roundPoly(m, c.center, c.a.x, c.a.y, c.a.z.toInt())
        else -> error("collider kind ${c.kind}")
    }

    fun convexMeshPoly(mesh: IqPack.Mesh, m: M4): IqPhysics.Poly {
        val v = Array(mesh.vertexCount) { m.point(mesh.vertex(it)) }
        val tris = mesh.submeshes.flatMap { sub ->
            (sub.indices step 3).map { t -> IntArray(3) { sub[t + it].toInt() and 0xffff } }
        }
        return IqPhysics.Poly.fromConvexTriangles(v, tris)
    }

    fun meshTriangles(mesh: IqPack.Mesh, m: M4): List<IqPhysics.Poly> {
        val w = Array(mesh.vertexCount) { m.point(mesh.vertex(it)) }
        val out = ArrayList<IqPhysics.Poly>()
        for (sub in mesh.submeshes) for (t in sub.indices step 3) {
            IqPhysics.Poly.triangle(w[sub[t].toInt() and 0xffff], w[sub[t + 1].toInt() and 0xffff], w[sub[t + 2].toInt() and 0xffff])?.let { out += it }
        }
        return out
    }

    /**
     * A sphere ([height] 0) or a capsule along axis [dir] (0 x, 1 y, 2 z) as a convex
     * polyhedron: 8 segments round, 4 rings per cap. An approximation, and the only one
     * here; PhysX tests these shapes exactly.
     */
    private fun roundPoly(m: M4, center: V3, radius: Float, height: Float, dir: Int): IqPhysics.Poly {
        val seg = 8
        val ringsPerCap = 3
        val half = (height / 2f - radius).coerceAtLeast(0f)
        val pts = ArrayList<V3>()
        // Rings from the -axis pole to the +axis pole, each cap's rings offset by half.
        val rings = ArrayList<Pair<Float, Float>>() // (axial, radial)
        for (i in 1..ringsPerCap) {
            val a = Math.PI.toFloat() / 2f * i / (ringsPerCap + 1)
            rings += (-half - radius * cos(a)) to radius * sin(a)
        }
        rings += -half to radius
        if (half > 0f) rings += half to radius
        for (i in ringsPerCap downTo 1) {
            val a = Math.PI.toFloat() / 2f * i / (ringsPerCap + 1)
            rings += (half + radius * cos(a)) to radius * sin(a)
        }
        fun axisPoint(axial: Float, u: Float, v: Float): V3 = when (dir) {
            0 -> V3(axial, u, v)
            2 -> V3(u, v, axial)
            else -> V3(u, axial, v)
        }
        val bottom = pts.size; pts += axisPoint(-half - radius, 0f, 0f)
        val ringStart = pts.size
        for ((ax, r) in rings) for (k in 0 until seg) {
            val t = 2 * Math.PI.toFloat() * k / seg
            pts += axisPoint(ax, r * cos(t), r * sin(t))
        }
        val top = pts.size; pts += axisPoint(half + radius, 0f, 0f)
        val tris = ArrayList<IntArray>()
        for (k in 0 until seg) tris += intArrayOf(bottom, ringStart + (k + 1) % seg, ringStart + k)
        for (r in 0 until rings.size - 1) for (k in 0 until seg) {
            val a = ringStart + r * seg + k
            val b = ringStart + r * seg + (k + 1) % seg
            val c = ringStart + (r + 1) * seg + k
            val d = ringStart + (r + 1) * seg + (k + 1) % seg
            tris += intArrayOf(a, b, d); tris += intArrayOf(a, d, c)
        }
        val last = ringStart + (rings.size - 1) * seg
        for (k in 0 until seg) tris += intArrayOf(top, last + k, last + (k + 1) % seg)
        val world = Array(pts.size) { m.point(center + pts[it]) }
        return IqPhysics.Poly.fromConvexTriangles(world, tris)
    }
}
