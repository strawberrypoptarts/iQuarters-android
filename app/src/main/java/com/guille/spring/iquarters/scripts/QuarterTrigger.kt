package com.guille.spring.iquarters.scripts

import com.guille.spring.iquarters.Behaviour
import com.guille.spring.iquarters.Col
import com.guille.spring.iquarters.GObj
import com.guille.spring.iquarters.Iq
import com.guille.spring.iquarters.IqCollision
import com.guille.spring.iquarters.IqKeyboard
import com.guille.spring.iquarters.IqStatic
import com.guille.spring.iquarters.IqTouch
import com.guille.spring.iquarters.Quat
import com.guille.spring.iquarters.V3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * `QuarterTrigger`, the game: the shot, the coin's flight, scoring, and the state machine
 * that sequences every round, exciter, replay, menu and high-score screen in `level0`.
 *
 * Where the AOT ARM and the decompiled C# disagree, the ARM wins, and each place says so:
 * - `stateShotInAir` is 1, not the C#'s 0 (which would make it the same state as
 *   `stateWaitForShot`).
 * - The flick is `pow(min(dy, 360) * touchYScale / debugTargetRange, debugFlickPower)`
 *   against `magThreshFlick`, with sideways speed `dx / dy * 4`. It is not the C#'s
 *   `magnitude * 0.1 / magThresh`.
 * - `OnCollisionStay` keeps the last contact whose normal has `y > 0.05`, not the C#'s
 *   `separation > 0.05`.
 *
 * The debug spacebar shot and the iPad branches are dropped.
 */
class QuarterTrigger : Behaviour() {

    // -- replay recording --
    private var lastFlickTouch: IqTouch? = null
    private var inAirTicks = 0
    private var shotTick = 0
    private var qdTimeInterval = 10
    private var curQuarterDataIndex = 0
    private var quarterDataTicks = 0
    private val maxQuarterData = 200
    private val replayQPosition = Array(maxQuarterData) { V3.ZERO }
    private val replayQRotation = Array(maxQuarterData) { Quat.IDENTITY }
    private val replayQVelocity = Array(maxQuarterData) { V3.ZERO }
    private val replayQAngVel = Array(maxQuarterData) { V3.ZERO }

    private var quarterVelThreshold = 1f
    private var quarterCountThresh = 20
    private var quarterVelCount = 0

    private val atsNone = 10
    private val atsDown = 11
    private var angleTouchState = 10
    private var angleStartPosition = com.guille.spring.iquarters.V2.ZERO

    private var lightRayObject: GObj? = null
    private var glassFlashEmitter: GObj? = null
    private var debugint = -1
    private var shotOverFlags = 0

    // -- collision sounds --
    private val colliderSoundArray = ArrayList<Col?>()
    private var prevColliderSoundTime = 0f
    private var prevColliderSoundType = -1
    private val minColliderSoundDelay = 0.01f
    private val numColliderSoundTypes = 30
    private val colliderSoundPlayedCount = IntArray(numColliderSoundTypes)

    private var ricochetBonusSFX = -1
    private var secretRoundSFX = -1
    private var defaultAudioClip = -1
    private var coinInGlassSFX = IntArray(0)
    private val sounds = HashMap<String, IntArray>()

    private var enterNameObject: GObj? = null
    private var secretRoundObject: GObj? = null
    private var roundIndicatorObject: GObj? = null
    private var ricochetParentObject: GObj? = null
    private var streakExciterObject: GObj? = null
    private var coinsLeftObject: GObj? = null
    private var practiceUIObject: GObj? = null
    private var quarterDrawObject: GObj? = null
    private var replaySaveDoneButtonsObject: GObj? = null
    private var shotTypeHelperObject: GObj? = null
    private var practiceGreatScoreObject: GObj? = null
    private var roundCompleteObject: GObj? = null
    private var gameOverExciterObject: GObj? = null
    private var inGameHSObject: GObj? = null
    private var statsScreenObject: GObj? = null
    private var powerXObject: GObj? = null
    private var mainCamObject: GObj? = null
    private var replayCamObject: GObj? = null
    private var replayCamObject2: GObj? = null
    private var replayCamObject3: GObj? = null
    private var introCamObject: GObj? = null

    var ReplayRicochetIndex = 0
    private var replayLaunchTime = 0f
    private var lastPlayerNumber = 0
    private var lastRoundScore = 0
    private var keyboard: IqKeyboard? = null

    private var shotDownZMag = 20f
    private var startPos = V3.ZERO
    private var startRot = V3.ZERO
    private var startQuat = Quat.IDENTITY
    var inGlassCollider: Col? = null
        private set

    // -- the ricochet chain --
    private val maxColliders = 15
    private val colliderInfo = ArrayList<ColliderInfoClass>()
    private var curColliderIndex = 0
    private var lastHitCollider: Col? = null
    private val maxColliderGameObjects = 10
    private val colliderGameObjectInfo = ArrayList<ColliderGameObjectClass>()
    var curColliderGameObjectIndex = 0
        private set

    private var waitCount = 10
    private var waitTime = 0f
    private val shotvely = -16f
    private val shotrotx = 6f
    private var mag = 0f
    private var xvel = 0f

    private var doingRequestedReplay = false
    private var waitForFirstBounceCount = 5
    private var shotLaunchTime = 0f
    private var shotTimeMax = 3.6f
    private var gameShouldEndFlag = false
    private var stringPlayerName1 = "Hello2"
    private val playerNames = arrayOf("PLR1", "PLR2", "PLR3", "PLR4")
    private var savedCurrentScore = 0

    private val glasses = HashMap<String, GObj?>()
    private val glassShadows = arrayOfNulls<GObj>(8)

    private var debugFlickPower = 1f
    private var touchYScale = 0.1f
    private var debugTargetRange = 1.6f
    private var magThreshFlick = 0.5f

    private var bounceFlyCount = 0
    private var bounceFlyTime = 0f

