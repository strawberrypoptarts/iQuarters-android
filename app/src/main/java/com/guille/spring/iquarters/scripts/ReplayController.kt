package com.guille.spring.iquarters.scripts

import com.guille.spring.iquarters.Behaviour
import com.guille.spring.iquarters.GObj
import com.guille.spring.iquarters.Iq
import com.guille.spring.iquarters.IqStatic
import com.guille.spring.iquarters.Rect
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * `ReplayController`: the instant replay's shot parameters and the six Hall of Fame replay
 * slots (in `PlayerPrefs`), plus the Save/Done buttons that slide in after a replay
 * (`OnGUI`, only in `QuarterTrigger.stateAskToSaveReplay`).
 *
 * The iPad rect branch is dropped.
 */
class ReplayController : Behaviour() {
    var ReplaySaveDoneButtonsObject: GObj? = null
    var dummySkin: String? = null
    var enableButtonView = false

    override fun onBind() {
        ReplaySaveDoneButtonsObject = obj("ReplaySaveDoneButtonsObject")
        dummySkin = skin("dummySkin")
        enableButtonView = bool("enableButtonView", false)
    }

    override fun start() {
        hallOfFameReplay = false
        replayDataValid = false
        replayRound = 0
        replayshotmag = 0f
        replayshotmagx = 0f
        replayshotvelz = 0f
        replayshotangle = 0f
        savedCurrentRound = 0
        shotTime = 0f
        shotSpeed = 0f
        shotnormalizedSpeed = 0f
        shotTick = 0
        replayDataPlayerNumber = 0
    }

    /**
     * Traced from the AOT ARM (`ReplayController.OnGUI`, 0x261188-0x2616b4): in
     * `stateAskToSaveReplay`, `GUI.skin = null` then `dummySkin` unless [enableButtonView], and
     * one state handled per call as an if/else-if chain. The only button is the invisible Done
     * at `Rect(0, 435, 145, 32)` (0x261414); the iPad `GetiPadRect` branch is dropped.
     */
    override fun onGUI() {
        if (QuarterTrigger.state != QuarterTrigger.stateAskToSaveReplay) return
        val buttons = ReplaySaveDoneButtonsObject!!
        if (triggerSlideIn) {
            triggerSlideIn = false
            buttons.anim?.play("SlideIn")
            rcButtonsStateCurrent = rcButtonsStateSlideIn
            PauseMenu.PlayAnimatingObjects(false, false)
        } else if (rcButtonsStateCurrent == rcButtonsStateSlideIn && buttons.anim?.isPlaying != true) {
            rcButtonsStateCurrent = rcButtonsStateOnScreen
        } else if (rcButtonsStateCurrent == rcButtonsStateOnScreen) {
            val doneRect = Rect(0f, 435f, 145f, 32f)
            if (guiButton(doneRect)) {
                AnnouncerScript.triggerClickSound = true
                rcButtonClickedValue = rcButtonClickedDone
                rcButtonsStateCurrent = rcButtonsStateSlideOut
                buttons.anim?.play("ClickDone")
                buttons.anim?.playQueued("SlideOut")
            }
        } else if (rcButtonsStateCurrent == rcButtonsStateSlideOut && buttons.anim?.isPlaying != true) {
            rcButtonsStateCurrent = rcButtonsStateIdle
            if (rcButtonClickedValue == rcButtonClickedSave) {
                QuarterTrigger.DisplayBackDrop(true)
            } else {
                stateAskToSaveReplay_OverFlag = true
                PauseMenu.PlayAnimatingObjects(!QuarterTrigger.muteF, true)
                QuarterTrigger.DisplayQuarterAndShadow(false)
            }
            buttons.setActiveRecursively(false)
        }
    }

