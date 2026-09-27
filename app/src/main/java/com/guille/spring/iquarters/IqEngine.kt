package com.guille.spring.iquarters

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The slice of the Unity iPhone 1.7 player that iQuarters' scripts run on, in pure
 * Kotlin: GameObjects and transforms, the legacy `Animation` component, `AudioSource`,
 * `iPhoneInput`, `OnGUI` buttons, `PlayerPrefs`, `Time`, `Application.LoadLevel`, and the
 * frame loop that calls the scripts. Rendering and sound are the host's; the physics is
 * [IqPhysics].
 *
 * Unity 2.x rules that are easy to get wrong, kept as such:
 * - **`active` belongs to one GameObject.** There is no `activeInHierarchy`: a child whose
 *   parent is inactive still renders and runs, which is why the game calls
 *   `SetActiveRecursively` everywhere. `GameObject.Find` returns only active objects.
 * - **Scripts on an inactive object do not run**, and a script's `Awake` runs when its
 *   object is first active, `Start` before its first update after that.
 * - **An exception in a callback aborts that call only.** The player logged it and carried
 *   on, and several of the game's scripts do throw (a `Find` that returns null).
 * - **`Animation.Play` stops every other state and does not rewind one already playing**;
 *   `playAutomatically` plays the default clip each time the object is activated.
 *
 * Like Unity, the state a script reaches through a class name (`Time.time`,
 * `GameObject.Find`, a static trigger flag) is global: [Iq] holds the running [IqRuntime].
 */
object Iq {
    lateinit var rt: IqRuntime

    val world get() = rt.world
    val time get() = rt.time
    val deltaTime get() = rt.deltaTime
    val prefs get() = rt.host.prefs
    val touches get() = rt.touches

    /** `GameObject.Find`: a name, or a path of names; a leading `/` anchors it at a root. */
    fun find(path: String): GObj? = rt.world.find(path)

    fun loadLevel(name: String) { rt.pendingLevel = name }

    /** `Random.value`. */
    fun random(): Float = rt.random.nextFloat()

    fun log(msg: String) = rt.host.log(msg)

    fun openURL(url: String) = rt.host.openUrl(url)
}

data class V2(val x: Float, val y: Float) {
    operator fun plus(o: V2) = V2(x + o.x, y + o.y)
    operator fun minus(o: V2) = V2(x - o.x, y - o.y)
    operator fun times(s: Float) = V2(x * s, y * s)
    fun length() = kotlin.math.sqrt(x * x + y * y)

    companion object { val ZERO = V2(0f, 0f) }
}

/** `iPhoneTouch`: [position] in points from the bottom left, y up. */
class IqTouch(val fingerId: Int, val position: V2, val deltaPosition: V2, val deltaTime: Float, val phase: Int, val flick: FlickShot? = null, val sampled: Boolean = false) {
    companion object {
        const val BEGAN = 0
        const val MOVED = 1
        const val STATIONARY = 2
        const val ENDED = 3
        const val CANCELED = 4
    }
}

/** `PlayerPrefs`. */
interface IqPrefs {
    fun hasKey(key: String): Boolean
    fun getInt(key: String, def: Int = 0): Int
    fun getFloat(key: String, def: Float = 0f): Float
    fun getString(key: String, def: String = ""): String
    fun setInt(key: String, v: Int)
    fun setFloat(key: String, v: Float)
    fun setString(key: String, v: String)
    fun deleteKey(key: String)
}

class MemoryPrefs : IqPrefs {
    val map = HashMap<String, Any>()
    override fun hasKey(key: String) = key in map
    override fun getInt(key: String, def: Int) = map[key] as? Int ?: def
    override fun getFloat(key: String, def: Float) = map[key] as? Float ?: def
    override fun getString(key: String, def: String) = map[key] as? String ?: def
    override fun setInt(key: String, v: Int) { map[key] = v }
    override fun setFloat(key: String, v: Float) { map[key] = v }
    override fun setString(key: String, v: String) { map[key] = v }
    override fun deleteKey(key: String) { map.remove(key) }
}

/** What the platform supplies: sound out, saved preferences, a log. */
interface IqHost {
    val prefs: IqPrefs
    /** Start [audio] (an index into [IqPack.audio]); returns a handle for [stop]. */
    fun play(audio: Int, volume: Float, pitch: Float, loop: Boolean): Int = 0
    fun stop(handle: Int) {}
    fun setVolume(handle: Int, volume: Float) {}
    fun log(msg: String) {}
    /** `iPhoneKeyboard.Open(text)`. The default types nothing and is done at once. */
    fun openKeyboard(text: String): IqKeyboard = IqKeyboard(text).also { it.done = true; it.active = false }
    /** `Application.OpenURL`. */
    fun openUrl(url: String) {}
}

/** `iPhoneKeyboard`: the host fills [text] and sets [done] when the user presses Done. */
class IqKeyboard(@Volatile var text: String) {
    @Volatile var active = true
    @Volatile var done = false
}