    override fun onBind() {
        qdTimeInterval = int("qdTimeInterval", 10)
        quarterVelThreshold = num("quarterVelThreshold", 1f)
        quarterCountThresh = int("quarterCountThresh", 20)
        lightRayObject = obj("lightRayObject")
        glassFlashEmitter = obj("GlassFlashEmitter")
        debugint = int("debugint", -1)
        ricochetBonusSFX = audioClip("ricochetBonusSFX")
        secretRoundSFX = audioClip("secretRoundSFX")
        defaultAudioClip = audioClip("defaultAudioClip")
        coinInGlassSFX = audioClips("coinInGlassSFX")
        for (k in SOUND_FIELDS) sounds[k] = audioClips(k)
        enterNameObject = obj("enterNameObject")
        secretRoundObject = obj("secretRoundObject")
        roundIndicatorObject = obj("roundIndicatorObject")
        ricochetParentObject = obj("ricochetParentObject")
        streakExciterObject = obj("streakExciterObject")
        coinsLeftObject = obj("coinsLeftObject")
        practiceUIObject = obj("PracticeUIObject")
        quarterDrawObject = obj("QuarterDrawObject")
        replaySaveDoneButtonsObject = obj("ReplaySaveDoneButtonsObject")
        shotTypeHelperObject = obj("shotTypeHelperObject")
        practiceGreatScoreObject = obj("PracticeGreatScoreObject")
        roundCompleteObject = obj("RoundCompleteObject")
        gameOverExciterObject = obj("GameOverExciterObject")
        inGameHSObject = obj("InGameHSObject")
        statsScreenObject = obj("StatsScreenObject")
        powerXObject = obj("PowerXObject")
        mainCamObject = obj("mainCamObject")
        replayCamObject = obj("replayCamObject")
        replayCamObject2 = obj("replayCamObject2")
        replayCamObject3 = obj("replayCamObject3")
        introCamObject = obj("introCamObject")
        shotDownZMag = num("shotDownZMag", 20f)
        waitCount = int("waitCount", 10)
        waitForFirstBounceCount = int("waitForFirstBounceCount", 5)
        shotTimeMax = num("shotTimeMax", 3.6f)
        stringPlayerName1 = str("stringPlayerName1", "Hello2")
        for (i in 0..3) playerNames[i] = str("playerName${i + 1}", "PLR${i + 1}")
        for (r in ROUNDS) for (g in r.glasses) glasses[g] = obj(g)
        for (name in BUZZING) glasses[name] = obj(name)
        glasses["RoundFourteenGlass02"] = obj("RoundFourteenGlass02")
        for (i in 0 until 8) glassShadows[i] = obj(if (i == 0) "glassShadow" else "glassShadow${i + 1}")
        debugFlickPower = num("debugFlickPower", 1f)
        touchYScale = num("touchYScale", 0.1f)
        debugTargetRange = num("debugTargetRange", 1.6f)
        magThreshFlick = num("magThreshFlick", 0.5f)
    }

    private val rb get() = rigidbody!!

    // -- replay data --

    private fun AddQuarterData(tick: Int) {
        if (tick % qdTimeInterval != 0) return
        if (curQuarterDataIndex >= maxQuarterData) { Iq.log("Quarter Data full"); return }
        replayQPosition[curQuarterDataIndex] = rb.position
        replayQRotation[curQuarterDataIndex] = rb.rotation
        replayQVelocity[curQuarterDataIndex] = rb.velocity
        replayQAngVel[curQuarterDataIndex] = rb.angularVelocity
        curQuarterDataIndex++
        quarterDataTicks = curQuarterDataIndex
    }

    private fun RestoreQuarterData(tick: Int) {
        if (ReplayController.hallOfFameReplay) { quarterDataTicks = 0; return }
        if (tick % qdTimeInterval != 0 || curQuarterDataIndex >= quarterDataTicks) return
        if (curQuarterDataIndex >= maxQuarterData) return
        rb.position = replayQPosition[curQuarterDataIndex]
        rb.rotation = replayQRotation[curQuarterDataIndex]
        rb.velocity = replayQVelocity[curQuarterDataIndex]
        rb.angularVelocity = replayQAngVel[curQuarterDataIndex]
        curQuarterDataIndex++
    }

    private fun LoadMutePref() {
        muteF = Iq.prefs.hasKey("MuteValue") && Iq.prefs.getInt("MuteValue") != 0
    }

    private fun LoadPlayerNamesFromPrefs() {
        for (i in 0..3) Iq.prefs.getString("savedName${i + 1}").let { if (it.isNotEmpty()) playerNames[i] = it }
    }

    override fun start() {
        debugint = 0
        curQuarterDataIndex = 0
        quarterDataTicks = 0
        practiceUIObject?.setActiveRecursively(false)
        mainCamObject?.active = false
        statsScreenObject?.setActiveRecursively(false)
        ResetColliderSoundArray()
        state = stateInitGame
        requestOptions = false
        requestAngleInput = false
        startPos = rb.position
        startRot = transform.eulerAngles
        startQuat = rb.rotation
        ResetQuarter()
        curReplayCam = camReplay3
        toggleCamera(camMainCam)
        ResetColliderInfo()
        ResetColliderGameObjectInfo()
        LoadPlayerNamesFromPrefs()
        LoadMutePref()
        GameManagerScript.shotAngle = GameManagerScript.defaultShotAngle
        if (mainmenu.resumeTriggerYes) {
            mainmenu.resumeTriggerYes = false
            mainmenu.LoadLastGame()
        } else {
            if (mainmenu.feGameType == mainmenu.gtClassic) mainmenu.DeleteLastGame()
            GameManagerScript.curMadeShotsThisRound = 0
        }
        HiScoreScript.guiIndex = -1
        skipToRackupState = 0
    }

    fun ResetQuarter() {
        skipToRackupState = 0
        debugint = 0
        inAirTicks = 0
        quarterVelCount = 0
        ReplayRicochetIndex = 0
        rb.movePosition(startPos)
        rb.moveRotation(startQuat)
        transform.position = startPos
        transform.eulerAngles = startRot
        GlassScaleController.inThisGlassObject = null
        inGlassCollider = null
        rb.isKinematic = false
        rb.velocity = V3.ZERO
        rb.angularVelocity = V3.ZERO
        rb.isKinematic = true
        birdAnimState = 0
        shotLaunchTime = -1f
        quarterDrawObject?.active = true
        lastHitCollider = null
        ResetColliderSoundArray()
        DisplayQuarterAndShadow(true)
        glassMultiplierTriggered = false
    }

    private fun ComputeQuarterVel() {
        if (rb.velocity.lengthSq() < quarterVelThreshold * quarterVelThreshold) quarterVelCount++ else quarterVelCount = 0
    }

    private fun CheckQuarterVel() = quarterVelCount > quarterCountThresh

    private fun GlassEmitterFlash(pos: V3) {
        val e = glassFlashEmitter ?: return
        e.position = pos
        e.particles?.emit(1)
    }

    private fun AddCollisionToList(c: IqCollision) {
        if (state != stateShotInAir) return
        for (contact in c.contacts) {
            colliderSoundArray += contact.otherCollider
            PlayColliderSound()
            if (!GameManagerScript.replayFlag && contact.otherCollider !== lastHitCollider) {
                AddColliderToList(contact.otherCollider)
                lastHitCollider = contact.otherCollider
                GlassEmitterFlash(contact.point)
            }
        }
    }

    override fun onCollisionEnter(c: IqCollision) = AddCollisionToList(c)

    /** ARM: the last contact whose normal points up (`y > 0.05`) names the glass. */
    override fun onCollisionStay(c: IqCollision) {
        for (contact in c.contacts) if (contact.normal.y > 0.05f) inGlassCollider = contact.otherCollider
    }

