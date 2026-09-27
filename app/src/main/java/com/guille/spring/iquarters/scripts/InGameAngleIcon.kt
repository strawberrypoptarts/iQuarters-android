package com.guille.spring.iquarters.scripts

import com.guille.spring.iquarters.Behaviour
import com.guille.spring.iquarters.GObj
import com.guille.spring.iquarters.Iq
import com.guille.spring.iquarters.IqStatic
import com.guille.spring.iquarters.MeshInst
import com.guille.spring.iquarters.Rect
import com.guille.spring.iquarters.V3

/**
 * `InGameAngleIcon`: the angle button at the screen's edge. It slides on when a shot can be
 * taken and off when one is launched, shows the angle's two digits, pulses when pressed,
 * and its button (plus Done) enters and leaves `QuarterTrigger.stateAngleInput`.
 *
 * The iPad scale/position and rect branches are dropped.
 */
class InGameAngleIcon : Behaviour() {
    var enableButtonView = false
    var dummySkin: String? = null
    var UISkin: String? = null
    var DoneButtonEnabled = true

    private var angleIconRootObject: GObj? = null
    private val stateOffScreen = 100
    private val stateSlidingOn = 101
    private val stateOnScreen = 102
    private val stateSlidingOff = 103
    private var state = 100
    private var angleIconOffScreenX = 0f
    private val angleIconOffScreenX_iPhone = 35f
    private val angleIconOffScreenX_iPad = 60f
    private var angleIconOnScreenDeltaX = 35f
    private var angleIconSlidingTimer = 0f
    private val angleIconSlideTime = 0.3f
    private val scaleStateIdle = 200
    private val scaleStateScalingDown = 201
    private val scaleStateScalingUp = 202
    private var scaleState = 200
    private var angleIconStartScale = V3.ZERO
    private var angleIconEndScale = V3.ZERO
    private val angleIconEndScalePercent = 0.99f
    private var angleIconScalingTimer = 0f
    private var angleIconDeltaScale = V3.ZERO
    private val angleIconScaleTime = 0.05f

    private var deg_01_mesh: MeshInst? = null
    private var deg_01_verts: FloatArray? = null
    private var deg_01_uv: FloatArray? = null
    private var deg_02_mesh: MeshInst? = null
    private var deg_02_verts: FloatArray? = null
    private var deg_02_uv: FloatArray? = null

    override fun onBind() {
        enableButtonView = bool("enableButtonView", false)
        dummySkin = skin("dummySkin")
        UISkin = skin("UISkin")
        DoneButtonEnabled = bool("DoneButtonEnabled", true)
    }

    override fun start() {
        val root = find("/ui_ingame_angle_root")!!
        angleIconRootObject = root
        angleIconOnScreenDeltaX = 35f
        angleIconOffScreenX = root.localPosition.x
        angleIconStartScale = root.localScale
        angleIconEndScale = angleIconStartScale * angleIconEndScalePercent
        angleIconDeltaScale = angleIconStartScale - angleIconEndScale
        triggerScaleReminder = false
        triggerReminder = false
        triggerOffScreen = false
        triggerOnScreen = false
        state = stateOffScreen
        scaleState = scaleStateIdle

        val m1 = find("/ui_ingame_angle_root/UI_ingame_angle/button_angle/deg_01")!!.meshFilter!!
        deg_01_mesh = m1
        deg_01_verts = m1.vertices.copyOf()
        deg_01_uv = m1.uv?.copyOf()
        val m2 = find("/ui_ingame_angle_root/UI_ingame_angle/button_angle/deg_02")!!.meshFilter!!
        deg_02_mesh = m2
        deg_02_verts = m2.vertices.copyOf()
        deg_02_uv = m2.uv?.copyOf()
    }

