package com.guille.spring.iquarters.scripts

import com.guille.spring.iquarters.Behaviour
import com.guille.spring.iquarters.GObj
import com.guille.spring.iquarters.Iq
import com.guille.spring.iquarters.IqRuntime
import com.guille.spring.iquarters.IqStatic
import com.guille.spring.iquarters.Rect

/**
 * `mainmenu`: the front end's state machine on `mainData/ui_main_menu_00`, run from `OnGUI`.
 * Intro, then Play Now (game type: practice or classic, then 1-4 players for classic), High
 * Scores (game or round table), About, and the resume-last-game prompt. It also owns the
 * last-game save in `PlayerPrefs` (`LG_*` keys, per player with the player index appended),
 * which `QuarterTrigger` writes and reads through [SaveLastGame]/[LoadLastGame]. The iPad
 * rects and background scale are dropped.
 */
class mainmenu : Behaviour() {
    var areYouSureObject: GObj? = null
    var resumeGameObject: GObj? = null
    var aboutGameObject: GObj? = null
    var frontEndBgndObject: GObj? = null
    var titleLogoRenderer: GObj? = null
    var titleQLetterRenderer: GObj? = null
    var practiceMaterial = -1
    var practiceTexture = -1
    var practiceLockedTexture = -1
    var introSound = -1
    var clickSound = -1
    var enableButtonView = false
    var dummySkin: String? = null
    var fontSkin: String? = null

    /** The ARM `.ctor` stores 1; the decompiled C# has 0. OnGUI hands it to `LoadEntries`. */
    private var currentHiScoreTable = 1

    private val feStateMainInit = 99
    private val feStateMainPlaying = 100
    private val feStateMainMenu = 101
    private val feStateMainClick = 102
    private val feStatePlayNowOutPlaying = 103
    private val feStateGameTypeIn = 104
    private val feStateGameTypeMenu = 105
    private val feStateGameTypeOut = 106
    private val feStateGameTypeClick = 107
    private val feStateGameTypeOutBack = 108
    private val feStateNumPlayersIn = 120
    private val feStateNumPlayersMenu = 121
    private val feStateNumPlayersOut = 122
    private val feStateNumPlayersClick = 123
    private val feStateHiScoreIn = 130
    private val feStateHiScoreMenu = 131
    private val feStateHiScoreOut = 132
    private val feStateHiScoreClick = 133
    private val feStateLevelLoadInit = 134
    private val feStateLevelLoad = 135
    private val feStateLevelLoadDone = 136
    private val feStateAboutScreen = 140
    private val feStateResumeScreen = 150
    private val feStateHiScoreClearYesNo = 151

    /** The ARM `.ctor` (and the scene) set 100, `feStateMainPlaying`; the decompiled C# has 0. */
    var feStateCurrent = 100
    var feNextState = 0

    override fun onBind() {
        areYouSureObject = obj("areYouSureObject")
        resumeGameObject = obj("resumeGameObject")
        aboutGameObject = obj("aboutGameObject")
        frontEndBgndObject = obj("frontEndBgndObject")
        titleLogoRenderer = obj("titleLogoRenderer")
        titleQLetterRenderer = obj("titleQLetterRenderer")
        practiceMaterial = material("practiceMaterial")
        practiceTexture = texture("practiceTexture")
        practiceLockedTexture = texture("practiceLockedTexture")
        introSound = audioClip("introSound")
        clickSound = audioClip("clickSound")
        enableButtonView = bool("enableButtonView", false)
        dummySkin = skin("dummySkin")
        fontSkin = skin("fontSkin")
        feStateCurrent = int("feStateCurrent", 100)
        feNextState = int("feNextState", 0)
    }

    fun isPracticeLocked(): Boolean = HiScoreScript.lockedRoundStartIndex < 2

    /**
     * `practiceMaterial.mainTexture = …` writes the shared material, so every renderer drawing
     * it changes: here, every material instance made from that pack material.
     */
    fun SetPracticeTexture() {
        if (practiceMaterial < 0) throw NullPointerException("practiceMaterial")
        val tex = if (isPracticeLocked()) practiceLockedTexture else practiceTexture
        val src = world.pack.materials[practiceMaterial]
        for (o in world.objects) o.renderer?.materials?.forEach { if (it.src === src) it.mainTex = tex }
    }