/**
 * One `GUI.Label` as the frame drew it: [skin] is the GUISkin's asset name (`fontSkin`,
 * `UISkin`, …), which the renderer maps to a font. [center] is `TextAnchor.MiddleCenter`,
 * [right] `UpperRight` and [topCenter] `UpperCenter` (otherwise the skin's upper-left).
 * [color] is the label style's `normal.textColor` as ARGB when a script changed it, else null
 * for the skin's own.
 */
class GuiLabel(
    val rect: Rect, val text: String, val skin: String, val center: Boolean,
    val right: Boolean = false, val topCenter: Boolean = false, val color: Int? = null,
) {
    companion object {
        /** r and g set to 255 and b to 0 over an opaque colour, which the game does for highlights. */
        const val YELLOW = 0xFFFFFF00.toInt()
    }
}

object SilentHost : IqHost {
    override val prefs = MemoryPrefs()
}

/**
 * Everything that outlives a level: the pack, the host, `Time`, the static fields of every
 * script class ([IqStatics]), and the level now loaded.
 */
class IqRuntime(val pack: IqPack, val host: IqHost, firstLevel: String = LEVEL_FRONTEND) {
    var viewport = IqViewport(320,480)
    var time = 0f
        private set
    var deltaTime = FRAME_DT
        private set
    var frameCount = 0
        private set
    val random = java.util.Random(1)
    var touches: List<IqTouch> = emptyList()
        private set
    internal var pendingLevel: String? = null
    lateinit var world: IqWorld
        private set

    /** The point, top-left origin, of a touch that ended this frame, for OnGUI buttons. */
    internal var guiUp: V2? = null
    internal var guiDown: V2? = null
    private var guiDownPos: V2? = null

    /** This frame's `GUI.Label`s, in draw order; the renderer reads them. */
    var labels: List<GuiLabel> = emptyList()
        private set
    internal val labelsBuilding = ArrayList<GuiLabel>()

    init {
        Iq.rt = this
        IqStatics.resetAll()
        load(firstLevel)
    }

    private fun load(level: String) {
        val scene = when (level) {
            LEVEL_FRONTEND, "mainData" -> "mainData"
            // The build named the game scene "qtr"; the pack calls it by its file name.
            "qtr" -> LEVEL_GAME
            else -> level
        }
        if (this::world.isInitialized) for (go in world.objects) go.audio?.stop()
        world = IqWorld(this, pack.scene(scene))
        world.awake()
    }

    /**
     * One player frame, Unity's order: the fixed steps that fall due (each `FixedUpdate`,
     * the simulation, collision messages), `Update`, the animations, `LateUpdate`, `OnGUI`,
     * then a pending `LoadLevel`.
     */
    fun frame(dt: Float, newTouches: List<IqTouch>) {
        Iq.rt = this
        frameCount++
        deltaTime = dt
        touches = newTouches
        guiUp = null
        guiDown = null
        for (t in newTouches) {
            val p = V2(t.position.x, SCREEN_H - t.position.y)
            if (t.phase == IqTouch.BEGAN) { guiDownPos = p; guiDown = p }
            if (t.phase == IqTouch.ENDED) guiUp = p
        }
        labelsBuilding.clear()
        world.frame(dt) { time = it }
        labels = ArrayList(labelsBuilding)
        if (guiUp != null) guiDownPos = null
        pendingLevel?.let { pendingLevel = null; load(it) }
    }

    /** `GUI.Button`'s hit: pressed and released inside [r] (top-left origin). */
    internal fun guiClicked(r: Rect): Boolean {
        val up = guiUp ?: return false
        val down = guiDownPos ?: return false
        return r.contains(up) && r.contains(down)
    }

    companion object {
        const val FRAME_DT = 1f / 30f
        const val SCREEN_W = 320f
        const val SCREEN_H = 480f
        const val LEVEL_FRONTEND = "frontend"
        const val LEVEL_GAME = "level0"
    }
}

/** `Rect`, top-left origin as GUI uses it. */
data class Rect(val x: Float, val y: Float, val width: Float, val height: Float) {
    fun contains(p: V2) = p.x >= x && p.x < x + width && p.y >= y && p.y < y + height
}

/** One loaded scene. */
class IqWorld(val rt: IqRuntime, val scene: IqPack.Scene) {
    val pack = rt.pack
    val objects: List<GObj>
    /** `Time.time` runs on across levels, so a new level starts from the runtime's clock. */
    var fixedTime = rt.time
        private set
    private var fixedAccumulator = 0f
    val physics: IqPhysics
    val bodies = ArrayList<GObj>()

    init {
        objects = scene.nodes.mapIndexed { i, n -> GObj(this, i, n) }
        for (o in objects) if (o.src.parent >= 0) {
            o.parent = objects[o.src.parent]
            objects[o.src.parent].children += o
        }
        for (o in objects) o.createScripts()
        val s = pack.settings
        physics = IqPhysics(
            gravity = V3(s.getValue("gravityX"), s.getValue("gravityY"), s.getValue("gravityZ")),
            bounceThreshold = s.getValue("bounceThreshold"),
            sleepVelocity = s.getValue("sleepVelocity"),
            sleepAngularVelocity = s.getValue("sleepAngularVelocity"),
            maxAngularVelocity = s.getValue("maxAngularVelocity"),
            slop = s.getValue("minPenetrationForPenalty"),
            iterations = s.getValue("solverIterationCount").toInt(),
        )
        IqColliders.build(this)
    }

