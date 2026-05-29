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
import dev.lizainslie.cafemc.casino.data.CasinoChipAccount
import dev.lizainslie.cafemc.casino.data.CasinoLimitState
import dev.lizainslie.cafemc.casino.data.CasinoTransaction
import dev.lizainslie.cafemc.casino.data.CasinoTransactionType
import dev.lizainslie.cafemc.economy.CafeEconomy
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.event.ClickEvent
import net.milkbowl.vault.economy.EconomyResponse
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.EnchantmentStorageMeta
import org.bukkit.enchantments.Enchantment
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
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
        val slotsHolder = event.view.topInventory.holder as? SlotsGame.SlotsInventoryHolder
        if (slotsHolder != null) {
            event.isCancelled = true
            val player = event.whoClicked as? Player ?: return
            if (player.uniqueId != slotsHolder.playerId || event.clickedInventory != event.view.topInventory) return
            SlotsGame.handleClick(
                player = player,
                holder = slotsHolder,
                slot = event.slot,
                settings = CasinoConfig.settings(SlotsGame),
                main = CasinoConfig.mainSettings(),
                canSpinWithBet = { bet ->
                    CasinoCommand.canSpinSlots(player, bet, CasinoConfig.settings(SlotsGame))
                },
                onFinished = { bet, round ->
                    CasinoCommand.settleSlotsRound(player, bet, round)
                },
            )
            return
        }
        val kenoHolder = event.view.topInventory.holder as? KenoGame.KenoInventoryHolder
        if (kenoHolder != null) {
            event.isCancelled = true
            val player = event.whoClicked as? Player ?: return
            if (player.uniqueId != kenoHolder.playerId || event.clickedInventory != event.view.topInventory) return
            KenoGame.handleClick(
                player = player,
                holder = kenoHolder,
                slot = event.slot,
                canRunBet = { bet ->
                    CasinoCommand.canSpinSlots(player, bet, CasinoConfig.settings(KenoGame))
                },
                onSettled = { bet, round ->
                    CasinoCommand.settleKenoRound(player, bet, round)
                },
            )
            return
        }
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
    fun onInventoryDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is SlotsGame.SlotsInventoryHolder ||
            event.view.topInventory.holder is KenoGame.KenoInventoryHolder
        ) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        playCooldowns.remove(event.player.uniqueId)
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
            if (args[0].equals("history", ignoreCase = true)) {
                handleHistory(player, args.drop(1))
                return
            }
            if (args[0].equals("leaderboard", ignoreCase = true)) {
                handleLeaderboard(player)
                return
            }
            if (args[0].equals("admin", ignoreCase = true)) {
                handleAdmin(player, args.drop(1))
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
            0 -> CasinoCategory.entries.map { it.name.lowercase() } + games.map { it.id } + listOf("chips", "history", "leaderboard", "admin")
            1 -> (CasinoCategory.entries.map { it.name.lowercase() } + games.map { it.id } + listOf("chips", "history", "leaderboard", "admin"))
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

            if (game == BlackjackGame) {
                val first = gameArgs.firstOrNull()
                val actionOnly = first != null && first.toDoubleOrNull() == null
                if (actionOnly) {
                    handleBlackjackActionOnly(player, first, settings, main)
                    return
                }
            }

            val bet = gameArgs.getOrNull(0)?.toDoubleOrNull()
            if (bet == null || bet <= 0) {
                player.sendError("Usage: /casino ${game.id} <bet> ${game.choiceUsage}")
                return
            }

            val cleanBet = floor(bet * 100) / 100
            if (cleanBet >= main.bigBetConfirmAmount && !gameArgs.any { it.equals("confirm", ignoreCase = true) }) {
                player.sendError("Large bet. Add 'confirm'.")
                return
            }
            val effectiveSettings = settings.copy(
                targetHouseWinRate = settings.targetHouseWinRate.coerceIn(main.edgeMin, main.edgeMax),
            )

            if (game == BlackjackGame) {
                handleBlackjack(player, cleanBet, gameArgs.drop(1), effectiveSettings, main)
                return
            }
            if (game == KenoGame) {
                KenoGame.openInteractive(player, cleanBet)
                return
            }

            if (game == SlotsGame) {
                SlotsGame.openInteractive(
                    player = player,
                    initialBet = cleanBet,
                    settings = effectiveSettings,
                    main = main,
                    canSpinWithBet = { betToUse -> canSpinSlots(player, betToUse, settings) },
                ) { betUsed, round ->
                    settleRound(player, game, betUsed, round, main)
                }
                return
            }

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
                    if (chipBalance(player) < cleanBet) {
                        player.sendError("Not enough ${main.chipCurrencyName.lowercase()}.")
                        return
                    }
                    setChipBalance(player, chipBalance(player) - cleanBet)
                }
            }

            val playArgs = gameArgs.drop(1)
            val naturalRound = game.play(player, cleanBet, playArgs, effectiveSettings)
            val round = CasinoConfig.applyPostResultOverride(game, effectiveSettings, naturalRound) {
                game.play(player, cleanBet, playArgs, effectiveSettings)
            }
            settleRound(player, game, cleanBet, round, main)
        }

        private fun handleBlackjack(
            player: Player,
            cleanBet: Double,
            playArgs: List<String>,
            settings: CasinoGameSettings,
            main: CasinoMainSettings,
        ) {
            val action = playArgs.firstOrNull()?.lowercase()
            if (!BlackjackGame.hasSession(player)) {
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
                        if (chipBalance(player) < cleanBet) {
                            player.sendError("Not enough ${main.chipCurrencyName.lowercase()}.")
                            return
                        }
                        setChipBalance(player, chipBalance(player) - cleanBet)
                    }
                }
                BlackjackGame.startSession(player, cleanBet)
                sendBlackjackControls(player, cleanBet, BlackjackGame.sessionSummary(player))
                return
            }

            val blackjackAction = when (action) {
                "hit" -> BlackjackGame.Action.HIT
                "stand", "pass", "stay" -> BlackjackGame.Action.STAND
                "double" -> BlackjackGame.Action.DOUBLE
                else -> {
                    player.sendError("Use /casino blackjack $cleanBet <hit|stand|double>")
                    return
                }
            }

            val round = BlackjackGame.act(player, blackjackAction) { extraBet ->
                when (main.mode) {
                    CasinoEconomyMode.MONEY -> {
                        val withdrawal = CafeEconomy.withdrawPlayer(player, extraBet)
                        withdrawal.type == EconomyResponse.ResponseType.SUCCESS
                    }
                    CasinoEconomyMode.CHIPS, CasinoEconomyMode.ITEMS -> {
                        if (chipBalance(player) < extraBet) return@act false
                        setChipBalance(player, chipBalance(player) - extraBet)
                        true
                    }
                }
            }

            if (round.payout < 0.0) {
                sendBlackjackControls(player, cleanBet, round.summary)
                return
            }

            settleRound(player, BlackjackGame, cleanBet, round, main)
        }

        private fun handleBlackjackActionOnly(
            player: Player,
            action: String,
            settings: CasinoGameSettings,
            main: CasinoMainSettings,
        ) {
            val activeBet = BlackjackGame.currentBet(player)
            if (activeBet == null) {
                player.sendError("No active hand. Start with /casino blackjack <bet>")
                return
            }
            handleBlackjack(player, activeBet, listOf(action), settings, main)
        }

        private fun sendBlackjackControls(player: Player, bet: Double, summary: String) {
            val base = "/casino blackjack"
            player.sendRichMessage {
                text("[Blackjack] ") { color = NamedTextColor.GOLD; bold = true }
                text(summary) { color = NamedTextColor.GRAY }
                newline()
                text("[HIT] ") {
                    color = NamedTextColor.GREEN
                    bold = true
                    events {
                        click = ClickEvent.runCommand("$base hit")
                        hover { text("Draw one card") { color = NamedTextColor.GRAY } }
                    }
                }
                text("[STAND] ") {
                    color = NamedTextColor.YELLOW
                    bold = true
                    events {
                        click = ClickEvent.runCommand("$base stand")
                        hover { text("Hold and settle") { color = NamedTextColor.GRAY } }
                    }
                }
                if (BlackjackGame.canDouble(player)) {
                    text("[DOUBLE]") {
                        color = NamedTextColor.RED
                        bold = true
                        events {
                            click = ClickEvent.runCommand("$base double")
                            hover { text("Double bet, one card, then settle") { color = NamedTextColor.GRAY } }
                        }
                    }
                } else {
                    text("[DOUBLE LOCKED]") { color = NamedTextColor.DARK_GRAY }
                }
                newline()
                text("Bet: ${bet.toCleanString()}") { color = NamedTextColor.AQUA }
            }
        }

        private fun settleRound(
            player: Player,
            game: CasinoGame,
            cleanBet: Double,
            round: CasinoRound,
            main: CasinoMainSettings,
        ) {
            when (main.mode) {
                CasinoEconomyMode.MONEY -> if (round.payout > 0) CafeEconomy.depositPlayer(player, round.payout)
                CasinoEconomyMode.CHIPS, CasinoEconomyMode.ITEMS -> if (round.payout > 0) {
                    setChipBalance(player, chipBalance(player) + round.payout)
                }
            }
            transaction {
                CasinoTransaction.create(player.uniqueId, CasinoTransactionType.BET, cleanBet, game.id, round.summary)
                if (round.payout > 0) CasinoTransaction.create(player.uniqueId, CasinoTransactionType.PAYOUT, round.payout, game.id, round.summary)
                val limits = CasinoLimitState.findOrCreate(player.uniqueId)
                limits.rolloverIfNeeded()
                limits.dailyBet += cleanBet
                limits.weeklyBet += cleanBet
                val loss = (cleanBet - round.payout).coerceAtLeast(0.0)
                limits.dailyLoss += loss
                limits.weeklyLoss += loss
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
                    text("(${main.chipCurrencyName}: ${chipBalance(player).toCleanString()})") { color = NamedTextColor.AQUA }
                }
            }
        }

        fun canSpinSlots(player: Player, bet: Double, settings: CasinoGameSettings): Boolean {
            validateBet(player, bet, settings)?.let {
                player.sendError(it)
                return false
            }
            val main = CasinoConfig.mainSettings()
            when (main.mode) {
                CasinoEconomyMode.MONEY -> {
                    val withdrawal = CafeEconomy.withdrawPlayer(player, bet)
                    if (withdrawal.type != EconomyResponse.ResponseType.SUCCESS) {
                        player.sendError(withdrawal.errorMessage ?: "Could not place that bet.")
                        return false
                    }
                }
                CasinoEconomyMode.CHIPS, CasinoEconomyMode.ITEMS -> {
                    if (chipBalance(player) < bet) {
                        player.sendError("Not enough ${main.chipCurrencyName.lowercase()}.")
                        return false
                    }
                    setChipBalance(player, chipBalance(player) - bet)
                }
            }
            return true
        }

        fun settleSlotsRound(player: Player, bet: Double, round: CasinoRound) {
            settleRound(player, SlotsGame, bet, round, CasinoConfig.mainSettings())
        }

        fun settleKenoRound(player: Player, bet: Double, round: CasinoRound) {
            settleRound(player, KenoGame, bet, round, CasinoConfig.mainSettings())
        }

        private fun validateBet(player: Player, bet: Double, settings: CasinoGameSettings): String? {
            val main = CasinoConfig.mainSettings()
            val now = System.currentTimeMillis()
            val lastPlayed = playCooldowns[player.uniqueId]
            val limits = transaction {
                CasinoLimitState.findOrCreate(player.uniqueId).apply { rolloverIfNeeded() }
            }
            val adaptivePenalty = (limits.dailyLoss / 1_000.0).toInt() * main.adaptiveCooldownStep
            val cooldown = (settings.cooldownSeconds + adaptivePenalty).coerceAtMost(main.adaptiveCooldownMax)
            if (lastPlayed != null && now - lastPlayed < cooldown * 1000L) {
                return "Slow down before playing another casino game."
            }

            if (bet > settings.maxBet) return "Max bet is ${CafeEconomy.format(settings.maxBet)}."
            if (main.mode == CasinoEconomyMode.MONEY && CafeEconomy.getBalance(player) < bet) return "You do not have enough money."
            if (limits.dailyBet + bet > main.dailyBetLimit) return "Daily bet limit reached."
            if (limits.weeklyBet + bet > main.weeklyBetLimit) return "Weekly bet limit reached."
            if (limits.dailyLoss >= main.dailyLossLimit) return "Daily loss limit reached."
            if (limits.weeklyLoss >= main.weeklyLossLimit) return "Weekly loss limit reached."

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
                    text("${main.chipCurrencyName}: ${chipBalance(player).toCleanString()}") { color = NamedTextColor.AQUA }
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
                    setChipBalance(player, chipBalance(player) + chips)
                    transaction {
                        CasinoTransaction.create(player.uniqueId, CasinoTransactionType.BUYIN, chips, null, "money=$amount")
                    }
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
                    if (chipBalance(player) < chips) return player.sendError("Not enough ${main.chipCurrencyName.lowercase()}.")
                    setChipBalance(player, chipBalance(player) - chips)
                    CafeEconomy.depositPlayer(player, chips * main.chipCashoutRate)
                    transaction {
                        CasinoTransaction.create(player.uniqueId, CasinoTransactionType.CASHOUT, chips, null, "money=${chips * main.chipCashoutRate}")
                    }
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
                    if (chipBalance(player) < rule.chips) return player.sendError("Not enough ${main.chipCurrencyName.lowercase()}.")
                    setChipBalance(player, chipBalance(player) - rule.chips)
                    player.inventory.addItem(createRedeemItem(rule))
                    transaction {
                        CasinoTransaction.create(player.uniqueId, CasinoTransactionType.REDEEM, rule.chips, null, rule.id)
                    }
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
                    if (!removeMatchingItems(player, rule)) return player.sendError("Required item not found.")
                    setChipBalance(player, chipBalance(player) + rule.chips)
                    transaction {
                        CasinoTransaction.create(player.uniqueId, CasinoTransactionType.ITEM_SELL, rule.chips, null, rule.id)
                    }
                    player.sendRichMessage {
                        text("[Casino] ") { color = NamedTextColor.DARK_GRAY }
                        text("Converted item to ${rule.chips.toCleanString()} ${main.chipCurrencyName}.") { color = NamedTextColor.GREEN }
                    }
                }
                else -> player.sendError("Usage: /casino chips <buyin|cashout|redeem|sell|balance> ...")
            }
        }

        private fun handleHistory(player: Player, args: List<String>) {
            val limit = args.firstOrNull()?.toIntOrNull()?.coerceIn(1, 30) ?: 10
            val rows = transaction { CasinoTransaction.getForPlayer(player.uniqueId, limit).toList() }
            if (rows.isEmpty()) return player.sendError("No casino history.")
            player.sendRichMessage {
                text("[Casino] Recent Activity") { color = NamedTextColor.GOLD }
                rows.forEach {
                    newline()
                    text("${it.type.name.lowercase()} ${it.amount.toCleanString()} ${it.gameId ?: "-"} ${it.metadata ?: ""}") { color = NamedTextColor.GRAY }
                }
            }
        }

        private fun handleLeaderboard(player: Player) {
            val rows = transaction {
                CasinoTransaction.find { dev.lizainslie.cafemc.casino.data.CasinoTransactionsTable.type eq CasinoTransactionType.PAYOUT.name }
                    .orderBy(dev.lizainslie.cafemc.casino.data.CasinoTransactionsTable.amount to SortOrder.DESC)
                    .limit(1000)
                    .toList()
            }
            val top = rows.groupBy { it.playerId }.mapValues { (_, value) -> value.sumOf { it.amount } }
                .entries.sortedByDescending { it.value }.take(10)
            if (top.isEmpty()) return player.sendError("No leaderboard data.")
            player.sendRichMessage {
                text("[Casino] Top Winners") { color = NamedTextColor.GOLD }
                top.forEachIndexed { index, entry ->
                    val name = Bukkit.getOfflinePlayer(entry.key).name ?: entry.key.toString().take(8)
                    newline()
                    text("${index + 1}. $name ${entry.value.toCleanString()}") { color = NamedTextColor.GRAY }
                }
            }
        }

        private fun handleAdmin(player: Player, args: List<String>) {
            if (!player.hasPermission("cafe.casino.admin")) return player.sendError("No permission.")
            if (args.isEmpty()) return player.sendError("Usage: /casino admin <grant|revoke|resetseason> ...")
            when (args[0].lowercase()) {
                "resetseason" -> {
                    transaction {
                        dev.lizainslie.cafemc.casino.data.CasinoTransactionsTable.deleteAll()
                        dev.lizainslie.cafemc.casino.data.CasinoLimitStatesTable.deleteAll()
                    }
                    player.sendRichMessage { text("Casino season reset.") { color = NamedTextColor.GOLD } }
                }
                "grant" -> {
                    if (args.size < 3) return player.sendError("Usage: /casino admin grant <player> <chips>")
                    val target = Bukkit.getOfflinePlayer(args[1])
                    val amount = args[2].toDoubleOrNull()?.coerceAtLeast(0.0) ?: return player.sendError("Invalid amount.")
                    transaction {
                        val account = CasinoChipAccount.findOrCreate(target.uniqueId)
                        account.chips += amount
                        CasinoTransaction.create(target.uniqueId, CasinoTransactionType.ADMIN_GRANT, amount, null, "by=${player.uniqueId}")
                    }
                    player.sendRichMessage { text("Granted ${amount.toCleanString()} chips to ${target.name}.") { color = NamedTextColor.GREEN } }
                }
                "revoke" -> {
                    if (args.size < 3) return player.sendError("Usage: /casino admin revoke <player> <chips>")
                    val target = Bukkit.getOfflinePlayer(args[1])
                    val amount = args[2].toDoubleOrNull()?.coerceAtLeast(0.0) ?: return player.sendError("Invalid amount.")
                    transaction {
                        val account = CasinoChipAccount.findOrCreate(target.uniqueId)
                        account.chips = (account.chips - amount).coerceAtLeast(0.0)
                        CasinoTransaction.create(target.uniqueId, CasinoTransactionType.ADMIN_REVOKE, amount, null, "by=${player.uniqueId}")
                    }
                    player.sendRichMessage { text("Revoked ${amount.toCleanString()} chips from ${target.name}.") { color = NamedTextColor.YELLOW } }
                }
                else -> player.sendError("Usage: /casino admin <grant|revoke|resetseason> ...")
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

    private fun chipBalance(player: Player): Double = transaction {
        CasinoChipAccount.findOrCreate(player.uniqueId).chips
    }

    private fun setChipBalance(player: Player, value: Double) {
        transaction {
            CasinoChipAccount.findOrCreate(player.uniqueId).chips = value.coerceAtLeast(0.0)
        }
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

    private fun createRedeemItem(rule: CasinoItemRule): ItemStack {
        val stack = ItemStack(rule.material, rule.amount)
        val meta = stack.itemMeta ?: return stack
        if (rule.customModelData != null) meta.setCustomModelData(rule.customModelData)
        if (!rule.displayNameContains.isNullOrBlank()) meta.setDisplayName(rule.displayNameContains)
        if (!rule.loreContains.isNullOrBlank()) meta.lore = listOf(rule.loreContains)
        rule.requiredPdc.forEach { (rawKey, value) ->
            val key = NamespacedKey.fromString(rawKey, CafeMC.instance) ?: return@forEach
            meta.persistentDataContainer.set(key, org.bukkit.persistence.PersistentDataType.STRING, value)
        }

        if (meta is EnchantmentStorageMeta) {
            val enchantId = rule.requiredPdc["cafemc:enchant"]
            val level = rule.requiredPdc["cafemc:enchant_level"]?.toIntOrNull()
            if (!enchantId.isNullOrBlank() && level != null) {
                val enchantment = Enchantment.getByKey(NamespacedKey.minecraft(enchantId.lowercase()))
                if (enchantment != null) meta.addStoredEnchant(enchantment, level, true)
            }
        }
        stack.itemMeta = meta
        return stack
    }
}

private fun String.toCategoryOrNull() = CasinoCategory.entries.firstOrNull { it.name.equals(this, ignoreCase = true) }

private fun Double.toCleanString(): String = toString().trimEnd('0').trimEnd('.')
