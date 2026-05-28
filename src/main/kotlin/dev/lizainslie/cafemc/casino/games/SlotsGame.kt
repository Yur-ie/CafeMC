package dev.lizainslie.cafemc.casino.games

import dev.lizainslie.cafemc.casino.CasinoCategory
import dev.lizainslie.cafemc.casino.CasinoConfig
import dev.lizainslie.cafemc.casino.CasinoGame
import dev.lizainslie.cafemc.casino.CasinoGameSettings
import dev.lizainslie.cafemc.casino.CasinoRound
import org.bukkit.Material
import org.bukkit.entity.Player

object SlotsGame : CasinoGame {
    override val id = "slots"
    override val displayName = "Slots"
    override val category = CasinoCategory.MACHINE
    override val icon = Material.LEVER
    override val description = "Spin three reels for pairs and jackpots."
    override val choiceUsage = ""
    override val choices = emptyList<String>()

    override fun play(player: Player, bet: Double, args: List<String>, settings: CasinoGameSettings): CasinoRound {
        val reels = if (CasinoConfig.shouldSteerToHouseWin(this, settings)) {
            listOf("cherry", "bar", "bell")
        } else {
            List(3) { symbols.random() }
        }
        val counts = reels.groupingBy { it }.eachCount()
        val multiplier = when {
            counts["diamond"] == 3 -> 10.0
            counts.values.any { it == 3 } -> 5.0
            counts.values.any { it == 2 } -> 2.0
            else -> 0.0
        }
        return CasinoRound(
            summary = reels.joinToString(" | ") { it.replaceFirstChar { char -> char.uppercase() } },
            payout = bet * multiplier
        )
    }

    private val symbols = listOf("cherry", "cherry", "bar", "bar", "bell", "seven", "diamond")
}