    val fixedDt = pack.settings.getValue("fixedTimestep")

    fun roots() = objects.asSequence().filter { it.parent == null }

    fun find(path: String): GObj? {
        if (path.startsWith("/")) {
            val parts = path.substring(1).split('/')
            var cur = roots().firstOrNull { it.name == parts[0] && it.active } ?: return null
            for (k in 1 until parts.size) cur = cur.children.firstOrNull { it.name == parts[k] && it.active } ?: return null
            return cur
        }
        if ('/' in path) {
            val parts = path.split('/')
            for (o in objects) {
                if (o.name != parts.last() || !o.active) continue
                var cur: GObj? = o
                var ok = true
                for (k in parts.size - 2 downTo 0) {
                    cur = cur?.parent
                    if (cur == null || cur.name != parts[k]) { ok = false; break }
                }
                if (ok) return o
            }
            return null
        }
        return objects.firstOrNull { it.name == path && it.active }
    }

    internal fun awake() {
        for (o in objects) if (o.active) o.onActivated()
    }

    private inline fun each(block: (Behaviour) -> Unit) {
        for (o in objects) {
            if (!o.active) continue
            for (s in o.scripts) if (s.enabled && s.awoken) guard(s) { block(s) }
        }
    }

    private fun startPending() {
        for (o in objects) {
            if (!o.active) continue
            for (s in o.scripts) if (s.enabled && s.awoken && !s.started) {
                s.started = true
                guard(s) { s.start() }
            }
        }
    }

    internal inline fun guard(s: Behaviour, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            if (e is IqFatal) throw e
            rt.host.log("${s.javaClass.simpleName} on ${s.gameObject.name}: $e")
            if (strict) throw e
        }
    }

    /** Tests set this so a script's exception fails the test instead of being logged. */
    var strict = false

    /**
     * `Time.time` is the fixed clock inside `FixedUpdate` and the frame clock elsewhere,
     * as in Unity; the two agree to within a step.
     */
    internal fun frame(dt: Float, setTime: (Float) -> Unit) {
        fixedAccumulator += dt
        startPending()
        while (fixedAccumulator >= fixedDt * 0.999f) {
            fixedAccumulator -= fixedDt
            fixedTime += fixedDt
            setTime(fixedTime)
            startPending()
            each { it.fixedUpdate() }
            IqColliders.step(this, fixedDt)
        }
        frameTime += dt
        setTime(frameTime)
        startPending()
        each { it.update() }
        for (o in objects) if (o.active) o.anim?.tick(dt)
        each { it.lateUpdate() }
        for (o in objects) if (o.active) o.particles?.tick(dt)
        each { it.onGUI() }
        for (o in objects) o.audio?.tick()
    }

    private var frameTime = rt.time
}

class IqFatal(msg: String) : RuntimeException(msg)

/** A GameObject with its Transform and the components the game uses. */
class GObj(val world: IqWorld, val index: Int, val src: IqPack.Node) {
    val name = src.name
    var parent: GObj? = null
        internal set
    val children = ArrayList<GObj>()
    val layer = src.layer
    val tag = src.tag

    private var _active = src.active
    var active: Boolean
        get() = _active
        set(v) {
            if (v == _active) return
            _active = v
            if (v) onActivated() else onDeactivated()
        }

    fun setActiveRecursively(v: Boolean) {
        active = v
        for (c in children) c.setActiveRecursively(v)
    }

    // -- Transform ------------------------------------------------------------------------

    var localPosition = src.position
        set(v) { field = v; moved() }
    var localRotation = src.rotation
        set(v) { field = v; moved() }
    var localScale = src.scale
        set(v) { field = v; moved() }

    private fun moved() {
        rigidbody?.body?.let { if (!syncingFromBody) { it.position = position; it.rotation = rotation } }
    }

    internal var syncingFromBody = false

    fun localMatrix() = M4.trs(localPosition, localRotation, localScale)
    fun worldMatrix(): M4 = parent?.let { it.worldMatrix() * localMatrix() } ?: localMatrix()

    var position: V3
        get() = parent?.worldMatrix()?.point(localPosition) ?: localPosition
        set(v) { localPosition = parent?.worldMatrix()?.inverse()?.point(v) ?: v }

    var rotation: Quat
        get() = parent?.let { it.rotation * localRotation } ?: localRotation
        set(v) { localRotation = parent?.let { it.rotation.conjugate() * v } ?: v }

    var eulerAngles: V3
        get() = rotation.eulerAngles()
        set(v) { rotation = Quat.euler(v) }

    var localEulerAngles: V3
        get() = localRotation.eulerAngles()
        set(v) { localRotation = Quat.euler(v) }

    val lossyScale get() = worldMatrix().lossyScale()
    val forward get() = rotation.rotate(V3.FORWARD)
    val right get() = rotation.rotate(V3.RIGHT)
    val up get() = rotation.rotate(V3.UP)