    override fun start() {
        CheckforBgndScale()
        HiScoreScript.LoadLocks()
        SetPracticeTexture()
        aboutGameObject!!.setActiveRecursively(true)
        About.TriggerButtonOn = true
        // The ARM (0x23bff8) stores this to feStateCurrent. The reconstruction writes
        // feNextState instead, which leaves the intro unplayed and every button off screen.
        feStateCurrent = feStateMainInit
        feNumPlayers = 1
        feGameType = gtClassic
        find("ui_main_menu_00/button_back_hs")!!.renderer!!.enabled = false
        find("ui_main_menu_00/button_clear_hs")!!.renderer!!.enabled = false
        if (!resumeQuitFromGame && DoesLastGameExist()) {
            resumeDelayTriggerUI = true
            resumeGameObject!!.setActiveRecursively(true)
        }
        resumeQuitFromGame = false
        for (index in 0 until 10) find("ui_main_menu_00/high_score_bg_0$index")!!.renderer!!.enabled = false
    }

    fun DoesLastGameExist(): Boolean {
        val p = Iq.prefs
        if (!p.hasKey(lastGameTotPlayers) || !p.hasKey(lastGameCurPlayers) ||
            !p.hasKey(lastGameCurRound) || !p.hasKey(lastGameCurShotThisRound) ||
            !p.hasKey(lastGameCurMadeShotsThisRound)
        ) return false
        val players = p.getInt(lastGameTotPlayers)
        for (index in 0 until players) {
            if (!p.hasKey(lastGameNameEnteredFlag + index) ||
                !p.hasKey(lastGamePlayerName + index) ||
                !p.hasKey(lastGameScore + index) ||
                !p.hasKey(lastGameRoundScore + index) ||
                !p.hasKey(lastGameShotsLeft + index) ||
                !p.hasKey(lastGameStreak + index) ||
                !p.hasKey(lastGameMaxStreak + index) ||
                !p.hasKey(lastGameRoundStreak + index) ||
                !p.hasKey(lastGamePlayedThisRound + index) ||
                !p.hasKey(lastGameGameOver + index) ||
                !p.hasKey(lastGameInputType + index) ||
                !p.hasKey(lastGameMaxRicochet + index)
            ) return false
        }
        return true
    }

    fun PlayClickSound() {
        audio!!.clip = clickSound
        audio!!.play()
    }

    fun PlayIntroSound() {
        audio!!.clip = introSound
        audio!!.play()
    }

    /**
     * The game table, as the ARM (0x23d9d0) lays it: rank right-aligned so it ends at x 30,
     * the name in the skin's own alignment, the score right-aligned, 34 points a row from 87.
     */
    fun DisplayGameScoreRecords() {
        val guiSkin = fontSkin ?: "fontSkin"
        val entries = HiScoreScript.GetArray()
        for (i in entries.indices) {
            val entry = entries[i]
            val y = 87f + i * 34f
            guiLabel(Rect(-130f, y, 160f, 30f), "${i + 1} ", guiSkin, right = true)
            guiLabel(Rect(50f, y, 160f, 30f), entry.name + " ", guiSkin)
            guiLabel(Rect(100f, y, 160f, 30f), fmtScore(entry.score) + " ", guiSkin, right = true)
        }
    }

    /** `float.ToString()` for the scores this game keeps, which are whole. */
    private fun fmtScore(v: Float): String = if (v == Math.floor(v.toDouble()).toFloat()) v.toInt().toString() else v.toString()

    /** "Round" in the skin's own alignment, the number and score `UpperRight`; ARM 0x23e050. */
    fun DisplayRoundScoreRecords() {
        val guiSkin = fontSkin ?: "fontSkin"
        for (i in 0 until GameManagerScript.numRounds) {
            val y = 90f + i * 28f
            guiLabel(Rect(45f, y, 100f, 30f), "Round", guiSkin)
            guiLabel(Rect(50f, y, 100f, 30f), (i + 1).toString(), guiSkin, right = true)
            guiLabel(Rect(150f, y, 100f, 30f), HiScoreRoundScript.GetScore(i).toString(), guiSkin, right = true)
        }
    }

    /** Only widens the background on an iPad. */
    fun CheckforBgndScale() {}

