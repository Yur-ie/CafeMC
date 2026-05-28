package dev.lizainslie.cafemc.casino

import dev.lizainslie.cafemc.CafeMC
import dev.lizainslie.cafemc.chat.addRichLoreLine
import dev.lizainslie.cafemc.chat.component
import dev.lizainslie.cafemc.chat.sendError
import dev.lizainslie.cafemc.chat.sendRichMessage
import dev.lizainslie.cafemc.chat.setRichDisplayName
import dev.lizainslie.cafemc.casino.games.BaccaratGame
import dev.lizainslie.cafemc.casino.games.BlackjackGame
import dev.lizainslie.cafemc.casino.games.CoinFlipGame
import dev.lizainslie.cafemc.casino.games.DiceGame
import dev.lizainslie.cafemc.casino.games.HighLowGame
import dev.lizainslie.cafemc.casino.games.KenoGame
import dev.lizainslie.cafemc.casino.games.MinesGame
import dev.lizainslie.cafemc.casino.games.RouletteGame
import dev.lizainslie.cafemc.casino.games.SlotsGame
import dev.lizainslie.cafemc.casino.games.WheelGame
import dev.lizainslie.cafemc.core.PluginModule
import dev.lizainslie.cafemc.core.cmd.AllowedSender
import dev.lizainslie.cafemc.core.cmd.CommandContext
import dev.lizainslie.cafemc.core.cmd.PluginCommand
import dev.lizainslie.cafemc.economy.CafeEconomy
import net.kyori.adventure.text.format.NamedTextColor
import net.milkbowl.vault.economy.EconomyResponse
import org.bukkit.Bukkit
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

object CasinoModule : PluginModule(), Listener {
    val games = listOf(
        CoinFlipGame,
        DiceGame,
        RouletteGame,
        SlotsGame,
        BlackjackGame,
        BaccaratGame,
        HighLowGame,
        MinesGame,
        KenoGame,
        WheelGame,
    )
    private val playCooldowns = mutableMapOf<UUID, Long>()

    init {
        commands += CasinoCommand
    }

    override fun onEnable(cafeMC: CafeMC) {
        super.onEnable(cafeMC)
        CasinoConfig.preGenerateAll(games)
        cafeMC.logger.info("Casino configs generated in /casino/config")
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? CasinoInventoryHolder ?: return
        event.isCancelled = true

        val player = event.whoClicked as? Player ?: return
        if (player.uniqueId != holder.playerId || event.clickedInventory != event.view.topInventory) return

        val game = holder.games.getOrNull(event.slot) ?: return
        player.closeInventory()
        val settings = CasinoConfig.settings(game)
        CasinoCommand.play(player, game, listOf(settings.defaultBet.toCleanString()))
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        playCooldowns.remove(event.player.uniqueId)
    }

    private object CasinoCommand : PluginCommand(
        command = "casino",
        usage = "[card|table|machine|risk|game] [bet] [choice]",
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

            val category = args[0].toCategoryOrNull()
            if (category != null) {
                openCasino(player, category)
                return
            }

            val game = games.firstOrNull { it.id.equals(args[0], ignoreCase = true) }
                ?: return sendError("Unknown casino game.")
            play(player, game, args.drop(1))
        }

        override fun CommandContext.tabComplete(): List<String> = when (args.size) {
            0 -> CasinoCategory.entries.map { it.name.lowercase() } + games.map { it.id }
            1 -> (CasinoCategory.entries.map { it.name.lowercase() } + games.map { it.id })
                .filter { it.startsWith(args[0], ignoreCase = true) }
            2 -> listOf("10", "25", "50", "100", "250").filter { it.startsWith(args[1], ignoreCase = true) }
            3 -> games.firstOrNull { it.id.equals(args[0], ignoreCase = true) }?.choices
                ?.filter { it.startsWith(args[2], ignoreCase = true) } ?: emptyList()
            else -> emptyList()
        }