    fun lookAt(target: V3) {
        val d = target - position
        if (d.lengthSq() > 0f) rotation = Quat.lookRotation(d)
    }

    fun lookAt(target: GObj) = lookAt(target.position)

    fun translate(d: V3) { localPosition += d }

    /** `Transform.Find`: a child path below this one, active or not. */
    fun child(path: String): GObj? {
        var cur: GObj = this
        for (p in path.split('/')) cur = cur.children.firstOrNull { it.name == p } ?: return null
        return cur
    }

    /** `GetComponentInChildren(typeof(Renderer))`: this object's, else the first below it. */
    fun rendererInChildren(): Rend? {
        renderer?.let { return it }
        for (c in children) c.rendererInChildren()?.let { return it }
        return null
    }

    fun isChildOf(o: GObj): Boolean {
        var k: GObj? = this
        while (k != null) { if (k === o) return true; k = k.parent }
        return false
    }

    // -- components -----------------------------------------------------------------------

    val renderer: Rend? = if (src.renderer != 0) Rend(this) else null
    val meshFilter: MeshInst? = if (src.mesh >= 0) MeshInst(world.pack.meshes[src.mesh]) else null
    val anim: Anim? = src.animation?.let { Anim(this, it) }
    val audio: AudioSrc? = src.audioSource?.let { AudioSrc(this, it) }
    val camera: Cam? = src.camera?.let { Cam(it) }
    val light: IqPack.Light? = src.light
    var rigidbody: RigidbodyComp? = src.rigidbody?.let { RigidbodyComp(this, it) }
        internal set
    val colliders: List<Col> = src.colliders.mapIndexed { i, c -> Col(this, c, i) }
    val collider get() = colliders.firstOrNull()
    val particles: Particles? = src.particles?.let { Particles(this, it) }

    val scripts = ArrayList<Behaviour>()

    internal fun createScripts() {
        for (s in src.scripts) {
            val b = IqScripts.create(s.className) ?: continue
            b.bind(this, s)
            scripts += b
        }
    }

    inline fun <reified T : Behaviour> script(): T? = scripts.firstOrNull { it is T } as T?

    /** The nearest Rigidbody at or above this object: a collider's `attachedRigidbody`. */
    val attachedRigidbody: RigidbodyComp?
        get() {
            var k: GObj? = this
            while (k != null) { k.rigidbody?.let { return it }; k = k.parent }
            return null
        }

    internal fun onActivated() {
        for (s in scripts) if (!s.awoken) {
            s.awoken = true
            world.guard(s) { s.awake() }
        }
        for (s in scripts) if (s.started && s.enabled) world.guard(s) { s.onEnable() }
        anim?.let { if (it.playAutomatically && it.enabled) it.play() }
        audio?.let { if (it.playOnAwake && it.enabled) it.play() }
    }

    private fun onDeactivated() {
        for (s in scripts) if (s.awoken && s.enabled) world.guard(s) { s.onDisable() }
        audio?.stop()
        anim?.let { it.stopAll() }
    }

    override fun toString() = "GObj($name)"
}

/** A `Renderer`, with the per-object material instances `renderer.material` makes. */
class Rend(val go: GObj) {
    var enabled = go.src.renderer == 1
    private val pack = go.world.pack
    val materials: Array<MatInst> = Array(go.src.materials.size) { MatInst(pack.materials[go.src.materials[it]]) }

    /** `renderer.material`: the first material, instanced on first write (here, always). */
    val material: MatInst get() = materials[0]
}

/** One material as drawn, mutable where scripts change it. */
class MatInst(val src: IqPack.Material) {
    /**
     * `material.shader = Shader.Find(path)`. The game's own shaders are named by their menu
     * path there (`iPhone/Transparent/Vertex Color`) and by their asset name in the pack
     * (`iPhone Transparent Vertex Color`); the renderer knows the second.
     */
    var shader: String = src.shader
        set(v) { field = if (v.startsWith("iPhone/")) v.replace('/', ' ') else v }
    var color: FloatArray = src.color.copyOf()
    var mainTex: Int = src.mainTex
    var mainTextureOffset = V2(src.mainST[2], src.mainST[3])
    var mainTextureScale = V2(src.mainST[0], src.mainST[1])

    fun setAlpha(a: Float) { color = color.copyOf().also { it[3] = a } }
}

/** `MeshFilter.mesh`: the pack mesh with overrides for what scripts rewrite. */
class MeshInst(val src: IqPack.Mesh) {
    /** Bumped on each write so the renderer knows to re-upload. */
    var version = 0
        private set
    /** Unity's `mesh.uv = a`: a copy is read, changed, and assigned back. */
    var uv: FloatArray? = src.uv
        set(v) { field = v; version++ }
    var colors: ByteArray? = src.colors
        set(v) { field = v; version++ }
    var vertices: FloatArray = src.positions
        set(v) { field = v; version++ }
    val modified get() = version > 0
}

class Cam(val src: IqPack.Camera) {
    var enabled = src.enabled
    var fieldOfView = src.fov
}

