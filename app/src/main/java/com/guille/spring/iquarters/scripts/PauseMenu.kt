package com.guille.spring.iquarters.scripts

import com.guille.spring.iquarters.Behaviour
import com.guille.spring.iquarters.GuiLabel
import com.guille.spring.iquarters.GObj
import com.guille.spring.iquarters.Iq
import com.guille.spring.iquarters.IqStatic
import com.guille.spring.iquarters.Rect

/**
 * `PauseMenu`: the in-game options menu. [triggerAnimIn] slides it in; its buttons are quit
 * (end the game, raising [cancelPauseMenu]), replay (watch the last shot), done (back to the
 * game), sound (toggle `QuarterTrigger.muteF`) and help, which slides the menu out and shows
 * the two "Game Rules" pages. Each button also feeds the secret-round unlock code. The GUI
 * methods are transcribed from the AOT ARM, not the decompiled C#; the iPad scaling and rects
 * are dropped.
 */
class PauseMenu : Behaviour() {
    var roundIndicatorObject: GObj? = null
    var helpGameObject: GObj? = null
    var PauseMenuObject: GObj? = null
    var PauseMenuBgndObject: GObj? = null
    var FlickFingerObject: GObj? = null
    var enableButtonView = false
    var dummySkin: String? = null
    var fontSkin: String? = null
    var fontSkin_iPad: String? = null
    var replayMaterial = -1
    var replayOnTexture = -1
    var replayGreyTexture = -1
    var soundMaterial = -1
    var soundOnTexture = -1
    var soundGreyTexture = -1

    // The ARM's ctor values; the C# numbers these 1000..1008. Only their distinctness matters.
    private val statePauseMenuIdle = 1000
    private val statePauseMenuIn = 233
    private val statePauseMenu = 234
    private val statePauseMenuOut = 235
    private val statePauseMenuOutToHOF = 1004
    private val statePauseMenuQuitOut = 237
    private val statePauseMenuHelpClick = 238
    private val statePauseMenuHelpSlideOut = 239
    private val statePauseMenuHelpScreen = 1008
    /** The ARM's ctor sets 1000 (the C# leaves it 0); `Start` sets it to idle either way. */
    private var currentState = 1000
    private var slideInUIFlag = true
    @Suppress("unused") private var test = 0
    /** The ctor dump could not read this string; the C#'s value is kept. */
    private val infoClickAnimString = "InfoClick"
    private var currentHelpPage = 0
    private val numHelpPages = 2

    override fun onBind() {
        roundIndicatorObject = obj("roundIndicatorObject")
        helpGameObject = obj("helpGameObject")
        PauseMenuObject = obj("PauseMenuObject")
        PauseMenuBgndObject = obj("PauseMenuBgndObject")
        FlickFingerObject = obj("FlickFingerObject")
        enableButtonView = bool("enableButtonView", false)
        dummySkin = skin("dummySkin")
        fontSkin = skin("fontSkin")
        fontSkin_iPad = skin("fontSkin_iPad")
        replayMaterial = material("replayMaterial")
        replayOnTexture = texture("replayOnTexture")
        replayGreyTexture = texture("replayGreyTexture")
        soundMaterial = material("soundMaterial")
        soundOnTexture = texture("soundOnTexture")
        soundGreyTexture = texture("soundGreyTexture")
    }

    override fun start() {
        currentState = statePauseMenuIdle
    }

    /** `material.mainTexture = t` on a shared material: every instance made from it changes. */
    private fun setSharedMainTexture(material: Int, tex: Int, field: String) {
        if (material < 0) throw NullPointerException(field)
        val src = world.pack.materials[material]
        for (o in world.objects) o.renderer?.materials?.forEach { if (it.src === src) it.mainTex = tex }
    }

    fun HandleButtonMaterials() {
        setSharedMainTexture(replayMaterial, if (ReplayController.IsReplayDataValid()) replayOnTexture else replayGreyTexture, "replayMaterial")
        setSharedMainTexture(soundMaterial, if (QuarterTrigger.muteF) soundGreyTexture else soundOnTexture, "soundMaterial")
    }

