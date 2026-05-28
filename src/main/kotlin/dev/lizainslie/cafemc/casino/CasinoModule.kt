package dev.lizainslie.cafemc.casino

import dev.lizainslie.cafemc.CafeMC
import dev.lizainslie.cafemc.chat.addRichLoreLine
import dev.lizainslie.cafemc.chat.component
import dev.lizainslie.cafemc.chat.sendError
import dev.lizainslie.cafemc.chat.sendRichMessage
import dev.lizainslie.cafemc.chat.setRichDisplayName
import dev.lizainslie.cafemc.core.PluginModule
import dev.lizainslie.cafemc.core.cmd.AllowedSender
import dev.lizainslie.cafemc.core.cmd.CommandContext
import dev.lizainslie.cafemc.core.cmd.PluginCommand
import dev.lizainslie.cafemc.economy.CafeEconomy
import net.kyori.adventure.text.format.NamedTextColor
import net.milkbowl.vault.economy.EconomyResponse
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import java.util.UUID
import kotlin.math.floor
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

object CasinoModule : PluginModule(), Listener {
    private const val DEFAULT_BET = 10.0
    private const val MAX_BET = 1_000.0
    private val playCooldowns = mutableMapOf<UUID, Long>()

    init {
        commands += CasinoCommand
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? CasinoInventoryHolder ?: return
        event.isCancelled = true

        val player = event.whoClicked as? Player ?: return
        if (player.uniqueId != holder.playerId || event.clickedInventory != event.view.topInventory) return

        val game = CasinoGame.entries.firstOrNull { it.slot == event.slot } ?: return
        player.closeInventory()
        CasinoCommand.play(player, game, listOf(DEFAULT_BET.toCleanString()))
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        playCooldowns.remove(event.player.uniqueId)
    }