    private fun fUpdate() {
        if (state == stateShotInAir) ComputeQuarterVel()
        // ARM 0x2523e4-0x2525b8, which the decompiled C# drops entirely: during a ricochet
        // replay, each recorded hit whose time since launch has passed flashes the glass,
        // lights the next ricochet coin (all but the last hit, the glass itself), and moves
        // ReplayRicochetIndex on. Without it SetRackupState's TriggerScore is -1.
        if (GameManagerScript.replayFlag && state == stateShotInAir && !doingRequestedReplay &&
            ReplayRicochetIndex < curColliderGameObjectIndex
        ) {
            val info = colliderGameObjectInfo[ReplayRicochetIndex]
            replayLaunchTime = time - shotLaunchTime
            if (info.m_time < replayLaunchTime) {
                GlassEmitterFlash(transform.position)
                if (ReplayRicochetIndex < curColliderGameObjectIndex - 1) {
                    RicochetExciter.numRicochets++
                    RicochetExciter.TriggerCoinX = RicochetExciter.numRicochets
                }
                ReplayRicochetIndex++
            }
        }
        if (state == stateWaitForFirstTableImpact) {
            if (waitCount == 5) birdAnimState = if (GameManagerScript.replayFlag) 2 else 1
            waitCount--
            if (waitCount < 0) {
                rb.position = rb.position + V3(0f, 1f, 0f)
                val power = shotMagnitude * GameManagerScript.shotMagnitude
                val angle = Math.toRadians(GameManagerScript.shotAngle.toDouble()).toFloat()
                rb.velocity = V3(GameManagerScript.shotMagX, power * sin(angle), power * cos(angle))
                rb.addTorque(V3.RIGHT * shotrotx)
                shotTime = time + shotTimeMax
                shotLaunchTime = time
                if (!muteF) audio?.let { it.clip = defaultAudioClip; it.play() }
                state = stateShotInAir
            }
            if (skipToRackupState == 1 && !doingRequestedReplay) {
                GameManagerScript.replayFlag = false
                skipToRackupState = 2
                ricochetParentObject?.setActiveRecursively(false)
                SetRackupState(true)
            }
        }
    }

    private fun isInGlass(): Int {
        GlassScaleController.inThisGlassObject = null
        val col = inGlassCollider ?: return 0
        val glass = when (col.name) {
            "COL" -> 1
            "COL2" -> 2
            "COL3" -> 3
            "COL4" -> 4
            else -> 0
        }
        if (glass != 0) col.attachedRigidbody?.let { GlassScaleController.inThisGlassObject = it.go }
        return glass
    }

    private fun SetSelectLevelState() {
        state = stateSelectLevel
        waitTime = time + 10f
        quarterDrawObject?.active = false
        practiceUIObject?.setActiveRecursively(true)
        PracticeUI.triggerPracticeUI = true
        if (GameManagerScript.curRound < 0) GameManagerScript.curRound = 0
        PracticeUI.curLevelSelected = GameManagerScript.curRound
        levelSelected = GameManagerScript.curRound
        GameManagerScript.shotAngle = GameManagerScript.defaultShotAngle
        GameManagerScript.ClearAllScores()
        GameManagerScript.SetCurrentShotsLeft(40)
        GameManagerScript.curMadeShotsThisRound = 0
        find("/shadowQuarter")?.renderer?.enabled = false
    }

    private fun SetRoundDisplayState() {
        state = stateDisplayRoundIntro
        waitTime = time + 0.5f
    }

    private fun SetBounceFlyCountState(shotScore: Int) {
        state = stateBounceFlyInitWait
        waitTime = time + 0.75f
        bounceFlyCount = min(shotScore, 9)
        CrowdScript.playApplauseSound = GameManagerScript.curMadeShotsThisRound + 1
        bounceFlyTime = time - 0.1f
    }

    private fun SetShotResultState(longer: Boolean) {
        state = stateShotResult
        waitTime = time + if (longer) 1.5f else 0f
    }

    private fun SetRackupState(skip: Boolean) {
        if (!skip) {
            RicochetExciter.TriggerScore = ReplayRicochetIndex - 1
            if (!muteF) audio?.let { it.clip = ricochetBonusSFX; it.play() }
        }
        waitTime = if (skip) time else time + 1.25f
        state = stateRackup
    }

    private fun SetShotOverState() {
        RicochetExciter.numRicochets = 0
        skipToRackupState = 0
        state = stateShotOver
    }

    private fun SetGameOverState() {
        state = stateGameOver
        waitTime = time + 10f
        DisplayBackDrop(true)
        statsScreenObject?.setActiveRecursively(true)
        StatsScreen.triggerStatscreen = true
    }

    private fun SetInputNameState() {
        DisplayBackDrop(true)
        state = stateInputNameInit
    }

    private fun SetHiScoresTableState() {
        inGameHSObject?.setActiveRecursively(true)
        InGameHiScore.triggerHiScores = true
        DisplayBackDrop(true)
        state = stateHiScoresTable
        waitTime = time + 10f
    }

    private fun ResetAndExitLevel() {
        levelSelected = 0
        Iq.loadLevel("frontend")
    }

    /** One finger dragged up or down steps the angle a degree per 13.3 points. */
    private fun ProcessTouchforAngle() {
        val touches = Iq.touches
        if (touches.size != 1) { angleTouchState = atsNone; return }
        for (t in touches) {
            if (angleTouchState == atsNone && t.phase == IqTouch.BEGAN) {
                angleStartPosition = t.position
                angleTouchState = atsDown
            } else if (angleTouchState == atsDown) {
                val end = t.position
                val delta = floor((end.y - angleStartPosition.y) * 0.075f).toInt()
                if (delta != 0) {
                    angleStartPosition = end
                    GameManagerScript.shotAngle = (GameManagerScript.shotAngle - delta).coerceIn(minShotAngle, maxShotAngle)
                }
            }
        }
    }

    private fun SetPlayerRoundFinishedPracticeOutofShots() {
        gameOverExciterObject?.setActiveRecursively(true)
        GameOver.triggerExciterOutOfShots = true
        state = statePlayerRoundFinishedExciter
        waitTime = time + 2.25f
    }

