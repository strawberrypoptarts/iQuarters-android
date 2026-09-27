package com.guille.spring.iquarters

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.SoundPool
import android.net.Uri
import android.util.Log
import android.view.Display
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.EditText

/**
 * The Android side of the port, shared by the launcher's `IqActivity` and the standalone
 * `:iquarters` app, which compiles this package out of the launcher's source tree.
 */
object IqAndroid {
    const val PACK_ASSET = "iquarters/scene.pack"
    const val AUDIO_DIR = "iquarters/audio"

    /** The game's own coordinates: 320x480 points. */
    const val SCREEN_W = 320
    const val SCREEN_H = 480

    /** Drawn at the iPhone 4's 640x960. */
    const val RENDER_W = 640
    const val RENDER_H = 960

    internal const val TAG = "iQuarters"

    /** The display's fastest mode at its current resolution. */
    @Suppress("DEPRECATION")
    @androidx.annotation.RequiresApi(23)
    fun fastestMode(activity: Activity): Display.Mode? {
        val d = activity.windowManager.defaultDisplay ?: return null
        val cur = d.mode
        return d.supportedModes
            .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            .maxByOrNull { it.refreshRate }
    }
}

/**
 * `iPhoneInput.touches`, rebuilt once per game frame from the MotionEvents since the last
 * one: a finger is BEGAN on its first frame, ENDED on its last (a frame after BEGAN at the
 * earliest, so a quick tap is still seen to begin), MOVED if it moved, else STATIONARY.
 * Positions are points from the bottom left, y up; `deltaPosition` is the movement summed
 * over the frame.
 */
class IqTouches {
    private class Finger(var x: Float, var y: Float, var rawX: Float, var rawY: Float, time: Double) {
        var dx = 0f; var dy = 0f
        var began = true; var moved = false; var ended = false; var canceled = false
        var shot: FlickShot? = null
        val gesture = TimedFlick().apply { begin(time) }
    }
    private val fingers = LinkedHashMap<Int, Finger>()
    fun cancel() = synchronized(fingers) { fingers.clear() }
    fun onTouch(w: Int, h: Int, e: MotionEvent) {
        if (w <= 0 || h <= 0) return
        val layout = IqViewport(w, h)
        val scale = 320f / w
        fun update(f: Finger, x: Float, y: Float, time: Double) {
            val dx = (x - f.rawX) * scale; val dy = (f.rawY - y) * scale
            f.shot = f.shot ?: f.gesture.sample(dx, dy, time)
            f.dx += dx; f.dy += dy; f.moved = f.moved || dx != 0f || dy != 0f
            f.rawX = x; f.rawY = y
            val point = layout.touch(x, y); f.x = point.x; f.y = point.y
        }
        synchronized(fingers) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    if (e.actionMasked == MotionEvent.ACTION_DOWN) fingers.clear()
                    val i = e.actionIndex; val point = layout.touch(e.getX(i), e.getY(i))
                    fingers[e.getPointerId(i)] = Finger(point.x, point.y, e.getX(i), e.getY(i), e.eventTime / 1000.0)
                }
                MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {
                    val f = fingers[e.getPointerId(i)] ?: continue
                    if (f.ended) continue
                    for (j in 0 until e.historySize) update(f, e.getHistoricalX(i,j), e.getHistoricalY(i,j), e.getHistoricalEventTime(j)/1000.0)
                    update(f, e.getX(i), e.getY(i), e.eventTime/1000.0)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    val i = e.actionIndex
                    fingers[e.getPointerId(i)]?.let { f ->
                        update(f, e.getX(i), e.getY(i), e.eventTime/1000.0)
                        f.shot = f.shot ?: f.gesture.end(); f.ended = true
                    }
                }
                MotionEvent.ACTION_CANCEL -> for (f in fingers.values) {
                    f.gesture.cancel(); f.shot = null; f.canceled = true; f.ended = true; f.began = false
                }
            }
        }
    }
    fun frame(): List<IqTouch> = synchronized(fingers) {
        val out = ArrayList<IqTouch>(fingers.size); val it = fingers.entries.iterator()
        while (it.hasNext()) {
            val (id,f) = it.next()
            val phase = when { f.canceled -> IqTouch.CANCELED; f.began -> IqTouch.BEGAN; f.ended -> IqTouch.ENDED; f.moved -> IqTouch.MOVED; else -> IqTouch.STATIONARY }
            out += IqTouch(id, V2(f.x,f.y), V2(f.dx,f.dy), IqRuntime.FRAME_DT, phase, f.shot, true)
            if (phase == IqTouch.ENDED || phase == IqTouch.CANCELED) it.remove()
            f.began = false; f.moved = false; f.dx = 0f; f.dy = 0f; f.shot = null
        }
        out
    }
}