    private object CasinoCommand : PluginCommand(
        command = "casino",
        usage = "[coinflip|dice|roulette|slots|blackjack|mines] <bet> [choice]",
        permission = "cafe.casino",
        minArgs = 0,
        maxArgs = -1,
        allowedSender = AllowedSender.PLAYER,
    ) {
        override fun CommandContext.onCommand() {
            if (args.isEmpty()) {
                openCasino(player)
                return
            }

            val game = CasinoGame.fromInput(args[0]) ?: return sendError("Unknown casino game.")
            play(player, game, args.drop(1))
        }

        override fun CommandContext.tabComplete(): List<String> = when (args.size) {
            0 -> CasinoGame.entries.map { it.command }
            1 -> CasinoGame.entries.map { it.command }.filter { it.startsWith(args[0], ignoreCase = true) }
            2 -> listOf("10", "25", "50", "100", "250").filter { it.startsWith(args[1], ignoreCase = true) }
            3 -> CasinoGame.fromInput(args[0])?.choices?.filter { it.startsWith(args[2], ignoreCase = true) } ?: emptyList()
            else -> emptyList()
        }

        fun play(player: Player, game: CasinoGame, gameArgs: List<String>) {
            val bet = gameArgs.getOrNull(0)?.toDoubleOrNull()
            if (bet == null || bet <= 0) {
                player.sendError("Usage: /casino ${game.command} <bet> ${game.choiceUsage}")
                return
            }

            val cleanBet = floor(bet * 100) / 100
            val validation = validateBet(player, cleanBet)
            if (validation != null) {
                player.sendError(validation)
                return
            }

            val withdrawal = CafeEconomy.withdrawPlayer(player, cleanBet)
            if (withdrawal.type != EconomyResponse.ResponseType.SUCCESS) {
                player.sendError(withdrawal.errorMessage ?: "Could not place that bet.")
                return
            }
            val result = game.play(cleanBet, gameArgs.drop(1))
            if (result.payout > 0) CafeEconomy.depositPlayer(player, result.payout)

            player.sendRichMessage {
                text("[Casino] ") { color = NamedTextColor.DARK_GRAY }
                text(game.displayName) {
                    color = NamedTextColor.GOLD
                    bold = true
                }
                text(": ") { color = NamedTextColor.GRAY }
                text(result.summary) { color = NamedTextColor.GRAY }
                space()
                text(result.outcomeText) {
                    color = if (result.payout > cleanBet) NamedTextColor.GREEN else if (result.payout == cleanBet) NamedTextColor.YELLOW else NamedTextColor.RED
                    bold = true
                }
            }
        }

        private fun validateBet(player: Player, bet: Double): String? {
            val now = System.currentTimeMillis()
            val lastPlayed = playCooldowns[player.uniqueId]
            if (lastPlayed != null && now - lastPlayed < 3.seconds.inWholeMilliseconds) {
                return "Slow down before playing another casino game."
            }

            if (bet > MAX_BET) return "Max bet is ${CafeEconomy.format(MAX_BET)}."
            if (CafeEconomy.getBalance(player) < bet) return "You do not have enough money."

            playCooldowns[player.uniqueId] = now
            return null
        }

        private fun openCasino(player: Player) {
            val holder = CasinoInventoryHolder(player.uniqueId)
            val inventory = Bukkit.createInventory(holder, 27, component {
                text("Cafe Casino") { color = NamedTextColor.GOLD }
            })
            holder.inventoryRef = inventory

            CasinoGame.entries.forEach { game ->
                inventory.setItem(game.slot, ItemStack(game.icon).apply {
                    itemMeta = itemMeta?.apply {
                        setRichDisplayName {
                            text(game.displayName) { color = NamedTextColor.GOLD }
                        }
                        addRichLoreLine {
                            text(game.description) { color = NamedTextColor.GRAY }
                        }
                        addRichLoreLine {
                            text("Click: ") { color = NamedTextColor.DARK_GRAY }
                            text("${CafeEconomy.format(DEFAULT_BET)} quick play") { color = NamedTextColor.GREEN }
                        }
                        addRichLoreLine {
                            text("Chat: ") { color = NamedTextColor.DARK_GRAY }
                            text("/casino ${game.command} <bet> ${game.choiceUsage}") { color = NamedTextColor.AQUA }
                        }
                    }
                })
            }

            player.openInventory(inventory)
        }
    }

