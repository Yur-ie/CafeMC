package dev.lizainslie.cafemc.casino.games

import dev.lizainslie.cafemc.casino.CasinoCategory
import dev.lizainslie.cafemc.casino.CasinoConfig
import dev.lizainslie.cafemc.casino.CasinoGame
import dev.lizainslie.cafemc.casino.CasinoGameSettings
import dev.lizainslie.cafemc.casino.CasinoRound
import org.bukkit.Material
import org.bukkit.entity.Player

object WheelGame : CasinoGame {
    override val id = "wheel"
    override val displayName = "Prize Wheel"
    override val category = CasinoCategory.MACHINE
    override val icon = Material.CLOCK
    override val description = "Spin a weighted prize wheel."
    override val choiceUsage = ""
    override val choices = emptyList<String>()

    override fun play(player: Player, bet: Double, args: List<String>, settings: CasinoGameSettings): CasinoRound {
        val multiplier = if (CasinoConfig.shouldSteerToHouseWin(this, settings)) {
            0.0
        } else {
            weightedMultipliers.random()
        }
        return CasinoRound(
            summary = "Wheel landed on ${multiplier}x.",
            payout = bet * multiplier
        )
    }

    private val weightedMultipliers = listOf(0.0, 0.0, 0.0, 0.5, 1.0, 1.5, 2.0, 5.0)
}