    /**
     * Transcribed from the ARM (0x23e6dc), not the reconstruction, whose rects were 480 wide
     * and side by side where the portrait art stacks its buttons as edge tabs. One state is
     * handled per call; the buttons within a state are separate `if`s, as they are there.
     * The iPad orientation fix and `GetiPadRect` are dropped.
     */
    override fun onGUI() {
        val anim = animation!!
        when (feStateCurrent) {
            feStateMainInit -> {
                anim.play("intro")
                feStateCurrent = feStateMainPlaying
            }
            feStateMainPlaying -> {
                if (anim.isPlaying) return
                aboutGameObject!!.setActiveRecursively(true)
                About.TriggerButtonOn = true
                if (resumeDelayTriggerUI) {
                    resumeDelayTriggerUI = false
                    resumeTriggerYes = false
                    resumeTriggerNo = false
                    feStateCurrent = feStateResumeScreen
                } else {
                    feStateCurrent = feStateMainMenu
                }
            }
            feStateMainMenu -> {
                if (guiButton(Rect(0f, 330f, 230f, 50f))) {
                    feStateCurrent = feStateMainClick; feNextState = feStateGameTypeIn
                    anim.play("playnowclick"); About.TriggerButtonOff = true; PlayClickSound()
                }
                if (guiButton(Rect(90f, 392f, 230f, 50f))) {
                    feStateCurrent = feStateMainClick; feNextState = feStateHiScoreIn
                    anim.play("hiscoreclick"); DisplayMainLogo(false); About.TriggerButtonOff = true; PlayClickSound()
                }
                if (guiButton(Rect(250f, 325f, 65f, 50f))) {
                    titleLogoRenderer!!.renderer!!.enabled = false
                    titleQLetterRenderer!!.renderer!!.enabled = false
                    anim.play("playnowout"); About.TriggerTextOn = true
                    feStateCurrent = feStateAboutScreen; PlayClickSound()
                }
            }
            feStateMainClick -> {
                if (anim.isPlaying) return
                anim.play("playnowout"); feStateCurrent = feStatePlayNowOutPlaying
            }
            feStateResumeScreen -> {
                val resume = resumeGameObject!!
                if (resumeTriggerYes) {
                    if (resume.anim!!.isPlaying("YesClick")) return
                    resume.setActiveRecursively(false); feStateCurrent = feStateLevelLoadInit
                } else if (resumeTriggerNo) {
                    if (resume.anim!!.isPlaying("NoClick")) return
                    resumeTriggerNo = false; resume.setActiveRecursively(false); feStateCurrent = feStateMainMenu
                } else {
                    if (guiButton(Rect(60f, 259f, 90f, 44f))) {
                        PlayClickSound(); resumeTriggerYes = true; resume.anim!!.play("YesClick")
                    }
                    if (guiButton(Rect(179f, 259f, 90f, 44f))) {
                        PlayClickSound(); DeleteLastGame(); resumeTriggerNo = true; resume.anim!!.play("NoClick")
                    }
                }
            }
            feStateAboutScreen -> {
                if (guiButton(Rect(30f, 410f, 260f, 53f))) {
                    Iq.openURL("http://www.itmobileentertainment.com"); PlayClickSound()
                } else if (guiButton(Rect(0f, 0f, 320f, 480f))) {
                    titleLogoRenderer!!.renderer!!.enabled = true
                    titleQLetterRenderer!!.renderer!!.enabled = true
                    About.TriggerTextOff = true; anim.play("intro"); feStateCurrent = feStateMainPlaying
                }
            }
            feStatePlayNowOutPlaying -> {
                if (anim.isPlaying) return
                if (feNextState == feStateGameTypeIn) {
                    anim.play("gtin"); feStateCurrent = feStateGameTypeIn
                } else if (feNextState == feStateHiScoreIn) {
                    HiScoreScript.LoadEntries(currentHiScoreTable); HiScoreRoundScript.LoadEntries()
                    BackClearButtons.TriggerIn = true
                    GameOrRoundButtons.TriggerIn = true
                    GameHighScreen.TriggerIn = true
                    GameHighScreen.IsScreenSliding = true
                    Logo.TriggerIn = true
                    feStateCurrent = feStateHiScoreIn
                }
            }
            feStateHiScoreIn -> {
                if (anim.isPlaying) return
                feStateCurrent = feStateHiScoreMenu
            }
            feStateHiScoreMenu -> {
                if (guiButton(Rect(0f, 432f, 155f, 40f))) {
                    feNextState = feStateMainPlaying; feStateCurrent = feStateHiScoreClick
                    BackClearButtons.TriggerBack = true; GameOrRoundButtons.TriggerOut = true; PlayClickSound()
                }
                if (guiButton(Rect(165f, 432f, 160f, 40f))) {
                    areYouSureObject!!.setActiveRecursively(true)
                    AreYouSure.returnState = AreYouSure.stateWaitForAnswer
                    AreYouSure.triggerAreYouSure = true
                    PlayClickSound()
                    BackClearButtons.TriggerClear = true
                    feStateCurrent = feStateHiScoreClearYesNo
                }
                if (guiButton(Rect(165f, 3f, 155f, 35f)) && !GameOrRoundButtons.gameButtonSelected) {
                    PlayClickSound()
                    GameOrRoundButtons.TriggerButtonGame = true
                    GameHighScreen.TriggerIn = true; GameHighScreen.IsScreenSliding = true
                    RoundHighScreen.TriggerOut = true; RoundHighScreen.IsScreenSliding = true
                }
                if (guiButton(Rect(165f, 43f, 155f, 35f)) && GameOrRoundButtons.gameButtonSelected) {
                    PlayClickSound()
                    GameOrRoundButtons.TriggerButtonRound = true
                    GameHighScreen.TriggerOut = true; GameHighScreen.IsScreenSliding = true
                    RoundHighScreen.TriggerIn = true; RoundHighScreen.IsScreenSliding = true
                }
                if (GameHighScreen.IsScreenSliding || RoundHighScreen.IsScreenSliding) return
                if (GameOrRoundButtons.gameButtonSelected) DisplayGameScoreRecords() else DisplayRoundScoreRecords()
            }
            feStateHiScoreClearYesNo -> {
                if (AreYouSure.returnState == AreYouSure.stateAnswerNo) {
                    PlayClickSound(); feStateCurrent = feStateHiScoreMenu
                } else if (AreYouSure.returnState == AreYouSure.stateAnswerYes) {
                    PlayClickSound()
                    HiScoreScript.WipeoutAllPrefs()
                    HiScoreScript.LoadEntries(currentHiScoreTable)
                    SetPracticeTexture()
                    feStateCurrent = feStateHiScoreMenu
                }
            }
            feStateHiScoreClick -> {
                if (anim.isPlaying) return
                feStateCurrent = feStateHiScoreOut
                if (GameOrRoundButtons.gameButtonSelected) {
                    GameHighScreen.TriggerOut = true; GameHighScreen.IsScreenSliding = true
                } else {
                    RoundHighScreen.TriggerOut = true; RoundHighScreen.IsScreenSliding = true
                }
                Logo.TriggerOut = true
            }
            feStateHiScoreOut -> {
                if (GameHighScreen.IsScreenSliding || RoundHighScreen.IsScreenSliding) return
                DisplayMainLogo(true); feStateCurrent = feStateMainPlaying; anim.play("intro")
            }
            feStateGameTypeIn -> {
                if (anim.isPlaying) return
                feStateCurrent = feStateGameTypeMenu
            }
            feStateGameTypeMenu -> {
                if (!isPracticeLocked() && guiButton(Rect(2f, 296f, 215f, 50f))) {
                    feNextState = 0; feStateCurrent = feStateGameTypeClick
                    anim.play("gtpracticeclick"); PlayClickSound(); feGameType = gtPractice
                }
                if (guiButton(Rect(14f, 238f, 215f, 50f))) {
                    feNextState = feStateNumPlayersIn; feStateCurrent = feStateGameTypeClick
                    anim.play("gtclassicclick"); PlayClickSound(); feGameType = gtClassic
                }
                if (guiButton(Rect(0f, 406f, 220f, 50f))) {
                    feNextState = feStateMainPlaying; feStateCurrent = feStateGameTypeClick
                    anim.play("gtbackclick"); PlayClickSound()
                }
            }
            // Practice leaves feNextState 0, and goes on through NumPlayersOut to the load.
            feStateGameTypeClick -> {
                if (anim.isPlaying) return
                if (feNextState == 0) {
                    feStateCurrent = feStateNumPlayersOut; anim.play("gtpracticeout")
                } else {
                    feStateCurrent = feStateGameTypeOut; anim.play("gtout")
                }
            }
            feStateGameTypeOut -> {
                if (anim.isPlaying) return
                GameTypeNextState(feNextState)
            }
            feStateNumPlayersIn -> {
                if (anim.isPlaying) return
                feStateCurrent = feStateNumPlayersMenu
            }
            feStateNumPlayersMenu -> {
                val picks = arrayOf(
                    Rect(90f, 200f, 180f, 50f) to "nponeplayer",
                    Rect(110f, 255f, 180f, 50f) to "nptwoplayer",
                    Rect(102f, 310f, 180f, 50f) to "npthreeplayer",
                    Rect(55f, 365f, 180f, 50f) to "npfourplayer",
                )
                for ((n, pick) in picks.withIndex()) {
                    if (guiButton(pick.first)) {
                        feNextState = feStateNumPlayersOut; feStateCurrent = feStateNumPlayersClick
                        anim.play(pick.second); PlayClickSound(); feNumPlayers = n + 1
                    }
                }
                if (guiButton(Rect(190f, 426f, 130f, 50f))) {
                    feNextState = feStateGameTypeIn; feStateCurrent = feStateNumPlayersClick
                    anim.play("npbackclick"); PlayClickSound()
                }
            }
            feStateNumPlayersClick -> {
                if (anim.isPlaying) return
                feStateCurrent = feStateNumPlayersOut; anim.play("npout")
            }
            feStateNumPlayersOut -> {
                if (anim.isPlaying) return
                if (feNextState == feStateGameTypeIn) {
                    feStateCurrent = feStateGameTypeIn; anim.play("gtin")
                } else {
                    feStateCurrent = feStateLevelLoadInit
                }
            }
            feStateLevelLoadInit -> { DisplayLoadingMessage(); feStateCurrent = feStateLevelLoad }
            feStateLevelLoad -> { DisplayLoadingMessage(); LevelLoad(); feStateCurrent = feStateLevelLoadDone }
            feStateLevelLoadDone -> DisplayLoadingMessage()
        }
    }

