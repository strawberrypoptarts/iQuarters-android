package com.guille.spring.iquarters.scripts

import com.guille.spring.iquarters.Behaviour
import com.guille.spring.iquarters.Iq
import com.guille.spring.iquarters.IqStatic

/**
 * `HiScoreScript`: the classic-game high-score table (ten rows, kept in `PlayerPrefs` under
 * `PrefsName*`/`PrefsScore*` plus the row index) and the practice-mode unlock index
 * (`lockedRoundStart`). Everything is static; the behaviour on `level0/HiScore` only loads the
 * table and the locks on `Awake`.
 */
class HiScoreScript : Behaviour() {
    var maxNameLength = 10
    var newName = "Hello"
    var newScore = 0

    override fun onBind() {
        maxNameLength = int("maxNameLength", 10)
        newName = str("newName", "Hello")
        newScore = int("newScore", 0)
    }

    override fun awake() {
        LoadEntries(GameMode)
        LoadLocks()
    }

    companion object : IqStatic {
        @JvmField var entries = ArrayList<Entry>()
        @JvmField var maxEntryCount = 10
        @JvmField var lockedRoundStartIndex = 1
        @JvmField var lockedRoundStartString = "lockedRoundStart"
        @JvmField var guiIndex = 0
        @JvmField var cancelPopup = false
        @JvmField var nameString = ""
        @JvmField var scoreString = ""
        @JvmField var GameMode = 0

        override fun reset() {
            entries = ArrayList()
            maxEntryCount = 10
            lockedRoundStartIndex = 1
            lockedRoundStartString = "lockedRoundStart"
            guiIndex = 0
            cancelPopup = false
            nameString = ""
            scoreString = ""
            GameMode = 0
        }

        @JvmStatic fun GetArray(): ArrayList<Entry> = entries

        @JvmStatic fun SetPrefStringPrefix(difficulty: Int) {
            if (difficulty == 1) {
                nameString = "PrefsNameSpeed "
                scoreString = "PrefsScoreSpeed "
            } else {
                nameString = "PrefsNameNormal "
                scoreString = "PrefsScoreNormal "
            }
        }

        /** [difficulty] is ignored: the prefix comes from [GameMode], as in the original. */
        @JvmStatic fun LoadEntries(@Suppress("UNUSED_PARAMETER") difficulty: Int) {
            SetPrefStringPrefix(GameMode)
            entries.clear()
            for (i in 0 until maxEntryCount) {
                val entry = Entry(
                    name = Iq.prefs.getString(nameString + i),
                    score = Iq.prefs.getFloat(scoreString + i),
                )
                if (entry.score != 0f) entries.add(entry)
            }
            while (entries.size < maxEntryCount) entries.add(Entry(0f, "Empty"))
            while (entries.size > maxEntryCount) entries.removeAt(entries.size - 1)
        }

        @JvmStatic fun SaveEntries() {
            SetPrefStringPrefix(GameMode)
            for (i in entries.indices) {
                val entry = entries[i]
                Iq.prefs.setString(nameString + i, entry.name)
                Iq.prefs.setFloat(scoreString + i, entry.score)
            }
        }

        @JvmStatic fun SetHiScoreName(index: Int, name: String) {
            if (index >= 0 && index < maxEntryCount) {
                entries[index].name = name
                SaveEntries()
            }
        }

        @JvmStatic fun GetHighScorePosition(score: Float): Int {
            for (i in entries.indices) if (score > entries[i].score) return i
            return -1
        }

        @JvmStatic fun InsertHiScore(score: Float, playerName: String): Int {
            val entry = Entry(score, playerName)
            val position = GetHighScorePosition(score)
            if (position < 0) return -1
            entries.add(position, entry)
            if (entries.size > maxEntryCount) entries.removeAt(entries.size - 1)
            SaveEntries()
            return position
        }

        @JvmStatic fun LoadLocks() {
            lockedRoundStartIndex = Iq.prefs.getInt(lockedRoundStartString)
            if (lockedRoundStartIndex < 1) lockedRoundStartIndex = 1
        }

        @JvmStatic fun UpdateLockedRoundIndex(roundCompleted: Int) {
            if (lockedRoundStartIndex < roundCompleted + 1) {
                lockedRoundStartIndex = roundCompleted + 1
                Iq.prefs.setInt(lockedRoundStartString, lockedRoundStartIndex)
            }
        }

        @JvmStatic fun WipeoutPrefs() {
            SetPrefStringPrefix(GameMode)
            for (i in 0 until maxEntryCount) {
                Iq.prefs.deleteKey(nameString + i)
                Iq.prefs.deleteKey(scoreString + i)
            }
            LoadEntries(GameMode)
        }

        @JvmStatic fun WipeoutAllPrefs() {
            QuarterTrigger.SavePlayerNameToPrefs("PLR1", 0)
            QuarterTrigger.SavePlayerNameToPrefs("PLR2", 1)
            QuarterTrigger.SavePlayerNameToPrefs("PLR3", 2)
            QuarterTrigger.SavePlayerNameToPrefs("PLR4", 3)
            SetPrefStringPrefix(0)
            if (GameOrRoundButtons.gameButtonSelected) WipeoutPrefs() else HiScoreRoundScript.WipeoutPrefs()
            SetPrefStringPrefix(1)
        }
    }
}