/**
 * Unity's legacy particles: an `EllipsoidParticleEmitter` with its `ParticleAnimator`,
 * simulated once per frame ([tick]) and drawn as billboards by [IqRenderer].
 *
 * The game has one, `GlassFlash`: QuarterTrigger moves it to each contact and calls
 * `Emit(1)`, and a 0.1s `flash_00` starburst fades out there. What each field means
 * is Unity's documented legacy behaviour. Four details are argued, not traced, because
 * Unity's own code is not in the bundle:
 * - `sizeGrow` is taken as relative, `size += size * sizeGrow * dt`.
 * - `damping` is applied once per frame, as the docs word it.
 * - The ellipsoid is spawned in as a solid volume of that size.
 * - `tangentVelocity`, `emitterVelocityScale` and local-space simulation are not
 *   modelled. `GlassFlash` simulates in world space with zero tangent velocity; its
 *   velocity scale is Unity's default 0.05, left out because how Unity measures an
 *   emitter's velocity when a script teleports it to each contact is not known.
 *
 * Particles have their own random source, so emitting never moves the sequence the
 * game's scripts draw from.
 */
class Particles(val go: GObj, val src: IqPack.Particles) {
    class Particle(var position: V3, var velocity: V3, var size: Float, var energy: Float, val startEnergy: Float) {
        /** The animator's colour now, RGBA 0..1. */
        val color = FloatArray(4) { 1f }
    }

    val particles = ArrayList<Particle>()
    var emit = src.emit
    private var pending = 0f
    private val random = java.util.Random(go.index.toLong())

    private fun range(a: Float, b: Float) = a + (b - a) * random.nextFloat()
    private fun signed() = random.nextFloat() * 2f - 1f

    /** `ParticleEmitter.Emit(n)`. */
    fun emit(n: Int) = repeat(n) { spawn() }

    private fun spawn() {
        val rot = go.rotation
        var p = V3(signed(), signed(), signed())
        while (p.lengthSq() > 1f) p = V3(signed(), signed(), signed())
        val e = src.ellipsoid * 0.5f
        val offset = V3(p.x * e.x, p.y * e.y, p.z * e.z)
        val r = src.rndVelocity
        val velocity = src.worldVelocity + rot.rotate(src.localVelocity) +
            V3(r.x * signed(), r.y * signed(), r.z * signed())
        val energy = range(src.minEnergy, src.maxEnergy)
        val particle = Particle(go.position + rot.rotate(offset), velocity, range(src.minSize, src.maxSize), energy, energy)
        animate(particle)
        particles += particle
    }

    internal fun tick(dt: Float) {
        if (emit && go.active) {
            pending += range(src.minEmission, src.maxEmission) * dt
            while (pending >= 1f) { pending -= 1f; spawn() }
        }
        val a = src.animator
        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.energy -= dt
            if (p.energy <= 0f) { it.remove(); continue }
            if (a != null) {
                val rf = a.rndForce
                p.velocity = (p.velocity + a.force * dt + V3(rf.x * signed(), rf.y * signed(), rf.z * signed()) * dt) * a.damping
                p.size += p.size * a.sizeGrow * dt
            }
            p.position = p.position + p.velocity * dt
            animate(p)
        }
    }

    private fun animate(p: Particle) {
        val a = src.animator ?: return
        if (!a.animateColor) return
        val t = (1f - p.energy / p.startEnergy).coerceIn(0f, 1f) * (a.colors.size - 1)
        val k = minOf(t.toInt(), a.colors.size - 2)
        val f = t - k
        for (c in 0..3) p.color[c] = a.colors[k][c] + (a.colors[k + 1][c] - a.colors[k][c]) * f
    }
}

class Col(val go: GObj, val src: IqPack.Collider, val ordinal: Int) {
    var enabled = true
    val name get() = go.name
    val attachedRigidbody get() = go.attachedRigidbody
    val gameObject get() = go
    override fun toString() = "Col(${go.name})"
}

/**
 * A Rigidbody. The quarter's is the one dynamic [body]; every other rigidbody in the game is
 * kinematic and only matters for its [mass], which the game reads as a sound type.
 */
class RigidbodyComp(val go: GObj, val src: IqPack.Rigidbody) {
    var body: IqPhysics.Body? = null
        internal set
    val mass = src.mass
    private var kinematicNoBody = src.isKinematic
    var freezeRotation = false

    var isKinematic: Boolean
        get() = body?.isKinematic ?: kinematicNoBody
        set(v) { body?.let { it.isKinematic = v; if (!v) it.wakeUp() } ?: run { kinematicNoBody = v } }

    var position: V3
        get() = body?.position ?: go.position
        set(v) { body?.position = v; syncTransform() }

    var rotation: Quat
        get() = body?.rotation ?: go.rotation
        set(v) { body?.rotation = v; syncTransform() }

    var velocity: V3
        get() = body?.velocity ?: V3.ZERO
        set(v) { body?.let { it.velocity = v; if (v != V3.ZERO) it.wakeUp() } }

