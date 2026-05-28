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
import org.bukkit.NamespacedKey
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
    private val chipBalances = mutableMapOf<UUID, Double>()

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
        chipBalances.remove(event.player.uniqueId)
    }

    private object CasinoCommand : PluginCommand(
        command = "casino",
        usage = "[card|table|machine|risk|game|chips] [bet|buyin|cashout|redeem] [choice]",
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
            if (args[0].equals("chips", ignoreCase = true)) {
                handleChips(player, args.drop(1))
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
            val main = CasinoConfig.mainSettings()
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

            when (main.mode) {
                CasinoEconomyMode.MONEY -> {
                    val withdrawal = CafeEconomy.withdrawPlayer(player, cleanBet)
                    if (withdrawal.type != EconomyResponse.ResponseType.SUCCESS) {
                        player.sendError(withdrawal.errorMessage ?: "Could not place that bet.")
                        return
                    }
                }
                CasinoEconomyMode.CHIPS, CasinoEconomyMode.ITEMS -> {
                    if (CasinoModule.chipBalance(player) < cleanBet) {
                        player.sendError("Not enough ${main.chipCurrencyName.lowercase()}.")
                        return
                    }
                    CasinoModule.setChipBalance(player, CasinoModule.chipBalance(player) - cleanBet)
                }
            }

            val playArgs = gameArgs.drop(1)
            val naturalRound = game.play(player, cleanBet, playArgs, settings)
            val round = CasinoConfig.applyPostResultOverride(game, settings, naturalRound) {
                game.play(player, cleanBet, playArgs, settings)
            }
            when (main.mode) {
                CasinoEconomyMode.MONEY -> if (round.payout > 0) CafeEconomy.depositPlayer(player, round.payout)
                CasinoEconomyMode.CHIPS, CasinoEconomyMode.ITEMS -> if (round.payout > 0) {
                    CasinoModule.setChipBalance(player, CasinoModule.chipBalance(player) + round.payout)
                }
            }
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
                if (main.mode != CasinoEconomyMode.MONEY) {
                    space()
                    text("(${main.chipCurrencyName}: ${CasinoModule.chipBalance(player).toCleanString()})") { color = NamedTextColor.AQUA }
                }
            }
        }

        private fun validateBet(player: Player, bet: Double, settings: CasinoGameSettings): String? {
            val main = CasinoConfig.mainSettings()
            val now = System.currentTimeMillis()
            val lastPlayed = playCooldowns[player.uniqueId]
            if (lastPlayed != null && now - lastPlayed < settings.cooldownSeconds * 1000L) {
                return "Slow down before playing another casino game."
            }

            if (bet > settings.maxBet) return "Max bet is ${CafeEconomy.format(settings.maxBet)}."
            if (main.mode == CasinoEconomyMode.MONEY && CafeEconomy.getBalance(player) < bet) return "You do not have enough money."

            playCooldowns[player.uniqueId] = now
            return null
        }

        private fun handleChips(player: Player, args: List<String>) {
            val main = CasinoConfig.mainSettings()
            if (args.isEmpty()) {
                player.sendError("Usage: /casino chips <buyin|cashout|redeem|balance> ...")
                return
            }
            when (args[0].lowercase()) {
                "balance" -> player.sendRichMessage {
                    text("[Casino] ") { color = NamedTextColor.DARK_GRAY }
                    text("${main.chipCurrencyName}: ${CasinoModule.chipBalance(player).toCleanString()}") { color = NamedTextColor.AQUA }
                }
                "buyin" -> {
                    val amount = args.getOrNull(1)?.toDoubleOrNull() ?: run {
                        player.sendError("Usage: /casino chips buyin <money>")
                        return
                    }
                    if (amount <= 0) return player.sendError("Amount must be positive.")
                    val withdrawal = CafeEconomy.withdrawPlayer(player, amount)
                    if (withdrawal.type != EconomyResponse.ResponseType.SUCCESS) {
                        player.sendError(withdrawal.errorMessage ?: "Could not buy in.")
                        return
                    }
                    val chips = amount * main.chipBuyInRate
                    CasinoModule.setChipBalance(player, CasinoModule.chipBalance(player) + chips)
                    player.sendRichMessage {
                        text("[Casino] ") { color = NamedTextColor.DARK_GRAY }
                        text("Bought ${chips.toCleanString()} ${main.chipCurrencyName}.") { color = NamedTextColor.GREEN }
                    }
                }
                "cashout" -> {
                    val chips = args.getOrNull(1)?.toDoubleOrNull() ?: run {
                        player.sendError("Usage: /casino chips cashout <chips>")
                        return
                    }
                    if (chips <= 0) return player.sendError("Amount must be positive.")
                    if (CasinoModule.chipBalance(player) < chips) return player.sendError("Not enough ${main.chipCurrencyName.lowercase()}.")
                    CasinoModule.setChipBalance(player, CasinoModule.chipBalance(player) - chips)
                    CafeEconomy.depositPlayer(player, chips * main.chipCashoutRate)
                    player.sendRichMessage {
                        text("[Casino] ") { color = NamedTextColor.DARK_GRAY }
                        text("Cashed out ${chips.toCleanString()} ${main.chipCurrencyName}.") { color = NamedTextColor.YELLOW }
                    }
                }
                "redeem" -> {
                    val ruleId = args.getOrNull(1) ?: run {
                        player.sendError("Usage: /casino chips redeem <id>")
                        return
                    }
                    val rule = main.itemRedeemRules.firstOrNull { it.id.equals(ruleId, ignoreCase = true) } ?: run {
                        player.sendError("Unknown redeem id.")
                        return
                    }
                    if (CasinoModule.chipBalance(player) < rule.chips) return player.sendError("Not enough ${main.chipCurrencyName.lowercase()}.")
                    CasinoModule.setChipBalance(player, CasinoModule.chipBalance(player) - rule.chips)
                    player.inventory.addItem(ItemStack(rule.material, rule.amount))
                    player.sendRichMessage {
                        text("[Casino] ") { color = NamedTextColor.DARK_GRAY }
                        text("Redeemed ${rule.id}.") { color = NamedTextColor.GREEN }
                    }
                }
                "sell", "buyinitem" -> {
                    val ruleId = args.getOrNull(1) ?: run {
                        player.sendError("Usage: /casino chips sell <id>")
                        return
                    }
                    val rule = main.itemBuyInRules.firstOrNull { it.id.equals(ruleId, ignoreCase = true) } ?: run {
                        player.sendError("Unknown buy-in id.")
                        return
                    }
                    if (!CasinoModule.removeMatchingItems(player, rule)) return player.sendError("Required item not found.")
                    CasinoModule.setChipBalance(player, CasinoModule.chipBalance(player) + rule.chips)
                    player.sendRichMessage {
                        text("[Casino] ") { color = NamedTextColor.DARK_GRAY }
                        text("Converted item to ${rule.chips.toCleanString()} ${main.chipCurrencyName}.") { color = NamedTextColor.GREEN }
                    }
                }
                else -> player.sendError("Usage: /casino chips <buyin|cashout|redeem|sell|balance> ...")
            }
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

    private fun chipBalance(player: Player): Double = chipBalances[player.uniqueId] ?: 0.0

    private fun setChipBalance(player: Player, value: Double) {
        chipBalances[player.uniqueId] = value.coerceAtLeast(0.0)
    }

    private fun removeMatchingItems(player: Player, rule: CasinoItemRule): Boolean {
        var needed = rule.amount
        for (slot in 0 until player.inventory.size) {
            if (needed <= 0) break
            val stack = player.inventory.getItem(slot) ?: continue
            if (!matchesRule(stack, rule)) continue
            val take = minOf(needed, stack.amount)
            stack.amount -= take
            if (stack.amount <= 0) player.inventory.setItem(slot, null)
            needed -= take
        }
        return needed <= 0
    }

    private fun matchesRule(stack: ItemStack, rule: CasinoItemRule): Boolean {
        if (stack.type != rule.material) return false
        val meta = stack.itemMeta ?: return rule.customModelData == null &&
            rule.displayNameContains == null &&
            rule.loreContains == null &&
            rule.requiredPdc.isEmpty()
        if (rule.customModelData != null && (!meta.hasCustomModelData() || meta.customModelData != rule.customModelData)) return false
        if (!rule.displayNameContains.isNullOrBlank()) {
            if (!meta.hasDisplayName()) return false
            if (!meta.displayName.contains(rule.displayNameContains, ignoreCase = true)) return false
        }
        if (!rule.loreContains.isNullOrBlank()) {
            val lore = meta.lore ?: emptyList()
            if (!lore.joinToString(" ").contains(rule.loreContains, ignoreCase = true)) return false
        }
        if (rule.requiredPdc.isNotEmpty()) {
            val container = meta.persistentDataContainer
            for ((rawKey, expected) in rule.requiredPdc) {
                val key = NamespacedKey.fromString(rawKey, CafeMC.instance) ?: return false
                val found = container.get(key, org.bukkit.persistence.PersistentDataType.STRING) ?: return false
                if (!found.equals(expected, ignoreCase = true)) return false
            }
        }
        return true
    }
}

private fun String.toCategoryOrNull() = CasinoCategory.entries.firstOrNull { it.name.equals(this, ignoreCase = true) }

private fun Double.toCleanString(): String = toString().trimEnd('0').trimEnd('.')
