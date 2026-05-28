package dev.lizainslie.cafemc.casino.games

import dev.lizainslie.cafemc.casino.CasinoCategory
import dev.lizainslie.cafemc.casino.CasinoConfig
import dev.lizainslie.cafemc.casino.CasinoGame
import dev.lizainslie.cafemc.casino.CasinoGameSettings
import dev.lizainslie.cafemc.casino.CasinoRound
import org.bukkit.Material
import org.bukkit.entity.Player
import kotlin.random.Random

object RouletteGame : CasinoGame {
    override val id = "roulette"
    override val displayName = "Roulette"
    override val category = CasinoCategory.TABLE
    override val icon = Material.COMPASS
    override val description = "Bet color, odd/even, or exact number."
    override val choiceUsage = "<red|black|green|odd|even|0-36>"
    override val choices = listOf("red", "black", "green", "odd", "even", "0")

    override fun play(player: Player, bet: Double, args: List<String>, settings: CasinoGameSettings): CasinoRound {
        val pick = args.getOrNull(0)?.lowercase() ?: "red"
        val roll = if (CasinoConfig.shouldSteerToHouseWin(this, settings)) losingNumberFor(pick) else Random.nextInt(0, 37)
        val color = rouletteColor(roll)
        val win = when (pick) {
            "red", "black" -> color == pick
            "green" -> roll == 0
            "odd" -> roll != 0 && roll % 2 == 1
            "even" -> roll != 0 && roll % 2 == 0
            else -> pick.toIntOrNull() == roll
        }
        val multiplier = when {
            !win -> 0.0
            pick == "green" -> 14.0
            pick.toIntOrNull() != null -> 36.0
            else -> 2.0
        }
        return CasinoRound(
            summary = "Ball landed $roll $color. Bet: $pick.",
            payout = bet * multiplier
        )
    }

    private fun losingNumberFor(pick: String): Int = when (pick) {
        "red" -> blackNumbers.random()
        "black" -> redNumbers.random()
        "green" -> Random.nextInt(1, 37)
        "odd" -> evenNumbers.random()
        "even" -> oddNumbers.random()
        else -> (0..36).filter { it != pick.toIntOrNull() }.random()
    }

    private fun rouletteColor(number: Int): String {
        if (number == 0) return "green"
        return if (number in redNumbers) "red" else "black"
    }

    private val redNumbers = setOf(1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36)
    private val blackNumbers = (1..36).filter { it !in redNumbers }
    private val oddNumbers = (1..36).filter { it % 2 == 1 }
    private val evenNumbers = (1..36).filter { it % 2 == 0 }
}