    var angularVelocity: V3
        get() = body?.angularVelocity ?: V3.ZERO
        set(v) { body?.angularVelocity = v }

    fun movePosition(p: V3) { position = p }
    fun moveRotation(q: Quat) { rotation = q }
    fun addTorque(t: V3) { body?.let { it.addTorque(t); it.wakeUp() } }
    fun wakeUp() { body?.wakeUp() }
    fun isSleeping() = body?.sleeping ?: true

    internal fun syncTransform() {
        val b = body ?: return
        go.syncingFromBody = true
        go.position = b.position
        go.rotation = b.rotation
        go.syncingFromBody = false
    }
}

/**
 * `AudioSource`. Playing hands the clip to the host; `isPlaying` is judged by the clip's
 * length, which is what the game ever asks of it.
 */
class AudioSrc(val go: GObj, src: IqPack.AudioSource) {
    var enabled = src.enabled
    var clip: Int = src.clip
    var volume = src.volume
        set(v) { field = v; if (handle != 0) host.setVolume(handle, v) }
    var pitch = src.pitch
    var loop = src.loop
    val playOnAwake = src.playOnAwake
    private val host get() = go.world.rt.host
    private var handle = 0
    private var endsAt = -1f

    val isPlaying get() = handle != 0 && (loop || Iq.time < endsAt)

    fun play() {
        stop()
        if (clip < 0) return
        handle = host.play(clip, volume, pitch, loop)
        if (handle == 0) handle = -1
        endsAt = Iq.time + go.world.pack.audio[clip].length / max(pitch, 0.01f)
    }

    fun playOneShot(c: Int, vol: Float = 1f) {
        if (c >= 0) host.play(c, volume * vol, pitch, false)
    }

    fun stop() {
        if (handle > 0) host.stop(handle)
        handle = 0
    }

    internal fun tick() {
        if (handle != 0 && !loop && Iq.time >= endsAt) handle = 0
    }
}

/**
 * The legacy `Animation` component: named states over clips, each with its own time,
 * speed and wrap mode, sampled onto the transforms under this object after `Update`.
 * Clips are Hermite curves per position/rotation/scale channel, as Unity stored them.
 */
class Anim(val go: GObj, src: IqPack.Animation) {
    var enabled = src.enabled
    var playAutomatically = src.playAutomatically
    private val pack = go.world.pack
    private val defaultWrap = src.wrap

    /** A state: [clip] from [start] to [start]+[length] seconds of the source clip. */
    inner class State(val name: String, val clip: IqPack.Clip, val start: Float, val length: Float) {
        var time = 0f
        var speed = 1f
        var enabled = false
        var weight = 1f
        var wrapMode = if (defaultWrap != 0) defaultWrap else clip.wrap
        val normalizedTime get() = if (length > 0f) time / length else 0f
    }

    private val states = LinkedHashMap<String, State>()
    private val queue = ArrayList<String>()

    /** `animation.clip`: the default clip's name. */
    var clip: String? = null
        private set

    init {
        for (ci in src.clips) if (ci >= 0) addState(pack.clips[ci].name, pack.clips[ci], 0f, pack.clips[ci].length)
        if (src.clip >= 0) {
            val c = pack.clips[src.clip]
            if (c.name !in states) addState(c.name, c, 0f, c.length)
            clip = c.name
        }
    }

    private fun addState(name: String, c: IqPack.Clip, start: Float, length: Float) {
        states[name] = State(name, c, start, length)
    }

    operator fun get(name: String): State? = states[name]

    /** `foreach (AnimationState s in animation)`. */
    val allStates: Collection<State> get() = states.values

    fun getClipCount() = states.size

    /** `AddClip(clip, name, firstFrame, lastFrame)`: frames at the clip's sample rate. */
    fun addClip(source: String?, name: String, firstFrame: Int, lastFrame: Int) {
        val base = states[source ?: return] ?: return
        val sr = base.clip.sampleRate.takeIf { it > 0f } ?: 30f
        addState(name, base.clip, firstFrame / sr, (lastFrame - firstFrame) / sr)
    }

    fun play(name: String? = clip): Boolean {
        val s = states[name ?: return false] ?: return false
        for (o in states.values) if (o !== s) { o.enabled = false; o.time = 0f }
        queue.clear()
        if (!s.enabled) { s.enabled = true; s.time = 0f }
        sample(s)
        return true
    }

    /** `PlayQueued(name)`: plays once everything now playing has finished. */
    fun playQueued(name: String) {
        if (name !in states) return
        if (!isPlaying) play(name) else queue += name
    }

    fun stop(name: String? = null) {
        if (name == null) stopAll() else states[name]?.let { it.enabled = false; it.time = 0f }
    }

    internal fun stopAll() {
        for (s in states.values) { s.enabled = false; s.time = 0f }
        queue.clear()
    }

    fun isPlaying(name: String) = states[name]?.enabled == true || name in queue
    val isPlaying get() = states.values.any { it.enabled } || queue.isNotEmpty()