    fun isFlickShot(): Boolean = GameManagerScript.GetCurrentInputType() != GameManagerScript.inputTypeShake

    /**
     * The "Game Rules" pages, as the ARM draws them (0x26a430). Every label is under `fontSkin`.
     *
     * The title and the footer are drawn `UpperCenter` (alignment 1, 0x26a9a4) in the text colour
     * with r and g forced to 255 and b to 0 (0x26aaec, 0x26ac34, 0x26ad7c: three separate
     * `set_textColor`s, each changing one channel of the current colour, so yellow at the skin's
     * own alpha). The skin's alignment and colour are saved first and put back after them
     * (0x26afb8, 0x26affc). On each page the section headings are yellow again, and the colour
     * is put back before the dashes and the body, which are in the skin's own alignment.
     *
     * The ARM keeps the columns in registers, x 10 for headings, 20 for the dashes and 25 for
     * the body (0x26a964; the iPad's 20/25/30 are dropped), and computes every y as a running
     * sum from 50: a 12-point line (r6, 0x26b000) with 20-point gaps between paragraphs. The
     * ys below are those sums evaluated, read off the ARM by stepping it for page 0 and 1.
     * A page other than 0 and 1 draws only the title and footer (0x26d858).
     *
     */
    fun DrawHelpText() {
        val s = fontSkin ?: "fontSkin"
        // Title and footer: UpperCenter, yellow (see above).
        guiLabel(Rect(0f, 17f, 320f, 30f), "Game Rules", s, topCenter = true, color = GuiLabel.YELLOW)
        guiLabel(Rect(0f, 450f, 320f, 30f), "Tap To Continue", s, topCenter = true, color = GuiLabel.YELLOW)
        fun heading(y: Float, t: String) = guiLabel(Rect(10f, y, 320f, 100f), t, s, color = GuiLabel.YELLOW)
        fun dash(y: Float) = guiLabel(Rect(20f, y, 100f, 100f), "-", s)
        fun body(y: Float, t: String) = guiLabel(Rect(25f, y, 320f, 100f), t, s)
        if (currentHelpPage == 0) {
            // 0x26b13c
            heading(50f, "Two Modes: Classic and Practice")
            heading(180f, "Play")
            for (y in floatArrayOf(70f, 126f, 200f, 244f, 288f, 332f, 376f)) dash(y)
            body(70f, "Classic Mode is the regular game")
            body(82f, "where players must sink 3 shots")
            body(94f, "before advancing to the next")
            body(106f, "round.")
            body(126f, "Practice Mode allows players to")
            body(138f, "practice taking shots in any of")
            body(150f, "the rounds.")
            body(200f, "Flick the quarter to make it fly")
            body(212f, "toward the glass. The quicker")
            body(224f, "you flick, the farther it flies.")
            body(244f, "Before taking a shot, tap on the")
            body(256f, "shot angle button in the upper")
            body(268f, "right of the screen.")
            body(288f, "Flick down on the green arrow")
            body(300f, "to take a steeper angle shot")
            body(312f, "(shorter distance).")
            body(332f, "Flick up on the green arrow to")
            body(344f, "use a flatter angle (longer")
            body(356f, "distance).")
            body(376f, "Tap on the shot angle button")
            body(388f, "again to return to the game.")
        } else if (currentHelpPage == 1) {
            // 0x26d86c
            heading(50f, "Rules")
            heading(268f, "Scoring")
            for (y in floatArrayOf(70f, 114f, 158f, 214f, 288f, 308f, 340f, 372f)) dash(y)
            // The trailing space is in the binary's string.
            body(70f, "The game starts with up to 4 ")
            body(82f, "players and 40 quarters per")
            body(94f, "player.")
            body(114f, "During the 12 rounds of Classic")
            body(126f, "Mode, attempt to make 3 shots per")
            body(138f, "round to move onto the next round.")
            body(158f, "The harder the shot, the more")
            body(170f, "points you earn. Bounce a shot off")
            body(182f, "a neighboring object for big")
            body(194f, "ricochet bonus points.")
            body(214f, "Be careful! Miss a shot and one")
            body(226f, "quarter is removed from your")
            body(238f, "available quarters.")
            body(288f, "Make an easy shot for 25 points.")
            body(308f, "Harder shots are worth 50 (2X)")
            body(320f, "and 75 (3X) points.")
            body(340f, "10 points for each unique ricochet")
            body(352f, "before sinking in the glass.")
            body(372f, "5 bonus points for every unused")
            body(384f, "quarter at the end of the game.")
            // 0x270514: UpperCenter and yellow again, then both restored (0x270534, 0x270578).
            // The "||" is in the binary's string, verbatim.
            guiLabel(Rect(0f, 414f, 320f, 100f), "For Replays go to the Pause || Menu", s, topCenter = true, color = GuiLabel.YELLOW)
        }
    }

