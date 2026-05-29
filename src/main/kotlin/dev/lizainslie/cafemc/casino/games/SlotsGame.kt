package dev.lizainslie.cafemc.casino.games

import dev.lizainslie.cafemc.casino.CasinoCategory
import dev.lizainslie.cafemc.casino.CasinoConfig
import dev.lizainslie.cafemc.casino.CasinoGame
import dev.lizainslie.cafemc.casino.CasinoGameSettings
import dev.lizainslie.cafemc.casino.CasinoMainSettings
import dev.lizainslie.cafemc.casino.CasinoRound
import dev.lizainslie.cafemc.chat.component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import kotlin.math.floor
import java.util.UUID

object SlotsGame : CasinoGame {
    override val id = "slots"
    override val displayName = "Slots"
    override val category = CasinoCategory.MACHINE
    override val icon = Material.LEVER
    override val description = "Spin three reels for pairs and jackpots."
    override val choiceUsage = ""
    override val choices = emptyList<String>()

    override fun play(player: Player, bet: Double, args: List<String>, settings: CasinoGameSettings): CasinoRound {
        val reels = if (CasinoConfig.shouldSteerToHouseWin(this, settings)) {
            listOf("cherry", "bar", "bell")
        } else {
            List(3) { symbols.random() }
        }
        val counts = reels.groupingBy { it }.eachCount()
        val multiplier = when {
            counts["diamond"] == 3 -> 10.0
            counts.values.any { it == 3 } -> 5.0
            counts.values.any { it == 2 } -> 2.0
            else -> 0.0
        }
        return CasinoRound(
            summary = reels.joinToString(" | ") { it.replaceFirstChar { char -> char.uppercase() } },
            payout = bet * multiplier
        )
    }

    fun openInteractive(
        player: Player,
        initialBet: Double,
        settings: CasinoGameSettings,
        main: CasinoMainSettings,
        canSpinWithBet: (Double) -> Boolean,
        onFinished: (Double, CasinoRound) -> Unit,
    ) {
        val holder = SlotsInventoryHolder(player.uniqueId, initialBet.coerceIn(0.01, settings.maxBet))
        val inventory = Bukkit.createInventory(holder, 54, component {
            text("Slots") { color = NamedTextColor.GOLD }
        })
        holder.inventoryRef = inventory
        decorateFrame(inventory)
        refreshControls(inventory, holder)
        player.openInventory(inventory)
    }