    private fun SetPlayerRoundFinishedState(lastRound: Int, playerIndex: Int) {
        var newRoundHigh = false
        if (mainmenu.feGameType == mainmenu.gtClassic) {
            var showRoundComplete = true
            if (GameManagerScript.secretRoundUnlocked && lastRound == GameManagerScript.roundBeforeSecretRound) {
                secretRoundObject?.setActiveRecursively(true)
                SecretRound.TriggerSecretRoundIntro = true
                if (!muteF) audio?.let { it.clip = secretRoundSFX; it.play() }
            } else if (lastRound == GameManagerScript.secretRoundNumber) {
                secretRoundObject?.setActiveRecursively(true)
                SecretRound.TriggerSecretRoundOutro = true
                if (!muteF) audio?.let { it.clip = secretRoundSFX; it.play() }
                showRoundComplete = false
            } else {
                roundCompleteObject?.setActiveRecursively(true)
                RoundComplete.roundToDisplay = lastRound
                RoundComplete.displayScore = lastRoundScore
            }
            if (showRoundComplete) {
                newRoundHigh = HiScoreRoundScript.AddRoundHiScore(lastRound, lastRoundScore.toFloat(), GameManagerScript.GetName(playerIndex))
                if (newRoundHigh) HiScoreRoundScript.SaveEntries()
                HiScoreScript.UpdateLockedRoundIndex(lastRound)
                if (shotOverFlags and (GameManagerScript.flagPlayerGameOver or GameManagerScript.flagOutOfShotsReason) != 0) {
                    GameManagerScript.AddPlayerScore(GameManagerScript.GetPlayerShotsLeft(playerIndex) * 5, playerIndex)
                    HiScoreScript.guiIndex = HiScoreScript.InsertHiScore(GameManagerScript.GetPlayerScore(playerIndex).toFloat(), "PLR${playerIndex + 1}")
                    HiScoreScript.SaveEntries()
                }
            }
        } else if (lastRoundScore > HiScoreRoundScript.GetScore(lastRound)) {
            practiceGreatScoreObject?.setActiveRecursively(true)
            PracticeGreatScore.triggerGreatScore = true
            PracticeGreatScore.displayScore = lastRoundScore
        }
        if (newRoundHigh) RoundComplete.triggerExciterNewRoundHigh = true
        else RoundComplete.triggerExciterRoundFinished = true
        if (curColliderGameObjectIndex < 2) CoinHolder.triggerCoinHolderOutAll = true
        state = statePlayerRoundFinishedExciter
        waitTime = if (mainmenu.feGameType == mainmenu.gtPractice && !PracticeGreatScore.triggerGreatScore) time else time + 2.25f
    }

    private fun SetGameOverExciterState() {
        gameOverExciterObject?.setActiveRecursively(true)
        if (shotOverFlags and GameManagerScript.flagOutOfShotsReason != 0) {
            GameOver.triggerExciterOutOfShots = true
            HiScoreScript.guiIndex = HiScoreScript.InsertHiScore(GameManagerScript.GetPlayerScore(lastPlayerNumber).toFloat(), "PLR${lastPlayerNumber + 1}")
            HiScoreScript.SaveEntries()
        } else GameOver.triggerExciterGameComplete = true
        quarterDrawObject?.let {
            if (abs(it.position.x) > 20f || abs(it.position.z) > 20f) {
                rb.velocity = V3.ZERO
                rb.angularVelocity = V3.ZERO
                rb.isKinematic = true
            }
        }
        waitTime = time + 2.35f
        state = stateGameOverExciter
    }

    private fun ExitShotOverState(gameShouldEnd: Boolean) {
        gameShouldEndFlag = gameShouldEnd
        if (HiScoreScript.guiIndex >= 0) SetInputNameState()
        else if (gameShouldEndFlag) SetGameOverState()
        else { SetRoundDisplayState(); ResetQuarter() }
    }

    private fun CheckForAngleReminder() {
        if (GameManagerScript.curRound in ANGLE_REMINDER_ROUNDS) InGameAngleIcon.triggerReminder = true
    }