    /**
     * ARM 0x27059c: the whole screen is one invisible button (0,0,320,480), so a tap anywhere
     * turns the page, and on the last page slides the menu back in. The iPad branch that also
     * re-enables `FlickFingerObject`'s renderer is dropped.
     */
    fun HandleHelpScreen() {
        DrawHelpText()
        if (guiButton(Rect(0f, 0f, 320f, 480f))) {
            if (currentHelpPage == numHelpPages - 1) {
                HideHelpButton()
                animation!!.play("animin")
                AnnouncerScript.triggerClickSound = true
                currentState = statePauseMenuIn
            } else currentHelpPage++
        }
    }

    /**
     * ARM 0x270814. Five separate `if`s, in this order; the rects are the ARM's (help's
     * (270,0,50,50) is built from ints at 0x271000; `allrects` misreads it as 250s, which is
     * only the iPad's x). Replay's `RequestReplay` is evaluated only when its button fires.
     */
    fun HandlePauseMenu() {
        val quitRect = Rect(100f, 200f, 180f, 48f)
        val replayRect = Rect(120f, 256f, 200f, 48f)
        val doneRect = Rect(0f, 426f, 120f, 40f)
        val soundRect = Rect(146f, 426f, 43f, 40f)
        val helpRect = Rect(270f, 0f, 50f, 50f)
        val anim = animation!!
        if (guiButton(quitRect)) {
            AnnouncerScript.triggerClickSound = true
            anim.play("quitclick"); anim.playQueued("animout")
            HideHelpButton()
            GameManagerScript.StoreSecretRoundUnlockCode(6)
            currentState = statePauseMenuQuitOut
        }
        if (guiButton(replayRect) && ReplayController.RequestReplay()) {
            AnnouncerScript.triggerClickSound = true
            anim.play("flickclick"); anim.playQueued("animout")
            slideInUIFlag = false
            GameManagerScript.StoreSecretRoundUnlockCode(5)
            HideHelpButton()
            currentState = statePauseMenuOut
        }
        if (guiButton(doneRect)) {
            AnnouncerScript.triggerClickSound = true
            anim.play("doneclick"); anim.playQueued("animout")
            HideHelpButton()
            GameManagerScript.StoreSecretRoundUnlockCode(0)
            currentState = statePauseMenuOut
        }
        if (guiButton(soundRect)) {
            anim.play("soundclick")
            QuarterTrigger.muteF = !QuarterTrigger.muteF
            if (!QuarterTrigger.muteF) {
                AnnouncerScript.triggerClickSound = true
                GameManagerScript.StoreSecretRoundUnlockCode(2)
            } else GameManagerScript.StoreSecretRoundUnlockCode(1)
            QuarterTrigger.SaveMutePref()
        }
        if (guiButton(helpRect)) {
            AnnouncerScript.triggerClickSound = true
            helpGameObject!!.anim!!.play(infoClickAnimString)
            GameManagerScript.StoreSecretRoundUnlockCode(3)
            currentHelpPage = 0
            currentState = statePauseMenuHelpClick
        }
    }