    companion object : IqStatic {
        var triggerSlideIn = false
        const val rcButtonsStateIdle = 100
        const val rcButtonsStateSlideIn = 101
        const val rcButtonsStateOnScreen = 102
        const val rcButtonsStateSlideOut = 103
        /** The ARM's `.cctor` stores 103 (SlideOut), not the C#'s `rcButtonsStateIdle`. */
        var rcButtonsStateCurrent = rcButtonsStateSlideOut
        const val rcButtonClickedDone = 200
        const val rcButtonClickedSave = 201
        /** The ARM's `.cctor` stores 201 (Save), not the C#'s `rcButtonClickedDone`. */
        var rcButtonClickedValue = rcButtonClickedSave
        var stateAskToSaveReplay_OverFlag = false
        var replayDataValid = false
        var replayRound = 0
        var replayshotmag = 0f
        var replayshotmagx = 0f
        var replayshotvelz = 0f
        var replayshotangle = 0f
        var savedCurrentRound = 0
        var hallOfFameReplay = false
        const val replayDataMaxSlots = 6
        var replayDataCurIndex = 0

        var store_replayDataValid: BooleanArray? = null
        var store_replayStar: IntArray? = null
        var store_replayRound: IntArray? = null
        var store_replayName: Array<String>? = null
        var store_replayshotmag: FloatArray? = null
        var store_replayshotmagx: FloatArray? = null
        var store_replayshotvelz: FloatArray? = null
        var store_replayshotangle: FloatArray? = null
        var storeTime: FloatArray? = null
        var storeTicks: IntArray? = null
        var storeSpeed: FloatArray? = null
        var storenormalizedSpeed: FloatArray? = null

        var replayDataPlayerNumber = 0
        var shotTime = 0f
        var shotSpeed = 0f
        var shotnormalizedSpeed = 0f
        var shotTick = 0
        const val separatorChar = ";"

        override fun reset() {
            triggerSlideIn = false
            rcButtonsStateCurrent = rcButtonsStateSlideOut
            rcButtonClickedValue = rcButtonClickedSave
            stateAskToSaveReplay_OverFlag = false
            replayDataValid = false
            replayRound = 0
            replayshotmag = 0f
            replayshotmagx = 0f
            replayshotvelz = 0f
            replayshotangle = 0f
            savedCurrentRound = 0
            hallOfFameReplay = false
            replayDataCurIndex = 0
            store_replayDataValid = null
            store_replayStar = null
            store_replayRound = null
            store_replayName = null
            store_replayshotmag = null
            store_replayshotmagx = null
            store_replayshotvelz = null
            store_replayshotangle = null
            storeTime = null
            storeTicks = null
            storeSpeed = null
            storenormalizedSpeed = null
            replayDataPlayerNumber = 0
            shotTime = 0f
            shotSpeed = 0f
            shotnormalizedSpeed = 0f
            shotTick = 0
        }

        fun InitReplayData() {
            val n = replayDataMaxSlots
            replayDataCurIndex = 0
            store_replayDataValid = BooleanArray(n)
            store_replayStar = IntArray(n)
            store_replayRound = IntArray(n)
            store_replayName = Array(n) { "" }
            store_replayshotmag = FloatArray(n)
            store_replayshotmagx = FloatArray(n)
            store_replayshotvelz = FloatArray(n)
            store_replayshotangle = FloatArray(n)
            storeTime = FloatArray(n)
            storeTicks = IntArray(n)
            storeSpeed = FloatArray(n)
            storenormalizedSpeed = FloatArray(n)
        }

        fun LoadAllReplays() {
            if (store_replayDataValid?.size != replayDataMaxSlots) InitReplayData()
            val p = Iq.prefs
            for (i in 0 until replayDataMaxSlots) {
                store_replayDataValid!![i] = p.getInt("IsValid$i") != 0
                if (!store_replayDataValid!![i]) continue
                store_replayStar!![i] = p.getInt("Star$i")
                store_replayRound!![i] = p.getInt("Round$i")
                store_replayName!![i] = p.getString("Name$i")
                store_replayshotmag!![i] = p.getFloat("ShotMag$i")
                store_replayshotmagx!![i] = p.getFloat("ShotMagX$i")
                store_replayshotvelz!![i] = p.getFloat("ShotVelZ$i")
                store_replayshotangle!![i] = p.getFloat("ShotAngle$i")
                storeTime!![i] = p.getFloat("Time$i")
                storeTicks!![i] = p.getInt("Ticks$i")
                storeSpeed!![i] = p.getFloat("Speed$i")
                storenormalizedSpeed!![i] = p.getFloat("NormalizedSpeed$i")
            }
        }

        private val KEYS = arrayOf(
            "IsValid", "Star", "Round", "Name", "ShotMag", "ShotMagX", "ShotVelZ", "ShotAngle",
            "Time", "Ticks", "Speed", "NormalizedSpeed",
        )

        fun ClearAllReplays() {
            for (i in 0 until replayDataMaxSlots) for (k in KEYS) Iq.prefs.deleteKey(k + i)
            InitReplayData()
        }

        fun SaveReplay(slotIndex: Int) {
            if (!ValidUsedSlot(slotIndex)) return
            val playerName = if (mainmenu.feGameType == mainmenu.gtClassic)
                GameManagerScript.GetName(replayDataPlayerNumber) else "Practice"
            // `DateTime.Today.ToString("d")` under the player's en-US culture.
            val date = SimpleDateFormat("M/d/yyyy", Locale.US).format(Date())
            store_replayName!![slotIndex] = playerName + separatorChar + date +
                separatorChar + "Rnd " + (store_replayRound!![slotIndex] + 1)
            val p = Iq.prefs
            val s = slotIndex
            p.setInt("IsValid$s", 1)
            p.setInt("Star$s", store_replayStar!![s])
            p.setInt("Round$s", store_replayRound!![s])
            p.setString("Name$s", store_replayName!![s])
            p.setFloat("ShotMag$s", store_replayshotmag!![s])
            p.setFloat("ShotMagX$s", store_replayshotmagx!![s])
            p.setFloat("ShotVelZ$s", store_replayshotvelz!![s])
            p.setFloat("ShotAngle$s", store_replayshotangle!![s])
            p.setFloat("Time$s", storeTime!![s])
            p.setInt("Ticks$s", storeTicks!![s])
            p.setFloat("Speed$s", storeSpeed!![s])
            p.setFloat("NormalizedSpeed$s", storenormalizedSpeed!![s])
        }

        fun GetReplayNameString(slotIndex: Int): String {
            if (!ValidUsedSlot(slotIndex)) return ""
            val v = store_replayName!![slotIndex]
            val sep = v.indexOf(separatorChar)
            return if (sep > 0) v.substring(0, sep) else ""
        }

        fun GetReplayRoundString(slotIndex: Int): String {
            if (!ValidUsedSlot(slotIndex)) return ""
            val v = store_replayName!![slotIndex]
            val first = v.indexOf(separatorChar)
            val second = if (first < 0) -1 else v.indexOf(separatorChar, first + 1)
            return if (first >= 0 && second > first) v.substring(first + 1, second) else ""
        }

        fun GetReplayDateString(slotIndex: Int): String {
            if (!ValidUsedSlot(slotIndex)) return ""
            val v = store_replayName!![slotIndex]
            val first = v.indexOf(separatorChar)
            val second = if (first < 0) -1 else v.indexOf(separatorChar, first + 1)
            return if (second >= 0 && second + 1 < v.length) v.substring(second + 1) else ""
        }

        fun GetReplayRoundNumber(slotIndex: Int): Int =
            if (ValidUsedSlot(slotIndex)) store_replayRound!![slotIndex] else -1

        fun SetShotParameters(mag: Float, magx: Float, shotVelZ: Float, shotAngle: Float) {
            replayDataValid = true
            replayRound = GameManagerScript.curRound
            replayshotmag = mag
            replayshotmagx = magx
            replayshotvelz = shotVelZ
            replayshotangle = shotAngle
            replayDataPlayerNumber = GameManagerScript.curPlayer
        }

        fun GetMaxSlots(): Int = replayDataMaxSlots

        fun IsSlotUsed(slotIndex: Int): Boolean = ValidUsedSlot(slotIndex)

        fun GetSavedReplaysRound(slotIndex: Int): Int =
            if (ValidSlot(slotIndex)) store_replayRound!![slotIndex] else -1

        fun GetNumSavedSlots(): Int {
            val v = store_replayDataValid!!
            var count = 0
            for (i in 0 until replayDataMaxSlots) if (v[i]) count++
            return count
        }

        fun IsReplayDataValid(): Boolean = replayDataValid

        fun RequestReplay(): Boolean {
            if (!replayDataValid) return false
            QuarterTrigger.requestLastReplay = true
            replayDataCurIndex = -1
            return true
        }

        fun RequestHallOfFameReplay(slotIndex: Int): Boolean {
            if (!ValidUsedSlot(slotIndex)) return false
            replayDataValid = false
            QuarterTrigger.requestLastReplay = true
            replayDataCurIndex = slotIndex
            return true
        }

        /** [index] is unused, as in the original. */
        @Suppress("UNUSED_PARAMETER")
        fun SaveAnimationData(index: Int, saveTime: Float, saveSpeed: Float, saveNS: Float) {
            shotTime = saveTime
            shotSpeed = saveSpeed
            shotnormalizedSpeed = saveNS
        }

        fun SaveShotTick(tick: Int) {
            shotTick = tick
            if (ValidSlot(replayDataCurIndex)) storeTicks!![replayDataCurIndex] = shotTick
        }

        fun RestoreShotTick(): Int = shotTick

        fun storeReplayData(curIdx: Int) {
            if (!replayDataValid || !ValidSlot(curIdx)) return
            store_replayDataValid!![curIdx] = replayDataValid
            store_replayRound!![curIdx] = replayRound
            store_replayshotmag!![curIdx] = replayshotmag
            store_replayshotmagx!![curIdx] = replayshotmagx
            store_replayshotvelz!![curIdx] = replayshotvelz
            store_replayshotangle!![curIdx] = replayshotangle
            storeTime!![curIdx] = shotTime
            storeTicks!![curIdx] = shotTick
            storeSpeed!![curIdx] = shotSpeed
            storenormalizedSpeed!![curIdx] = shotnormalizedSpeed
        }

        fun RestoreReplayData(curIdx: Int) {
            if (!ValidUsedSlot(curIdx)) return
            replayDataValid = false
            replayRound = store_replayRound!![curIdx]
            replayshotmag = store_replayshotmag!![curIdx]
            replayshotmagx = store_replayshotmagx!![curIdx]
            replayshotvelz = store_replayshotvelz!![curIdx]
            replayshotangle = store_replayshotangle!![curIdx]
            GameManagerScript.shotMagnitude = replayshotmag
            GameManagerScript.shotMagX = replayshotmagx
            GameManagerScript.shotVelZ = replayshotvelz
            GameManagerScript.shotAngle = replayshotangle
            shotTick = storeTicks!![curIdx]
            hallOfFameReplay = true
        }

        fun TriggerSlideIn() {
            triggerSlideIn = true
            rcButtonsStateCurrent = rcButtonsStateIdle
            rcButtonClickedValue = rcButtonClickedDone
        }

        private fun ValidSlot(slotIndex: Int): Boolean =
            slotIndex >= 0 && slotIndex < replayDataMaxSlots && store_replayDataValid != null

        private fun ValidUsedSlot(slotIndex: Int): Boolean =
            ValidSlot(slotIndex) && store_replayDataValid!![slotIndex]
    }
}
