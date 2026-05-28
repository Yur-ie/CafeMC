package dev.lizainslie.cafemc.casino.games

import dev.lizainslie.cafemc.casino.CasinoCategory
import dev.lizainslie.cafemc.casino.CasinoConfig
import dev.lizainslie.cafemc.casino.CasinoGame
import dev.lizainslie.cafemc.casino.CasinoGameSettings
import dev.lizainslie.cafemc.casino.CasinoRound
import org.bukkit.Material
import org.bukkit.entity.Player

object CoinFlipGame : CasinoGame {
    override val id = "coinflip"
    override val displayName = "Coin Flip"
    override val category = CasinoCategory.TABLE
    override val icon = Material.GOLD_NUGGET
    override val description = "Pick heads or tails. Win 2x."
    override val choiceUsage = "[heads|tails]"
    override val choices = listOf("heads", "tails")

    override fun play(player: Player, bet: Double, args: List<String>, settings: CasinoGameSettings): CasinoRound {
        val pick = args.getOrNull(0)?.lowercase()?.takeIf { it in choices } ?: "heads"
        val flip = if (CasinoConfig.shouldSteerToHouseWin(this, settings)) {
            if (pick == "heads") "tails" else "heads"
        } else {
            choices.random()
        }
        return CasinoRound(
            summary = "You picked $pick. It landed $flip.",
            payout = if (pick == flip) bet * 2 else 0.0
        )
    }
}
