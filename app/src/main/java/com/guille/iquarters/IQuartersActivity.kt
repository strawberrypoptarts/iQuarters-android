package com.guille.iquarters

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.os.Build
import android.os.Bundle
import android.opengl.GLSurfaceView
import android.graphics.BitmapFactory
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.view.Gravity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.guille.spring.iquarters.*
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Standalone native-resolution game, with no emulated iPhone frame or home button. */
class IQuartersActivity : Activity() {
    private lateinit var frame: FrameLayout
    private var view: GLSurfaceView? = null
    private var renderer: IqRenderer? = null
    private var host: IqAndroidHost? = null
    private val touches = IqTouches()
    private var resumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        if (Build.VERSION.SDK_INT >= 23) IqAndroid.fastestMode(this)?.let { mode ->
            window.attributes = window.attributes.also { it.preferredDisplayModeId = mode.modeId }
        }
        frame = FrameLayout(this).apply { setBackgroundColor(0xff000000.toInt()) }
        val loading = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(assets.open("ios4/launch/iquarters.png").use { BitmapFactory.decodeStream(it) })
        }
        val label = TextView(this).apply {
            text = "Loading..."; setTextColor(-1); textSize = 16f
            setPadding(12, 0, 0, 12); gravity = Gravity.BOTTOM or Gravity.START
        }
        frame.addView(loading, FrameLayout.LayoutParams(-1, -1))
        frame.addView(label, FrameLayout.LayoutParams(-1, -1))
        setContentView(frame)
        Thread({
            try {
                val pack = IqPack.parse(assets.open(IqAndroid.PACK_ASSET).use { it.readBytes() })
                val h = IqAndroidHost(this, pack)
                val rt = IqRuntime(pack, h)
                runOnUiThread {
                    if (isFinishing || isDestroyed) { h.release(); return@runOnUiThread }
                    host = h
                    mountGame(rt) { frame.removeView(loading); frame.removeView(label) }
                }
            } catch (e: Exception) {
                android.util.Log.e(IqAndroid.TAG, "Game initialization failed", e)
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) AlertDialog.Builder(this)
                        .setTitle("Unable to load iQuarters").setMessage(e.message ?: "Game assets could not be loaded.")
                        .setPositiveButton("Close") { _, _ -> finish() }.show()
                }
            }
        }, "iq-load").start()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun mountGame(rt: IqRuntime, shown: () -> Unit) {
        val r = IqRenderer(rt) { touches.frame() }; renderer = r
        val v = GLSurfaceView(this)
        v.setEGLContextClientVersion(1)
        v.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        v.preserveEGLContextOnPause = true
        v.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (Build.VERSION.SDK_INT >= 30) runCatching {
                    holder.surface.setFrameRate(IqAndroid.fastestMode(this@IQuartersActivity)?.refreshRate ?: 60f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
                }
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) { touches.cancel() }
        })
        v.setRenderer(object : GLSurfaceView.Renderer {
            private var frames = 0
            override fun onSurfaceCreated(gl: GL10, config: EGLConfig) = r.onSurfaceCreated(gl, config)
            override fun onSurfaceChanged(gl: GL10, w: Int, h: Int) = r.onSurfaceChanged(gl, w, h)
            override fun onDrawFrame(gl: GL10) {
                r.onDrawFrame(gl)
                if (frames < 2 && ++frames == 2) frame.post { shown() }
            }
        })
        v.setOnTouchListener { sv, e -> touches.onTouch(sv.width, sv.height, e); true }
        frame.addView(v, 0, FrameLayout.LayoutParams(-1, -1)); view = v
        if (!resumed) { v.onPause(); host?.pauseAll() }
    }
    override fun onResume() {
        super.onResume(); resumed = true; touches.cancel(); renderer?.resumeClock(); view?.onResume(); host?.resumeAll()
    }
    override fun onPause() {
        resumed = false; touches.cancel(); view?.onPause(); host?.pauseAll(); super.onPause()
    }
    override fun onDestroy() { host?.release(); super.onDestroy() }

}