    fun DisplayMainLogo(flag: Boolean) {
        find("/ui_main_menu_00/logo_bg_q_00")!!.renderer!!.enabled = flag
        find("/ui_main_menu_00/logo_quarters_00")!!.renderer!!.enabled = flag
    }

    /** Bottom left: `Rect(6, 480 - 32, 120, 32)` (ARM 0x240fd0). */
    fun DisplayLoadingMessage() {
        guiLabel(Rect(6f, 448f, 120f, 32f), "Loading...", fontSkin ?: "fontSkin")
    }

    /** The ARM (0x241158, 0x2411ac) stores feStateCurrent; the reconstruction had feNextState. */
    fun GameTypeNextState(nextState: Int) {
        if (nextState == feStateMainPlaying) {
            feStateCurrent = feStateMainPlaying
            animation!!.play("intro")
        } else if (nextState == feStateNumPlayersIn) {
            feStateCurrent = feStateNumPlayersIn
            animation!!.play("npin")
        }
    }

    /** `Application.LoadLevel("qtr")`: the game scene, which the pack calls `level0`. */
    fun LevelLoad() {
        Iq.loadLevel(IqRuntime.LEVEL_GAME)
    }

    companion object : IqStatic {
        const val gtPractice = 1
        const val gtClassic = 0
        @JvmField var feNumPlayers = 1
        /** The ARM `.cctor` copies `gtClassic` into it, so 0. */
        @JvmField var feGameType = gtClassic
        @JvmField var resumeQuitFromGame = false
        @JvmField var resumeDelayTriggerUI = false
        @JvmField var resumeTriggerYes = false
        @JvmField var resumeTriggerNo = false

        const val lastGameTotPlayers = "LG_TotPlayers"
        const val lastGameCurPlayers = "LG_CurPlayer"
        const val lastGameCurRound = "LG_CurRound"
        const val lastGameCurShotThisRound = "LG_CurShotThisRound"
        const val lastGameCurMadeShotsThisRound = "LG_CurMadeShotsThisRound"
        const val lastGameNameEnteredFlag = "LG_NameEnteredFlag"
        const val lastGamePlayerName = "LG_PlayerName"
        const val lastGameScore = "LG_Score"
        const val lastGameRoundScore = "LG_RoundScore"
        const val lastGameShotsLeft = "LG_ShotsLeft"
        const val lastGameStreak = "LG_Streak"
        const val lastGameMaxStreak = "LG_MaxStreak"
        const val lastGameRoundStreak = "LG_RoundStreak"
        const val lastGamePlayedThisRound = "LG_PlayedThisRound"
        const val lastGameGameOver = "LG_GameOver"
        const val lastGameInputType = "LG_InputType"
        const val lastGameMaxRicochet = "LG_MaxRicochet"

        override fun reset() {
            feNumPlayers = 1
            feGameType = gtClassic
            resumeQuitFromGame = false
            resumeDelayTriggerUI = false
            resumeTriggerYes = false
            resumeTriggerNo = false
        }

        @JvmStatic fun DeleteLastGame() {
            val p = Iq.prefs
            p.deleteKey(lastGameTotPlayers)
            p.deleteKey(lastGameCurPlayers)
            p.deleteKey(lastGameCurRound)
            p.deleteKey(lastGameCurShotThisRound)
            p.deleteKey(lastGameCurMadeShotsThisRound)
            for (index in 0 until 4) {
                p.deleteKey(lastGameNameEnteredFlag + index)
                p.deleteKey(lastGamePlayerName + index)
                p.deleteKey(lastGameScore + index)
                p.deleteKey(lastGameRoundScore + index)
                p.deleteKey(lastGameShotsLeft + index)
                p.deleteKey(lastGameStreak + index)
                p.deleteKey(lastGameMaxStreak + index)
                p.deleteKey(lastGameRoundStreak + index)
                p.deleteKey(lastGamePlayedThisRound + index)
                p.deleteKey(lastGameGameOver + index)
                p.deleteKey(lastGameInputType + index)
                p.deleteKey(lastGameMaxRicochet + index)
            }
        }

        @JvmStatic fun LoadLastGame() {
            val p = Iq.prefs
            feGameType = gtClassic
            GameManagerScript.totPlayers = p.getInt(lastGameTotPlayers)
            GameManagerScript.curPlayer = p.getInt(lastGameCurPlayers)
            GameManagerScript.curRound = p.getInt(lastGameCurRound)
            GameManagerScript.curShotThisRound = p.getInt(lastGameCurShotThisRound)
            GameManagerScript.curMadeShotsThisRound = p.getInt(lastGameCurMadeShotsThisRound)
            for (index in 0 until GameManagerScript.totPlayers) {
                val player = GameManagerScript.playerData[index] as PlayerInfoClass
                player.nameEnteredFlag = p.getInt(lastGameNameEnteredFlag + index) != 0
                player.playerName = p.getString(lastGamePlayerName + index)
                player.score = p.getInt(lastGameScore + index)
                player.roundScore = p.getInt(lastGameRoundScore + index)
                player.shotsLeft = p.getInt(lastGameShotsLeft + index)
                player.streak = p.getInt(lastGameStreak + index)
                player.maxStreak = p.getInt(lastGameMaxStreak + index)
                player.roundStreak = p.getInt(lastGameRoundStreak + index)
                player.playedThisRound = p.getInt(lastGamePlayedThisRound + index) != 0
                player.gameOver = p.getInt(lastGameGameOver + index) != 0
                player.inputType = p.getInt(lastGameInputType + index)
                player.maxRicochet = p.getInt(lastGameMaxRicochet + index)
            }
        }

        @JvmStatic fun SaveLastGame() {
            val p = Iq.prefs
            p.setInt(lastGameTotPlayers, GameManagerScript.totPlayers)
            p.setInt(lastGameCurPlayers, GameManagerScript.curPlayer)
            p.setInt(lastGameCurRound, GameManagerScript.curRound)
            p.setInt(lastGameCurShotThisRound, GameManagerScript.curShotThisRound)
            p.setInt(lastGameCurMadeShotsThisRound, GameManagerScript.curMadeShotsThisRound)
            for (index in 0 until GameManagerScript.totPlayers) {
                val player = GameManagerScript.playerData[index] as PlayerInfoClass
                p.setInt(lastGameNameEnteredFlag + index, if (player.nameEnteredFlag) 1 else 0)
                p.setString(lastGamePlayerName + index, player.playerName)
                p.setInt(lastGameScore + index, player.score)
                p.setInt(lastGameRoundScore + index, player.roundScore)
                p.setInt(lastGameShotsLeft + index, player.shotsLeft)
                p.setInt(lastGameStreak + index, player.streak)
                p.setInt(lastGameMaxStreak + index, player.maxStreak)
                p.setInt(lastGameRoundStreak + index, player.roundStreak)
                p.setInt(lastGamePlayedThisRound + index, if (player.playedThisRound) 1 else 0)
                p.setInt(lastGameGameOver + index, if (player.gameOver) 1 else 0)
                p.setInt(lastGameInputType + index, player.inputType)
                p.setInt(lastGameMaxRicochet + index, player.maxRicochet)
            }
        }
    }
}
