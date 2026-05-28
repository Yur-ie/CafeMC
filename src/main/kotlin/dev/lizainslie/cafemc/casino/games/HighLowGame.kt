package dev.lizainslie.cafemc.casino.games

import dev.lizainslie.cafemc.casino.CasinoCategory
import dev.lizainslie.cafemc.casino.CasinoConfig
import dev.lizainslie.cafemc.casino.CasinoGame
import dev.lizainslie.cafemc.casino.CasinoGameSettings
import dev.lizainslie.cafemc.casino.CasinoRound
import org.bukkit.Material
import org.bukkit.entity.Player
import kotlin.random.Random

object HighLowGame : CasinoGame {
    override val id = "highlow"
    override val displayName = "High Low"
    override val category = CasinoCategory.CARD
    override val icon = Material.MAP
    override val description = "Guess if the next card is higher or lower."
    override val choiceUsage = "[higher|lower]"
    override val choices = listOf("higher", "lower")

    override fun play(player: Player, bet: Double, args: List<String>, settings: CasinoGameSettings): CasinoRound {
        val pick = args.getOrNull(0)?.lowercase()?.takeIf { it in choices } ?: "higher"
        val first = Random.nextInt(1, 14)
        val second = if (CasinoConfig.shouldSteerToHouseWin(this, settings)) {
            if (pick == "higher") Random.nextInt(1, first + 1) else Random.nextInt(first, 14)
        } else {
            Random.nextInt(1, 14)
        }
        val win = if (pick == "higher") second > first else second < first
        return CasinoRound(
            summary = "First card $first, next card $second. You picked $pick.",
            payout = if (win) bet * 2 else 0.0
        )
    }
}