        fun play(player: Player, game: CasinoGame, gameArgs: List<String>) {
            val settings = CasinoConfig.settings(game)
            if (!settings.enabled) {
                player.sendError("${game.displayName} is disabled.")
                return
            }

            val bet = gameArgs.getOrNull(0)?.toDoubleOrNull()
            if (bet == null || bet <= 0) {
                player.sendError("Usage: /casino ${game.id} <bet> ${game.choiceUsage}")
                return
            }

            val cleanBet = floor(bet * 100) / 100
            validateBet(player, cleanBet, settings)?.let {
                player.sendError(it)
                return
            }

            val withdrawal = CafeEconomy.withdrawPlayer(player, cleanBet)
            if (withdrawal.type != EconomyResponse.ResponseType.SUCCESS) {
                player.sendError(withdrawal.errorMessage ?: "Could not place that bet.")
                return
            }

            val playArgs = gameArgs.drop(1)
            val naturalRound = game.play(player, cleanBet, playArgs, settings)
            val round = CasinoConfig.applyPostResultOverride(game, settings, naturalRound) {
                game.play(player, cleanBet, playArgs, settings)
            }
            if (round.payout > 0) CafeEconomy.depositPlayer(player, round.payout)
            CasinoConfig.record(game, cleanBet, round.payout)

            player.sendRichMessage {
                text("[Casino] ") { color = NamedTextColor.DARK_GRAY }
                text(game.displayName) {
                    color = NamedTextColor.GOLD
                    bold = true
                }
                text(": ") { color = NamedTextColor.GRAY }
                text(round.summary) { color = NamedTextColor.GRAY }
                space()
                text(if (round.payout > 0) "Paid ${CafeEconomy.format(round.payout)}." else "No payout.") {
                    color = if (round.payout > cleanBet) NamedTextColor.GREEN else if (round.payout == cleanBet) NamedTextColor.YELLOW else NamedTextColor.RED
                    bold = true
                }
            }
        }

        private fun validateBet(player: Player, bet: Double, settings: CasinoGameSettings): String? {
            val now = System.currentTimeMillis()
            val lastPlayed = playCooldowns[player.uniqueId]
            if (lastPlayed != null && now - lastPlayed < settings.cooldownSeconds * 1000L) {
                return "Slow down before playing another casino game."
            }

            if (bet > settings.maxBet) return "Max bet is ${CafeEconomy.format(settings.maxBet)}."
            if (CafeEconomy.getBalance(player) < bet) return "You do not have enough money."

            playCooldowns[player.uniqueId] = now
            return null
        }

        private fun openCasino(player: Player, category: CasinoCategory? = null) {
            val shownGames = games.filter { category == null || it.category == category }
            val holder = CasinoInventoryHolder(player.uniqueId, shownGames)
            val inventory = Bukkit.createInventory(holder, 27, component {
                text(if (category == null) "Cafe Casino" else "Casino: ${category.name.lowercase()}") {
                    color = NamedTextColor.GOLD
                }
            })
            holder.inventoryRef = inventory

            shownGames.forEachIndexed { index, game ->
                val settings = CasinoConfig.settings(game)
                inventory.setItem(index, ItemStack(game.icon).apply {
                    itemMeta = itemMeta?.apply {
                        setRichDisplayName {
                            text(game.displayName) { color = NamedTextColor.GOLD }
                        }
                        addRichLoreLine {
                            text(game.description) { color = NamedTextColor.GRAY }
                        }
                        addRichLoreLine {
                            text("Type: ") { color = NamedTextColor.DARK_GRAY }
                            text(game.category.name.lowercase()) { color = NamedTextColor.AQUA }
                        }
                        addRichLoreLine {
                            text("Click: ") { color = NamedTextColor.DARK_GRAY }
                            text("${CafeEconomy.format(settings.defaultBet)} quick play") { color = NamedTextColor.GREEN }
                        }
                        addRichLoreLine {
                            text("Chat: ") { color = NamedTextColor.DARK_GRAY }
                            text("/casino ${game.id} <bet> ${game.choiceUsage}") { color = NamedTextColor.AQUA }
                        }
                    }
                })
            }

            player.openInventory(inventory)
        }
    }

    private class CasinoInventoryHolder(
        val playerId: UUID,
        val games: List<CasinoGame>,
    ) : InventoryHolder {
        lateinit var inventoryRef: Inventory

        override fun getInventory(): Inventory = inventoryRef
    }
}

private fun String.toCategoryOrNull() = CasinoCategory.entries.firstOrNull { it.name.equals(this, ignoreCase = true) }

private fun Double.toCleanString(): String = toString().trimEnd('0').trimEnd('.')
