package dev.lizainslie.cafemc.casino.games

import dev.lizainslie.cafemc.casino.CasinoCategory
import dev.lizainslie.cafemc.casino.CasinoConfig
import dev.lizainslie.cafemc.casino.CasinoGame
import dev.lizainslie.cafemc.casino.CasinoGameSettings
import dev.lizainslie.cafemc.casino.CasinoRound
import org.bukkit.Material
import org.bukkit.entity.Player
import kotlin.random.Random

object BaccaratGame : CasinoGame {
    override val id = "baccarat"
    override val displayName = "Baccarat"
    override val category = CasinoCategory.CARD
    override val icon = Material.BOOK
    override val description = "Bet player, banker, or tie."
    override val choiceUsage = "[player|banker|tie]"
    override val choices = listOf("player", "banker", "tie")

    override fun play(player: Player, bet: Double, args: List<String>, settings: CasinoGameSettings): CasinoRound {
        val pick = args.getOrNull(0)?.lowercase()?.takeIf { it in choices } ?: "player"
        val playerScore = score()
        val bankerScore = if (CasinoConfig.shouldSteerToHouseWin(this, settings)) {
            when (pick) {
                "player" -> (playerScore + 1).coerceAtMost(9)
                "banker" -> (playerScore - 1).coerceAtLeast(0)
                else -> (playerScore + 1).coerceAtMost(9)
            }
        } else {
            score()
        }
        val winner = when {
            playerScore > bankerScore -> "player"
            bankerScore > playerScore -> "banker"
            else -> "tie"
        }
        val multiplier = when {
            pick != winner -> 0.0
            pick == "tie" -> 8.0
            pick == "banker" -> 1.95
            else -> 2.0
        }
        return CasinoRound(
            summary = "Player $playerScore, banker $bankerScore. Winner: $winner.",
            payout = bet * multiplier
        )
    }

    private fun score() = (Random.nextInt(1, 10) + Random.nextInt(1, 10)) % 10
}