    override fun fixedUpdate() {
        fUpdate()
        val now = time
        when (state) {
            stateInitGame -> { SetGlasses(); state = stateDisplayRoundIntro }
            stateSelectLevel -> {
                if (PracticeUI.triggerLevelSwitch) {
                    levelSelected = PracticeUI.curLevelSelected
                    SetGlasses()
                    PracticeUI.triggerDelayedLockAnim = true
                    PracticeUI.triggerLevelSwitch = false
                }
                if (PracticeUI.selectLevelDone) {
                    state = stateDisplayRound
                    waitTime = now + 0.5f
                    ResetQuarter()
                    PracticeUI.selectLevelDone = false
                    find("/shadowQuarter")?.renderer?.enabled = true
                }
            }
            stateDisplayRoundIntro -> {
                if ((GameManagerScript.totPlayers != 1 || GameManagerScript.curRound == 0) && mainmenu.feGameType == mainmenu.gtClassic)
                    AnnouncerScript.triggerPlayerVO = GameManagerScript.curPlayer + 1
                if (mainmenu.feGameType == mainmenu.gtPractice) SetSelectLevelState()
                else {
                    state = stateDisplayRound
                    UIPlayer.triggerPlayer1 = true
                    shotTypeHelperObject?.setActiveRecursively(true)
                    ShotTypeHelper.triggerShotTypeHelper = true
                }
            }
            stateDisplayRound -> if (now > waitTime) {
                state = stateWaitForShotIntro
                CheckForAngleReminder()
                if (!GameManagerScript.replayFlag) CoinHolder.triggerCoinHolderIn = GameManagerScript.curMadeShotsThisRound
            }
            stateWaitForShotIntro -> {
                if (!GameManagerScript.replayFlag) {
                    InGameAngleIcon.triggerOnScreen = true
                    roundIndicatorObject?.setActiveRecursively(true)
                    RoundIndicator.TriggerSlideIn = true
                }
                state = stateWaitForShot
            }
            stateWaitForHOFLoad -> if (now > waitTime) { DisplayBackDrop(false); state = stateWaitForShotIntro }
            stateWaitForShot -> waitForShot()
            stateAngleInput -> ProcessTouchforAngle()
            stateOptionsMenu -> {}
            stateShotInAir -> shotInAir()
            stateAskToSaveReplay -> if (ReplayController.stateAskToSaveReplay_OverFlag) {
                RestoreFromReplay()
                ReplayController.stateAskToSaveReplay_OverFlag = false
            }
            stateHallOfFameReplayExit -> RestoreFromReplay()
            stateBounceFlyInitWait -> if (now > waitTime) state = stateBounceFly
            stateBounceFly -> {
                if (now > bounceFlyTime && bounceFlyCount > 0) {
                    if (!CoinHolder.coinTriggered) {
                        CoinHolder.triggerCoinHolderIn = GameManagerScript.curMadeShotsThisRound
                        CoinHolder.TriggerCoinIn(GameManagerScript.curMadeShotsThisRound)
                    }
                    bounceFlyCount--
                    bounceFlyTime = now + 0.2f
                    if (bounceFlyCount == 0) waitTime = now + 0.5f
                }
                if (now > waitTime && bounceFlyCount == 0) {
                    if (curColliderGameObjectIndex < 2) { toggleCamera(camMainCam); SetShotOverState() }
                    else SetShotResultState(false)
                }
            }
            // ARM 0x258434-0x25867c. The decompiled C# drops the state store at 0x258654, which
            // left this block re-entering every frame: a new replay camera and a reset coin each
            // time. It also zeroes ReplayRicochetIndex (+0x304) here, not debugint.
            stateShotResult -> if (now > waitTime) {
                if (curColliderGameObjectIndex < 2) state = stateShotOver
                else {
                    GameManagerScript.replayFlag = true
                    ricochetParentObject?.setActiveRecursively(true)
                    RicochetExciter.TriggerOn = true
                    CoinHolder.triggerCoinHolderOut = GameManagerScript.curMadeShotsThisRound + 1
                    ReplayRicochetIndex = 0
                    toggleCamera(curReplayCam)
                    curReplayCam = when (curReplayCam) { camReplay1 -> camReplay2; camReplay2 -> camReplay3; else -> camReplay1 }
                    state = stateWaitForShotIntro
                    ResetQuarter()
                }
            }
            stateRackup -> if (now > waitTime) {
                GameManagerScript.AddScore((curColliderGameObjectIndex - 1) * 10)
                if (GameManagerScript.curMadeShotsThisRound + 1 < GameManagerScript.shotsPerRound)
                    CoinHolder.triggerCoinHolderIn = GameManagerScript.curMadeShotsThisRound + 1
                ResetQuarter()
                SetShotOverState()
                GameManagerScript.replayFlag = false
                toggleCamera(camMainCam)
            }
            stateShotOver -> shotOver()
            statePlayerRoundFinishedExciter -> if (now > waitTime) {
                if (shotOverFlags and GameManagerScript.flagPlayerGameOver != 0 && mainmenu.feGameType == mainmenu.gtClassic) {
                    GameManagerScript.shotAngle = GameManagerScript.defaultShotAngle
                    SetGameOverExciterState()
                } else {
                    SetRoundDisplayState()
                    ResetQuarter()
                    GameManagerScript.shotAngle = GameManagerScript.defaultShotAngle
                    SetGlasses()
                }
            }
            stateGameOverExciter -> if (now > waitTime) {
                if (shotOverFlags and (GameManagerScript.flagPlayerGameOver or GameManagerScript.flagOutOfShotsReason) == 0) gameOverExciterRules()
                else {
                    val coins = GameManagerScript.GetPlayerShotsLeft(lastPlayerNumber)
                    coinsLeftObject?.setActiveRecursively(true)
                    CoinsLeft.TriggerAnim(coins)
                    DisplayBackDrop(coins > 0 || shotOverFlags and GameManagerScript.flagGameOver != 0)
                    state = stateCoinsLeftStack
                }
                TurnBuzzingSoundOff()
            }
            stateCoinsLeftStack -> if (CoinsLeft.IsDonePlaying()) {
                coinsLeftObject?.setActiveRecursively(false)
                gameOverExciterRules()
                if (shotOverFlags and GameManagerScript.flagRoundChange != 0 && shotOverFlags and GameManagerScript.flagGameOver == 0) SetGlasses()
            }
            stateGameOver -> if (StatsScreen.cancelStatscreen || PauseMenu.cancelPauseMenu || PracticeUI.cancelPracticeScreen) {
                StatsScreen.cancelStatscreen = false
                PauseMenu.cancelPauseMenu = false
                PracticeUI.cancelPracticeScreen = false
                ResetAndExitLevel()
            }
            stateInputNameInit -> {
                if (GameManagerScript.GetNameEnteredFlag(lastPlayerNumber)) {
                    stringPlayerName1 = GetPlayerName(lastPlayerNumber)
                    SetHiScoresTableState()
                } else {
                    enterNameObject?.setActiveRecursively(true)
                    enterNameObject?.anim?.play("SlideIn")
                    waitTime = now + 0.5f
                    DisplayBackDrop(true)
                    state = stateInputNameLoadKeyboard
                }
            }
            stateInputNameLoadKeyboard -> if (now > waitTime) {
                stringPlayerName1 = GetPlayerName(lastPlayerNumber)
                keyboard = Iq.rt.host.openKeyboard(stringPlayerName1)
                state = stateInputName
            }
            stateInputName -> {
                val kb = keyboard
                if (kb != null) stringPlayerName1 = kb.text
                if (kb != null && (kb.done || !kb.active)) {
                    enterNameObject?.setActiveRecursively(false)
                    stringPlayerName1 = kb.text
                    GameManagerScript.SetNameEnteredFlag(lastPlayerNumber)
                    GameManagerScript.SetName(stringPlayerName1, lastPlayerNumber)
                    SavePlayerNameToPrefs(stringPlayerName1, lastPlayerNumber)
                    HiScoreScript.SetHiScoreName(HiScoreScript.guiIndex, GameManagerScript.GetName(lastPlayerNumber))
                    HiScoreScript.SaveEntries()
                    SetHiScoresTableState()
                }
            }
            stateHiScoresTable -> if (InGameHiScore.cancelHiScores) {
                HiScoreScript.guiIndex = -1
                InGameHiScore.cancelHiScores = false
                if (gameShouldEndFlag) SetGameOverState() else { SetGlasses(); SetRoundDisplayState(); ResetQuarter() }
            }
        }
    }

