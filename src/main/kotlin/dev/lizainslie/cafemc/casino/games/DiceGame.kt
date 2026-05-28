package dev.lizainslie.cafemc.casino.games

import dev.lizainslie.cafemc.casino.CasinoCategory
import dev.lizainslie.cafemc.casino.CasinoConfig
import dev.lizainslie.cafemc.casino.CasinoGame
import dev.lizainslie.cafemc.casino.CasinoGameSettings
import dev.lizainslie.cafemc.casino.CasinoRound
import org.bukkit.Material
import org.bukkit.entity.Player
import kotlin.random.Random

object DiceGame : CasinoGame {
    override val id = "dice"
    override val displayName = "Dice"
    override val category = CasinoCategory.TABLE
    override val icon = Material.BONE
    override val description = "Roll 1-100. Bet over or under 50."
    override val choiceUsage = "[over|under]"
    override val choices = listOf("over", "under")

    override fun play(player: Player, bet: Double, args: List<String>, settings: CasinoGameSettings): CasinoRound {
        val pick = args.getOrNull(0)?.lowercase()?.takeIf { it in choices } ?: "over"
        val roll = if (CasinoConfig.shouldSteerToHouseWin(this, settings)) {
            if (pick == "over") Random.nextInt(1, 51) else Random.nextInt(50, 101)
        } else {
            Random.nextInt(1, 101)
        }
        val win = if (pick == "over") roll > 50 else roll < 50
        return CasinoRound(
            summary = "You picked $pick 50. Rolled $roll.",
            payout = if (win) bet * 2 else 0.0
        )
    }
}
