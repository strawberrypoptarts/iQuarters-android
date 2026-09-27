package com.guille.spring.iquarters

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater

/**
 * The pack `tools/iquarters/bake.py` writes out of iQuarters' own Unity data: both scenes
 * (`mainData`, the front end, and `level0`, the game) whole, with every mesh, material,
 * texture, physic material, animation clip and audio clip they use, and every
 * MonoBehaviour's serialized fields as a [Value] tree. The format is documented in
 * `bake.py`'s docstring, which this reader follows field for field.
 *
 * Pure Kotlin. Texture pixels stay encoded until [Texture.pixels] is asked for, so
 * the tests can load the pack without paying for the RGBA.
 */
class IqPack(
    val textures: List<Texture>,
    val meshes: List<Mesh>,
    val physMaterials: List<PhysMaterial>,
    val materials: List<Material>,
    val clips: List<Clip>,
    val audio: List<Audio>,
    val settings: Map<String, Float>,
    val scenes: List<Scene>,
) {
    class Texture(
        val name: String, val width: Int, val height: Int,
        /** Unity `TextureWrapMode`: 0 Repeat, 1 Clamp. */
        val wrap: Int,
        /** Unity `FilterMode`: 0 Point, 1 Bilinear, 2 Trilinear. */
        val filter: Int,
        val mipmap: Boolean,
        /** zlib'd RGBA8 rows, or a whole WebP image ([isWebp]). */
        val data: ByteArray,
    ) {
        /**
         * The pack may hold a texture as a WebP of the image the right way up rather
         * than as deflated rows: `tools/iquarters/webp_textures.py` rewrites them to
         * keep the APK small. Decoding one needs Android, so the renderer does it.
         */
        val isWebp: Boolean
            get() = data.size >= 12 && data[0] == 'R'.code.toByte() && data[1] == 'I'.code.toByte() &&
                data[2] == 'F'.code.toByte() && data[3] == 'F'.code.toByte()

        /** RGBA8, bottom row first, as `glTexImage2D` takes it. Deflated textures only. */
        fun pixels(): ByteArray {
            require(!isWebp) { "$name is WebP; decode it with BitmapFactory" }
            val deflated = data
            val out = ByteArray(width * height * 4)
            val inf = Inflater()
            inf.setInput(deflated)
            var n = 0
            while (n < out.size) {
                val k = inf.inflate(out, n, out.size - n)
                if (k == 0 && (inf.finished() || inf.needsInput())) break
                n += k
            }
            inf.end()
            require(n == out.size) { "$name: inflated $n of ${out.size} bytes" }
            return out
        }
    }

    class Mesh(
        val name: String,
        val positions: FloatArray,
        val normals: FloatArray?,
        val uv: FloatArray?,
        /** RGBA8 per vertex. */
        val colors: ByteArray?,
        val uv1: FloatArray?,
        /** Triangle lists, one per submesh / material slot. */
        val submeshes: List<ShortArray>,
    ) {
        val vertexCount get() = positions.size / 3
        fun vertex(i: Int) = V3(positions[3 * i], positions[3 * i + 1], positions[3 * i + 2])
    }

    /**
     * Unity `PhysicMaterial`. The combine modes are Unity's numbering: 0 Average,
     * 1 Multiply, 2 Minimum, 3 Maximum.
     */
    class PhysMaterial(
        val name: String, val dynamicFriction: Float, val staticFriction: Float,
        val bounciness: Float, val frictionCombine: Int, val bounceCombine: Int,
    )

    class Material(
        val name: String, val shader: String,
        val color: FloatArray, val specColor: FloatArray, val emission: FloatArray,
        val tintColor: FloatArray, val shininess: Float,
        val mainTex: Int, val mainST: FloatArray,
        val detailTex: Int, val detailST: FloatArray,
    )

    /**
     * An `AnimationClip` of transform curves only (iQuarters has no float curves).
     * [wrap] is Unity's `WrapMode`: 0 Default, 1 Once, 2 Loop, 4 PingPong, 8 ClampForever.
     */
    class Clip(val name: String, val sampleRate: Float, val wrap: Int, val curves: List<Curve>) {
        val length: Float = curves.maxOfOrNull { c -> c.keys.lastOrNull()?.time ?: 0f } ?: 0f
    }

    /** One path's position (0), rotation (1) or scale (2) curve. */
    class Curve(val path: String, val kind: Int, val preInfinity: Int, val postInfinity: Int, val keys: List<Key>) {
        val dim get() = if (kind == ROTATION) 4 else 3

        companion object {
            const val POSITION = 0
            const val ROTATION = 1
            const val SCALE = 2
        }
    }

    class Key(val time: Float, val value: FloatArray, val inSlope: FloatArray, val outSlope: FloatArray)

    /** An `AudioClip`, stored as `audio/[file]` beside the pack. */
    class Audio(val name: String, val file: String, val length: Float)

    class Rigidbody(
        val mass: Float, val drag: Float, val angularDrag: Float,
        val useGravity: Boolean, val isKinematic: Boolean, val interpolate: Boolean,
    )

    /** kind: 1 box, 2 sphere, 3 capsule, 4 mesh. See `bake.py` for [a]'s meaning. */
    class Collider(
        val kind: Int, val isTrigger: Boolean, val convex: Boolean, val physMaterial: Int,
        val center: V3, val a: V3, val mesh: Int,
    ) {
        companion object {
            const val BOX = 1
            const val SPHERE = 2
            const val CAPSULE = 3
            const val MESH = 4
        }
    }

    /** Unity `LightType`: 0 Spot, 1 Directional, 2 Point. */
    class Light(
        val enabled: Boolean, val type: Int, val color: FloatArray, val intensity: Float, val range: Float,
        val spotAngle: Float, val cullingMask: Int,
    )

    /** [clearFlags]: 1 Skybox, 2 SolidColor, 3 Depth, 4 Nothing. */
    class Camera(
        val enabled: Boolean,
        val fov: Float, val near: Float, val far: Float, val orthographic: Boolean,
        val orthoSize: Float, val depth: Float, val clearFlags: Int,
        val background: FloatArray, val cullingMask: Int, val viewport: FloatArray,
    )

    class Animation(
        val enabled: Boolean, val clip: Int, val clips: IntArray, val wrap: Int, val playAutomatically: Boolean,
    )

    /**
     * A legacy particle system: `EllipsoidParticleEmitter`, `ParticleAnimator` and
     * `ParticleRenderer` on one GameObject. Sizes and velocities are in world units,
     * energies in seconds, emission in particles per second.
     */
    class Particles(
        val minSize: Float, val maxSize: Float, val minEnergy: Float, val maxEnergy: Float,
        val minEmission: Float, val maxEmission: Float,
        val worldVelocity: V3, val localVelocity: V3, val rndVelocity: V3,
        val emitterVelocityScale: Float, val tangentVelocity: V3,
        val emit: Boolean, val worldSpace: Boolean, val oneShot: Boolean,
        val ellipsoid: V3, val minEmitterRange: Float,
        val animator: Animator?,
        val renderer: ParticleRenderer?,
    )

    /** [colors] is five RGBA keys spread evenly over a particle's life, 0..1. */
    class Animator(
        val animateColor: Boolean, val colors: Array<FloatArray>,
        val worldRotationAxis: V3, val localRotationAxis: V3, val sizeGrow: Float,
        val rndForce: V3, val force: V3, val damping: Float, val autodestruct: Boolean,
    )

    /** [stretch]: 0 billboard (the only mode drawn). */
    class ParticleRenderer(
        val enabled: Boolean, val material: Int, val stretch: Int,
        val xTile: Int, val yTile: Int, val cycles: Float,
    )

    class AudioSource(
        val enabled: Boolean, val clip: Int, val playOnAwake: Boolean, val volume: Float, val pitch: Float,
        val loop: Boolean,
    )

    class Script(val className: String, val enabled: Boolean, val fields: Value.Obj)

    class Node(
        val name: String, val parent: Int, val active: Boolean, val layer: Int, val tag: Int,
        val position: V3, val rotation: Quat, val scale: V3,
        val mesh: Int,
        /** 0 no renderer, 1 enabled, 2 disabled. */
        val renderer: Int,
        val materials: IntArray,
        val rigidbody: Rigidbody?,
        val colliders: List<Collider>,
        val light: Light?,
        val camera: Camera?,
        val animation: Animation?,
        val audioSource: AudioSource?,
        val particles: Particles?,
        val scripts: List<Script>,
    ) {
        fun script(className: String) = scripts.firstOrNull { it.className == className }
    }

    class Scene(val name: String, val settings: Map<String, Float>, val nodes: List<Node>) {
        /** The one root called [name]. */
        fun root(name: String): Int {
            val hits = nodes.indices.filter { nodes[it].name == name && nodes[it].parent < 0 }
            require(hits.size == 1) { "root $name: ${hits.size} matches" }
            return hits[0]
        }

        fun children(index: Int) = nodes.indices.filter { nodes[it].parent == index }
    }

    fun scene(name: String) = scenes.first { it.name == name }

    /** A serialized field value; see `bake.py` for the encoding. */
    sealed class Value {
        object Null : Value()
        data class Num(val v: Double) : Value()
        data class Str(val v: String) : Value()
        class Floats(val v: FloatArray) : Value()
        /** A reference into the same scene: the GameObject ([classId] 1) or one of its components. */
        data class Ref(val node: Int, val classId: Int, val ordinal: Int) : Value()
        data class Asset(val kind: Int, val index: Int) : Value()
        data class Named(val name: String) : Value()
        class Arr(val items: List<Value>) : Value()
        class Obj(val fields: Map<String, Value>) : Value() {
            operator fun get(key: String) = fields[key]
        }

        companion object {
            const val TEXTURE = 1
            const val MATERIAL = 2
            const val MESH = 3
            const val AUDIO = 4
            const val CLIP = 5
            const val PHYSMAT = 6
        }
    }

    companion object {
        /** Unity class ids a [Value.Ref] can name. */
        const val CLASS_GAMEOBJECT = 1
        const val CLASS_TRANSFORM = 4
        const val CLASS_CAMERA = 20
        const val CLASS_RENDERER = 23
        const val CLASS_RIGIDBODY = 54
        const val CLASS_MESH_COLLIDER = 64
        const val CLASS_BOX_COLLIDER = 65
        const val CLASS_AUDIO_SOURCE = 82
        const val CLASS_LIGHT = 108
        const val CLASS_ANIMATION = 111
        const val CLASS_MONOBEHAVIOUR = 114

        fun parse(bytes: ByteArray): IqPack {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { b.get(it) }
            require(String(magic, Charsets.US_ASCII) == "IQP2") { "not an iQuarters v2 pack" }
            require(b.int == 3) { "pack version: re-bake with tools/iquarters/bake.py" }

            fun str(): String {
                val n = b.short.toInt() and 0xffff
                val a = ByteArray(n).also { b.get(it) }
                return String(a, Charsets.UTF_8)
            }
            fun u8() = b.get().toInt() and 0xff
            fun u16() = b.short.toInt() and 0xffff
            fun f() = b.float
            fun v3() = V3(f(), f(), f())
            fun f4() = floatArrayOf(f(), f(), f(), f())
            fun floats(n: Int) = FloatArray(n).also { for (i in 0 until n) it[i] = b.float }
            fun settings(): Map<String, Float> {
                val s = LinkedHashMap<String, Float>()
                repeat(b.int) { s[str()] = f() }
                return s
            }
            fun value(): Value = when (val tag = u8()) {
                0 -> Value.Null
                1 -> Value.Num(b.double)
                2 -> Value.Str(str())
                3 -> Value.Floats(floats(u8()))
                4 -> Value.Ref(b.int, u16(), u8())
                5 -> Value.Asset(u8(), b.int)
                6 -> Value.Named(str())
                7 -> Value.Arr(List(u16()) { value() })
                8 -> {
                    val m = LinkedHashMap<String, Value>()
                    repeat(u16()) { m[str()] = value() }
                    Value.Obj(m)
                }
                else -> error("value tag $tag")
            }

            val textures = List(b.int) {
                val name = str(); val w = u16(); val h = u16()
                val wrap = u8(); val filter = u8(); val mip = u8() != 0; u8()
                val z = ByteArray(b.int).also { b.get(it) }
                Texture(name, w, h, wrap, filter, mip, z)
            }
            val meshes = List(b.int) {
                val name = str(); val n = b.int; val attrs = u8()
                val pos = floats(3 * n)
                val nrm = if (attrs and 1 != 0) floats(3 * n) else null
                val uv = if (attrs and 2 != 0) floats(2 * n) else null
                val col = if (attrs and 4 != 0) ByteArray(4 * n).also { b.get(it) } else null
                val uv1 = if (attrs and 8 != 0) floats(2 * n) else null
                val subs = List(u16()) {
                    val k = b.int
                    ShortArray(k).also { for (i in 0 until k) it[i] = b.short }
                }
                Mesh(name, pos, nrm, uv, col, uv1, subs)
            }
            val phys = List(b.int) { PhysMaterial(str(), f(), f(), f(), u8(), u8()) }
            val materials = List(b.int) {
                val name = str(); val shader = str()
                val c = f4(); val s = f4(); val e = f4(); val t = f4(); val sh = f()
                val mt = b.int; val mst = f4(); val dt = b.int; val dst = f4()
                Material(name, shader, c, s, e, t, sh, mt, mst, dt, dst)
            }
            val clips = List(b.int) {
                val name = str(); val sr = f(); val wrap = u8()
                val curves = List(u16()) {
                    val path = str(); val kind = u8(); val pre = u8(); val post = u8()
                    val d = if (kind == Curve.ROTATION) 4 else 3
                    val keys = List(u16()) { Key(f(), floats(d), floats(d), floats(d)) }
                    Curve(path, kind, pre, post, keys)
                }
                Clip(name, sr, wrap, curves)
            }
            val audio = List(b.int) { Audio(str(), str(), f()) }
            val globals = settings()
            val scenes = List(b.int) {
                val sceneName = str()
                val sceneSettings = settings()
                val nodes = List(b.int) {
                    val name = str(); val parent = b.int; val active = u8() != 0; val layer = u8(); val tag = u16()
                    val pos = v3(); val rot = Quat(f(), f(), f(), f()); val scale = v3()
                    val mesh = b.int
                    val renderer = u8()
                    val mats = IntArray(u8()) { b.int }
                    val rb = if (u8() != 0) {
                        val m = f(); val d = f(); val ad = f()
                        Rigidbody(m, d, ad, u8() != 0, u8() != 0, u8() != 0)
                    } else null
                    val cols = List(u8()) {
                        val kind = u8(); val trig = u8() != 0; val convex = u8() != 0
                        Collider(kind, trig, convex, b.int, v3(), v3(), b.int)
                    }
                    val light = if (u8() != 0) {
                        val en = u8() != 0; val type = u8(); val c = f4(); val i = f(); val r = f(); val sa = f()
                        Light(en, type, c, i, r, sa, b.int)
                    } else null
                    val cam = if (u8() != 0) {
                        val en = u8() != 0
                        val fov = f(); val near = f(); val far = f(); val ortho = u8() != 0
                        val size = f(); val depth = f(); val clear = u8(); val bg = f4()
                        val mask = b.int; val vp = f4()
                        Camera(en, fov, near, far, ortho, size, depth, clear, bg, mask, vp)
                    } else null
                    val anim = if (u8() != 0) {
                        val en = u8() != 0; val clip = b.int
                        val list = IntArray(u8()) { b.int }
                        Animation(en, clip, list, u8(), u8() != 0)
                    } else null
                    val src = if (u8() != 0) {
                        val en = u8() != 0; val clip = b.int; val poa = u8() != 0
                        val vol = f(); val pitch = f()
                        AudioSource(en, clip, poa, vol, pitch, u8() != 0)
                    } else null
                    val particles = if (u8() != 0) {
                        val sz = floats(6)
                        val wv = v3(); val lv = v3(); val rv = v3(); val evs = f(); val tv = v3()
                        val emit = u8() != 0; val ws = u8() != 0; val os = u8() != 0
                        val ell = v3(); val range = f()
                        val animator = if (u8() != 0) {
                            val ac = u8() != 0
                            val cols = Array(5) { FloatArray(4) { u8() / 255f } }
                            val wa = v3(); val la = v3(); val grow = f(); val rf = v3(); val fo = v3(); val damp = f()
                            Animator(ac, cols, wa, la, grow, rf, fo, damp, u8() != 0)
                        } else null
                        val renderer = if (u8() != 0) {
                            val en = u8() != 0; val mat = b.int; val st = u8()
                            ParticleRenderer(en, mat, st, b.int, b.int, f())
                        } else null
                        Particles(sz[0], sz[1], sz[2], sz[3], sz[4], sz[5], wv, lv, rv, evs, tv,
                            emit, ws, os, ell, range, animator, renderer)
                    } else null
                    val scripts = List(u8()) {
                        val cls = str(); val en = u8() != 0
                        Script(cls, en, value() as Value.Obj)
                    }
                    Node(name, parent, active, layer, tag, pos, rot, scale, mesh, renderer, mats, rb, cols,
                        light, cam, anim, src, particles, scripts)
                }
                Scene(sceneName, sceneSettings, nodes)
            }
            require(!b.hasRemaining()) { "${b.remaining()} bytes left over" }
            return IqPack(textures, meshes, phys, materials, clips, audio, globals, scenes)
        }
    }
}
