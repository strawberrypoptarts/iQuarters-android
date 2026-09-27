package com.guille.spring.iquarters

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import javax.microedition.khronos.opengles.GL11
import kotlin.math.tan

/**
 * The runtime's scene through OpenGL ES 1.1's fixed-function pipeline, which is all the
 * iPhone OS 2 GPU (and Unity iPhone 1.7) had. Each enabled camera on an active object draws
 * in `depth` order (scene order within a depth), with its clear flags, its culling mask and
 * its projection. Level 0 has three at once: the table, the orthographic HUD overlay and the
 * perspective overlay. Every material is one of Unity's built-in fixed-function shaders, set
 * up here the way their ShaderLab passes read:
 *
 * - **Diffuse**: `Material { Diffuse [_Color] Ambient [_Color] } Lighting On`,
 *   `SetTexture [_MainTex] { combine texture * primary DOUBLE }`.
 * - **VertexLit**: the same plus `Specular`, `Emission`, `Shininess`, `SeparateSpecular On`.
 * - **Transparent/Diffuse**, **Transparent/VertexLit**: those two with
 *   `Blend SrcAlpha OneMinusSrcAlpha`, `AlphaTest Greater 0`, `ZWrite Off`, drawn after the
 *   opaques, back to front.
 * - **iPhone Detail** (the quarter): diffuse lighting, `texture * primary DOUBLE`, then
 *   `previous * _Detail DOUBLE`.
 * - **iPhone Vertex Colored** / **iPhone Transparent Vertex Color** (all the UI):
 *   `Lighting Off` (both sources, in `sharedassets0.assets`, say so; the `ColorMaterial`
 *   line above it does nothing unlit), so primary is the vertex colour, white without one;
 *   `texture * primary`, then `previous * _Color DOUBLE`. The transparent one blends like the
 *   Transparent shaders. Lit, the front end (which has no lights) came out at ambient 0.2.
 * - **Particles/Additive** (the light ray): unlit, `Blend SrcAlpha One`, `Cull Off`,
 *   `_TintColor * primary`, then `texture * previous DOUBLE`.
 *
 * Lights reach an object only if their culling mask holds its layer. None attenuates (every
 * light in `level0` has attenuation off). Light colour goes in as `color * intensity`: the
 * ×2 that pre-5 Unity took out again is the shaders' `DOUBLE`.
 *
 * The camera looks down Unity's +Z, so the view matrix is `diag(1, 1, -1) × camera⁻¹`. Screen
 * winding survives that, so Unity's clockwise front faces are `GL_CW`, flipped for a
 * mirroring model matrix.
 *
 * `GUI.Label`s are drawn last, from a 320x480 text bitmap, re-rasterized only when they
 * change. The game runs on this thread, one [IqRuntime.frame] per 1/30s of wall time, and
 * every frame between steps is drawn by [IqInterp] at the display's own rate.
 */
class IqRenderer(private val rt: IqRuntime, private val input: () -> List<IqTouch>) : GLSurfaceView.Renderer {

    private class GpuMesh(
        var pos: FloatBuffer, val nrm: FloatBuffer?, var uv: FloatBuffer?, val uv1: FloatBuffer?,
        var colors: ByteBuffer?, val subs: List<ShortBuffer>, val center: V3,
    )

    /** A script-modified mesh keeps its own buffers, re-uploaded when its version moves. */
    private class Dyn(val gpu: GpuMesh, var version: Int)

    private val pack = rt.pack
    private lateinit var textures: IntArray
    private lateinit var meshes: List<GpuMesh>
    private val dynamic = HashMap<MeshInst, Dyn>()
    private var drawingUi = false
    private var width = 320
    private var height = 480
    private var lastNanos = 0L
    private var accumulator = 0f
    private val interp = IqInterp()
    @Volatile private var resetClock = false
    fun resumeClock() { resetClock = true }


    private var labelTex = 0
    private var labelKey: List<Any?>? = null
    private val labelBitmap = Bitmap.createBitmap(LABEL_W, LABEL_H, Bitmap.Config.ARGB_8888)
    private val labelQuad = floats(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val labelUv = floats(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f))

    /** Called on the GL thread after each drawn frame, for the snapshot. */
    @Volatile var onFrame: (() -> Unit)? = null

