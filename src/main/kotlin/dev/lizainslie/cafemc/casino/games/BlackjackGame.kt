package dev.lizainslie.cafemc.casino.games

import dev.lizainslie.cafemc.casino.CasinoCategory
import dev.lizainslie.cafemc.casino.CasinoConfig
import dev.lizainslie.cafemc.casino.CasinoGame
import dev.lizainslie.cafemc.casino.CasinoGameSettings
import dev.lizainslie.cafemc.casino.CasinoRound
import org.bukkit.Material
import org.bukkit.entity.Player
import java.util.UUID
import kotlin.random.Random

object BlackjackGame : CasinoGame {
    override val id = "blackjack"
    override val displayName = "Blackjack"
    override val category = CasinoCategory.CARD
    override val icon = Material.PAPER
    override val description = "Beat the dealer without busting."
    override val choiceUsage = "[hit|stand|double]"
    override val choices = listOf("hit", "stand", "double")

    data class BlackjackSession(
        val playerId: UUID,
        val playerHand: MutableList<Int>,
        val dealerHand: MutableList<Int>,
        var bet: Double,
        var canDouble: Boolean = true,
    )

    enum class Action { HIT, STAND, DOUBLE }
    private val sessions = mutableMapOf<UUID, BlackjackSession>()

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

    fun hasSession(player: Player): Boolean = sessions.containsKey(player.uniqueId)
    fun currentBet(player: Player): Double? = sessions[player.uniqueId]?.bet
    fun canDouble(player: Player): Boolean = sessions[player.uniqueId]?.canDouble == true

    fun startSession(player: Player, bet: Double): BlackjackSession {
        val session = BlackjackSession(
            playerId = player.uniqueId,
            playerHand = mutableListOf(drawCard(), drawCard()),
            dealerHand = mutableListOf(drawCard(), drawCard()),
            bet = bet,
        )
        sessions[player.uniqueId] = session
        return session
    }

    fun sessionSummary(player: Player): String {
        val session = sessions[player.uniqueId] ?: return "No active hand."
        return "You: ${handValue(session.playerHand)} (${session.playerHand.joinToString()}) Dealer: ${session.dealerHand.first()} + ?."
    }

    fun act(player: Player, action: Action, canAffordDouble: (Double) -> Boolean): CasinoRound {
        val session = sessions[player.uniqueId] ?: return CasinoRound("No active blackjack hand.", 0.0)
        when (action) {
            Action.HIT -> {
                session.playerHand += drawCard()
                session.canDouble = false
                val playerValue = handValue(session.playerHand)
                if (playerValue > 21) {
                    sessions.remove(player.uniqueId)
                    return CasinoRound("Bust at $playerValue (${session.playerHand.joinToString()}).", 0.0)
                }
                return CasinoRound("Hit: $playerValue (${session.playerHand.joinToString()}).", -1.0)
            }
            Action.DOUBLE -> {
                if (!session.canDouble) return CasinoRound("Double no longer available.", -1.0)
                if (!canAffordDouble(session.bet)) return CasinoRound("Cannot afford double.", -1.0)
                session.bet *= 2.0
                session.playerHand += drawCard()
                session.canDouble = false
                val playerValue = handValue(session.playerHand)
                if (playerValue > 21) {
                    sessions.remove(player.uniqueId)
                    return CasinoRound("Double bust at $playerValue (${session.playerHand.joinToString()}).", 0.0)
                }
                return settle(player)
            }
            Action.STAND -> return settle(player)
        }
    }

    private fun settle(player: Player): CasinoRound {
        val session = sessions[player.uniqueId] ?: return CasinoRound("No active blackjack hand.", 0.0)
        while (handValue(session.dealerHand) < 17) session.dealerHand += drawCard()

        val playerValue = handValue(session.playerHand)
        val dealerValue = handValue(session.dealerHand)
        val natural = session.playerHand.size == 2 && playerValue == 21
        val payout = when {
            playerValue > 21 -> 0.0
            dealerValue > 21 -> session.bet * if (natural) 2.5 else 2.0
            playerValue > dealerValue -> session.bet * if (natural) 2.5 else 2.0
            playerValue == dealerValue -> session.bet
            else -> 0.0
        }
        sessions.remove(player.uniqueId)
        return CasinoRound(
            summary = "You: $playerValue (${session.playerHand.joinToString()}) Dealer: $dealerValue (${session.dealerHand.joinToString()}).",
            payout = payout,
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