    private fun waitForShot() {
        fixedUpdateCounter++
        if (GameManagerScript.replayFlag) {
            rb.isKinematic = false
            rb.velocity = V3(ReplayController.replayshotmagx, shotvely, GameManagerScript.shotVelZ)
            rb.wakeUp()
            state = stateWaitForFirstTableImpact
            waitCount = waitForFirstBounceCount
            return
        }
        if (requestLastReplay) {
            GameManagerScript.replayFlag = true
            ReplayController.savedCurrentRound = GameManagerScript.curRound
            val changedRound = GameManagerScript.curRound != ReplayController.replayRound
            GameManagerScript.curRound = ReplayController.replayRound
            if (mainmenu.feGameType == mainmenu.gtPractice) levelSelected = GameManagerScript.curRound
            if (changedRound) SetGlasses()
            requestLastReplay = false
            doingRequestedReplay = true
            if (changedRound) { DisplayBackDrop(true); waitTime = time + 0.5f; state = stateWaitForHOFLoad }
            else { DisplayBackDrop(false); state = stateWaitForShotIntro }
            return
        }
        if (requestOptions) { state = stateOptionsMenu; requestOptions = false; return }
        if (requestAngleInput) { state = stateAngleInput; requestAngleInput = false; return }

        // Android samples are timestamp-normalized before the fixed physics loop.
        // A frame may contain multiple fixed steps; consume a gesture only once.
        val sample = Iq.touches.firstOrNull { it.sampled && it.flick != null && it !== lastFlickTouch }
        if (sample != null) {
            lastFlickTouch = sample
            mag = sample.flick!!.power; xvel = sample.flick.aim
        } else {
            // Legacy headless/replay callers retain their authored sample convention.
            if (Iq.touches.any { it.sampled }) return
            var dx = 0f; var dy = 0f
            for (t in Iq.touches) { if (t.phase != IqTouch.CANCELED) { dx += t.deltaPosition.x; dy += t.deltaPosition.y } }
            val up = min(dy, 360f)
            if (up <= 0f) return
            mag = (up * touchYScale / debugTargetRange).pow(debugFlickPower)
            if (mag <= magThreshFlick) return
            xvel = dx / up * X_SLOPE_SCALE
        }

        shotTick = 5
        ReplayController.SaveShotTick(shotTick)
        fixedUpdateCounter = 0
        inAirTicks = 0
        quarterVelCount = 0
        val normalizedAngle = ((GameManagerScript.shotAngle - minShotAngle) / (maxShotAngle - minShotAngle)).coerceIn(0f, 1f)
        GameManagerScript.shotVelZ = (1f - normalizedAngle) * shotDownZMag
        rb.isKinematic = false
        rb.velocity = V3(xvel, shotvely, GameManagerScript.shotVelZ)
        GameManagerScript.shotMagnitude = mag
        GameManagerScript.shotMagX = xvel
        rb.wakeUp()
        CoinHolder.coinTriggered = false
        CoinHolder.triggerCoinHolderOut = GameManagerScript.curMadeShotsThisRound
        InGameAngleIcon.triggerOffScreen = true
        RoundIndicator.TriggerSlideOut = true
        ReplayController.SetShotParameters(mag, xvel, GameManagerScript.shotVelZ, GameManagerScript.shotAngle)
        doingRequestedReplay = false
        ReplayController.stateAskToSaveReplay_OverFlag = false
        state = stateWaitForFirstTableImpact
        waitCount = waitForFirstBounceCount
    }

    private fun shotInAir() {
        if (GameManagerScript.replayFlag) RestoreQuarterData(inAirTicks) else AddQuarterData(inAirTicks)
        inAirTicks++
        if (skipToRackupState == 1 && !doingRequestedReplay) {
            GameManagerScript.replayFlag = false
            skipToRackupState = 2
            ricochetParentObject?.setActiveRecursively(false)
            SetRackupState(true)
            return
        }
        if (!rb.isSleeping() && rb.position.y >= -10f && time <= shotTime && !CheckQuarterVel()) return
        if (rb.position.y < -10f) { AnnouncerScript.triggerOffTableVO = true; inGlassCollider = null }
        if (GameManagerScript.replayFlag) {
            GameManagerScript.replayFlag = false
            if (doingRequestedReplay) {
                if (ReplayController.hallOfFameReplay) state = stateHallOfFameReplayExit
                else {
                    rb.velocity = V3.ZERO
                    rb.angularVelocity = V3.ZERO
                    rb.isKinematic = true
                    ReplayController.TriggerSlideIn()
                    replaySaveDoneButtonsObject?.setActiveRecursively(true)
                    state = stateAskToSaveReplay
                }
            } else SetRackupState(false)
            return
        }
        val shotScore = isInGlass()
        savedCurrentScore = shotScore
        GameManagerScript.AddScore(shotScore * 25)
        if (shotScore > 1) {
            glassMultiplierTriggered = true
            powerXObject?.setActiveRecursively(true)
            PowerX.pUpIndex = shotScore
            PowerX.triggerPowerUp = true
        }
        BuildCollisionChain()
        if (shotScore == 0) {
            if (GameManagerScript.GetCurrentShotsLeft() > 1) {
                CoinHolder.triggerCoinHolderIn = GameManagerScript.curMadeShotsThisRound
                CoinHolder.triggerCoinsLeftAnim = true
            }
            toggleCamera(camMainCam)
            SetShotOverState()
        } else {
            GameManagerScript.UpdateCurRicochet(curColliderGameObjectIndex - 1)
            GameManagerScript.IncrementCurrentStreak()
            val streak = GameManagerScript.GetCurrentTotalStreak()
            if (streak > 1) {
                streakExciterObject?.setActiveRecursively(true)
                Streak.streakCount = streak
                Streak.stateCurrent = Streak.stateTrigger
            }
            AnnouncerScript.triggerPerfectRoundVO = true
            GlassScaleController.triggerGlassScale = true
            lightRayObject?.setActiveRecursively(true)
            lightray.triggerLightRay = true
            SetBounceFlyCountState(shotScore)
            // ARM 0x257f00-0x258050, missing from the decompiled C#: the made shot that
            // completes a Classic round plays coinInGlassSFX[shotsPerRound - 1], the jingle.
            if (audio != null && !muteF && mainmenu.feGameType == mainmenu.gtClassic &&
                GameManagerScript.curMadeShotsThisRound == GameManagerScript.shotsPerRound - 1
            ) {
                audio!!.clip = coinInGlassSFX[GameManagerScript.shotsPerRound - 1]
                audio!!.play()
            }
        }
    }

    private fun shotOver() {
        lastPlayerNumber = GameManagerScript.curPlayer
        val lastRound = GameManagerScript.curRound
        lastRoundScore = GameManagerScript.GetCurrentRoundScore()
        shotOverFlags = GameManagerScript.UpdateShotCount(savedCurrentScore)
        if (mainmenu.feGameType == mainmenu.gtClassic) {
            if (shotOverFlags and GameManagerScript.flagGameOver == 0) mainmenu.SaveLastGame() else mainmenu.DeleteLastGame()
        }
        if (shotOverFlags == 0) { state = stateWaitForShotIntro; ResetQuarter() }
        else if (shotOverFlags and GameManagerScript.flagOutOfShotsReason == 0) {
            if (shotOverFlags and (GameManagerScript.flagPlayerChange or GameManagerScript.flagRoundChange) != 0)
                SetPlayerRoundFinishedState(lastRound, lastPlayerNumber)
        } else if (mainmenu.feGameType == mainmenu.gtPractice) SetPlayerRoundFinishedPracticeOutofShots()
        else { GameManagerScript.shotAngle = GameManagerScript.defaultShotAngle; SetGameOverExciterState() }
    }

    private fun RestoreFromReplay() {
        ReplayController.hallOfFameReplay = false
        doingRequestedReplay = false
        if (ReplayController.savedCurrentRound != GameManagerScript.curRound) {
            GameManagerScript.curRound = ReplayController.savedCurrentRound
            levelSelected = ReplayController.savedCurrentRound
            SetGlasses()
        }
        CoinHolder.triggerCoinHolderIn = GameManagerScript.curMadeShotsThisRound
        state = stateWaitForShotIntro
        ResetQuarter()
    }

    private fun gameOverExciterRules() = ExitShotOverState(shotOverFlags and GameManagerScript.flagGameOver != 0)