    private enum class CasinoGame(
        val command: String,
        val displayName: String,
        val icon: Material,
        val slot: Int,
        val description: String,
        val choiceUsage: String,
        val choices: List<String> = emptyList(),
        val play: (Double, List<String>) -> CasinoResult,
    ) {
        COINFLIP(
            command = "coinflip",
            displayName = "Coin Flip",
            icon = Material.GOLD_NUGGET,
            slot = 10,
            description = "Pick heads or tails. Win 2x.",
            choiceUsage = "[heads|tails]",
            choices = listOf("heads", "tails"),
            play = { bet, args ->
                val pick = args.getOrNull(0)?.lowercase()?.takeIf { it in listOf("heads", "tails") } ?: "heads"
                val flip = listOf("heads", "tails").random()
                val win = pick == flip
                CasinoResult(
                    summary = "You picked $pick. It landed $flip.",
                    payout = if (win) bet * 2 else 0.0
                )
            }
        ),
        DICE(
            command = "dice",
            displayName = "Dice",
            icon = Material.BONE,
            slot = 11,
            description = "Roll 1-100. Bet over or under 50.",
            choiceUsage = "[over|under]",
            choices = listOf("over", "under"),
            play = { bet, args ->
                val pick = args.getOrNull(0)?.lowercase()?.takeIf { it in listOf("over", "under") } ?: "over"
                val roll = Random.nextInt(1, 101)
                val win = if (pick == "over") roll > 50 else roll < 50
                CasinoResult(
                    summary = "You picked $pick 50. Rolled $roll.",
                    payout = if (win) bet * 2 else 0.0
                )
            }
        ),
        ROULETTE(
            command = "roulette",
            displayName = "Roulette",
            icon = Material.COMPASS,
            slot = 12,
            description = "Bet color, odd/even, or exact number.",
            choiceUsage = "<red|black|green|odd|even|0-36>",
            choices = listOf("red", "black", "green", "odd", "even", "0"),
            play = { bet, args ->
                val pick = args.getOrNull(0)?.lowercase() ?: "red"
                val roll = Random.nextInt(0, 37)
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
                CasinoResult(
                    summary = "Ball landed $roll $color. Bet: $pick.",
                    payout = bet * multiplier
                )
            }
        ),
        SLOTS(
            command = "slots",
            displayName = "Slots",
            icon = Material.LEVER,
            slot = 13,
            description = "Spin three reels for pairs and jackpots.",
            choiceUsage = "",
            play = { bet, _ ->
                val reels = List(3) { slotSymbols.random() }
                val counts = reels.groupingBy { it }.eachCount()
                val multiplier = when {
                    counts["diamond"] == 3 -> 10.0
                    counts.values.any { it == 3 } -> 5.0
                    counts.values.any { it == 2 } -> 2.0
                    else -> 0.0
                }
                CasinoResult(
                    summary = reels.joinToString(" | ") { it.replaceFirstChar { char -> char.uppercase() } },
                    payout = bet * multiplier
                )
            }
        ),
        BLACKJACK(
            command = "blackjack",
            displayName = "Blackjack",
            icon = Material.PAPER,
            slot = 14,
            description = "Beat the dealer without busting.",
            choiceUsage = "",
            play = { bet, _ ->
                val playerHand = mutableListOf(drawCard(), drawCard())
                val dealerHand = mutableListOf(drawCard(), drawCard())
                while (handValue(playerHand) < 17) playerHand += drawCard()
                while (handValue(dealerHand) < 17) dealerHand += drawCard()

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

                CasinoResult(
                    summary = "You: $playerValue (${playerHand.joinToString()}) Dealer: $dealerValue (${dealerHand.joinToString()}).",
                    payout = payout
                )
            }
        ),
        MINES(
            command = "mines",
            displayName = "Mines",
            icon = Material.TNT,
            slot = 15,
            description = "Reveal safe tiles. More picks means more risk.",
            choiceUsage = "[picks 1-10]",
            choices = (1..10).map { it.toString() },
            play = { bet, args ->
                val picks = args.getOrNull(0)?.toIntOrNull()?.coerceIn(1, 10) ?: 3
                val mineTiles = (1..25).shuffled().take(5).toSet()
                val pickedTiles = (1..25).shuffled().take(picks)
                val hitMine = pickedTiles.any { it in mineTiles }
                val multiplier = if (hitMine) 0.0 else 1.0 + picks * 0.35
                CasinoResult(
                    summary = if (hitMine) "Picked ${pickedTiles.joinToString()} and hit a mine." else "Picked ${pickedTiles.joinToString()} safely.",
                    payout = bet * multiplier
                )
            }
        );

        companion object {
            fun fromInput(input: String) = entries.firstOrNull {
                it.command.equals(input, ignoreCase = true) || it.name.equals(input, ignoreCase = true)
            }
        }
    }

    private data class CasinoResult(
        val summary: String,
        val payout: Double,
    ) {
        val outcomeText: String
            get() = when {
                payout > 0 -> "Paid ${CafeEconomy.format(payout)}."
                else -> "No payout."
            }
    }

    private class CasinoInventoryHolder(val playerId: UUID) : InventoryHolder {
        lateinit var inventoryRef: Inventory

        override fun getInventory(): Inventory = inventoryRef
    }

    private fun Double.toCleanString(): String = toString().trimEnd('0').trimEnd('.')
}

private val slotSymbols = listOf("cherry", "cherry", "bar", "bar", "bell", "seven", "diamond")

private fun rouletteColor(number: Int): String {
    if (number == 0) return "green"
    val redNumbers = setOf(1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36)
    return if (number in redNumbers) "red" else "black"
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