    override fun onSurfaceCreated(gl: GL10, config: EGLConfig) {
        val gl11 = gl as GL11
        textures = IntArray(pack.textures.size)
        gl.glGenTextures(textures.size, textures, 0)
        for ((i, t) in pack.textures.withIndex()) {
            gl.glBindTexture(GL10.GL_TEXTURE_2D, textures[i])
            val wrap = if (t.wrap == 1) GL10.GL_CLAMP_TO_EDGE else GL10.GL_REPEAT
            gl.glTexParameterf(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_WRAP_S, wrap.toFloat())
            gl.glTexParameterf(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_WRAP_T, wrap.toFloat())
            val mag = if (t.filter == 0) GL10.GL_NEAREST else GL10.GL_LINEAR
            val min = when {
                !t.mipmap -> mag
                t.filter == 0 -> GL10.GL_NEAREST_MIPMAP_NEAREST
                t.filter == 1 -> GL10.GL_LINEAR_MIPMAP_NEAREST
                else -> GL10.GL_LINEAR_MIPMAP_LINEAR
            }
            gl.glTexParameterf(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_MAG_FILTER, mag.toFloat())
            gl.glTexParameterf(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_MIN_FILTER, min.toFloat())
            if (t.mipmap) gl11.glTexParameteri(GL10.GL_TEXTURE_2D, GL11.GL_GENERATE_MIPMAP, GL10.GL_TRUE)
            val px = if (t.isWebp) webpPixels(t) else t.pixels()
            val buf = ByteBuffer.allocateDirect(px.size).order(ByteOrder.nativeOrder()).put(px)
            buf.position(0)
            gl.glTexImage2D(GL10.GL_TEXTURE_2D, 0, GL10.GL_RGBA, t.width, t.height, 0, GL10.GL_RGBA, GL10.GL_UNSIGNED_BYTE, buf)
        }
        meshes = pack.meshes.map { m -> gpu(m, m.positions, m.uv, m.colors) }
        dynamic.clear()
        val ids = IntArray(1)
        gl.glGenTextures(1, ids, 0)
        labelTex = ids[0]
        gl.glBindTexture(GL10.GL_TEXTURE_2D, labelTex)
        gl.glTexParameterf(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_MIN_FILTER, GL10.GL_LINEAR.toFloat())
        gl.glTexParameterf(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_MAG_FILTER, GL10.GL_LINEAR.toFloat())
        labelKey = null

        gl.glDepthFunc(GL10.GL_LEQUAL)
        gl.glCullFace(GL10.GL_BACK)
        gl.glEnable(GL10.GL_NORMALIZE)
        gl.glShadeModel(GL10.GL_SMOOTH)
        gl.glHint(GL10.GL_PERSPECTIVE_CORRECTION_HINT, GL10.GL_NICEST)
        lastNanos = SystemClock.elapsedRealtimeNanos()
    }