    internal fun tick(dt: Float) {
        if (!enabled) return
        for (s in states.values) {
            if (!s.enabled) continue
            s.time += dt * s.speed
            val once = s.wrapMode == WRAP_ONCE || s.wrapMode == WRAP_DEFAULT
            if (once && (s.time >= s.length || s.time < 0f)) {
                s.time = s.time.coerceIn(0f, s.length)
                sample(s)
                s.enabled = false
                s.time = 0f
                continue
            }
            sample(s)
        }
        if (queue.isNotEmpty() && states.values.none { it.enabled }) play(queue.removeAt(0))
    }

    /** Where in the source clip a state's [State.time] falls, by its wrap mode. */
    private fun clipTime(s: State): Float {
        val len = s.length
        val t = s.time
        val local = when (s.wrapMode) {
            WRAP_LOOP -> if (len > 0f) t - floor(t / len) * len else 0f
            WRAP_PINGPONG -> if (len > 0f) {
                val k = t - floor(t / (2 * len)) * 2 * len
                if (k > len) 2 * len - k else k
            } else 0f
            else -> t.coerceIn(0f, len)
        }
        return s.start + local
    }

    private fun sample(s: State) {
        val t = clipTime(s)
        for (c in s.clip.curves) {
            val target = if (c.path.isEmpty()) go else go.child(c.path) ?: continue
            val v = evaluate(c, t)
            when (c.kind) {
                IqPack.Curve.POSITION -> target.localPosition = V3(v[0], v[1], v[2])
                IqPack.Curve.ROTATION -> target.localRotation = Quat(v[0], v[1], v[2], v[3]).normalized()
                IqPack.Curve.SCALE -> target.localScale = V3(v[0], v[1], v[2])
            }
        }
    }

    companion object {
        const val WRAP_DEFAULT = 0
        const val WRAP_ONCE = 1
        const val WRAP_LOOP = 2
        const val WRAP_PINGPONG = 4
        const val WRAP_CLAMP_FOREVER = 8

        /** Unity's `AnimationCurve.Evaluate`: cubic Hermite between keys, clamped outside. */
        fun evaluate(c: IqPack.Curve, t: Float): FloatArray {
            val k = c.keys
            val d = c.dim
            if (k.isEmpty()) return FloatArray(d)
            if (t <= k[0].time) return k[0].value.copyOf()
            if (t >= k.last().time) return k.last().value.copyOf()
            var i = 0
            while (i < k.size - 2 && t >= k[i + 1].time) i++
            val a = k[i]
            val b = k[i + 1]
            val dt = b.time - a.time
            if (dt <= 0f) return b.value.copyOf()
            val u = (t - a.time) / dt
            val u2 = u * u
            val u3 = u2 * u
            val h00 = 2 * u3 - 3 * u2 + 1
            val h10 = u3 - 2 * u2 + u
            val h01 = -2 * u3 + 3 * u2
            val h11 = u3 - u2
            return FloatArray(d) { j ->
                val m0 = a.outSlope[j]
                val m1 = b.inSlope[j]
                if (m0.isInfinite() || m1.isInfinite()) a.value[j]
                else h00 * a.value[j] + h10 * dt * m0 + h01 * b.value[j] + h11 * dt * m1
            }
        }
    }
}

/**
 * A `MonoBehaviour`. Ported scripts override the callbacks they had and read their
 * serialized fields with [num], [int], [bool], [str], [obj], [audioClip] and friends, each
 * with the default the class's constructor gives when the field was not serialized.
 */
abstract class Behaviour {
    lateinit var gameObject: GObj
        private set
    lateinit var serialized: IqPack.Script
        private set
    var enabled = true
    internal var awoken = false
    internal var started = false

    internal fun bind(go: GObj, s: IqPack.Script) {
        gameObject = go
        serialized = s
        enabled = s.enabled
        onBind()
    }

    /** Reads serialized fields; called once the object is bound, before `Awake`. */
    protected open fun onBind() {}

    open fun awake() {}
    open fun start() {}
    open fun update() {}
    open fun fixedUpdate() {}
    open fun lateUpdate() {}
    open fun onGUI() {}
    open fun onEnable() {}
    open fun onDisable() {}
    open fun onCollisionEnter(c: IqCollision) {}
    open fun onCollisionStay(c: IqCollision) {}

    // Unity's shortcuts.
    val transform get() = gameObject
    val renderer get() = gameObject.renderer
    val animation get() = gameObject.anim
    val audio get() = gameObject.audio
    val rigidbody get() = gameObject.rigidbody
    val camera get() = gameObject.camera
    val world get() = gameObject.world