    private fun GetPlayerName(idx: Int) = playerNames[if (idx in 1..3) idx else 0]

    private fun toggleCamera(camNumberIn: Int) {
        var camNumber = camNumberIn
        if (GameManagerScript.replayFlag) {
            curReplayCam = GetValidReplayCamera(camNumber)
            camNumber = curReplayCam
        }
        for (c in listOf(mainCamObject, replayCamObject, replayCamObject2, replayCamObject3, introCamObject)) c?.active = false
        when (camNumber) {
            camReplay1 -> replayCamObject
            camReplay2 -> replayCamObject2
            camReplay3 -> replayCamObject3
            camIntro -> introCamObject
            else -> mainCamObject
        }?.active = true
    }

    private fun GetValidReplayCamera(camNumber: Int): Int {
        val r = ReplayController.replayRound
        if (r == 3 || r == 6) return camReplay3
        if (r == 8 && curReplayCam == camReplay1) return camReplay2
        if ((r == 10 || r == 12) && curReplayCam == camReplay3) return camReplay1
        return camNumber
    }

    private fun SetGlassShadow(glass: GObj, shadowID: Int, scaleDelta: Float) {
        val shadow = glassShadows[(shadowID - 1).coerceIn(0, 7)] ?: return
        shadow.renderer?.enabled = true
        shadow.position = shadow.position.copy(x = glass.position.x, z = glass.position.z)
        shadow.localScale = shadow.localScale.copy(x = 0.15f + scaleDelta, z = 0.15f + scaleDelta)
    }

    fun SetGlasses() {
        for (r in ROUNDS) for (g in r.glasses) glasses[g]?.setActiveRecursively(false)
        for (s in glassShadows) s?.renderer?.enabled = false
        if (mainmenu.feGameType == mainmenu.gtPractice) GameManagerScript.curRound = levelSelected
        DisplayBackDrop(false)
        for (name in BUZZING) glasses[name]?.audio?.stop()
        val round = ROUNDS.getOrElse(GameManagerScript.curRound) { DEFAULT_ROUND }
        for (g in round.glasses) glasses[g]?.setActiveRecursively(true)
        for ((g, id, delta) in round.shadows) glasses[g]?.let { SetGlassShadow(it, id, delta) }
    }

    private fun ResetColliderInfo() {
        colliderInfo.clear()
        repeat(maxColliders) { colliderInfo += ColliderInfoClass() }
        curColliderIndex = 0
    }

    private fun ResetColliderSoundArray() {
        colliderSoundArray.clear()
        colliderSoundPlayedCount.fill(0)
        prevColliderSoundTime = 0f
        prevColliderSoundType = -1
    }

    /** The coin's hit sound, chosen by the struck rigidbody's mass (the game's sound type). */
    private fun PlayColliderSound() {
        val hit = colliderSoundArray.lastOrNull() ?: return
        val body = hit.attachedRigidbody ?: return
        val soundType = body.mass.toInt()
        val set = when (soundType) {
            2 -> sounds["glassMartiniSounds"]
            3, 5, 6 -> if (Iq.random() < 0.5f) sounds["glassMediumSounds"] else sounds["glassBigSounds"]
            4 -> sounds["glassSmallSounds"]
            7 -> sounds["glassTallSounds"]
            8 -> sounds["lazySusanSounds"]
            9 -> sounds["tableTopSounds"]
            10 -> sounds["whiskeyBottleSounds"]
            11 -> sounds["popBottleSounds"]
            12 -> sounds["drinkingBirdSounds"]
            13 -> sounds["checkBookSounds"]
            14 -> sounds["bobbleHeadSounds"]
            15 -> sounds["planeSounds"]
            16 -> sounds["lighterSounds"]
            17 -> sounds["phoneSounds"]
            18 -> sounds["catapultSounds"]
            19 -> sounds["pendulumSounds"]
            else -> null
        }
        if (muteF || set == null || set.isEmpty()) return
        if (soundType == prevColliderSoundType && time - prevColliderSoundTime <= minColliderSoundDelay) return
        var idx = colliderSoundPlayedCount[soundType].coerceIn(0, set.size - 1)
        if (body.go === glasses["RoundFourteenGlass02"]) idx %= 2
        audio?.let { it.clip = set[idx]; it.play() }
        prevColliderSoundTime = time
        colliderSoundPlayedCount[soundType]++
        prevColliderSoundType = soundType
    }

    private fun AddColliderToList(c: Col) {
        if (curColliderIndex < 0 || curColliderIndex >= colliderInfo.size) return
        val info = colliderInfo[curColliderIndex]
        info.m_Collider = c
        info.m_time = time - shotLaunchTime
        curColliderIndex++
    }

    private fun ResetColliderGameObjectInfo() {
        colliderGameObjectInfo.clear()
        repeat(maxColliderGameObjects) { colliderGameObjectInfo += ColliderGameObjectClass() }
        curColliderGameObjectIndex = 0
    }

    private fun AddColliderToGameObjectList(go: GObj, t: Float) {
        if (curColliderGameObjectIndex < 0 || curColliderGameObjectIndex >= colliderGameObjectInfo.size) return
        val info = colliderGameObjectInfo[curColliderGameObjectIndex]
        info.m_GameObject = go
        info.m_time = t
        curColliderGameObjectIndex++
    }

    /** Collapses the shot's hits into the chain of distinct objects the ricochet bonus counts. */
    private fun BuildCollisionChain() {
        ResetColliderGameObjectInfo()
        var previousTime = 0f
        var previousObject: GObj? = null
        for (i in 0 until curColliderIndex) {
            val hit = colliderInfo[i]
            val go = hit.m_Collider?.attachedRigidbody?.go ?: continue
            val add = i == 0 || (previousObject !== go && (i == curColliderIndex - 1 || hit.m_time - previousTime > 0.01f))
            if (!add) continue
            AddColliderToGameObjectList(go, hit.m_time)
            previousObject = go
            previousTime = hit.m_time
        }
        ResetColliderInfo()
        ResetColliderSoundArray()
    }

    /** The objects the ricochet replay shows, in hit order. */
    fun chainObject(i: Int): GObj? = colliderGameObjectInfo.getOrNull(i)?.m_GameObject

    private fun TurnBuzzingSoundOff() {
        if (shotOverFlags and (GameManagerScript.flagPlayerGameOver or GameManagerScript.flagGameOver) == 0) return
        for (name in BUZZING) glasses[name]?.audio?.stop()
    }

    private class Round(val glasses: List<String>, val shadows: List<Triple<String, Int, Float>>)

