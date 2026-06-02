package dev.lizainslie.cafemc.casino.games

import dev.lizainslie.cafemc.casino.CasinoCategory
import dev.lizainslie.cafemc.casino.CasinoConfig
import dev.lizainslie.cafemc.casino.CasinoGame
import dev.lizainslie.cafemc.casino.CasinoGameSettings
import dev.lizainslie.cafemc.chat.component
import dev.lizainslie.cafemc.casino.CasinoRound
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.components.CustomModelDataComponent
import java.util.UUID

object KenoGame : CasinoGame {
    override val id = "keno"
    override val displayName = "Keno"
    override val category = CasinoCategory.RISK
    override val icon = Material.EMERALD
    override val description = "Pick 3 numbers from 1-20."
    override val choiceUsage = "<n,n,n>"
    override val choices = (1..20).map { it.toString() }

    override fun play(player: Player, bet: Double, args: List<String>, settings: CasinoGameSettings): CasinoRound {
        val picks = args.joinToString(" ")
            .split(",", " ")
            .mapNotNull { it.toIntOrNull() }
            .filter { it in 1..20 }
            .distinct()
            .take(3)
            .ifEmpty { listOf(1, 2, 3) }
        val draw = if (CasinoConfig.shouldSteerToHouseWin(this, settings)) {
            (1..20).filter { it !in picks }.shuffled().take(5)
        } else {
            (1..20).shuffled().take(5)
        }
        val hits = picks.count { it in draw }
        val multiplier = when (hits) {
            3 -> 12.0
            2 -> 3.0
            1 -> 1.25
            else -> 0.0
        }
        return CasinoRound(
            summary = "Picked ${picks.joinToString()}. Draw: ${draw.joinToString()}. Hits: $hits.",
            payout = bet * multiplier
        )
    }

    fun openInteractive(player: Player, initialBet: Double) {
        val holder = KenoInventoryHolder(player.uniqueId, initialBet)
        val inventory = Bukkit.createInventory(holder, 54, component {
            text("Keno") { color = NamedTextColor.GREEN }
        })
        holder.inventoryRef = inventory
        renderBoard(inventory, holder)
        player.openInventory(inventory)
    }