    // Serialized fields.
    private fun raw(name: String) = serialized.fields[name]
    protected fun num(name: String, def: Float = 0f) = (raw(name) as? IqPack.Value.Num)?.v?.toFloat() ?: def
    protected fun int(name: String, def: Int = 0) = (raw(name) as? IqPack.Value.Num)?.v?.toInt() ?: def
    protected fun bool(name: String, def: Boolean = false) = (raw(name) as? IqPack.Value.Num)?.let { it.v != 0.0 } ?: def
    protected fun str(name: String, def: String = "") = (raw(name) as? IqPack.Value.Str)?.v ?: def
    protected fun vec(name: String, def: V3 = V3.ZERO) = (raw(name) as? IqPack.Value.Floats)?.v?.let { V3(it[0], it[1], it[2]) } ?: def
    protected fun obj(name: String): GObj? = ref(raw(name))
    protected fun objs(name: String): List<GObj?> = (raw(name) as? IqPack.Value.Arr)?.items?.map { ref(it) } ?: emptyList()
    protected fun audioClip(name: String): Int = asset(raw(name), IqPack.Value.AUDIO)
    protected fun audioClips(name: String): IntArray =
        (raw(name) as? IqPack.Value.Arr)?.items?.map { asset(it, IqPack.Value.AUDIO) }?.toIntArray() ?: IntArray(0)
    protected fun texture(name: String): Int = asset(raw(name), IqPack.Value.TEXTURE)
    protected fun textures(name: String): IntArray =
        (raw(name) as? IqPack.Value.Arr)?.items?.map { asset(it, IqPack.Value.TEXTURE) }?.toIntArray() ?: IntArray(0)
    protected fun material(name: String): Int = asset(raw(name), IqPack.Value.MATERIAL)
    protected fun materials(name: String): IntArray =
        (raw(name) as? IqPack.Value.Arr)?.items?.map { asset(it, IqPack.Value.MATERIAL) }?.toIntArray() ?: IntArray(0)

    private fun ref(v: IqPack.Value?): GObj? = (v as? IqPack.Value.Ref)?.let { if (it.node >= 0) world.objects[it.node] else null }
    private fun asset(v: IqPack.Value?, kind: Int) = (v as? IqPack.Value.Asset)?.takeIf { it.kind == kind }?.index ?: -1

    // Unity statics used everywhere.
    protected val time get() = Iq.time
    protected val deltaTime get() = Iq.deltaTime
    protected fun find(path: String) = Iq.find(path)

    /** `GUI.Button`: invisible (every button in the game draws with a dummy skin). */
    protected fun guiButton(r: Rect): Boolean {
        val v = world.rt.viewport
        val adjusted = when(this) {
            is com.guille.spring.iquarters.scripts.PauseButtonScript -> r.copy(x=r.x+v.extraX,y=r.y+v.extraY)
            is com.guille.spring.iquarters.scripts.InGameAngleIcon -> r.copy(x=r.x+if(r.x>160) v.extraX else -v.extraX,y=r.y-v.extraY)
            else -> r
        }
        return world.rt.guiClicked(adjusted)
    }

    /** `GUI.Label(r, text)` under the skin named [skin]. */
    protected fun guiLabel(
        r: Rect, text: String, skin: String, center: Boolean = false, right: Boolean = false,
        topCenter: Boolean = false, color: Int? = null,
    ) {
        world.rt.labelsBuilding += GuiLabel(r, text, skin, center, right, topCenter, color)
    }

    /** A GUISkin field, as the asset name it points at (null if unassigned). */
    protected fun skin(name: String): String? = (serialized.fields[name] as? IqPack.Value.Named)?.name

    /** `Camera.allCameras`: every enabled camera on an active object. */
    protected fun allCameras(): List<GObj> = world.objects.filter { it.active && it.camera?.enabled == true }

    /** `Screen.height > 480`: never, on the phone this runs as. */
    protected fun isIPad() = false
}

/** `Collision`, as the body's side sees it: [collider] is the other one. */
class IqCollision(val collider: Col, val contacts: List<IqContact>, val relativeVelocity: V3) {
    val gameObject get() = collider.go
    val rigidbody get() = collider.attachedRigidbody
}

/** `ContactPoint`: [normal] points into the object receiving the message. */
class IqContact(val point: V3, val normal: V3, val thisCollider: Col, val otherCollider: Col, val separation: Float)

/** A script class's static fields, reset when a new runtime starts. */
interface IqStatic {
    fun reset()
}

object IqStatics {
    private val all = LinkedHashSet<IqStatic>()
    fun register(s: IqStatic) { all += s; s.reset() }
    fun resetAll() { IqScripts.registerStatics(); for (s in all) s.reset() }
}

/** Unity `Mathf` helpers that Kotlin lacks. */
object Mathf {
    fun clamp01(v: Float) = v.coerceIn(0f, 1f)
    fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * clamp01(t)
    fun moveTowards(cur: Float, target: Float, maxDelta: Float) =
        if (abs(target - cur) <= maxDelta) target else cur + Math.signum(target - cur) * maxDelta
    fun lerpAngle(a: Float, b: Float, t: Float): Float {
        var d = (b - a) % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return a + d * clamp01(t)
    }
    fun repeat(t: Float, len: Float) = t - floor(t / len) * len
    fun min(a: Float, b: Float) = kotlin.math.min(a, b)
    fun max(a: Float, b: Float) = kotlin.math.max(a, b)
}

/** V3 lerp, `Vector3.Lerp`. */
fun lerp(a: V3, b: V3, t: Float): V3 {
    val k = t.coerceIn(0f, 1f)
    return a + (b - a) * k
}
