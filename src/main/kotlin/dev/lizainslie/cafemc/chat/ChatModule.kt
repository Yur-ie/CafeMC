package dev.lizainslie.cafemc.chat

import dev.lizainslie.cafemc.afk.AfkModule
import dev.lizainslie.cafemc.chat.MailDebug
import dev.lizainslie.cafemc.chat.commands.FavoriteMailCommand
import dev.lizainslie.cafemc.chat.commands.MailCommand
import dev.lizainslie.cafemc.chat.commands.MessageCommand
import dev.lizainslie.cafemc.chat.commands.NicknameCommand
import dev.lizainslie.cafemc.chat.commands.TestComponentCommand
import dev.lizainslie.cafemc.chat.commands.UnfavoriteMailCommand
import dev.lizainslie.cafemc.chat.data.MailMessage
import dev.lizainslie.cafemc.chat.nms.NicknameUtil
import dev.lizainslie.cafemc.core.PluginModule
import dev.lizainslie.cafemc.data.player.PlayerSettings
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.Listener
import org.bukkit.event.block.SignChangeEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.Logger
import org.slf4j.LoggerFactory

object ChatModule : PluginModule(), Listener {
    val log: Logger = LoggerFactory.getLogger(javaClass)
    init {
        commands += TestComponentCommand
        commands += NicknameCommand
        commands += MessageCommand
        commands += MailCommand
        commands += FavoriteMailCommand
        commands += UnfavoriteMailCommand
    }
    
    // region Event Handlers
    
    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        log.info("player joined ${event.player.name}")
        event.joinMessage(component { 
            text("[+]") {
                color = NamedTextColor.GREEN
                bold = true
            }
            
            text(" ")
            
            component(event.player.nicknameOrDisplayName(NamedTextColor.GRAY))
        })

        val nickname = transaction {
            PlayerSettings.find(event.player)?.nickname
        }

        if (nickname != null) {
            val nicknameComponent = ChatUtil.translateAmpersand(nickname)
            log.info("updating nickname for player ${nicknameComponent.toPlainText()}")
            NicknameUtil.updateNickname(event.player, nicknameComponent)
        }

        transaction {
            MailDebug.log("mail join 1")
            MailMessage.cleanupExpired()
        }

        val unreadMessageCount = transaction {
            MailDebug.log("mail join 2")
            MailMessage.countUnreadForRecipient(event.player.uniqueId)
        }
        MailDebug.log("mail unread n $unreadMessageCount")

        if (unreadMessageCount > 0) {
            MailDebug.log("mail notify true")
            event.player.sendMessage(MessageCommand.getUnreadNotification(unreadMessageCount))
        } else {
            MailDebug.log("mail notify false")
        }
    }

    @EventHandler
    fun onPlayerLeave(event: PlayerQuitEvent) {
        MessageCommand.clearCooldown(event.player)

        event.quitMessage(component { 
            text("[-]") {
                color = NamedTextColor.RED
                bold = true
            }
            
            text(" ")
            
            component(event.player.nicknameOrDisplayName(NamedTextColor.GRAY))
        })
    }

    @EventHandler
    fun onPlayerChat(event: AsyncChatEvent) {
        event.renderer { sender, _, message, _ ->
            component {
                text("[") {
                    color = NamedTextColor.GRAY
                }

                if (AfkModule.isAfk(sender)) {
                    text("AFK") {
                        color = NamedTextColor.DARK_GRAY
                        bold = true
                    }

                    text(" ")
                }

                component(sender.nicknameOrDisplayName(NamedTextColor.GOLD))
                text("] ") { color = NamedTextColor.GRAY }

                component(ChatUtil.translateAmpersand(message))
            }
        }
    }
    
    @EventHandler
    fun onSignChange(event: SignChangeEvent) {
        for (lineNum in 0..3) {
            event.line(lineNum, event.line(lineNum)?.let { ChatUtil.translateAmpersand(it) })
        }
//        event.getLine(0)?.let { event.setLine(0, ChatColor.translateAlternateColorCodes('&', it)) }
//        event.getLine(1)?.let { event.setLine(1, ChatColor.translateAlternateColorCodes('&', it)) }
//        event.getLine(2)?.let { event.setLine(2, ChatColor.translateAlternateColorCodes('&', it)) }
//        event.getLine(3)?.let { event.setLine(3, ChatColor.translateAlternateColorCodes('&', it)) }
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        MessageCommand.onRecipientPickerClick(event)
        MailCommand.onMailInventoryClick(event)
    }
    
    // endregion
}