    override fun update() {
        val root = angleIconRootObject!!
        if (triggerScaleReminder || triggerReminder) {
            triggerScaleReminder = false
            triggerReminder = false
        }
        if (triggerOffScreen) {
            angleIconSlidingTimer = time
            state = stateSlidingOff
            triggerOffScreen = false
        }
        if (triggerOnScreen) {
            HandleTextures(GameManagerScript.shotAngle.toInt())
            angleIconSlidingTimer = time
            state = stateSlidingOn
            triggerOnScreen = false
        }

        if (state == stateSlidingOn || state == stateSlidingOff) {
            val on = state == stateSlidingOn
            val direction = if (on) 1f else -1f
            var x = root.localPosition.x + direction * (time - angleIconSlidingTimer) * angleIconOnScreenDeltaX / angleIconSlideTime
            angleIconSlidingTimer = time
            val target = if (on) angleIconOffScreenX + angleIconOnScreenDeltaX else angleIconOffScreenX
            if ((direction > 0f && x >= target) || (direction < 0f && x <= target)) {
                x = target
                state = if (on) stateOnScreen else stateOffScreen
            }
            root.localPosition = root.localPosition.copy(x = x)
        }
        if (QuarterTrigger.state == QuarterTrigger.stateAngleInput) DisplayDoneButton(true)

        if (scaleState == scaleStateScalingDown || scaleState == scaleStateScalingUp) {
            DisplayDoneButton(false)
            val direction = if (scaleState == scaleStateScalingDown) -1f else 1f
            root.localScale = root.localScale + angleIconDeltaScale * (direction * ((time - angleIconScalingTimer) / angleIconScaleTime))
            angleIconScalingTimer = time
            if (scaleState == scaleStateScalingDown && root.localScale.x <= angleIconEndScale.x) {
                root.localScale = angleIconEndScale
                scaleState = scaleStateScalingUp
            } else if (scaleState == scaleStateScalingUp && root.localScale.x >= angleIconStartScale.x) {
                root.localScale = angleIconStartScale
                scaleState = scaleStateIdle
            }
        }
    }

    fun DisplayDoneButton(flag: Boolean) {
        if (!DoneButtonEnabled) return
        find("/ui_ingame_angle_root/UI_ingame_angle/button_done2")?.renderer?.enabled = flag
    }

    /**
     * Traced from the AOT ARM (`InGameAngleIcon.OnGUI`, 0x268694-0x268d74). The angle button is
     * `Rect(250, 2, 64, 64)` in both states: x, y and w are held in r6/r5/r4 and h in a stack
     * slot, set once in the prologue (0x2686b0-0x2686c0), which is why a literal scan reads them
     * as computed. Done is `Rect(0, 2, 160, 64)` (0x268bc4); the `(0, 2, 200, 64)` built at
     * 0x268ac8 is the iPad branch, fed to `GetiPadRect`, and is dropped. Both are invisible
     * buttons (`String.Empty`); the art is the 3D `button_angle`/`button_done2` meshes.
     *
     * Skins: the first block sets `dummySkin` only when `!enableButtonView`; the second sets
     * `dummySkin` when `!enableButtonView` and `null` otherwise (0x268968-0x268984). Buttons here
     * take no skin, so neither changes anything.
     */
    override fun onGUI() {
        val angleButton = Rect(250f, 2f, 64f, 64f)
        val doneButton = Rect(0f, 2f, 160f, 64f)
        if (QuarterTrigger.state == QuarterTrigger.stateWaitForShot) {
            // 0x268834: pressed -> actions then branch to the end (0x2688c4); not pressed -> end.
            if (guiButton(angleButton)) {
                DisplayDoneButton(true)
                angleIconScalingTimer = time
                scaleState = scaleStateScalingDown
                CoinHolder.triggerCoinHolderOut = GameManagerScript.curMadeShotsThisRound
                AnnouncerScript.triggerClickSound = true
                QuarterTrigger.requestAngleInput = true
            }
            return
        }
        if (QuarterTrigger.state != QuarterTrigger.stateAngleInput) return
        HandleTextures(GameManagerScript.shotAngle.toInt())
        // A local flag (fp+0x24) is set by either button; both are always drawn (0x268a90, 0x268ca0).
        var done = guiButton(angleButton)
        if (DoneButtonEnabled && guiButton(doneButton)) done = true
        if (done) {
            DisplayDoneButton(false)
            angleIconScalingTimer = time
            scaleState = scaleStateScalingDown
            CoinHolder.triggerCoinHolderIn = GameManagerScript.curMadeShotsThisRound
            AnnouncerScript.triggerClickSound = true
            QuarterTrigger.state = QuarterTrigger.stateWaitForShot
        }
    }

    fun HandleTextures(number: Int) {
        var n = number
        if (n < 10 || n > 99) {
            Iq.log("Round Score is out of range!")
            n = n.coerceIn(10, 99)
        }
        CoinHolder.DisplayDigit(deg_01_mesh!!, deg_01_uv!!, n / 10)
        CoinHolder.DisplayDigit(deg_02_mesh!!, deg_02_uv!!, n % 10)
    }

    companion object : IqStatic {
        var triggerScaleReminder = false
        var triggerReminder = false
        var triggerOffScreen = false
        var triggerOnScreen = false

        override fun reset() {
            triggerScaleReminder = false
            triggerReminder = false
            triggerOffScreen = false
            triggerOnScreen = false
        }
    }
}
