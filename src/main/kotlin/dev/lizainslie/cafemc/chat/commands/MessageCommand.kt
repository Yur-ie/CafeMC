package dev.lizainslie.cafemc.chat.commands

import dev.lizainslie.cafemc.chat.ChatUtil
import dev.lizainslie.cafemc.chat.MailDebug
import dev.lizainslie.cafemc.chat.component
import dev.lizainslie.cafemc.chat.data.MailMessage
import dev.lizainslie.cafemc.chat.data.PrivateMessage
import dev.lizainslie.cafemc.chat.nicknameOrDisplayName
import dev.lizainslie.cafemc.chat.sendError
import dev.lizainslie.cafemc.chat.sendRichMessage
import dev.lizainslie.cafemc.chat.toPlainText
import dev.lizainslie.cafemc.CafeMC
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
import kotlin.time.Duration.Companion.seconds
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
    private const val MAX_MESSAGE_LENGTH = 256
    private const val MAX_UNDELIVERED_MESSAGES_PER_RECIPIENT = 10
    private const val NICKNAME_CACHE_TTL_MS = 30_000L
    private val sendCooldowns = mutableMapOf<UUID, Long>()
    private var nicknameCache = emptyList<NicknameCacheEntry>()
    private var nicknameCacheLoadedAt = 0L

    override fun CommandContext.onCommand() {
        MailDebug.log("msg send 1")
        MailDebug.log("msg args n ${args.size}")
        val message = args.drop(1).joinToString(" ")
        MailDebug.log("msg chars n ${message.length}")
        val valid = validateMessage(player, message)
        MailDebug.log("msg valid $valid")
        if (!valid) {
            MailDebug.log("msg stop valid")
            return
        }

        when (val target = resolveTarget(player, args[0])) {
            is TargetResolution.Found -> {
                MailDebug.log("msg find ok")
                sendPrivateMessage(player, target.player, message)
            }
            is TargetResolution.Ambiguous -> {
                MailDebug.log("msg find many")
                openRecipientPicker(player, target.players, message)
            }
            TargetResolution.NotFound -> {
                MailDebug.log("msg find api")
                lookupOfflineTargetAsync(player, args[0], message)
            }
        }
    }

    override fun CommandContext.tabComplete(): List<String> {
        MailDebug.log("msg tab 1")
        MailDebug.log("msg tab args ${args.size}")
        val playerNames = Bukkit.getOnlinePlayers().mapNotNull { OnlinePlayerCacheModule[it.uniqueId]?.realName }
        MailDebug.log("msg tab players ${playerNames.size}")
        return when (args.size) {
            0 -> {
                MailDebug.log("msg tab all")
                playerNames
            }
            1 -> {
                val matches = playerNames.filter { it.startsWith(args[0], ignoreCase = true) }
                MailDebug.log("msg tab n ${matches.size}")
                matches
            }
            else -> {
                MailDebug.log("msg tab none")
                emptyList()
            }
        }
    }

    fun onRecipientPickerClick(event: InventoryClickEvent) {
        MailDebug.log("msg click 1")
        val holder = event.view.topInventory.holder as? MessageRecipientInventoryHolder
        MailDebug.log("msg holder ${holder != null}")
        if (holder == null) {
            MailDebug.log("msg click nohold")
            return
        }
        MailDebug.log("msg click holder")

        event.isCancelled = true

        val player = event.whoClicked as? Player
        MailDebug.log("msg player ${player != null}")
        if (player == null) {
            MailDebug.log("msg click noplr")
            return
        }
        MailDebug.log("msg click player")
        val senderMatches = player.uniqueId == holder.senderId
        val topClicked = event.clickedInventory == event.view.topInventory
        MailDebug.log("msg click sender $senderMatches")
        MailDebug.log("msg click top $topClicked")
        if (!senderMatches || !topClicked) {
            MailDebug.log("msg click stop")
            return
        }

        MailDebug.log("msg click slot ${event.slot}")
        val recipientId = holder.recipients.getOrNull(event.slot)
        if (recipientId == null) {
            MailDebug.log("msg click empty")
            return
        }
        MailDebug.log("msg confirm 1")
        player.closeInventory()
        sendPrivateMessage(player, Bukkit.getOfflinePlayer(recipientId), holder.message)
    }

    fun clearCooldown(player: Player) {
        MailDebug.log("msg cool clear")
        sendCooldowns.remove(player.uniqueId)
    }

    fun invalidateNicknameCache() {
        MailDebug.log("msg nick inv")
        nicknameCacheLoadedAt = 0L
        nicknameCache = emptyList()
    }

    fun getReceivedMessage(sender: OfflinePlayer, message: String): Component = component {
        MailDebug.log("msg comp recv")
        text("[MSG] ") { color = NamedTextColor.DARK_GRAY }
        component(sender.nicknameOrDisplayName(NamedTextColor.GOLD))
        text(" -> you: ") { color = NamedTextColor.GRAY }
        component(ChatUtil.translateAmpersand(message))
    }

    fun getSentMessage(recipient: OfflinePlayer, message: String): Component = component {
        MailDebug.log("msg comp sent")
        text("[MSG] ") { color = NamedTextColor.DARK_GRAY }
        text("you") { color = NamedTextColor.GOLD }
        text(" -> ") { color = NamedTextColor.GRAY }
        component(recipient.nicknameOrDisplayName(NamedTextColor.GOLD))
        text(": ") { color = NamedTextColor.GRAY }
        component(ChatUtil.translateAmpersand(message))
    }

    private fun getReceivedMessage(sender: Player, message: String): Component = getReceivedMessage(sender as OfflinePlayer, message)

    fun getUnreadNotification(count: Long): Component = component {
        MailDebug.log("mail note 1")
        MailDebug.log("mail note $count")
        text("[MAIL] ") { color = NamedTextColor.DARK_GRAY }
        text("You have ") { color = NamedTextColor.GRAY }
        text(count.toString()) { color = NamedTextColor.GOLD }
        text(" unread mail message") { color = NamedTextColor.GRAY }
        if (count != 1L) text("s") { color = NamedTextColor.GRAY }
        text(". Run ") { color = NamedTextColor.GRAY }
        text("/mail") { color = NamedTextColor.GOLD }
        text(" to view.") { color = NamedTextColor.GRAY }
    }

    private fun resolveTarget(sender: Player, input: String): TargetResolution {
        MailDebug.log("msg find 1")
        MailDebug.log("msg input n ${input.length}")
        val onlineTarget = Bukkit.getOnlinePlayers()
            .firstOrNull { OnlinePlayerCacheModule[it.uniqueId]?.realName?.equals(input, ignoreCase = true) == true }
        MailDebug.log("msg online ${onlineTarget != null}")
        onlineTarget?.let {
            MailDebug.log("msg online ret")
            return TargetResolution.Found(it)
        }

        val nicknameMatches = findNicknameMatches(input).filter { it.uniqueId != sender.uniqueId }
        MailDebug.log("msg nick n ${nicknameMatches.size}")

        return when (nicknameMatches.size) {
            0 -> {
                MailDebug.log("msg nick zero")
                TargetResolution.NotFound
            }
            1 -> {
                MailDebug.log("msg nick one")
                TargetResolution.Found(nicknameMatches.single())
            }
            else -> {
                MailDebug.log("msg nick many")
                val historyMatches = transaction {
                    MailDebug.log("msg hist db")
                    val partners = PrivateMessage.getConversationPartnerIds(sender.uniqueId)
                    MailDebug.log("msg histp ${partners.size}")
                    nicknameMatches.filter { it.uniqueId in partners }
                }
                MailDebug.log("msg hist n ${historyMatches.size}")

                if (historyMatches.size == 1) {
                    MailDebug.log("msg hist one")
                    TargetResolution.Found(historyMatches.single())
                } else {
                    MailDebug.log("msg hist many")
                    TargetResolution.Ambiguous(nicknameMatches)
                }
            }
        }
    }

    private fun findNicknameMatches(input: String): List<OfflinePlayer> {
        MailDebug.log("msg nick 1")
        val cache = getNicknameCache()
        val matches = cache.filter { it.nickname.equals(input, ignoreCase = true) }
            .map { Bukkit.getOfflinePlayer(it.playerId) }
        MailDebug.log("msg nickc n ${cache.size}")
        MailDebug.log("msg nickr n ${matches.size}")
        return matches
    }

    private fun getNicknameCache(): List<NicknameCacheEntry> {
        MailDebug.log("msg nick cache")
        val now = System.currentTimeMillis()
        val fresh = now - nicknameCacheLoadedAt < NICKNAME_CACHE_TTL_MS
        MailDebug.log("msg nick fresh $fresh")
        if (fresh) return nicknameCache

        nicknameCache = transaction {
            MailDebug.log("msg nick load")
            PlayerSettings.all().mapNotNull { settings ->
                settings.nickname?.let {
                    NicknameCacheEntry(
                        playerId = settings.id.value,
                        nickname = ChatUtil.translateAmpersand(it).toPlainText()
                    )
                }
            }
        }
        nicknameCacheLoadedAt = now
        MailDebug.log("msg nick loaded ${nicknameCache.size}")
        return nicknameCache
    }

    private fun lookupOfflineTargetAsync(sender: Player, input: String, message: String) {
        MailDebug.log("msg api async")
        val senderId = sender.uniqueId
        Bukkit.getScheduler().runTaskAsynchronously(CafeMC.instance, Runnable {
            MailDebug.log("msg api 1")
            val uuid = AccountUtils.getUuidForAccountName(input)
            Bukkit.getScheduler().runTask(CafeMC.instance, Runnable {
                MailDebug.log("msg api main")
                val currentSender = Bukkit.getPlayer(senderId)
                MailDebug.log("msg api sender ${currentSender != null}")
                if (currentSender == null) {
                    MailDebug.log("msg api gone")
                    return@Runnable
                }

                if (uuid == null) {
                    MailDebug.log("msg api none")
                    currentSender.sendError("Player not found")
                    return@Runnable
                }

                MailDebug.log("msg api ok")
                sendPrivateMessage(currentSender, Bukkit.getOfflinePlayer(uuid), message)
            })
        })
    }

    private fun sendPrivateMessage(sender: Player, recipient: OfflinePlayer, message: String) {
        MailDebug.log("msg send 2")
        val self = recipient.uniqueId == sender.uniqueId
        MailDebug.log("msg self $self")
        if (self) {
            MailDebug.log("msg self 1")
            sender.sendError("You cannot message yourself.")
            return
        }

        val recipientPlayer = Bukkit.getPlayer(recipient.uniqueId)
        MailDebug.log(if (recipientPlayer == null) "msg off 1" else "msg on 1")
        val pendingMessages = transaction {
            MailMessage.countUnreadFromSenderToRecipient(sender.uniqueId, recipient.uniqueId)
        }
        MailDebug.log("msg pend n $pendingMessages")

        if (pendingMessages >= MAX_UNDELIVERED_MESSAGES_PER_RECIPIENT) {
            MailDebug.log("msg spam 1")
            MailDebug.log("msg spam true")
            sender.sendError("Wait for this player to read or clear their mail before sending more.")
            return
        }
        MailDebug.log("msg spam false")

        transaction {
            MailDebug.log("msg db 1")
            PrivateMessage.create(
                senderId = sender.uniqueId,
                recipientId = recipient.uniqueId,
                message = message,
                delivered = true
            )

            if (recipientPlayer == null) {
                MailDebug.log("mail save 1")
                MailMessage.create(sender, recipient, message)
            } else {
                MailDebug.log("mail save skip")
            }
        }
        MailDebug.log("msg db 2")

        sender.sendMessage(getSentMessage(recipient, message))
        MailDebug.log("msg sent ack")

        recipientPlayer?.sendMessage(getReceivedMessage(sender, message)) ?: sender.sendRichMessage {
            MailDebug.log("mail save 2")
            text("That player is offline. They will receive your message when they join.") {
                color = NamedTextColor.GRAY
            }
        }
        MailDebug.log("msg send end")
    }

    private fun validateMessage(sender: Player, message: String): Boolean {
        MailDebug.log("msg valid 1")
        val blank = message.isBlank()
        MailDebug.log("msg blank $blank")
        if (blank) {
            MailDebug.log("msg blank 1")
            sender.sendError("Message cannot be empty.")
            return false
        }

        val tooLong = message.length > MAX_MESSAGE_LENGTH
        MailDebug.log("msg long $tooLong")
        if (tooLong) {
            MailDebug.log("msg long 1")
            sender.sendError("Message is too long. Keep it under $MAX_MESSAGE_LENGTH characters.")
            return false
        }

        val now = System.currentTimeMillis()
        val lastSent = sendCooldowns[sender.uniqueId]
        val cooledDown = lastSent == null || now - lastSent >= 5.seconds.inWholeMilliseconds
        MailDebug.log("msg cool $cooledDown")
        if (!cooledDown) {
            MailDebug.log("msg slow 1")
            sender.sendError("Slow down before sending another message.")
            return false
        }

        sendCooldowns[sender.uniqueId] = now
        MailDebug.log("msg valid ok")
        return true
    }

    private fun openRecipientPicker(sender: Player, recipients: List<OfflinePlayer>, message: String) {
        MailDebug.log("msg inv 1")
        MailDebug.log("msg inv n ${recipients.size}")
        val holder = MessageRecipientInventoryHolder(
            senderId = sender.uniqueId,
            message = message,
            recipients = recipients.map { it.uniqueId }
        )
        val size = ceil(recipients.size / 9.0).toInt().coerceIn(1, 6) * 9
        MailDebug.log("msg invsize $size")
        val inventory = Bukkit.createInventory(holder, size, component {
            text("Choose a recipient") { color = NamedTextColor.GOLD }
        })
        holder.inventoryRef = inventory

        recipients.take(size).forEachIndexed { index, recipient ->
            MailDebug.log("msg invslot $index")
            inventory.setItem(index, createRecipientHead(recipient))
        }

        sender.openInventory(inventory)
        MailDebug.log("msg inv open")
    }

    private fun createRecipientHead(player: OfflinePlayer) = ItemStack(Material.PLAYER_HEAD).apply {
        MailDebug.log("msg head 1")
        itemMeta = (itemMeta as SkullMeta).apply {
            owningPlayer = player
            displayName(player.nicknameOrDisplayName(NamedTextColor.GOLD))
            lore(listOf(component {
                text("Username: ") { color = NamedTextColor.GRAY }
                text(player.name ?: "Unknown (${player.uniqueId.toString().take(8)})") { color = NamedTextColor.AQUA }
            }))
        }
    }

    private sealed interface TargetResolution {
        data class Found(val player: OfflinePlayer) : TargetResolution
        data class Ambiguous(val players: List<OfflinePlayer>) : TargetResolution
        data object NotFound : TargetResolution
    }

    private data class NicknameCacheEntry(
        val playerId: UUID,
        val nickname: String,
    )

    class MessageRecipientInventoryHolder(
        val senderId: UUID,
        val message: String,
        val recipients: List<UUID>,
    ) : InventoryHolder {
        lateinit var inventoryRef: Inventory

        override fun getInventory(): Inventory = inventoryRef
    }
}
