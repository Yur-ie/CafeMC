package dev.lizainslie.cafemc.casino.games

import dev.lizainslie.cafemc.casino.CasinoCategory
import dev.lizainslie.cafemc.casino.CasinoConfig
import dev.lizainslie.cafemc.casino.CasinoGame
import dev.lizainslie.cafemc.casino.CasinoGameSettings
import dev.lizainslie.cafemc.casino.CasinoRound
import org.bukkit.Material
import org.bukkit.entity.Player

object KenoGame : CasinoGame {
    override val id = "keno"
    override val displayName = "Keno"
    override val category = CasinoCategory.RISK
    override val icon = Material.EMERALD
    override val description = "Pick 3 numbers from 1-20."
    override val choiceUsage = "<n,n,n>"
    override val choices = (1..20).map { it.toString() }

    override fun play(player: Player, bet: Double, args: List<String>, settings: CasinoGameSettings): CasinoRound {
        val picks = args.joinToString(" ")
            .split(",", " ")
            .mapNotNull { it.toIntOrNull() }
            .filter { it in 1..20 }
            .distinct()
            .take(3)
            .ifEmpty { listOf(1, 2, 3) }
        val draw = if (CasinoConfig.shouldSteerToHouseWin(this, settings)) {
            (1..20).filter { it !in picks }.shuffled().take(5)
        } else {
            (1..20).shuffled().take(5)
        }
        val hits = picks.count { it in draw }
        val multiplier = when (hits) {
            3 -> 12.0
            2 -> 3.0
            1 -> 1.25
            else -> 0.0
        }
        return CasinoRound(
            summary = "Picked ${picks.joinToString()}. Draw: ${draw.joinToString()}. Hits: $hits.",
            payout = bet * multiplier
        )
    }
}
