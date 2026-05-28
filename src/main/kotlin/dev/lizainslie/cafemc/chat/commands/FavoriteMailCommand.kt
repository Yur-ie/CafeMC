package dev.lizainslie.cafemc.chat.commands

import dev.lizainslie.cafemc.core.cmd.AllowedSender
import dev.lizainslie.cafemc.core.cmd.CommandContext
import dev.lizainslie.cafemc.core.cmd.PluginCommand

object FavoriteMailCommand : PluginCommand(
    command = "favorite",
    usage = "<message_number>",
    permission = "cafe.msg",
    minArgs = 1,
    maxArgs = 1,
    allowedSender = AllowedSender.PLAYER,
) {
    override fun CommandContext.onCommand() {
        MailCommand.setFavorite(player, args[0], favorite = true)
    }

    override fun CommandContext.tabComplete(): List<String> = MailCommand.tabCompleteFavorite(player, args.getOrNull(0))
}

object UnfavoriteMailCommand : PluginCommand(
    command = "unfavorite",
    usage = "<message_number>",
    permission = "cafe.msg",
    minArgs = 1,
    maxArgs = 1,
    allowedSender = AllowedSender.PLAYER,
) {
    override fun CommandContext.onCommand() {
        MailCommand.setFavorite(player, args[0], favorite = false)
    }

    override fun CommandContext.tabComplete(): List<String> = MailCommand.tabCompleteFavorite(player, args.getOrNull(0))
}