    fun ReturnToGame() {
        QuarterTrigger.state = QuarterTrigger.stateWaitForShot
        if (slideInUIFlag) {
            CoinHolder.triggerCoinHolderIn = GameManagerScript.curMadeShotsThisRound
            InGameAngleIcon.triggerOnScreen = true
            roundIndicatorObject!!.setActiveRecursively(true)
            RoundIndicator.TriggerSlideIn = true
        }
        PauseMenuObject!!.setActiveRecursively(false)
    }

    override fun update() {
        val anim = animation!!
        if (triggerAnimIn) {
            anim.play("animin")
            triggerAnimIn = false
            slideInUIFlag = true
            HandleButtonMaterials()
            PlayAnimatingObjects(false, true)
            currentState = statePauseMenuIn
        }
        if (currentState == statePauseMenuIn) {
            if (anim["animin"]!!.time > 0.25f) QuarterTrigger.DisplayBackDrop(true)
            if (!anim.isPlaying) {
                DisplayHelpButton()
                currentState = statePauseMenu
            }
        } else if (currentState == statePauseMenuOut) {
            if (!anim.isPlaying) {
                PlayAnimatingObjects(!QuarterTrigger.muteF, true)
                if (!QuarterTrigger.requestLastReplay) QuarterTrigger.DisplayBackDrop(false)
                ReturnToGame()
                currentState = statePauseMenuIdle
            }
        } else if (currentState == statePauseMenuOutToHOF) {
            if (!anim.isPlaying) {
                PauseMenuObject!!.setActiveRecursively(false)
                currentState = statePauseMenuIdle
            }
        } else if (currentState == statePauseMenuQuitOut) {
            if (!anim.isPlaying) {
                QuarterTrigger.state = QuarterTrigger.stateGameOver
                cancelPauseMenu = true
                mainmenu.DeleteLastGame()
                mainmenu.resumeQuitFromGame = true
                currentState = statePauseMenuIdle
            }
        } else if (currentState == statePauseMenuHelpClick && !helpGameObject!!.anim!!.isPlaying(infoClickAnimString)) {
            HideHelpButton()
            anim.play("animout")
            currentState = statePauseMenuHelpSlideOut
        } else if (currentState == statePauseMenuHelpSlideOut && !anim.isPlaying("animout")) {
            DisplayHelpScreen()
            currentState = statePauseMenuHelpScreen
        }
    }

    private fun helpRenderers(background: Boolean, dim: Boolean, help: Boolean, text: Boolean) {
        find("/ui_help/background01")!!.renderer!!.enabled = background
        find("/ui_help/dimplane")!!.renderer!!.enabled = dim
        find("/ui_help/help")!!.renderer!!.enabled = help
        find("/ui_help/help_text")!!.renderer!!.enabled = text
    }

    fun HideHelpButton() = helpRenderers(false, false, false, false)
    fun DisplayHelpButton() = helpRenderers(false, false, true, false)
    fun DisplayHelpScreen() = helpRenderers(false, true, false, true)

    /** ARM 0x271b5c: menu or help screen, an if/else-if on the state. */
    override fun onGUI() {
        if (currentState == statePauseMenu) {
            HandleButtonMaterials()
            HandlePauseMenu()
        } else if (currentState == statePauseMenuHelpScreen) HandleHelpScreen()
    }

    companion object : IqStatic {
        @JvmField var replayDataMaxSlots = 9
        @JvmField var triggerAnimIn = false
        @JvmField var cancelPauseMenu = false

        /** Plays (or stops) the biplane, helicopter and lazy susan; [soundFlag] is unused. */
        @JvmStatic fun PlayAnimatingObjects(@Suppress("UNUSED_PARAMETER") soundFlag: Boolean, animFlag: Boolean) {
            for (path in arrayOf("/biplane_00", "/helicopter_00", "/lazy_susan_00")) {
                val a = Iq.find(path)?.anim ?: continue
                if (animFlag) a.play() else a.stop()
            }
        }

        override fun reset() {
            replayDataMaxSlots = 9
            triggerAnimIn = false
            cancelPauseMenu = false
        }
    }
}