    fun handleClick(
        player: Player,
        holder: KenoInventoryHolder,
        slot: Int,
        canRunBet: (Double) -> Boolean,
        onSettled: (Double, CasinoRound) -> Unit,
    ) {
        if (holder.locked) return
        when (slot) {
            BET_MINUS -> holder.bet = (holder.bet - 10.0).coerceAtLeast(1.0)
            BET_PLUS -> holder.bet = (holder.bet + 10.0).coerceAtMost(10_000.0)
            CLEAR_BUTTON -> {
                holder.picks.clear()
                holder.lastDraw = emptySet()
                player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.7f, 1.4f)
                renderBoard(holder.inventoryRef, holder)
                return
            }
            DRAW_BUTTON -> {
                if (holder.picks.size != 3) {
                    player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 0.7f, 0.8f)
                    return
                }
                if (!canRunBet(holder.bet)) return
                holder.locked = true
                val draw = (1..20).shuffled().take(5)
                holder.lastDraw = emptySet()
                renderBoard(holder.inventoryRef, holder)
                animateDraw(player, holder, draw, onSettled)
                return
            }
        }

        val number = slotToNumber[slot]
        if (number != null) {
            if (number in holder.picks) holder.picks.remove(number)
            else if (holder.picks.size < 3) holder.picks += number
            player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.6f, 1.6f)
        }
        renderBoard(holder.inventoryRef, holder)
    }

    private fun animateDraw(
        player: Player,
        holder: KenoInventoryHolder,
        draw: List<Int>,
        onSettled: (Double, CasinoRound) -> Unit,
    ) {
        val drawSet = mutableSetOf<Int>()
        fun step(index: Int) {
            if (!player.isOnline || player.openInventory.topInventory.holder != holder) return
            if (index >= draw.size) {
                dev.lizainslie.cafemc.CafeMC.instance.logger.info("keno draw done n ${draw.joinToString(",")}")
                val hits = holder.picks.count { it in drawSet }
                val multiplier = when (hits) {
                    3 -> 12.0
                    2 -> 3.0
                    1 -> 1.25
                    else -> 0.0
                }
                val round = CasinoRound(
                    summary = "Picked ${holder.picks.sorted().joinToString()}. Draw: ${draw.joinToString()}. Hits: $hits.",
                    payout = holder.bet * multiplier,
                )
                holder.locked = false
                renderBoard(holder.inventoryRef, holder)
                onSettled(holder.bet, round)
                return
            }
            drawSet += draw[index]
            dev.lizainslie.cafemc.CafeMC.instance.logger.info("keno draw step n ${draw[index]}")
            holder.lastDraw = drawSet.toSet()
            renderBoard(holder.inventoryRef, holder)
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 1.8f)
            Bukkit.getScheduler().runTaskLater(dev.lizainslie.cafemc.CafeMC.instance, Runnable { step(index + 1) }, 8L)
        }
        step(0)
    }

    private fun renderBoard(inventory: Inventory, holder: KenoInventoryHolder) {
        for (slot in 0 until inventory.size) inventory.setItem(slot, ItemStack(Material.BLACK_STAINED_GLASS_PANE))

        // top row: x x x [pick1] [pick2] [pick3] x x x
        for (slot in listOf(3, 4, 5)) {
            inventory.setItem(slot, ItemStack(Material.GRAY_STAINED_GLASS_PANE).apply {
                itemMeta = itemMeta?.apply { setDisplayName("-") }
            })
        }
        holder.picks.sorted().forEachIndexed { idx, number ->
            val slot = 3 + idx
            inventory.setItem(slot, kenoNumberItem(number, "selected"))
        }

        // row 2 empty spacer
        for (col in 0..8) inventory.setItem(9 + col, ItemStack(Material.AIR))

        slotToNumber.forEach { (slot, number) ->
            val hit = number in holder.lastDraw
            val picked = number in holder.picks
            val state = when {
                holder.lastDraw.isNotEmpty() && picked && !hit -> "miss"
                hit -> "hit"
                picked -> "selected"
                else -> "neutral"
            }
            inventory.setItem(slot, kenoNumberItem(number, state))
        }

        inventory.setItem(BET_MINUS, ItemStack(Material.RED_STAINED_GLASS_PANE).apply {
            itemMeta = itemMeta?.apply { setDisplayName("-10") }
        })
        inventory.setItem(BET_PLUS, ItemStack(Material.GREEN_STAINED_GLASS_PANE).apply {
            itemMeta = itemMeta?.apply { setDisplayName("+10") }
        })
        inventory.setItem(BET_DISPLAY, ItemStack(Material.GOLD_INGOT).apply {
            itemMeta = itemMeta?.apply { setDisplayName("Bet: ${holder.bet.toInt()}") }
        })
        inventory.setItem(DRAW_BUTTON, ItemStack(Material.EMERALD_BLOCK).apply {
            itemMeta = itemMeta?.apply { setDisplayName(if (holder.locked) "Drawing..." else "Draw (${holder.picks.size}/3)") }
        })
        inventory.setItem(CLEAR_BUTTON, ItemStack(Material.BARRIER).apply {
            itemMeta = itemMeta?.apply { setDisplayName("Clear Picks") }
        })
    }

    private fun kenoNumberItem(number: Int, state: String): ItemStack {
        val cmd = when (state) {
            "selected" -> BASE_CMD + 100 + number
            "hit" -> BASE_CMD + 200 + number
            "miss" -> BASE_CMD + 300 + number
            else -> BASE_CMD + number
        }
        return ItemStack(Material.PAPER).apply {
            itemMeta = itemMeta?.apply {
                setCustomModelData(cmd)
                val modelComponent: CustomModelDataComponent = customModelDataComponent
                modelComponent.floats = listOf(cmd.toFloat())
                setCustomModelDataComponent(modelComponent)
                setDisplayName(number.toString())
                lore = listOf(state.replaceFirstChar { it.uppercase() })
            }
        }
    }

    class KenoInventoryHolder(
        val playerId: UUID,
        initialBet: Double,
    ) : InventoryHolder {
        var bet: Double = initialBet
        val picks = mutableSetOf<Int>()
        var locked = false
        var lastDraw: Set<Int> = emptySet()
        lateinit var inventoryRef: Inventory
        override fun getInventory(): Inventory = inventoryRef
    }

    // boxed field with 1-column side borders and 5x4 number grid
    private val numberSlots = listOf(
        20, 21, 22, 23, 24,
        29, 30, 31, 32, 33,
        38, 39, 40, 41, 42,
        47, 48, 49, 50, 51,
    )
    private val slotToNumber = numberSlots.withIndex().associate { (index, slot) -> slot to (index + 1) }
    private const val BASE_CMD = 91000
    const val BET_MINUS = 43
    const val BET_DISPLAY = 52
    const val BET_PLUS = 44
    const val CLEAR_BUTTON = 45
    const val DRAW_BUTTON = 53
}
