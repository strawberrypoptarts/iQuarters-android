package com.guille.spring.iquarters.scripts

import com.guille.spring.iquarters.Behaviour
import com.guille.spring.iquarters.IqStatic

/**
 * `GameManagerScript`: the players, their shots and scores, and the round order, all static.
 * The `.cctor` values match the decompiled source once `playerData`'s initializer is allowed
 * for (it shifts the ARM's static slots by one). The iPad rect helpers are dropped.
 */
class GameManagerScript : Behaviour() {

    private val maxPlayers = 4

    override fun start() {
        userSecretCodes = IntArray(maxUserSecretCodes)
        ResetUserSecretCodes()
        numUnlockSecretCodes = 6
        unlockSecretCodes = intArrayOf(1, 2, 3, 1, 2, 0)
        secretRoundUnlocked = false
    }

    override fun awake() {
        replayFlag = false
        totPlayers = mainmenu.feNumPlayers
        ResetPlayerData()
        ClearAllScores()
        ResetGameManagerVars()
    }

    fun ResetPlayerData() {
        playerData.clear()
        repeat(maxPlayers) { playerData += PlayerInfoClass() }
    }

    companion object : IqStatic {
        val playerData = ArrayList<PlayerInfoClass>()
        var replayFlag = false
        var shotMagnitude = 6f
        const val defaultShotAngle = 50f
        var shotAngle = 50f
        var shotMagX = 0f
        var shotVelZ = 0f
        const val shotsPerRound = 3
        var curShotThisRound = 0
        var curMadeShotsThisRound = 0
        const val numRounds = 12
        var curRound = 0
        var curPlayer = 0
        var totPlayers = 1
        const val flagNone = 0
        const val flagPlayerChange = 1
        const val flagRoundChange = 2
        const val flagPlayerGameOver = 4
        const val flagGameOver = 8
        const val flagOutOfShotsReason = 16
        const val inputTypeShake = 0
        const val inputTypeFlick = 1
        const val inputTypeMax = 2
        var secretRoundUnlocked = false
        const val secretRoundNumber = 12
        const val roundBeforeSecretRound = 8
        const val roundAfterSecretRound = 9
        var userSecretCodes = IntArray(0)
        var numUserSecretCodes = 0
        const val maxUserSecretCodes = 8
        var unlockSecretCodes = IntArray(0)
        var numUnlockSecretCodes = 0

        override fun reset() {
            playerData.clear()
            replayFlag = false
            shotMagnitude = 6f
            shotAngle = 50f
            shotMagX = 0f
            shotVelZ = 0f
            curShotThisRound = 0
            curMadeShotsThisRound = 0
            curRound = 0
            curPlayer = 0
            totPlayers = 1
            secretRoundUnlocked = false
            userSecretCodes = IntArray(maxUserSecretCodes)
            numUserSecretCodes = 0
            unlockSecretCodes = IntArray(0)
            numUnlockSecretCodes = 0
        }

        fun is_iPad() = false

        fun StoreSecretRoundUnlockCode(code: Int) {
            if (mainmenu.feGameType != mainmenu.gtClassic || secretRoundUnlocked || totPlayers != 1 ||
                curRound != roundBeforeSecretRound || curMadeShotsThisRound != shotsPerRound - 1 ||
                numUserSecretCodes >= numUnlockSecretCodes
            ) return
            userSecretCodes[numUserSecretCodes++] = code
            var matches = true
            for (i in 0 until numUserSecretCodes) if (userSecretCodes[i] != unlockSecretCodes[i]) { matches = false; break }
            if (!matches) ResetUserSecretCodes()
            else if (numUserSecretCodes == numUnlockSecretCodes) {
                AnnouncerScript.triggerUnlockSound = true
                secretRoundUnlocked = true
            }
        }

        fun ResetUserSecretCodes() {
            numUserSecretCodes = 0
            if (userSecretCodes.size < maxUserSecretCodes) userSecretCodes = IntArray(maxUserSecretCodes)
            userSecretCodes.fill(-1)
        }

        fun IsLastActivePlayer(player: Int): Boolean {
            if (player < 0 || player >= totPlayers) return false
            var lastActive = 0
            for (i in 0 until totPlayers) if (PlayerAt(i).shotsLeft > 0) lastActive = i
            return player == lastActive
        }

        fun ResetShotAngleToDefault() { shotAngle = defaultShotAngle }

        fun GetCurrentRoundScore() = if (IsValidPlayer(curPlayer)) PlayerAt(curPlayer).roundScore else 0
        fun SetCurrentRoundScore(roundScore: Int): Int { if (IsValidPlayer(curPlayer)) PlayerAt(curPlayer).roundScore = roundScore; return 0 }

        fun UpdateCurRicochet(curRicochet: Int): Int {
            if (IsValidPlayer(curPlayer) && PlayerAt(curPlayer).maxRicochet < curRicochet) PlayerAt(curPlayer).maxRicochet = curRicochet
            return 0
        }

        fun AddRoundScore(add: Int) { SetCurrentRoundScore(GetCurrentRoundScore() + add) }

        fun AddScore(add: Int) {
            AddRoundScore(add)
            SetCurrentScore(GetCurrentScore() + add)
            if (add == 0) { SetCurrentTotalStreak(0); SetCurrentRoundStreak(0) }
        }

        fun IncrementCurrentStreak() {
            SetCurrentTotalStreak(GetCurrentTotalStreak() + 1)
            SetCurrentRoundStreak(GetCurrentRoundStreak() + 1)
        }

        fun SetCurrentStreak(score: Int) {
            SetCurrentTotalStreak(if (score == 0) 0 else GetCurrentTotalStreak() + 1)
            SetCurrentRoundStreak(if (score == 0) 0 else GetCurrentRoundStreak() + 1)
        }

        fun ClearAllScores() { ClearScores(); ClearTotalStreaks(); ClearRoundStreaks(); ClearAllGameOverFlags() }

        fun ResetGameManagerVars() {
            curPlayer = 0
            curShotThisRound = 0
            curMadeShotsThisRound = 0
            curRound = 0
            ClearAllScores()
        }

        fun UpdateRound() {
            ClearRoundStreaks()
            ClearAllPlayedThisRoundFlags()
            SetCurrentRoundScore(0)
            if (secretRoundUnlocked && curRound == roundBeforeSecretRound) curRound = secretRoundNumber
            else if (curRound == secretRoundNumber) curRound = roundAfterSecretRound
            else { curRound++; if (curRound >= numRounds) curRound = 0 }
        }

        fun GetNextCurrentPlayer(): Boolean {
            val previous = curPlayer
            for (p in previous until totPlayers) if (!GetPlayedThisRound(p) && GetPlayerShotsLeft(p) > 0) { curPlayer = p; return true }
            for (p in 0 until previous) if (!GetPlayedThisRound(p) && GetPlayerShotsLeft(p) > 0) { curPlayer = p; return true }
            if (curRound < numRounds - 1) {
                var playedWithShots = 0
                var outOfShots = 0
                var first = -1
                for (p in 0 until totPlayers) {
                    if (GetPlayedThisRound(p) && GetPlayerShotsLeft(p) > 0) { playedWithShots++; if (first == -1) first = p }
                    else if (GetPlayerShotsLeft(p) == 0) outOfShots++
                }
                if (playedWithShots + outOfShots == totPlayers && playedWithShots > 0) { curPlayer = first; return false }
            }
            curPlayer = 0
            return false
        }

        fun UpdateShotCount(score: Int): Int {
            var result = flagNone
            val previousRound = curRound
            val previousPlayer = curPlayer
            if (score == 0) { if (DecrementCurrentShotsLeft() == 1) SetCurrentPlayedThisRound(true) }
            else curMadeShotsThisRound++
            curShotThisRound++
            if (curMadeShotsThisRound >= shotsPerRound || GetCurrentShotsLeft() < 1) {
                SetCurrentPlayedThisRound(true)
                SetCurrentRoundScore(0)
                if ((curRound >= numRounds - 1 && curRound != secretRoundNumber) || GetCurrentShotsLeft() < 1) {
                    result = result or flagPlayerGameOver
                    SetCurrentGameOverFlag(true)
                    if (AllPlayersGameOver()) result = result or flagGameOver
                    if (GetCurrentShotsLeft() < 1) result = result or flagOutOfShotsReason
                }
                curShotThisRound = 0
                curMadeShotsThisRound = 0
                if (!GetNextCurrentPlayer()) UpdateRound()
            }
            if (curPlayer != previousPlayer) result = result or flagPlayerChange
            if (curRound != previousRound) result = result or flagRoundChange
            return result
        }

        fun ClearAllGameOverFlags() { for (p in playerData) p.gameOver = false }
        fun SetCurrentGameOverFlag(f: Boolean): Int { if (IsValidPlayer(curPlayer)) PlayerAt(curPlayer).gameOver = f; return 0 }
        fun AllPlayersGameOver() = playerData.all { it.gameOver }
        fun ClearAllPlayedThisRoundFlags() { for (p in playerData) p.playedThisRound = false }
        fun SetCurrentPlayedThisRound(f: Boolean): Int { if (IsValidPlayer(curPlayer)) PlayerAt(curPlayer).playedThisRound = f; return 0 }
        fun GetCurrentPlayedThisRound() = if (IsValidPlayer(curPlayer)) PlayerAt(curPlayer).playedThisRound else true
        fun GetPlayedThisRound(n: Int) = if (IsValidPlayer(n)) PlayerAt(n).playedThisRound else true
        fun SetAllShotsLeft(shotsLeft: Int) { for (p in playerData) p.shotsLeft = shotsLeft }
        fun AllPlayersOutOfShots() = playerData.none { it.shotsLeft > 0 }

        fun SetCurrentInputType(input: Int): Int {
            if (IsValidPlayer(curPlayer) && input >= 0 && input < inputTypeMax) PlayerAt(curPlayer).inputType = input
            return 0
        }

        fun GetCurrentInputType() = if (IsValidPlayer(curPlayer)) PlayerAt(curPlayer).inputType else 0
        fun SetCurrentShotsLeft(shotsLeft: Int): Int { if (IsValidPlayer(curPlayer)) PlayerAt(curPlayer).shotsLeft = shotsLeft; return 0 }
        fun GetCurrentShotsLeft(player: Int) = if (IsValidPlayer(player)) PlayerAt(player).shotsLeft else 0
        fun GetPlayerShotsLeft(n: Int) = if (IsValidPlayer(n)) PlayerAt(n).shotsLeft else 0
        fun GetCurrentShotsLeft() = GetCurrentShotsLeft(curPlayer)

        fun DecrementCurrentShotsLeft(): Int {
            if (!IsValidPlayer(curPlayer)) return 0
            val p = PlayerAt(curPlayer)
            p.shotsLeft--
            if (p.shotsLeft < 1) { p.shotsLeft = 0; return 1 }
            return 0
        }

        fun ClearScores() { for (p in playerData) { p.score = 0; p.roundScore = 0 } }
        fun AddPlayerScore(add: Int, n: Int): Int { if (IsValidPlayer(n)) PlayerAt(n).score += add; return 0 }
        fun SetCurrentScore(score: Int): Int { if (IsValidPlayer(curPlayer)) PlayerAt(curPlayer).score = score; return 0 }
        fun GetCurrentScore() = if (IsValidPlayer(curPlayer)) PlayerAt(curPlayer).score else 0
        fun GetPlayerScore(n: Int) = if (IsValidPlayer(n)) PlayerAt(n).score else 0
        fun ClearRoundStreaks() { for (p in playerData) p.roundStreak = 0 }
        fun SetCurrentRoundStreak(s: Int): Int { if (IsValidPlayer(curPlayer)) PlayerAt(curPlayer).roundStreak = s; return 0 }
        fun GetPlayerMaxStreak(n: Int) = if (IsValidPlayer(n)) PlayerAt(n).maxStreak else 0
        fun GetPlayerMaxRicochet(n: Int) = if (IsValidPlayer(n)) PlayerAt(n).maxRicochet else 0
        fun GetCurrentRoundStreak() = if (IsValidPlayer(curPlayer)) PlayerAt(curPlayer).roundStreak else 0

        fun SetCurrentTotalStreak(s: Int): Int {
            if (IsValidPlayer(curPlayer)) {
                val p = PlayerAt(curPlayer)
                p.streak = s
                if (p.maxStreak < s) p.maxStreak = s
            }
            return 0
        }

        fun ClearTotalStreaks() { for (p in playerData) { p.streak = 0; p.maxStreak = 0 } }
        fun GetCurrentTotalStreak() = if (IsValidPlayer(curPlayer)) PlayerAt(curPlayer).streak else 0

        fun SetName(name: String, n: Int): Boolean { if (!IsValidPlayer(n)) return true; PlayerAt(n).playerName = name; return false }
        fun GetName(n: Int) = if (IsValidPlayer(n)) PlayerAt(n).playerName else "error"
        fun GetNameEnteredFlag(n: Int) = if (IsValidPlayer(n)) PlayerAt(n).nameEnteredFlag else true
        fun SetNameEnteredFlag(n: Int): Boolean { if (!IsValidPlayer(n)) return true; PlayerAt(n).nameEnteredFlag = true; return false }

        private fun IsValidPlayer(n: Int) = n >= 0 && n < playerData.size
        private fun PlayerAt(n: Int) = playerData[n]
    }
}