    /**
     * A WebP texture as [IqPack.Texture.pixels] would give it: straight (not
     * premultiplied) RGBA8, bottom row first.
     */
    private fun webpPixels(t: IqPack.Texture): ByteArray {
        val opts = BitmapFactory.Options().apply {
            inPremultiplied = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = requireNotNull(BitmapFactory.decodeByteArray(t.data, 0, t.data.size, opts)) { "${t.name}: bad WebP" }
        require(bmp.width == t.width && bmp.height == t.height) { "${t.name}: ${bmp.width}x${bmp.height}" }
        val argb = IntArray(t.width * t.height)
        bmp.getPixels(argb, 0, t.width, 0, 0, t.width, t.height)
        bmp.recycle()
        val out = ByteArray(argb.size * 4)
        var o = 0
        for (y in t.height - 1 downTo 0) {
            for (x in 0 until t.width) {
                val c = argb[y * t.width + x]
                out[o++] = (c shr 16).toByte(); out[o++] = (c shr 8).toByte()
                out[o++] = c.toByte(); out[o++] = (c ushr 24).toByte()
            }
        }
        return out
    }

    /**
     * `RenderSettings.ambientLight` is per scene in the pack, so it follows level loads. A
     * scene that bakes none gets Unity's default, 0.2 grey.
     */
    private fun setAmbient(gl: GL10) {
        val s = rt.world.scene.settings
        val c = floatArrayOf(s["ambientR"] ?: 0.2f, s["ambientG"] ?: 0.2f, s["ambientB"] ?: 0.2f, 1f)
        gl.glLightModelfv(GL10.GL_LIGHT_MODEL_AMBIENT, c, 0)
    }

    private fun gpu(m: IqPack.Mesh, pos: FloatArray, uv: FloatArray?, colors: ByteArray?): GpuMesh {
        var c = V3.ZERO
        for (i in 0 until m.vertexCount) c += m.vertex(i)
        return GpuMesh(
            floats(pos), m.normals?.let(::floats), uv?.let(::floats), m.uv1?.let(::floats), colors?.let(::bytes),
            m.submeshes.map(::shorts), c * (1f / m.vertexCount.coerceAtLeast(1)),
        )
    }

    override fun onSurfaceChanged(gl: GL10, w: Int, h: Int) {
        width = w
        height = h
        rt.viewport = IqViewport(w,h)
    }

    override fun onDrawFrame(gl: GL10) {
        val now = SystemClock.elapsedRealtimeNanos()
        if (resetClock) { lastNanos = now; accumulator = 0f; resetClock = false }
        accumulator += ((now - lastNanos) / 1e9f).coerceAtMost(0.25f)
        lastNanos = now
        var n = 0
        while (accumulator >= IqRuntime.FRAME_DT && n < 4) {
            rt.frame(IqRuntime.FRAME_DT, input())
            interp.capture(rt.world)
            accumulator -= IqRuntime.FRAME_DT
            n++
        }
        if (n == 4) accumulator = 0f
        interp.begin(accumulator / IqRuntime.FRAME_DT)
        setAmbient(gl)
        draw(gl as GL11)
        onFrame?.invoke()
    }

    private class Item(val go: GObj, val sub: Int, val mat: MatInst, val model: M4, val depth: Float, val gpu: GpuMesh)

    private fun draw(gl: GL11) {
        gl.glDisable(GL10.GL_SCISSOR_TEST)
        gl.glViewport(0, 0, width, height)
        val world = rt.world
        val cams = world.objects.filter { it.active && it.camera?.enabled == true }.sortedBy { it.camera!!.src.depth }
        if (cams.isEmpty()) {
            gl.glClearColor(0f, 0f, 0f, 1f)
            gl.glClear(GL10.GL_COLOR_BUFFER_BIT or GL10.GL_DEPTH_BUFFER_BIT)
        }
        val lights = world.objects.filter { it.active && it.light?.enabled == true }
        for (c in cams) drawCamera(gl, c, lights)
        drawLabels(gl)
    }

    private fun drawCamera(gl: GL11, camGo: GObj, lights: List<GObj>) {
        val cam = camGo.camera!!.src
        val layout = rt.viewport
        gl.glDisable(GL10.GL_SCISSOR_TEST)
        // The original game uses layers 8/12/13 for its UI cameras; world cameras
        // include the default layer. Retain authored UI proportions on every screen.
        val uiCamera = cam.orthographic || (cam.cullingMask and 1 == 0)
        drawingUi = uiCamera
        gl.glViewport(0, 0, width, height)
        gl.glDepthMask(true)
        when (cam.clearFlags) {
            1, 2 -> {
                val bg = cam.background
                gl.glClearColor(bg[0], bg[1], bg[2], 1f)
                gl.glClear(GL10.GL_COLOR_BUFFER_BIT or GL10.GL_DEPTH_BUFFER_BIT)
            }
            3 -> gl.glClear(GL10.GL_DEPTH_BUFFER_BIT)
        }

        gl.glMatrixMode(GL10.GL_PROJECTION)
        gl.glLoadIdentity()
        val aspect = width.toFloat() / height
        if (cam.orthographic) {
            val h = cam.orthoSize * layout.verticalExpansion
            gl.glOrthof(-h * aspect, h * aspect, -h, h, cam.near, cam.far)
        } else {
            val top = cam.near * tan(Math.toRadians(interp.fieldOfView(camGo) / 2.0).toFloat()) * layout.verticalExpansion
            gl.glFrustumf(-top * aspect, top * aspect, -top, top, cam.near, cam.far)
        }

        val camWorld = interp.worldMatrix(camGo)
        val camPos = camWorld.point(V3.ZERO)
        val view = M4.scale(V3(1f, 1f, -1f)) * M4.trs(camPos, camWorld.rotation(), V3(1f, 1f, 1f)).rigidInverse()

        val opaque = ArrayList<Item>()
        val transparent = ArrayList<Item>()
        for (go in rt.world.objects) {
            val r = go.renderer ?: continue
            val mf = go.meshFilter ?: continue
            if (!go.active || !r.enabled) continue
            if ((cam.cullingMask ushr go.layer) and 1 == 0) continue
            if (r.materials.isEmpty()) continue
            val g = meshFor(mf)
            val model = interp.worldMatrix(go)
            val d = (model.point(g.center) - camPos).lengthSq()
            for (k in mf.src.submeshes.indices) {
                val mat = r.materials[minOf(k, r.materials.size - 1)]
                (if (isTransparent(mat.shader)) transparent else opaque) += Item(go, k, mat, model, d, g)
            }
        }
        for (it in opaque) drawItem(gl, it, view, lights)
        // Transparent meshes and particle systems share one back-to-front order.
        val late = ArrayList<Pair<Float, () -> Unit>>()
        for (it in transparent) late += it.depth to { drawItem(gl, it, view, lights) }
        val camRot = camWorld.rotation()
        for (go in rt.world.objects) {
            val ps = go.particles ?: continue
            if (!go.active || ps.particles.isEmpty() || ps.src.renderer?.enabled != true) continue
            if ((cam.cullingMask ushr go.layer) and 1 == 0) continue
            val d = (ps.particles.last().position - camPos).lengthSq()
            late += d to { drawParticles(gl, ps, view, camRot) }
        }
        late.sortByDescending { it.first }
        for ((_, draw) in late) draw()
        gl.glDisable(GL10.GL_SCISSOR_TEST)
    }

    private var particleCap = 0
    private lateinit var particlePos: FloatBuffer
    private lateinit var particleUv: FloatBuffer
    private lateinit var particleColors: ByteBuffer
    private lateinit var particleIdx: ShortBuffer

    /**
     * A `ParticleRenderer` in billboard mode: one camera-facing quad per particle,
     * [IqPack.Particles]' size wide, in the animator's colour. Its material's shader is
     * `iPhone/Particles/Additive Culled` (in `sharedassets`): `Blend SrcAlpha One`,
     * `AlphaTest Greater .01`, `ZWrite Off`, `_TintColor * primary`, then
     * `texture * previous DOUBLE` — the light ray's combiners. A billboard always faces
     * the camera, so its `Cull Back` never removes one, and culling is left off here.
     * Only a 1x1 UV tile is drawn, which is all `GlassFlash` uses.
     */
    private fun drawParticles(gl: GL11, ps: Particles, view: M4, camRot: Quat) {
        gl.glDisable(GL10.GL_SCISSOR_TEST)
        val r = ps.src.renderer ?: return
        if (r.material < 0) return
        val mat = pack.materials[r.material]
        val n = ps.particles.size
        if (n > particleCap) {
            particleCap = maxOf(n, 8)
            particlePos = ByteBuffer.allocateDirect(particleCap * 12 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            particleUv = ByteBuffer.allocateDirect(particleCap * 8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            particleColors = ByteBuffer.allocateDirect(particleCap * 16).order(ByteOrder.nativeOrder())
            particleIdx = ByteBuffer.allocateDirect(particleCap * 6 * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        }
        val right = camRot.rotate(V3.RIGHT)
        val up = camRot.rotate(V3.UP)
        particlePos.clear(); particleUv.clear(); particleColors.clear(); particleIdx.clear()
        for ((i, p) in ps.particles.withIndex()) {
            val h = p.size / 2f
            val rx = right * h
            val uy = up * h
            for (c4 in CORNERS) {
                val v = p.position + rx * c4[0] + uy * c4[1]
                particlePos.put(v.x).put(v.y).put(v.z)
                particleUv.put(c4[2]).put(c4[3])
                for (c in 0..3) particleColors.put((p.color[c].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte())
            }
            val b = (i * 4).toShort()
            particleIdx.put(b).put((b + 1).toShort()).put((b + 2).toShort())
            particleIdx.put(b).put((b + 2).toShort()).put((b + 3).toShort())
        }
        particlePos.flip(); particleUv.flip(); particleColors.flip(); particleIdx.flip()

        gl.glMatrixMode(GL10.GL_MODELVIEW)
        gl.glLoadMatrixf(view.m, 0)
        gl.glDisable(GL10.GL_LIGHTING)
        gl.glDisable(GL10.GL_CULL_FACE)
        gl.glEnable(GL10.GL_DEPTH_TEST)
        gl.glEnable(GL10.GL_BLEND)
        gl.glBlendFunc(GL10.GL_SRC_ALPHA, GL10.GL_ONE)
        gl.glEnable(GL10.GL_ALPHA_TEST)
        gl.glAlphaFunc(GL10.GL_GREATER, 0.01f)
        gl.glDepthMask(false)

        gl.glEnableClientState(GL10.GL_VERTEX_ARRAY)
        gl.glVertexPointer(3, GL10.GL_FLOAT, 0, particlePos)
        gl.glDisableClientState(GL10.GL_NORMAL_ARRAY)
        gl.glEnableClientState(GL10.GL_COLOR_ARRAY)
        gl.glColorPointer(4, GL10.GL_UNSIGNED_BYTE, 0, particleColors)

        val tex = mat.mainTex
        bindStage(gl, 0, tex, mat.mainST, particleUv)
        gl.glTexEnvfv(GL10.GL_TEXTURE_ENV, GL10.GL_TEXTURE_ENV_COLOR, mat.tintColor, 0)
        combine(gl, GL11.GL_CONSTANT, GL11.GL_PRIMARY_COLOR, GL11.GL_CONSTANT, GL11.GL_PRIMARY_COLOR, 1f, true)
        bindStage(gl, 1, tex, mat.mainST, particleUv)
        combine(gl, GL10.GL_TEXTURE, GL11.GL_PREVIOUS, GL10.GL_TEXTURE, GL11.GL_PREVIOUS, 2f, tex >= 0)

        gl.glDrawElements(GL10.GL_TRIANGLES, n * 6, GL10.GL_UNSIGNED_SHORT, particleIdx)
        disableStage(gl, 1)
        gl.glActiveTexture(GL10.GL_TEXTURE0)
        gl.glClientActiveTexture(GL10.GL_TEXTURE0)
        gl.glDisableClientState(GL10.GL_COLOR_ARRAY)
    }

    private fun meshFor(mf: MeshInst): GpuMesh {
        val base = meshes[pack.meshes.indexOf(mf.src).also { check(it >= 0) }]
        if (!mf.modified) return base
        val d = dynamic[mf]
        if (d != null && d.version == mf.version) return d.gpu
        val g = d?.gpu ?: gpu(mf.src, mf.vertices, mf.uv, mf.colors)
        if (d != null) {
            g.pos = floats(mf.vertices)
            g.uv = mf.uv?.let(::floats)
            g.colors = mf.colors?.let(::bytes)
        }
        dynamic[mf] = Dyn(g, mf.version)
        return g
    }

    private fun uiAnchor(go: GObj): IqViewport.Anchor {
        var node: GObj? = go
        while (node != null) {
            IqViewport.anchor(node.name)?.let { return it }
            node = node.parent
        }
        return IqViewport.Anchor.CENTER
    }

    /** Match the base iOS anchor wrappers without changing original animation curves. */
    private fun adaptUi(go: GObj, modelView: M4): M4 {
        if (!drawingUi) return modelView
        val v = rt.viewport
        if (IqViewport.fullScreenBackground(go.name)) {
            return M4.scale(V3(1 + v.extraX / 160f, 1 + v.extraY / 240f, 1f)) * modelView
        }
        val anchor = uiAnchor(go)
        if (anchor == IqViewport.Anchor.CENTER) return modelView
        val unit = 200f / 480f
        val offset = V3(anchor.x * v.extraX * unit, anchor.y * v.extraY * unit, 0f)
        return M4.trs(offset, Quat.IDENTITY, V3(1f, 1f, 1f)) * modelView
    }

    private fun clipUi(gl: GL11, go: GObj) {
        if (!drawingUi || IqViewport.fullScreenBackground(go.name)) {
            gl.glDisable(GL10.GL_SCISSOR_TEST)
            return
        }
        val clip = rt.viewport.clip(uiAnchor(go))
        gl.glEnable(GL10.GL_SCISSOR_TEST)
        gl.glScissor(clip.left, clip.bottom, clip.width, clip.height)
    }

    private fun isTransparent(shader: String) =
        shader.startsWith("Transparent/") || shader == "iPhone Transparent Vertex Color" || shader.startsWith("Particles/")

    private fun drawItem(gl: GL11, item: Item, view: M4, lights: List<GObj>) {
        clipUi(gl, item.go)
        val mesh = item.gpu
        val mat = item.mat
        val src = mat.src
        val shader = mat.shader
        val additive = shader.startsWith("Particles/")
        val vertexColored = shader.startsWith("iPhone") && shader.contains("Vertex Colo")
        val transparent = isTransparent(shader)
        val vertexLit = shader.endsWith("VertexLit")
        val detail = shader.contains("Detail")
        val lit = !additive && !vertexColored
        val layer = item.go.layer

        gl.glMatrixMode(GL10.GL_MODELVIEW)
        if (lit) {
            // Lights, placed in eye space under the view matrix alone.
            gl.glLoadMatrixf(view.m, 0)
            var used = 0
            for (lg in lights) {
                val ln = lg.light!!
                if (used >= 8) break
                if ((ln.cullingMask ushr layer) and 1 == 0) continue
                val id = GL10.GL_LIGHT0 + used++
                val c = floatArrayOf(ln.color[0] * ln.intensity, ln.color[1] * ln.intensity, ln.color[2] * ln.intensity, 1f)
                gl.glEnable(id)
                gl.glLightfv(id, GL10.GL_AMBIENT, floatArrayOf(0f, 0f, 0f, 1f), 0)
                gl.glLightfv(id, GL10.GL_DIFFUSE, c, 0)
                gl.glLightfv(id, GL10.GL_SPECULAR, c, 0)
                gl.glLightf(id, GL10.GL_CONSTANT_ATTENUATION, 1f)
                gl.glLightf(id, GL10.GL_LINEAR_ATTENUATION, 0f)
                gl.glLightf(id, GL10.GL_QUADRATIC_ATTENUATION, 0f)
                val lw = interp.worldMatrix(lg)
                if (ln.type == 1) {
                    val f = lw.rotation().rotate(V3.FORWARD)
                    gl.glLightfv(id, GL10.GL_POSITION, floatArrayOf(-f.x, -f.y, -f.z, 0f), 0)
                    gl.glLightf(id, GL10.GL_SPOT_CUTOFF, 180f)
                } else {
                    val p = lw.point(V3.ZERO)
                    gl.glLightfv(id, GL10.GL_POSITION, floatArrayOf(p.x, p.y, p.z, 1f), 0)
                    if (ln.type == 0) {
                        val f = lw.rotation().rotate(V3.FORWARD)
                        gl.glLightfv(id, GL10.GL_SPOT_DIRECTION, floatArrayOf(f.x, f.y, f.z), 0)
                        gl.glLightf(id, GL10.GL_SPOT_CUTOFF, ln.spotAngle / 2f)
                        gl.glLightf(id, GL10.GL_SPOT_EXPONENT, 0f)
                    } else gl.glLightf(id, GL10.GL_SPOT_CUTOFF, 180f)
                }
            }
            for (k in used until 8) gl.glDisable(GL10.GL_LIGHT0 + k)
            gl.glEnable(GL10.GL_LIGHTING)
        } else gl.glDisable(GL10.GL_LIGHTING)

        gl.glLoadMatrixf(adaptUi(item.go, view * item.model).m, 0)
        gl.glFrontFace(if (item.model.det3() < 0f) GL10.GL_CCW else GL10.GL_CW)
        if (additive) gl.glDisable(GL10.GL_CULL_FACE) else gl.glEnable(GL10.GL_CULL_FACE)
        gl.glEnable(GL10.GL_DEPTH_TEST)

        val black = floatArrayOf(0f, 0f, 0f, 1f)
        val white = floatArrayOf(1f, 1f, 1f, 1f)
        val color = mat.color
        if (vertexColored) {
            // ColorMaterial AmbientAndDiffuse: the vertex colour (white without one) is the material.
            gl.glEnable(GL10.GL_COLOR_MATERIAL)
            gl.glMaterialfv(GL10.GL_FRONT_AND_BACK, GL10.GL_SPECULAR, black, 0)
            gl.glMaterialfv(GL10.GL_FRONT_AND_BACK, GL10.GL_EMISSION, src.emission.copyOf().also { it[3] = 1f }, 0)
        } else {
            gl.glDisable(GL10.GL_COLOR_MATERIAL)
            gl.glMaterialfv(GL10.GL_FRONT_AND_BACK, GL10.GL_DIFFUSE, color, 0)
            gl.glMaterialfv(GL10.GL_FRONT_AND_BACK, GL10.GL_AMBIENT, if (detail) black else color, 0)
            gl.glMaterialfv(GL10.GL_FRONT_AND_BACK, GL10.GL_SPECULAR, if (vertexLit) src.specColor else black, 0)
            gl.glMaterialfv(GL10.GL_FRONT_AND_BACK, GL10.GL_EMISSION, if (vertexLit) src.emission.copyOf().also { it[3] = 1f } else black, 0)
        }
        gl.glMaterialf(GL10.GL_FRONT_AND_BACK, GL10.GL_SHININESS, (src.shininess * 128f).coerceIn(0f, 128f))

        if (transparent) {
            gl.glEnable(GL10.GL_BLEND)
            gl.glBlendFunc(GL10.GL_SRC_ALPHA, if (additive) GL10.GL_ONE else GL10.GL_ONE_MINUS_SRC_ALPHA)
            gl.glEnable(GL10.GL_ALPHA_TEST)
            gl.glAlphaFunc(GL10.GL_GREATER, if (additive) 0.01f else 0f)
            gl.glDepthMask(false)
        } else {
            gl.glDisable(GL10.GL_BLEND)
            gl.glDisable(GL10.GL_ALPHA_TEST)
            gl.glDepthMask(true)
        }

        gl.glEnableClientState(GL10.GL_VERTEX_ARRAY)
        gl.glVertexPointer(3, GL10.GL_FLOAT, 0, mesh.pos)
        if (mesh.nrm != null) {
            gl.glEnableClientState(GL10.GL_NORMAL_ARRAY)
            gl.glNormalPointer(GL10.GL_FLOAT, 0, mesh.nrm)
        } else {
            gl.glDisableClientState(GL10.GL_NORMAL_ARRAY)
            gl.glNormal3f(0f, 0f, -1f)
        }
        val useColors = (vertexColored || additive) && mesh.colors != null
        if (useColors) {
            gl.glEnableClientState(GL10.GL_COLOR_ARRAY)
            gl.glColorPointer(4, GL10.GL_UNSIGNED_BYTE, 0, mesh.colors)
        } else {
            gl.glDisableClientState(GL10.GL_COLOR_ARRAY)
            gl.glColor4f(1f, 1f, 1f, 1f)
        }

        val st = floatArrayOf(mat.mainTextureScale.x, mat.mainTextureScale.y, mat.mainTextureOffset.x, mat.mainTextureOffset.y)
        val tex = mat.mainTex
        when {
            additive -> {
                // Stage 0: _TintColor * primary; stage 1: texture * previous DOUBLE.
                bindStage(gl, 0, tex, st, mesh.uv)
                gl.glTexEnvfv(GL10.GL_TEXTURE_ENV, GL10.GL_TEXTURE_ENV_COLOR, src.tintColor, 0)
                combine(gl, GL11.GL_CONSTANT, GL11.GL_PRIMARY_COLOR, GL11.GL_CONSTANT, GL11.GL_PRIMARY_COLOR, 1f, true)
                bindStage(gl, 1, tex, st, mesh.uv)
                combine(gl, GL10.GL_TEXTURE, GL11.GL_PREVIOUS, GL10.GL_TEXTURE, GL11.GL_PREVIOUS, 2f, tex >= 0)
            }
            vertexColored -> {
                // Stage 0: texture * primary; stage 1: previous * _Color DOUBLE (alpha undoubled).
                bindStage(gl, 0, tex, st, mesh.uv)
                combine(gl, GL10.GL_TEXTURE, GL11.GL_PRIMARY_COLOR, GL10.GL_TEXTURE, GL11.GL_PRIMARY_COLOR, 1f, tex >= 0)
                bindStage(gl, 1, tex, st, mesh.uv)
                gl.glTexEnvfv(GL10.GL_TEXTURE_ENV, GL10.GL_TEXTURE_ENV_COLOR, color, 0)
                combine(gl, GL11.GL_PREVIOUS, GL11.GL_CONSTANT, GL11.GL_PREVIOUS, GL11.GL_CONSTANT, 2f, true)
            }
            else -> {
                // Stage 0: texture * primary DOUBLE; alpha texture * _Color.a.
                bindStage(gl, 0, tex, st, mesh.uv)
                gl.glTexEnvfv(GL10.GL_TEXTURE_ENV, GL10.GL_TEXTURE_ENV_COLOR, color, 0)
                combine(gl, GL10.GL_TEXTURE, GL11.GL_PRIMARY_COLOR, GL10.GL_TEXTURE, GL11.GL_CONSTANT, 2f, tex >= 0)
                if (detail && src.detailTex >= 0) {
                    bindStage(gl, 1, src.detailTex, src.detailST, mesh.uv1 ?: mesh.uv)
                    combine(gl, GL11.GL_PREVIOUS, GL10.GL_TEXTURE, GL11.GL_PREVIOUS, GL11.GL_PREVIOUS, 2f, true)
                } else disableStage(gl, 1)
            }
        }

        gl.glDrawElements(GL10.GL_TRIANGLES, mesh.subs[item.sub].capacity(), GL10.GL_UNSIGNED_SHORT, mesh.subs[item.sub])
        disableStage(gl, 1)
        gl.glActiveTexture(GL10.GL_TEXTURE0)
        gl.glClientActiveTexture(GL10.GL_TEXTURE0)
        gl.glDisableClientState(GL10.GL_COLOR_ARRAY)
        gl.glDisable(GL10.GL_COLOR_MATERIAL)
        @Suppress("UNUSED_VARIABLE") val unused = white
    }

    private fun bindStage(gl: GL11, unit: Int, tex: Int, st: FloatArray, uv: FloatBuffer?) {
        gl.glActiveTexture(GL10.GL_TEXTURE0 + unit)
        gl.glClientActiveTexture(GL10.GL_TEXTURE0 + unit)
        gl.glMatrixMode(GL10.GL_TEXTURE)
        gl.glLoadIdentity()
        gl.glTranslatef(st[2], st[3], 0f)
        gl.glScalef(st[0], st[1], 1f)
        gl.glMatrixMode(GL10.GL_MODELVIEW)
        gl.glEnable(GL10.GL_TEXTURE_2D)
        if (tex >= 0 && uv != null) {
            gl.glBindTexture(GL10.GL_TEXTURE_2D, textures[tex])
            gl.glEnableClientState(GL10.GL_TEXTURE_COORD_ARRAY)
            gl.glTexCoordPointer(2, GL10.GL_FLOAT, 0, uv)
        } else {
            // A stage still has to run for its combiner; the untextured case never reads the texture.
            gl.glBindTexture(GL10.GL_TEXTURE_2D, labelTex)
            gl.glDisableClientState(GL10.GL_TEXTURE_COORD_ARRAY)
        }
    }

    private fun disableStage(gl: GL11, unit: Int) {
        gl.glActiveTexture(GL10.GL_TEXTURE0 + unit)
        gl.glClientActiveTexture(GL10.GL_TEXTURE0 + unit)
        gl.glDisable(GL10.GL_TEXTURE_2D)
        gl.glDisableClientState(GL10.GL_TEXTURE_COORD_ARRAY)
    }

    /**
     * `GL_COMBINE` with RGB = [rgbA] × [rgbB] × [scale] and alpha = [aA] × [aB]. With no
     * texture, a `GL_TEXTURE` source falls back to the primary colour, which is what Unity
     * does for a missing `_MainTex` (a white default texture).
     */
    private fun combine(gl: GL11, rgbA: Int, rgbB: Int, aA: Int, aB: Int, scale: Float, textured: Boolean) {
        fun src(s: Int) = if (!textured && s == GL10.GL_TEXTURE) GL11.GL_PRIMARY_COLOR else s
        val env = GL10.GL_TEXTURE_ENV
        gl.glTexEnvf(env, GL10.GL_TEXTURE_ENV_MODE, GL11.GL_COMBINE.toFloat())
        gl.glTexEnvf(env, GL11.GL_COMBINE_RGB, GL10.GL_MODULATE.toFloat())
        gl.glTexEnvf(env, GL11.GL_SRC0_RGB, src(rgbA).toFloat())
        gl.glTexEnvf(env, GL11.GL_SRC1_RGB, if (!textured && rgbA == GL10.GL_TEXTURE && rgbB == GL11.GL_PRIMARY_COLOR) GL11.GL_CONSTANT.toFloat().also { gl.glTexEnvfv(env, GL10.GL_TEXTURE_ENV_COLOR, WHITE, 0) } else src(rgbB).toFloat())
        gl.glTexEnvf(env, GL11.GL_OPERAND0_RGB, GL10.GL_SRC_COLOR.toFloat())
        gl.glTexEnvf(env, GL11.GL_OPERAND1_RGB, GL10.GL_SRC_COLOR.toFloat())
        gl.glTexEnvf(env, GL11.GL_RGB_SCALE, scale)
        gl.glTexEnvf(env, GL11.GL_COMBINE_ALPHA, GL10.GL_MODULATE.toFloat())
        gl.glTexEnvf(env, GL11.GL_SRC0_ALPHA, src(aA).toFloat())
        gl.glTexEnvf(env, GL11.GL_SRC1_ALPHA, if (!textured && aA == GL10.GL_TEXTURE && aB == GL11.GL_PRIMARY_COLOR) GL11.GL_CONSTANT.toFloat() else src(aB).toFloat())
        gl.glTexEnvf(env, GL11.GL_OPERAND0_ALPHA, GL10.GL_SRC_ALPHA.toFloat())
        gl.glTexEnvf(env, GL11.GL_OPERAND1_ALPHA, GL10.GL_SRC_ALPHA.toFloat())
        gl.glTexEnvf(env, GL11.GL_ALPHA_SCALE, 1f)
    }

    // -- GUI.Label --

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }

    /**
     * The frame's `GUI.Label`s. The skins' fonts are not baked yet, so every skin draws as a
     * bold sans at the size its name suggests; the rects and alignment are the game's.
     */
    private fun drawLabels(gl: GL11) {
        val layout = IqViewport(width, height)
        val loading = rt.labels.any { it.text == "Loading..." }
        gl.glViewport(if(loading) 0 else layout.left, if(loading) 0 else layout.bottom, layout.uiWidth, layout.uiHeight)
        val labels = rt.labels
        if (labels.isEmpty()) return
        val key = labels.flatMap { listOf(it.rect, it.text, it.skin, it.center, it.right, it.topCenter, it.color) }
        gl.glActiveTexture(GL10.GL_TEXTURE0)
        gl.glBindTexture(GL10.GL_TEXTURE_2D, labelTex)
        if (key != labelKey) {
            labelKey = key
            labelBitmap.eraseColor(Color.TRANSPARENT)
            val c = Canvas(labelBitmap)
            val k = LABEL_W / IqRuntime.SCREEN_W
            c.scale(k, k)
            for (l in labels) {
                labelPaint.textSize = if (l.skin.contains("font", ignoreCase = true)) 16f else 14f
                labelPaint.color = l.color ?: Color.WHITE
                labelPaint.textAlign = when {
                    l.center || l.topCenter -> Paint.Align.CENTER
                    l.right -> Paint.Align.RIGHT
                    else -> Paint.Align.LEFT
                }
                val fm = labelPaint.fontMetrics
                var y = if (l.center) l.rect.y + l.rect.height / 2f - (fm.ascent + fm.descent) / 2f else l.rect.y - fm.ascent
                for (line in l.text.split('\n')) {
                    val x = when {
                        l.center || l.topCenter -> l.rect.x + l.rect.width / 2f
                        l.right -> l.rect.x + l.rect.width
                        else -> l.rect.x
                    }
                    c.drawText(line, x, y, labelPaint)
                    y += labelPaint.fontSpacing
                }
            }
            GLUtils.texImage2D(GL10.GL_TEXTURE_2D, 0, labelBitmap, 0)
        }
        gl.glMatrixMode(GL10.GL_PROJECTION)
        gl.glLoadIdentity()
        gl.glMatrixMode(GL10.GL_MODELVIEW)
        gl.glLoadIdentity()
        gl.glMatrixMode(GL10.GL_TEXTURE)
        gl.glLoadIdentity()
        gl.glMatrixMode(GL10.GL_MODELVIEW)
        gl.glDisable(GL10.GL_LIGHTING)
        gl.glDisable(GL10.GL_DEPTH_TEST)
        gl.glDisable(GL10.GL_CULL_FACE)
        gl.glDisable(GL10.GL_ALPHA_TEST)
        gl.glEnable(GL10.GL_BLEND)
        gl.glBlendFunc(GL10.GL_ONE, GL10.GL_ONE_MINUS_SRC_ALPHA) // the bitmap is premultiplied
        gl.glEnable(GL10.GL_TEXTURE_2D)
        gl.glTexEnvf(GL10.GL_TEXTURE_ENV, GL10.GL_TEXTURE_ENV_MODE, GL10.GL_REPLACE.toFloat())
        gl.glDisableClientState(GL10.GL_NORMAL_ARRAY)
        gl.glDisableClientState(GL10.GL_COLOR_ARRAY)
        gl.glEnableClientState(GL10.GL_VERTEX_ARRAY)
        gl.glVertexPointer(2, GL10.GL_FLOAT, 0, labelQuad)
        gl.glEnableClientState(GL10.GL_TEXTURE_COORD_ARRAY)
        gl.glTexCoordPointer(2, GL10.GL_FLOAT, 0, labelUv)
        gl.glDrawArrays(GL10.GL_TRIANGLE_STRIP, 0, 4)
        gl.glDisableClientState(GL10.GL_TEXTURE_COORD_ARRAY)
    }

    private companion object {
        const val LABEL_W = 640
        const val LABEL_H = 960
        val WHITE = floatArrayOf(1f, 1f, 1f, 1f)

        /** A billboard's corners, (x, y) in half-sizes and (u, v), bottom left first. */
        val CORNERS = arrayOf(
            floatArrayOf(-1f, -1f, 0f, 0f), floatArrayOf(-1f, 1f, 0f, 1f),
            floatArrayOf(1f, 1f, 1f, 1f), floatArrayOf(1f, -1f, 1f, 0f),
        )

        fun floats(a: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(a).also { it.position(0) }

        fun bytes(a: ByteArray): ByteBuffer =
            ByteBuffer.allocateDirect(a.size).order(ByteOrder.nativeOrder()).put(a).also { it.position(0) }

        fun shorts(a: ShortArray): ShortBuffer =
            ByteBuffer.allocateDirect(a.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().put(a).also { it.position(0) }
    }
}
