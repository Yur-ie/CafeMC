package dev.lizainslie.cafemc.chat.commands

import dev.lizainslie.cafemc.chat.ChatUtil
import dev.lizainslie.cafemc.chat.component
import dev.lizainslie.cafemc.chat.data.PrivateMessage
import dev.lizainslie.cafemc.chat.nicknameOrDisplayName
import dev.lizainslie.cafemc.chat.sendError
import dev.lizainslie.cafemc.chat.sendRichMessage
import dev.lizainslie.cafemc.chat.toPlainText
import dev.lizainslie.cafemc.core.cmd.AllowedSender
import dev.lizainslie.cafemc.core.cmd.CommandContext
import dev.lizainslie.cafemc.core.cmd.PluginCommand
import dev.lizainslie.cafemc.core.modules.OnlinePlayerCacheModule
import dev.lizainslie.cafemc.data.player.PlayerSettings
import dev.lizainslie.cafemc.util.AccountUtils
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID
import kotlin.math.ceil

object MessageCommand : PluginCommand(
    command = "msg",
    usage = "<player> <message>",
    permission = "cafe.msg",
    minArgs = 2,
    maxArgs = -1,
    allowedSender = AllowedSender.PLAYER,
) {
    override fun CommandContext.onCommand() {
        val message = args.drop(1).joinToString(" ")

        when (val target = resolveTarget(player, args[0])) {
            is TargetResolution.Found -> sendPrivateMessage(player, target.player, message)
            is TargetResolution.Ambiguous -> openRecipientPicker(player, target.players, message)
            TargetResolution.NotFound -> {
                val realNameTarget = AccountUtils.getUuidForAccountName(args[0])?.let { Bukkit.getOfflinePlayer(it) }
                    ?: return sendError("Player not found")
                sendPrivateMessage(player, realNameTarget, message)
            }
        }
    }

    override fun CommandContext.tabComplete(): List<String> {
        val playerNames = Bukkit.getOnlinePlayers().mapNotNull { OnlinePlayerCacheModule[it.uniqueId]?.realName }
        return when (args.size) {
            0 -> playerNames
            1 -> playerNames.filter { it.startsWith(args[0], ignoreCase = true) }
            else -> emptyList()
        }
    }

    fun onRecipientPickerClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? MessageRecipientInventoryHolder ?: return

        event.isCancelled = true

        val player = event.whoClicked as? Player ?: return
        if (player.uniqueId != holder.senderId || event.clickedInventory != event.view.topInventory) return

        val recipientId = holder.recipients.getOrNull(event.slot) ?: return
        player.closeInventory()
        sendPrivateMessage(player, Bukkit.getOfflinePlayer(recipientId), holder.message)
    }

    fun getReceivedMessage(sender: OfflinePlayer, message: String): Component = component {
        text("[MSG] ") { color = NamedTextColor.DARK_GRAY }
        component(sender.nicknameOrDisplayName(NamedTextColor.GOLD))
        text(" -> you: ") { color = NamedTextColor.GRAY }
        component(ChatUtil.translateAmpersand(message))
    }

    fun getSentMessage(recipient: OfflinePlayer, message: String): Component = component {
        text("[MSG] ") { color = NamedTextColor.DARK_GRAY }
        text("you") { color = NamedTextColor.GOLD }
        text(" -> ") { color = NamedTextColor.GRAY }
        component(recipient.nicknameOrDisplayName(NamedTextColor.GOLD))
        text(": ") { color = NamedTextColor.GRAY }
        component(ChatUtil.translateAmpersand(message))
    }

    private fun getReceivedMessage(sender: Player, message: String): Component = getReceivedMessage(sender as OfflinePlayer, message)

    private fun resolveTarget(sender: Player, input: String): TargetResolution {
        Bukkit.getOnlinePlayers()
            .firstOrNull { OnlinePlayerCacheModule[it.uniqueId]?.realName?.equals(input, ignoreCase = true) == true }
            ?.let { return TargetResolution.Found(it) }

        val nicknameMatches = findNicknameMatches(input).filter { it.uniqueId != sender.uniqueId }

        return when (nicknameMatches.size) {
            0 -> TargetResolution.NotFound
            1 -> TargetResolution.Found(nicknameMatches.single())
            else -> {
                val historyMatches = transaction {
                    val partners = PrivateMessage.getConversationPartnerIds(sender.uniqueId)
                    nicknameMatches.filter { it.uniqueId in partners }
                }

                if (historyMatches.size == 1) {
                    TargetResolution.Found(historyMatches.single())
                } else {
                    TargetResolution.Ambiguous(nicknameMatches)
                }
            }
        }
    }

    private fun findNicknameMatches(input: String): List<OfflinePlayer> = transaction {
        PlayerSettings.all().filter { settings ->
            settings.nickname?.let {
                ChatUtil.translateAmpersand(it).toPlainText().equals(input, ignoreCase = true)
            } ?: false
        }.map { Bukkit.getOfflinePlayer(it.id.value) }
    }

    private fun sendPrivateMessage(sender: Player, recipient: OfflinePlayer, message: String) {
        if (recipient.uniqueId == sender.uniqueId) {
            sender.sendError("You cannot message yourself.")
            return
        }

        val recipientPlayer = Bukkit.getPlayer(recipient.uniqueId)

        transaction {
            PrivateMessage.create(
                senderId = sender.uniqueId,
                recipientId = recipient.uniqueId,
                message = message,
                delivered = recipientPlayer != null
            )
        }

        sender.sendMessage(getSentMessage(recipient, message))

        recipientPlayer?.sendMessage(getReceivedMessage(sender, message)) ?: sender.sendRichMessage {
            text("That player is offline. They will receive your message when they join.") {
                color = NamedTextColor.GRAY
            }
        }
    }

    private fun openRecipientPicker(sender: Player, recipients: List<OfflinePlayer>, message: String) {
        val holder = MessageRecipientInventoryHolder(
            senderId = sender.uniqueId,
            message = message,
            recipients = recipients.map { it.uniqueId }
        )
        val size = ceil(recipients.size / 9.0).toInt().coerceIn(1, 6) * 9
        val inventory = Bukkit.createInventory(holder, size, component {
            text("Choose a recipient") { color = NamedTextColor.GOLD }
        })
        holder.inventoryRef = inventory

        recipients.take(size).forEachIndexed { index, recipient ->
            inventory.setItem(index, createRecipientHead(recipient))
        }

        sender.openInventory(inventory)
    }

    private fun createRecipientHead(player: OfflinePlayer) = ItemStack(Material.PLAYER_HEAD).apply {
        itemMeta = (itemMeta as SkullMeta).apply {
            owningPlayer = player
            displayName(player.nicknameOrDisplayName(NamedTextColor.GOLD))
            lore(listOf(component {
                text("Username: ") { color = NamedTextColor.GRAY }
                text(player.name ?: "Unknown") { color = NamedTextColor.AQUA }
            }))
        }
    }

    private sealed interface TargetResolution {
        data class Found(val player: OfflinePlayer) : TargetResolution
        data class Ambiguous(val players: List<OfflinePlayer>) : TargetResolution
        data object NotFound : TargetResolution
    }

    class MessageRecipientInventoryHolder(
        val senderId: UUID,
        val message: String,
        val recipients: List<UUID>,
    ) : InventoryHolder {
        lateinit var inventoryRef: Inventory

        override fun getInventory(): Inventory = inventoryRef
    }
}
