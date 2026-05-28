package dev.lizainslie.cafemc.casino.games

import dev.lizainslie.cafemc.casino.CasinoCategory
import dev.lizainslie.cafemc.casino.CasinoConfig
import dev.lizainslie.cafemc.casino.CasinoGame
import dev.lizainslie.cafemc.casino.CasinoGameSettings
import dev.lizainslie.cafemc.casino.CasinoRound
import org.bukkit.Material
import org.bukkit.entity.Player
import kotlin.random.Random

object BlackjackGame : CasinoGame {
    override val id = "blackjack"
    override val displayName = "Blackjack"
    override val category = CasinoCategory.CARD
    override val icon = Material.PAPER
    override val description = "Beat the dealer without busting."
    override val choiceUsage = ""
    override val choices = emptyList<String>()

    override fun play(player: Player, bet: Double, args: List<String>, settings: CasinoGameSettings): CasinoRound {
        val playerHand = mutableListOf(drawCard(), drawCard())
        val dealerHand = mutableListOf(drawCard(), drawCard())
        while (handValue(playerHand) < 17) playerHand += drawCard()
        while (handValue(dealerHand) < 17) dealerHand += drawCard()

        if (CasinoConfig.shouldSteerToHouseWin(this, settings)) {
            while (handValue(dealerHand) < 21 && handValue(dealerHand) <= handValue(playerHand)) {
                dealerHand += drawCard()
            }
        }

        val playerValue = handValue(playerHand)
        val dealerValue = handValue(dealerHand)
        val natural = playerHand.size == 2 && playerValue == 21
        val payout = when {
            playerValue > 21 -> 0.0
            dealerValue > 21 -> bet * if (natural) 2.5 else 2.0
            playerValue > dealerValue -> bet * if (natural) 2.5 else 2.0
            playerValue == dealerValue -> bet
            else -> 0.0
        }

        return CasinoRound(
            summary = "You: $playerValue (${playerHand.joinToString()}) Dealer: $dealerValue (${dealerHand.joinToString()}).",
            payout = payout
        )
    }

    private fun drawCard(): Int = Random.nextInt(1, 14).coerceAtMost(10)

    private fun handValue(cards: List<Int>): Int {
        var total = cards.sumOf { if (it == 1) 11 else it }
        var aces = cards.count { it == 1 }
        while (total > 21 && aces > 0) {
            total -= 10
            aces -= 1
        }
        return total
    }
}
