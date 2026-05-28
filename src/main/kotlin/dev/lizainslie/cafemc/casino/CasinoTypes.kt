package dev.lizainslie.cafemc.casino

import org.bukkit.Material
import org.bukkit.entity.Player

interface CasinoGame {
    val id: String
    val displayName: String
    val category: CasinoCategory
    val icon: Material
    val description: String
    val choiceUsage: String
    val choices: List<String>

    fun play(player: Player, bet: Double, args: List<String>, settings: CasinoGameSettings): CasinoRound
}

enum class CasinoCategory {
    CARD,
    TABLE,
    MACHINE,
    RISK,
}

data class CasinoGameSettings(
    val enabled: Boolean,
    val defaultBet: Double,
    val maxBet: Double,
    val cooldownSeconds: Int,
    val targetHouseWinRate: Double,
    val houseEdgeInfluence: Double,
)

data class CasinoRound(
    val summary: String,
    val payout: Double,
) {
    val houseWon: Boolean get() = payout <= 0.0
}