/**
 * The platform side of [IqRuntime]: SoundPool, SharedPreferences for `PlayerPrefs`, a
 * dialog for `iPhoneKeyboard`. Called from the GL thread.
 */
class IqAndroidHost(private val activity: Activity, pack: IqPack) : IqHost {
    private val pool: SoundPool = if (Build.VERSION.SDK_INT >= 21) SoundPool.Builder()
        .setMaxStreams(MAX_STREAMS)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build() else @Suppress("DEPRECATION") SoundPool(MAX_STREAMS, AudioManager.STREAM_MUSIC, 0)

    /** SoundPool ids by pack audio index; 0 where the file would not load. */
    private val ids = IntArray(pack.audio.size) { i ->
        runCatching { activity.assets.openFd("${IqAndroid.AUDIO_DIR}/${pack.audio[i].file}").use { pool.load(it, 1) } }.getOrDefault(0)
    }

    override val prefs: IqPrefs = SharedPrefs(activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    override fun play(audio: Int, volume: Float, pitch: Float, loop: Boolean): Int {
        val id = ids.getOrElse(audio) { 0 }
        if (id == 0) return 0
        val v = volume.coerceIn(0f, 1f)
        return pool.play(id, v, v, 1, if (loop) -1 else 0, pitch.coerceIn(0.5f, 2f))
    }

    override fun stop(handle: Int) = pool.stop(handle)

    override fun setVolume(handle: Int, volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        pool.setVolume(handle, v, v)
    }

    override fun log(msg: String) {
        Log.w(IqAndroid.TAG, msg)
    }

    override fun openUrl(url: String) {
        activity.runOnUiThread {
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: ActivityNotFoundException) {
                Log.w(IqAndroid.TAG, "no browser for $url")
            }
        }
    }

    /** `iPhoneKeyboard.Open`: a one-line dialog; Done or Cancel closes it. */
    override fun openKeyboard(text: String): IqKeyboard {
        val kb = IqKeyboard(text)
        activity.runOnUiThread {
            if (activity.isFinishing) { kb.done = true; kb.active = false; return@runOnUiThread }
            val edit = EditText(activity).apply {
                setText(text)
                setSingleLine()
                setSelection(text.length)
            }
            AlertDialog.Builder(activity)
                .setView(edit)
                .setPositiveButton(android.R.string.ok) { _, _ -> kb.text = edit.text.toString() }
                .setOnDismissListener { kb.done = true; kb.active = false }
                .show()
                .window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        return kb
    }

    fun pauseAll() = pool.autoPause()
    fun resumeAll() = pool.autoResume()
    fun release() = pool.release()

    /** `PlayerPrefs` on SharedPreferences: typed like Unity's, a wrong-typed read gives the default. */
    private class SharedPrefs(private val sp: SharedPreferences) : IqPrefs {
        override fun hasKey(key: String) = sp.contains(key)
        override fun getInt(key: String, def: Int) = runCatching { sp.getInt(key, def) }.getOrDefault(def)
        override fun getFloat(key: String, def: Float) = runCatching { sp.getFloat(key, def) }.getOrDefault(def)
        override fun getString(key: String, def: String) = runCatching { sp.getString(key, def) ?: def }.getOrDefault(def)
        override fun setInt(key: String, v: Int) = sp.edit().putInt(key, v).apply()
        override fun setFloat(key: String, v: Float) = sp.edit().putFloat(key, v).apply()
        override fun setString(key: String, v: String) = sp.edit().putString(key, v).apply()
        override fun deleteKey(key: String) = sp.edit().remove(key).apply()
    }

    private companion object {
        const val PREFS_NAME = "iquarters_prefs"
        const val MAX_STREAMS = 12
    }
}