    fun handleClick(
        player: Player,
        holder: SlotsInventoryHolder,
        slot: Int,
        settings: CasinoGameSettings,
        main: CasinoMainSettings,
        canSpinWithBet: (Double) -> Boolean,
        onFinished: (Double, CasinoRound) -> Unit,
    ) {
        val inventory = holder.inventoryRef
        if (holder.spinning) return
        when (slot) {
            BET_MINUS_100 -> holder.bet = normalizeBet(holder.bet - 100.0, settings.maxBet)
            BET_MINUS_10 -> holder.bet = normalizeBet(holder.bet - 10.0, settings.maxBet)
            BET_PLUS_10 -> holder.bet = normalizeBet(holder.bet + 10.0, settings.maxBet)
            BET_PLUS_100 -> holder.bet = normalizeBet(holder.bet + 100.0, settings.maxBet)
            BET_REPEAT -> {
                if (holder.lastBet > 0.0) holder.bet = normalizeBet(holder.lastBet, settings.maxBet)
            }
            BET_SPIN -> {
                val bet = holder.bet
                if (!canSpinWithBet(bet)) {
                    player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 0.7f, 0.8f)
                    return
                }
                holder.lastBet = bet
                holder.spinning = true
                animateSpin(player, holder, inventory, bet, settings, main, onFinished)
            }
        }
        refreshControls(inventory, holder)
    }

    private fun animateSpin(
        player: Player,
        holder: SlotsInventoryHolder,
        inventory: Inventory,
        bet: Double,
        settings: CasinoGameSettings,
        main: CasinoMainSettings,
        onFinished: (Double, CasinoRound) -> Unit,
    ) {
        val finalReels = if (CasinoConfig.shouldSteerToHouseWin(this, settings.copy(targetHouseWinRate = settings.targetHouseWinRate.coerceIn(main.edgeMin, main.edgeMax)))) {
            listOf("cherry", "bar", "bell")
        } else {
            List(3) { symbols.random() }
        }

        val spinTask = object : Runnable {
            var ticks = 0
            override fun run() {
                if (!player.isOnline || player.openInventory.topInventory.holder != holder) return
                ticks++
                val preview = List(3) { reel ->
                    val idx = (ticks + (reel * 2)) % oreValueSymbols.size
                    oreValueSymbols[idx]
                }
                renderReelRow(inventory, preview)
                player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.8f)

                if (ticks >= 28) {
                    renderReelRow(inventory, finalReels)
                    val round = toRound(finalReels, bet)
                    playResultSounds(player, round)
                    holder.spinning = false
                    refreshControls(inventory, holder)
                    onFinished(bet, round)
                    return
                }
                Bukkit.getScheduler().runTaskLater(dev.lizainslie.cafemc.CafeMC.instance, this, 2L)
            }
        }
        Bukkit.getScheduler().runTaskLater(dev.lizainslie.cafemc.CafeMC.instance, spinTask, 2L)
    }

    private fun refreshControls(inventory: Inventory, holder: SlotsInventoryHolder) {
        inventory.setItem(BET_MINUS_100, ItemStack(Material.RED_STAINED_GLASS_PANE))
        inventory.setItem(BET_MINUS_10, ItemStack(Material.ORANGE_STAINED_GLASS_PANE))
        inventory.setItem(BET_PLUS_10, ItemStack(Material.LIME_STAINED_GLASS_PANE))
        inventory.setItem(BET_PLUS_100, ItemStack(Material.GREEN_STAINED_GLASS_PANE))
        inventory.setItem(BET_REPEAT, ItemStack(Material.CLOCK))
        inventory.setItem(BET_SPIN, ItemStack(Material.LEVER))
        inventory.setItem(
            BET_DISPLAY,
            ItemStack(Material.GOLD_INGOT).apply {
                itemMeta = itemMeta?.apply {
                    setDisplayName("Bet: ${holder.bet.toCleanString()}")
                    lore = listOf("Last: ${holder.lastBet.toCleanString()}", if (holder.spinning) "Spinning..." else "Ready")
                }
            }
        )
    }

    private fun renderReelRow(inventory: Inventory, reels: List<String>) {
        val rowSlots = listOf(22, 23, 24)
        rowSlots.forEachIndexed { index, slot ->
            val symbol = reels[index]
            inventory.setItem(slot, ItemStack(symbolToMaterial(symbol)))
        }
    }

    private fun decorateFrame(inventory: Inventory) {
        val frame = ItemStack(Material.BLACK_STAINED_GLASS_PANE)
        val accent = ItemStack(Material.YELLOW_STAINED_GLASS_PANE)
        for (slot in 0 until inventory.size) inventory.setItem(slot, frame)
        listOf(21, 25, 13, 31, 4, 49).forEach { inventory.setItem(it, accent) }
    }

    private fun toRound(reels: List<String>, bet: Double): CasinoRound {
        val counts = reels.groupingBy { it }.eachCount()
        val multiplier = when {
            counts["diamond"] == 3 -> 10.0
            counts.values.any { it == 3 } -> 5.0
            counts.values.any { it == 2 } -> 2.0
            else -> 0.0
        }
        return CasinoRound(
            summary = reels.joinToString(" | ") { it.replaceFirstChar { char -> char.uppercase() } },
            payout = bet * multiplier,
        )
    }

    private fun playResultSounds(player: Player, round: CasinoRound) {
        if (round.payout <= 0.0) {
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 0.7f, 0.8f)
            return
        }
        if (round.payout > 0.0) {
            player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f)
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 0.9f, 1.6f)
        }
    }

    private fun symbolToMaterial(symbol: String): Material = when (symbol) {
        "cherry" -> Material.APPLE
        "bar" -> Material.IRON_INGOT
        "bell" -> Material.BELL
        "seven" -> Material.REDSTONE
        "diamond" -> Material.DIAMOND
        "coal" -> Material.COAL
        "copper" -> Material.RAW_COPPER
        "iron" -> Material.RAW_IRON
        "gold" -> Material.RAW_GOLD
        "emerald" -> Material.EMERALD
        "netherite" -> Material.NETHERITE_INGOT
        else -> Material.COAL
    }

    class SlotsInventoryHolder(val playerId: UUID) : InventoryHolder {
        var bet: Double = 10.0
        var lastBet: Double = 0.0
        var spinning: Boolean = false
        constructor(playerId: UUID, initialBet: Double) : this(playerId) {
            bet = initialBet
            lastBet = initialBet
        }
        lateinit var inventoryRef: Inventory
        override fun getInventory(): Inventory = inventoryRef
    }

    private fun normalizeBet(value: Double, maxBet: Double): Double = floor(value.coerceIn(0.01, maxBet) * 100) / 100
    private fun Double.toCleanString(): String = toString().trimEnd('0').trimEnd('.')

    const val BET_MINUS_100 = 37
    const val BET_MINUS_10 = 38
    const val BET_DISPLAY = 40
    const val BET_PLUS_10 = 42
    const val BET_PLUS_100 = 43
    const val BET_REPEAT = 49
    const val BET_SPIN = 53

    private val symbols = listOf("cherry", "cherry", "bar", "bar", "bell", "seven", "diamond")
    private val oreValueSymbols = listOf("coal", "copper", "iron", "gold", "emerald", "diamond", "netherite")
}