    companion object : IqStatic {
        var fixedUpdateCounter = 0
        var skipToRackupState = 0
        var glassMultiplierTriggered = false
        var birdAnimState = 0
        const val minShotAngle = 45f
        const val maxShotAngle = 55f
        const val camMainCam = 0
        const val camReplay1 = 1
        const val camReplay2 = 2
        const val camReplay3 = 3
        const val camIntro = 4
        var curReplayCam = 0
        const val shotMagnitude = 9f
        const val stateInitGame = 1000
        const val stateDisplayRound = 1001
        const val stateWaitForFirstTableImpact = 1002
        const val stateShotOver = 1003
        const val stateGameOver = 1004
        const val stateShotResult = 1005
        const val stateDisplayRoundIntro = 1007
        const val stateOptionsMenu = 1008
        const val stateInputNameInit = 1009
        const val stateInputName = 1010
        const val stateHiScoresTable = 1011
        const val stateAngleInput = 1013
        const val stateRackup = 1014
        const val stateBounceFly = 1015
        const val stateSelectLevel = 1016
        const val stateWaitForShotIntro = 1018
        const val stateWaitForShot = 0
        /** ARM: 1. The decompiled source's 0 would alias `stateWaitForShot`. */
        const val stateShotInAir = 1
        const val statePlayerRoundFinishedExciter = 1019
        const val stateGameOverExciter = 1020
        const val stateCoinsLeftStack = 1022
        const val stateAskToSaveReplay = 1023
        const val stateHallOfFame = 1024
        const val stateHallOfFameReplayExit = 1025
        const val stateBounceFlyInitWait = 1026
        const val stateInputNameLoadKeyboard = 1030
        const val stateWaitForHOFLoad = 1031
        var state = stateInitGame
        var levelSelected = 0
        var requestOptions = false
        var requestAngleInput = false
        var requestLastReplay = false
        var muteF = false
        var shotTime = 0f

        /** The ARM's sideways factor on `dx / dy`. */
        private const val X_SLOPE_SCALE = 4f

        override fun reset() {
            fixedUpdateCounter = 0
            skipToRackupState = 0
            glassMultiplierTriggered = false
            birdAnimState = 0
            curReplayCam = 0
            state = stateInitGame
            levelSelected = 0
            requestOptions = false
            requestAngleInput = false
            requestLastReplay = false
            muteF = false
            shotTime = 0f
        }

        fun DisplayQuarterAndShadow(show: Boolean) {
            Iq.find("/a_quarter5/root")?.renderer?.enabled = show
            Iq.find("/shadowQuarter")?.renderer?.enabled = show
        }

        fun SaveMutePref() = Iq.prefs.setInt("MuteValue", if (muteF) 1 else 0)

        fun SavePlayerNameToPrefs(name: String, idx: Int) {
            if (idx in 0..3) Iq.prefs.setString("savedName${idx + 1}", name)
        }

        fun DisplayBackDrop(show: Boolean) {
            Iq.find("BackDrop")?.renderer?.enabled = show
        }

        fun AddtoShotTime(add: Float) { shotTime += add }

        private val SOUND_FIELDS = listOf(
            "glassBigSounds", "glassMartiniSounds", "glassMediumSounds", "glassSmallSounds", "glassTallSounds",
            "lazySusanSounds", "tableTopSounds", "whiskeyBottleSounds", "popBottleSounds", "drinkingBirdSounds",
            "checkBookSounds", "bobbleHeadSounds", "planeSounds", "lighterSounds", "phoneSounds", "pendulumSounds",
            "catapultSounds",
        )

        private val BUZZING = listOf("RoundTwelveGlass02", "RoundFourteenGlass03", "RoundFifteenGlass00")

        private val ANGLE_REMINDER_ROUNDS = setOf(0, 3, 5, 6, 7, 8, 10)

        private fun g(prefix: String, n: Int) = (0 until n).map { "$prefix%02d".format(it) }
        private fun s(vararg v: Any) = v.toList().chunked(3).map { Triple(it[0] as String, it[1] as Int, (it[2] as Number).toFloat()) }

        /** `SetGlasses`, one entry per `curRound`: the glasses it raises and the shadows it lays. */
        private val ROUNDS = listOf(
            Round(g("RoundThreeGlass", 3), s("RoundThreeGlass00", 1, 0.05, "RoundThreeGlass01", 2, 0.15, "RoundThreeGlass02", 3, 0)),
            Round(g("RoundElevenGlass", 3), s("RoundElevenGlass00", 1, 0.15, "RoundElevenGlass01", 2, 0.20, "RoundElevenGlass02", 3, 0.05)),
            Round(g("RoundEightGlass", 5), s("RoundEightGlass00", 1, 0, "RoundEightGlass01", 2, 0.02, "RoundEightGlass02", 3, 0.10, "RoundEightGlass03", 4, 0)),
            Round(g("RoundNineGlass", 4), s("RoundNineGlass00", 1, 0.10, "RoundNineGlass02", 2, 0.05, "RoundNineGlass03", 3, 0.02)),
            Round(g("RoundTwelveGlass", 5), s("RoundTwelveGlass00", 1, 0, "RoundTwelveGlass01", 2, 0.15, "RoundTwelveGlass04", 3, 0)),
            Round(g("RoundTenGlass", 4), s("RoundTenGlass00", 1, 0.02, "RoundTenGlass01", 2, 0.15, "RoundTenGlass02", 3, 0.05)),
            Round(g("RoundOneGlass", 6), s("RoundOneGlass00", 1, 0, "RoundOneGlass01", 2, 0, "RoundOneGlass02", 3, 0, "RoundOneGlass03", 4, 0.10, "RoundOneGlass05", 5, 0.05)),
            Round(g("RoundFourGlass", 5), s("RoundFourGlass01", 1, 0.05, "RoundFourGlass02", 2, 0.05)),
            Round(g("RoundSixGlass", 5), s("RoundSixGlass00", 1, 0.10, "RoundSixGlass02", 2, 0.10, "RoundSixGlass04", 3, 0.05)),
            Round(g("RoundFourteenGlass", 6), s("RoundFourteenGlass00", 1, 0.15, "RoundFourteenGlass01", 2, 0.10, "RoundFourteenGlass02", 3, 0.05, "RoundFourteenGlass05", 4, 0)),
            Round(g("RoundThirteenGlass", 3), s("RoundThirteenGlass01", 1, 0.02)),
            Round(g("RoundFifteenGlass", 3), emptyList()),
            Round(g("RoundBonusGlass", 9), s(
                "RoundBonusGlass00", 1, 0.15, "RoundBonusGlass01", 2, 0.02, "RoundBonusGlass02", 3, 0.02, "RoundBonusGlass03", 4, 0.02,
                "RoundBonusGlass04", 5, 0.02, "RoundBonusGlass05", 6, 0.02, "RoundBonusGlass06", 7, 0.02, "RoundBonusGlass07", 8, 0.02,
            )),
        )
        private val DEFAULT_ROUND = Round(listOf("RoundOneGlass00"), s("RoundOneGlass00", 1, 0))
    }
}
