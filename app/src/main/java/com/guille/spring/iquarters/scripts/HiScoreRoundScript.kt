package com.guille.spring.iquarters.scripts

import com.guille.spring.iquarters.Behaviour
import com.guille.spring.iquarters.Iq
import com.guille.spring.iquarters.IqStatic

/**
 * `HiScoreRoundScript`: the best score for each practice round, one row per round, kept in
 * `PlayerPrefs` under `PrefsNamePractice `/`PrefsScorePractice ` plus the round index. The
 * behaviour on `level0/HiScoreRound` sizes the table to `GameManagerScript.numRounds` and
 * loads it on `Awake`.
 */
class HiScoreRoundScript : Behaviour() {
    var maxNameLength = 10

    override fun onBind() {
        maxNameLength = int("maxNameLength", 10)
    }

    override fun awake() {
        maxEntryCount = GameManagerScript.numRounds
        LoadEntries()
    }

    companion object : IqStatic {
        @JvmField var entries = ArrayList<Entry>()
        @JvmField var maxEntryCount = 15
        @JvmField var nameString = "PrefsNamePractice "
        @JvmField var scoreString = "PrefsScorePractice "

        override fun reset() {
            entries = ArrayList()
            maxEntryCount = 15
            nameString = "PrefsNamePractice "
            scoreString = "PrefsScorePractice "
        }

        @JvmStatic fun GetArray(): ArrayList<Entry> = entries

        @JvmStatic fun AddRoundHiScore(round: Int, score: Float, playerName: String): Boolean {
            if (round < 0 || round >= entries.size) return false
            val entry = entries[round]
            if (score <= entry.score) return false
            entry.name = playerName
            entry.score = score
            SaveEntries()
            return true
        }

        @JvmStatic fun SaveEntries() {
            for (i in entries.indices) {
                val entry = entries[i]
                Iq.prefs.setString(nameString + i, entry.name)
                Iq.prefs.setFloat(scoreString + i, entry.score)
            }
        }

        @JvmStatic fun LoadEntries() {
            entries.clear()
            for (i in 0 until maxEntryCount) {
                val entry = Entry()
                val savedScore = Iq.prefs.getFloat(scoreString + i)
                if (savedScore > 0f) {
                    entry.name = Iq.prefs.getString(nameString + i)
                    entry.score = savedScore
                } else {
                    entry.name = "Empty"
                    entry.score = 0f
                }
                entries.add(entry)
            }
        }

        @JvmStatic fun GetName(round: Int): String =
            if (round >= 0 && round < entries.size) entries[round].name else ""

        @JvmStatic fun GetScore(round: Int): Int =
            if (round >= 0 && round < entries.size) entries[round].score.toInt() else 0

        @JvmStatic fun WipeoutPrefs() {
            maxEntryCount = GameManagerScript.numRounds
            for (i in 0 until maxEntryCount) {
                Iq.prefs.deleteKey(nameString + i)
                Iq.prefs.deleteKey(scoreString + i)
            }
            LoadEntries()
        }
    }
}
