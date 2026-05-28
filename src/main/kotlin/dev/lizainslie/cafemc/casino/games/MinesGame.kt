package dev.lizainslie.cafemc.casino.games

import dev.lizainslie.cafemc.casino.CasinoCategory
import dev.lizainslie.cafemc.casino.CasinoConfig
import dev.lizainslie.cafemc.casino.CasinoGame
import dev.lizainslie.cafemc.casino.CasinoGameSettings
import dev.lizainslie.cafemc.casino.CasinoRound
import org.bukkit.Material
import org.bukkit.entity.Player

object MinesGame : CasinoGame {
    override val id = "mines"
    override val displayName = "Mines"
    override val category = CasinoCategory.RISK
    override val icon = Material.TNT
    override val description = "Reveal safe tiles. More picks means more risk."
    override val choiceUsage = "[picks 1-10]"
    override val choices = (1..10).map { it.toString() }

    override fun play(player: Player, bet: Double, args: List<String>, settings: CasinoGameSettings): CasinoRound {
        val picks = args.getOrNull(0)?.toIntOrNull()?.coerceIn(1, 10) ?: 3
        val mineTiles = (1..25).shuffled().take(5).toSet()
        val pickedTiles = if (CasinoConfig.shouldSteerToHouseWin(this, settings)) {
            mineTiles.take(1) + (1..25).filter { it !in mineTiles }.shuffled().take(picks - 1)
        } else {
            (1..25).shuffled().take(picks)
        }
        val hitMine = pickedTiles.any { it in mineTiles }
        val multiplier = if (hitMine) 0.0 else 1.0 + picks * 0.35
        return CasinoRound(
            summary = if (hitMine) "Picked ${pickedTiles.joinToString()} and hit a mine." else "Picked ${pickedTiles.joinToString()} safely.",
            payout = bet * multiplier
        )
    }
}
