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
            DRAW_BUTTON -> {
                if (holder.picks.size != 3) {
                    player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 0.7f, 0.8f)
                    return
                }
                if (!canRunBet(holder.bet)) return
                holder.locked = true
                val draw = (1..20).shuffled().take(5)
                val hits = holder.picks.count { it in draw }
                val multiplier = when (hits) {
                    3 -> 12.0
                    2 -> 3.0
                    1 -> 1.25
                    else -> 0.0
                }
                holder.lastDraw = draw.toSet()
                renderBoard(holder.inventoryRef, holder)
                player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.4f)
                val round = CasinoRound(
                    summary = "Picked ${holder.picks.sorted().joinToString()}. Draw: ${draw.joinToString()}. Hits: $hits.",
                    payout = holder.bet * multiplier,
                )
                onSettled(holder.bet, round)
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

    private fun renderBoard(inventory: Inventory, holder: KenoInventoryHolder) {
        val border = ItemStack(Material.BLACK_STAINED_GLASS_PANE)
        for (slot in 0 until inventory.size) inventory.setItem(slot, border)

        slotToNumber.forEach { (slot, number) ->
            val hit = number in holder.lastDraw
            val picked = number in holder.picks
            val mat = when {
                hit && picked -> Material.LIME_STAINED_GLASS_PANE
                hit -> Material.GREEN_STAINED_GLASS_PANE
                picked -> Material.YELLOW_STAINED_GLASS_PANE
                else -> Material.LIGHT_GRAY_STAINED_GLASS_PANE
            }
            inventory.setItem(slot, ItemStack(mat).apply {
                itemMeta = itemMeta?.apply { setDisplayName(number.toString()) }
            })
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
            itemMeta = itemMeta?.apply { setDisplayName(if (holder.locked) "Finished" else "Draw (${holder.picks.size}/3)") }
        })
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

    private val numberSlots = listOf(
        10, 11, 12, 13, 14,
        19, 20, 21, 22, 23,
        28, 29, 30, 31, 32,
        37, 38, 39, 40, 41,
    )
    private val slotToNumber = numberSlots.withIndex().associate { (index, slot) -> slot to (index + 1) }
    const val BET_MINUS = 47
    const val BET_DISPLAY = 49
    const val BET_PLUS = 51
    const val DRAW_BUTTON = 53
}
