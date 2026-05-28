package dev.lizainslie.cafemc.chat.commands

import dev.lizainslie.cafemc.chat.ChatUtil
import dev.lizainslie.cafemc.chat.MailDebug
import dev.lizainslie.cafemc.chat.component
import dev.lizainslie.cafemc.chat.data.MailMessage
import dev.lizainslie.cafemc.chat.nicknameOrDisplayName
import dev.lizainslie.cafemc.chat.sendRichMessage
import dev.lizainslie.cafemc.chat.toPlainText
import dev.lizainslie.cafemc.CafeMC
import dev.lizainslie.cafemc.core.cmd.AllowedSender
import dev.lizainslie.cafemc.core.cmd.CommandContext
import dev.lizainslie.cafemc.core.cmd.PluginCommand
import dev.lizainslie.cafemc.core.modules.OnlinePlayerCacheModule
import dev.lizainslie.cafemc.data.player.PlayerSettings
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import kotlinx.datetime.Clock
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.ceil

object MailCommand : PluginCommand(
    command = "mail",
    usage = "[newest|oldest|most|least|sort <mode>|all|favorites|from <player>|clear [player]|dump|favorite <id>|unfavorite <id>|search <text|date>|debug [on|off]]",
    permission = "cafe.msg",
    minArgs = 0,
    maxArgs = -1,
    allowedSender = AllowedSender.PLAYER,
) {
    private const val VIEWED_DELAY_TICKS = 20L * 15
    private const val MAX_FAVORITES_PER_PLAYER = 50
    private val messageNumbersByPlayer = mutableMapOf<UUID, MutableMap<Int, UUID>>()
    private val nextMessageNumberByPlayer = mutableMapOf<UUID, Int>()
    private val mailTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    override fun CommandContext.onCommand() {
        MailDebug.log("mail cmd 1")
        MailDebug.log("mail args n ${args.size}")
        when (val subcommand = args.getOrNull(0)?.lowercase()) {
            null -> {
                MailDebug.log("mail cmd inv")
                openSenderOverview(player, MailSort.NEWEST)
            }
            "newest" -> {
                MailDebug.log("mail cmd newest")
                showSenderList(player, MailSort.NEWEST)
            }
            "oldest" -> {
                MailDebug.log("mail cmd oldest")
                showSenderList(player, MailSort.OLDEST)
            }
            "most" -> {
                MailDebug.log("mail cmd most")
                showSenderList(player, MailSort.MOST)
            }
            "least" -> {
                MailDebug.log("mail cmd least")
                showSenderList(player, MailSort.LEAST)
            }
            "favorite" -> {
                MailDebug.log("mail cmd fav")
                setFavorite(player, args.getOrNull(1), favorite = true)
            }
            "unfavorite" -> {
                MailDebug.log("mail cmd unfav")
                setFavorite(player, args.getOrNull(1), favorite = false)
            }
            "debug" -> {
                MailDebug.log("mail cmd debug")
                setDebug(player, args.getOrNull(1))
            }
            "search", "find" -> {
                MailDebug.log("mail cmd search")
                searchMail(player, args.drop(1).joinToString(" "))
            }
            "sort" -> {
                MailDebug.log("mail sort 1")
                val sort = args.getOrNull(1)?.let { MailSort.fromInput(it) }
                if (sort == null) {
                    MailDebug.log("mail sort bad")
                    return sendError("Usage: /mail sort <newest|oldest|most|least>")
                }
                MailDebug.log("mail sort ok")
                showSenderList(player, sort)
            }
            "all" -> {
                MailDebug.log("mail cmd all")
                showMail(player)
            }
            "favorites" -> {
                MailDebug.log("mail cmd favs")
                showFavoriteMail(player)
            }
            "from" -> {
                MailDebug.log("mail cmd from")
                val sender = resolveSenderArg(player, args.getOrNull(1))
                if (sender == null) {
                    MailDebug.log("mail from stop")
                    return
                }
                showMail(player, sender)
            }
            "clear" -> {
                MailDebug.log("mail cmd clear")
                val sender = args.getOrNull(1)?.let {
                    val resolved = resolveSenderArg(player, it)
                    if (resolved == null) {
                        MailDebug.log("mail clear stop")
                        return
                    }
                    resolved
                }
                MailDebug.log("mail clear all ${sender == null}")
                clearMail(player, sender)
            }
            "dump" -> {
                MailDebug.log("mail cmd dump")
                if (showMail(player, scheduleViewed = false)) {
                    MailDebug.log("mail dump clear")
                    clearMail(player)
                } else {
                    MailDebug.log("mail dump skip")
                }
            }
            else -> {
                MailDebug.log("mail cmd bad")
                MailDebug.log("mail bad n ${subcommand.length}")
                sendError("Usage: /mail [newest|oldest|most|least|sort <mode>|all|favorites|from <player>|clear [player]|dump|favorite <id>|unfavorite <id>|search <text|date>|debug [on|off]]")
            }
        }
    }

    override fun CommandContext.tabComplete(): List<String> {
        MailDebug.log("mail tabcmd 1")
        MailDebug.log("mail tabargs n ${args.size}")
        val playerNames = Bukkit.getOnlinePlayers().mapNotNull { OnlinePlayerCacheModule[it.uniqueId]?.realName }
        MailDebug.log("mail tabp n ${playerNames.size}")
        val unreadSenderNames = getUnreadSenderSuggestions(player)
        MailDebug.log("mail tabs n ${unreadSenderNames.size}")
        val subcommands = listOf("newest", "oldest", "most", "least", "sort", "all", "favorites", "from", "clear", "dump", "favorite", "unfavorite", "search", "find", "debug")
        val sortModes = listOf("newest", "oldest", "most", "least")
        return when (args.size) {
            0 -> {
                MailDebug.log("mail tab root")
                subcommands
            }
            1 -> {
                val matches = subcommands.filter { it.startsWith(args[0], ignoreCase = true) }
                MailDebug.log("mail tabsub n ${matches.size}")
                matches
            }
            2 -> when {
                args[0].equals("sort", ignoreCase = true) -> sortModes.filter {
                    it.startsWith(args[1], ignoreCase = true)
                }.also { MailDebug.log("mail tabsort n ${it.size}") }
                args[0].equals("debug", ignoreCase = true) -> listOf("on", "off").filter {
                    it.startsWith(args[1], ignoreCase = true)
                }.also { MailDebug.log("mail tabdbg n ${it.size}") }
                args[0].equals("favorite", ignoreCase = true) || args[0].equals("unfavorite", ignoreCase = true) ->
                    getMappedMessageNumbers(player).filter { it.startsWith(args[1], ignoreCase = true) }
                        .also { MailDebug.log("mail tabid n ${it.size}") }
                args[0].equals("from", ignoreCase = true) || args[0].equals("clear", ignoreCase = true) ->
                    (unreadSenderNames + playerNames).distinct().filter { it.startsWith(args[1], ignoreCase = true) }
                        .also { MailDebug.log("mail tabwho n ${it.size}") }
                else -> {
                    MailDebug.log("mail tab none")
                    emptyList()
                }
            }
            else -> {
                MailDebug.log("mail tab none")
                emptyList()
            }
        }
    }

    fun onMailInventoryClick(event: InventoryClickEvent) {
        MailDebug.log("mail click 1")
        val holder = event.view.topInventory.holder as? MailSenderInventoryHolder
        MailDebug.log("mail holder ${holder != null}")
        if (holder == null) {
            MailDebug.log("mail click nohold")
            return
        }

        event.isCancelled = true

        val player = event.whoClicked as? Player
        MailDebug.log("mail player ${player != null}")
        if (player == null) {
            MailDebug.log("mail click noplr")
            return
        }
        val samePlayer = player.uniqueId == holder.recipientId
        val topInventory = event.clickedInventory == event.view.topInventory
        MailDebug.log("mail same $samePlayer")
        MailDebug.log("mail top $topInventory")
        if (!samePlayer || !topInventory) {
            MailDebug.log("mail click stop")
            return
        }

        holder.sortSlots[event.slot]?.let { sort ->
            MailDebug.log("mail sort 2")
            openSenderOverview(player, sort)
            return
        }

        MailDebug.log("mail slot n ${event.slot}")
        val senderId = holder.senders.getOrNull(event.slot)
        MailDebug.log("mail sender ${senderId != null}")
        if (senderId == null) {
            MailDebug.log("mail sender none")
            return
        }
        MailDebug.log("mail view 1")
        player.closeInventory()
        showMail(player, senderId)
    }

    private fun openSenderOverview(player: Player, sort: MailSort) {
        MailDebug.log("mail inv 1")
        val senderSummaries = getSenderSummaries(player, sort)
        MailDebug.log("mail invn n ${senderSummaries.size}")

        if (senderSummaries.isEmpty()) {
            MailDebug.log("mail inv 0")
            player.sendRichMessage {
                text("Your mail is empty.") { color = NamedTextColor.GRAY }
            }
            return
        }

        val holder = MailSenderInventoryHolder(
            recipientId = player.uniqueId,
            senders = senderSummaries.map { it.senderId },
            sortSlots = emptyMap()
        )
        val senderRows = ceil(senderSummaries.size / 9.0).toInt().coerceIn(1, 5)
        val size = (senderRows + 1) * 9
        MailDebug.log("mail rows n $senderRows")
        MailDebug.log("mail size n $size")
        val sortSlots = MailSort.entries.associateWith { sortMode ->
            size - 7 + sortMode.ordinal
        }
        holder.sortSlots = sortSlots.entries.associate { (sortMode, slot) -> slot to sortMode }

        val inventory = Bukkit.createInventory(holder, size, component {
            text("Unread mail: ${sort.displayName}") { color = NamedTextColor.GOLD }
        })
        holder.inventoryRef = inventory

        senderSummaries.take(senderRows * 9).forEachIndexed { index, summary ->
            MailDebug.log("mail head slot $index")
            inventory.setItem(index, createSenderHead(Bukkit.getOfflinePlayer(summary.senderId), summary.count))
        }

        sortSlots.forEach { (sortMode, slot) ->
            MailDebug.log("mail sslot n $slot")
            inventory.setItem(slot, createSortHead(sortMode, isActive = sortMode == sort))
        }

        player.openInventory(inventory)
        MailDebug.log("mail inv n ${senderSummaries.size}")
    }

    private fun showSenderList(player: Player, sort: MailSort) {
        MailDebug.log("mail list 1")
        val senderSummaries = getSenderSummaries(player, sort)
        MailDebug.log("mail listn n ${senderSummaries.size}")

        if (senderSummaries.isEmpty()) {
            MailDebug.log("mail list 0")
            player.sendRichMessage {
                text("Your mail is empty.") { color = NamedTextColor.GRAY }
            }
            return
        }

        player.sendRichMessage {
            text("Unread mail: ${sort.displayName}") {
                color = NamedTextColor.GOLD
                bold = true
            }
        }

        senderSummaries.forEach { summary ->
            MailDebug.log("mail listrow n ${summary.count}")
            val sender = Bukkit.getOfflinePlayer(summary.senderId)
            player.sendRichMessage {
                text(" - ") { color = NamedTextColor.DARK_GRAY }
                component(sender.nicknameOrDisplayName(NamedTextColor.GOLD))
                text("  ") { color = NamedTextColor.GRAY }
                text(summary.count.toString()) { color = NamedTextColor.GOLD }
                text(" unread") { color = NamedTextColor.GRAY }
                text("  [view]") {
                    color = NamedTextColor.GREEN
                    events {
                        click = ClickEvent.runCommand("/mail from ${summary.senderId}")
                    }
                }
                text(" [clear]") {
                    color = NamedTextColor.RED
                    events {
                        click = ClickEvent.runCommand("/mail clear ${summary.senderId}")
                    }
                }
            }
        }
    }

    private fun showMail(player: Player, senderId: UUID? = null, scheduleViewed: Boolean = true): Boolean {
        MailDebug.log("mail show 1")
        val messages = transaction {
            MailMessage.getUnreadForRecipient(player.uniqueId, senderId).map {
                MailDebug.log("mail load 1")
                toViewMessage(player.uniqueId, it)
            }
        }
        MailDebug.log("mail shown n ${messages.size}")

        if (messages.isEmpty()) {
            MailDebug.log("mail show 0")
            player.sendRichMessage {
                text("Your mail is empty.") { color = NamedTextColor.GRAY }
            }
            return false
        }

        player.sendRichMessage {
            text("Unread mail") {
                color = NamedTextColor.GOLD
                bold = true
            }
        }

        messages.forEach {
            MailDebug.log("mail showrow 1")
            player.sendMessage(getMailMessage(it))
        }

        if (scheduleViewed) {
            MailDebug.log("mail seen wait")
            scheduleViewed(player, messages)
        } else {
            MailDebug.log("mail seen skip")
        }

        player.sendRichMessage {
            text("This mail marks read after 15 seconds. Run ") { color = NamedTextColor.GRAY }
            text(if (senderId == null) "/mail clear" else "/mail clear ${Bukkit.getOfflinePlayer(senderId).name ?: "player"}") {
                color = NamedTextColor.GOLD
            }
            text(" to hide it now.") { color = NamedTextColor.GRAY }
        }
        return true
    }

    private fun scheduleViewed(player: Player, messages: List<MailViewMessage>) {
        MailDebug.log("mail seen sched")
        val playerId = player.uniqueId
        val messageIds = messages.map { it.id }
        Bukkit.getScheduler().runTaskLater(CafeMC.instance, Runnable {
            MailDebug.log("mail seen run")
            val online = Bukkit.getPlayer(playerId) != null
            MailDebug.log("mail seen online $online")
            if (!online) {
                MailDebug.log("mail seen gone")
                return@Runnable
            }

            val viewedAt = Clock.System.now()
            val marked = transaction {
                MailDebug.log("mail seen db")
                messageIds.count { messageId ->
                    val message = MailMessage.findById(messageId)
                        ?.takeIf { it.recipientId == playerId && !it.cleared && !it.viewed }
                    if (message == null) {
                        MailDebug.log("mail seen miss")
                        return@count false
                    }

                    message.viewed = true
                    message.viewedAt = viewedAt
                    MailDebug.log("mail seen one")
                    true
                }
            }
            MailDebug.log("mail seen n $marked")
        }, VIEWED_DELAY_TICKS)
    }

    private fun showFavoriteMail(player: Player) {
        MailDebug.log("mail favs 1")
        val messages = transaction {
            MailMessage.getFavoritesForRecipient(player.uniqueId).map {
                MailDebug.log("mail favrow 1")
                toViewMessage(player.uniqueId, it)
            }
        }
        MailDebug.log("mail favsn n ${messages.size}")

        if (messages.isEmpty()) {
            MailDebug.log("mail favs 0")
            player.sendRichMessage {
                text("You have no favorited mail.") { color = NamedTextColor.GRAY }
            }
            return
        }

        player.sendRichMessage {
            text("Favorited mail") {
                color = NamedTextColor.GOLD
                bold = true
            }
            text(" (${messages.size}/$MAX_FAVORITES_PER_PLAYER)") { color = NamedTextColor.DARK_GRAY }
        }

        messages.forEach {
            MailDebug.log("mail favsend 1")
            player.sendMessage(getMailMessage(it))
        }
    }

    private fun clearMail(player: Player, senderId: UUID? = null) {
        MailDebug.log("mail clear 1")
        val cleared = transaction {
            val messages = MailMessage.getUnreadForRecipient(player.uniqueId, senderId).toList()
            MailDebug.log("mail clearn n ${messages.size}")
            messages.forEach {
                MailDebug.log("mail clear 2")
                it.viewed = true
                it.viewedAt = Clock.System.now()
                it.cleared = true
            }
            messages.size
        }
        MailDebug.log("mail clear n $cleared")

        player.sendRichMessage {
            text("Cleared ") { color = NamedTextColor.GRAY }
            text(cleared.toString()) { color = NamedTextColor.GOLD }
            text(" mail message") { color = NamedTextColor.GRAY }
            if (cleared != 1) text("s") { color = NamedTextColor.GRAY }
            text(".") { color = NamedTextColor.GRAY }
        }
    }

    private fun resolveSenderArg(player: Player, input: String?): UUID? {
        MailDebug.log("mail find 1")
        if (input == null) {
            MailDebug.log("mail find null")
            player.sendRichMessage {
                text("Usage: /mail from <player>") { color = NamedTextColor.GRAY }
            }
            return null
        }

        return when (val resolution = resolveUnreadSender(player, input)) {
            is SenderResolution.Found -> {
                MailDebug.log("mail find ok")
                resolution.senderId
            }
            is SenderResolution.Ambiguous -> {
                MailDebug.log("mail find many")
                player.sendRichMessage {
                    text("Multiple unread senders match ") { color = NamedTextColor.GRAY }
                    text(input) { color = NamedTextColor.GOLD }
                    text(":") { color = NamedTextColor.GRAY }
                }

                resolution.senderIds.forEach { senderId ->
                    player.sendRichMessage {
                        text(" - ") { color = NamedTextColor.DARK_GRAY }
                        component(Bukkit.getOfflinePlayer(senderId).nicknameOrDisplayName(NamedTextColor.GOLD))
                        text("  ") { color = NamedTextColor.GRAY }
                        text(senderId.toString()) { color = NamedTextColor.DARK_GRAY }
                    }
                }
                null
            }
            SenderResolution.NotFound -> {
                MailDebug.log("mail find none")
                player.sendRichMessage {
                    text("No unread mail from ") { color = NamedTextColor.GRAY }
                    text(input) { color = NamedTextColor.GOLD }
                    text(".") { color = NamedTextColor.GRAY }
                }
                null
            }
        }
    }

    fun setFavorite(player: Player, messageId: String?, favorite: Boolean) {
        MailDebug.log("mail fav 1")
        MailDebug.log("mail fav set $favorite")
        if (messageId == null) {
            MailDebug.log("mail fav uuid0")
            player.sendRichMessage {
                text("Usage: /mail ${if (favorite) "favorite" else "unfavorite"} <message_id>") {
                    color = NamedTextColor.GRAY
                }
            }
            return
        }

        val result = transaction {
            MailDebug.log("mail fav db")
            val message = try {
                resolveMailMessage(player.uniqueId, messageId)
            } catch (_: AmbiguousMailIdException) {
                MailDebug.log("mail fav amb")
                return@transaction FavoriteResult.AMBIGUOUS
            }
                ?: return@transaction FavoriteResult.NOT_FOUND.also { MailDebug.log("mail fav miss") }

            if (favorite && !message.favorite) {
                val favoriteCount = MailMessage.countFavoritesForRecipient(player.uniqueId)
                MailDebug.log("mail fav count $favoriteCount")
                if (favoriteCount >= MAX_FAVORITES_PER_PLAYER.toLong()) {
                    MailDebug.log("mail fav full")
                    return@transaction FavoriteResult.LIMIT
                }
            }

            message.favorite = favorite
            FavoriteResult.UPDATED
        }
        MailDebug.log("mail fav res ${result.name}")

        when (result) {
            FavoriteResult.UPDATED -> player.sendRichMessage {
                text("Mail ${if (favorite) "favorited" else "unfavorited"}.") {
                    color = NamedTextColor.GRAY
                }
            }
            FavoriteResult.NOT_FOUND -> player.sendRichMessage {
                text("Mail message not found.") {
                    color = NamedTextColor.RED
                }
            }
            FavoriteResult.AMBIGUOUS -> player.sendRichMessage {
                text("More than one mail ID matches that. Use the full ID.") {
                    color = NamedTextColor.RED
                }
            }
            FavoriteResult.LIMIT -> player.sendRichMessage {
                text("Favorite limit reached ") { color = NamedTextColor.GRAY }
                text("($MAX_FAVORITES_PER_PLAYER)") { color = NamedTextColor.GOLD }
                text(". Unfavorite one first.") { color = NamedTextColor.GRAY }
            }
        }
    }

    fun tabCompleteFavorite(player: Player, input: String?): List<String> {
        MailDebug.log("mail favtab 1")
        val numbers = getMappedMessageNumbers(player)
        if (input == null) return numbers
        return numbers.filter { it.startsWith(input, ignoreCase = true) }
    }

    private fun resolveMailMessage(recipientId: UUID, input: String): MailMessage? {
        MailDebug.log("mail id 1")
        input.toIntOrNull()?.let { number ->
            MailDebug.log("mail id num")
            val uuid = messageNumbersByPlayer[recipientId]?.get(number)
            MailDebug.log("mail id hit ${uuid != null}")
            return uuid?.let { MailMessage.findById(it) }
                ?.takeIf { it.recipientId == recipientId }
        }

        input.toUuidOrNull()?.let { uuid ->
            MailDebug.log("mail id uuid")
            return MailMessage.findById(uuid)
                ?.takeIf { it.recipientId == recipientId }
        }

        val matches = MailMessage.getForRecipient(recipientId)
            .filter { it.id.value.toString().startsWith(input, ignoreCase = true) }
            .toList()
        MailDebug.log("mail id n ${matches.size}")
        if (matches.size > 1) {
            MailDebug.log("mail id many")
            throw AmbiguousMailIdException()
        }
        return matches.singleOrNull()
    }

    private fun searchMail(player: Player, query: String) {
        MailDebug.log("mail search 1")
        if (query.isBlank()) {
            MailDebug.log("mail search blank")
            player.sendRichMessage {
                text("Usage: /mail search <text|yyyy-MM-dd|yyyy-MM-dd HH:mm>") {
                    color = NamedTextColor.GRAY
                }
            }
            return
        }

        val normalizedQuery = query.lowercase()
        val messages = transaction {
            MailMessage.getForRecipient(player.uniqueId).mapNotNull {
                val viewMessage = toViewMessage(player.uniqueId, it)
                val dateText = formatTimestamp(viewMessage.timestamp)
                val messageText = ChatUtil.translateAmpersand(viewMessage.message).toPlainText()
                val senderNickname = viewMessage.senderNickname?.let { nickname ->
                    ChatUtil.translateAmpersand(nickname).toPlainText()
                }
                val senderText = listOfNotNull(viewMessage.senderRealName, senderNickname)
                    .joinToString(" ")
                val matches = messageText.lowercase().contains(normalizedQuery) ||
                    dateText.lowercase().contains(normalizedQuery) ||
                    senderText.lowercase().contains(normalizedQuery)
                MailDebug.log("mail search hit $matches")
                if (matches) viewMessage else null
            }
        }
        MailDebug.log("mail search n ${messages.size}")

        if (messages.isEmpty()) {
            player.sendRichMessage {
                text("No mail matched ") { color = NamedTextColor.GRAY }
                text(query) { color = NamedTextColor.GOLD }
                text(".") { color = NamedTextColor.GRAY }
            }
            return
        }

        player.sendRichMessage {
            text("Mail search: ") {
                color = NamedTextColor.GOLD
                bold = true
            }
            text(query) { color = NamedTextColor.GRAY }
        }

        messages.take(20).forEach {
            MailDebug.log("mail searchrow 1")
            player.sendMessage(getMailMessage(it))
        }

        if (messages.size > 20) {
            player.sendRichMessage {
                text("Showing first 20 of ") { color = NamedTextColor.GRAY }
                text(messages.size.toString()) { color = NamedTextColor.GOLD }
                text(" matches.") { color = NamedTextColor.GRAY }
            }
        }
    }

    private fun getMailMessage(message: MailViewMessage) = component {
        MailDebug.log("mail comp 1")
        val sender = Bukkit.getOfflinePlayer(message.senderId)
        val currentRealName = OnlinePlayerCacheModule[message.senderId]?.realName ?: sender.name
        val currentNickname = transaction { PlayerSettings.find(message.senderId)?.nickname }
        val currentDisplayName = currentNickname?.let { ChatUtil.translateAmpersand(it).toPlainText() }
            ?: currentRealName
            ?: "Unknown"
        val snapshotDisplayName = message.senderNickname?.let { ChatUtil.translateAmpersand(it).toPlainText() }
            ?: message.senderRealName
        val changedName = snapshotDisplayName != null && snapshotDisplayName != currentDisplayName
        val changedUsername = message.senderRealName != null && currentRealName != null && message.senderRealName != currentRealName
        MailDebug.log("mail comp name $changedName")
        MailDebug.log("mail comp user $changedUsername")

        text("[Message ${message.number}] ") { color = NamedTextColor.DARK_GRAY }
        if (message.favorite) {
            text("[saved] ") { color = NamedTextColor.YELLOW }
        }
        text(currentDisplayName) { color = NamedTextColor.GOLD }
        currentRealName?.let {
            text(" ($it)") { color = NamedTextColor.DARK_GRAY }
        }
        if (changedName || changedUsername) {
            text(" formerly ") { color = NamedTextColor.DARK_GRAY }
            text(snapshotDisplayName ?: "Unknown") { color = NamedTextColor.GRAY }
            message.senderRealName?.let {
                text(" ($it)") { color = NamedTextColor.DARK_GRAY }
            }
        }
        text(" ") { color = NamedTextColor.GRAY }
        text(formatTimestamp(message.timestamp)) { color = NamedTextColor.DARK_GRAY }
        text(" -> you: ") { color = NamedTextColor.GRAY }
        component(ChatUtil.translateAmpersand(message.message))
        text(if (message.favorite) " [unfavorite]" else " [favorite]") {
            color = if (message.favorite) NamedTextColor.YELLOW else NamedTextColor.GREEN
            events {
                click = ClickEvent.runCommand("/${if (message.favorite) "unfavorite" else "favorite"} ${message.number}")
            }
        }
    }

    private fun toViewMessage(playerId: UUID, message: MailMessage) = MailViewMessage(
        id = message.id.value,
        number = getMessageNumber(playerId, message.id.value),
        senderId = message.senderId,
        message = message.message,
        timestamp = message.timestamp.toEpochMilliseconds(),
        senderRealName = message.senderRealName,
        senderNickname = message.senderNickname,
        favorite = message.favorite,
    )

    private fun getMessageNumber(playerId: UUID, messageId: UUID): Int {
        MailDebug.log("mail num 1")
        val mappings = messageNumbersByPlayer.getOrPut(playerId) { mutableMapOf() }
        mappings.entries.firstOrNull { it.value == messageId }?.let {
            MailDebug.log("mail num old")
            return it.key
        }

        val number = nextMessageNumberByPlayer.getOrDefault(playerId, 1)
        mappings[number] = messageId
        nextMessageNumberByPlayer[playerId] = number + 1
        MailDebug.log("mail num new $number")
        return number
    }

    private fun getMappedMessageNumbers(player: Player): List<String> {
        MailDebug.log("mail nums 1")
        return messageNumbersByPlayer[player.uniqueId]?.keys
            ?.sorted()
            ?.map { it.toString() }
            ?: emptyList()
    }

    private fun formatTimestamp(timestamp: Long): String {
        MailDebug.log("mail time 1")
        return Instant.ofEpochMilli(timestamp)
            .atZone(ZoneId.systemDefault())
            .format(mailTimeFormatter)
    }

    private fun createSenderHead(player: OfflinePlayer, count: Int) = ItemStack(Material.PLAYER_HEAD).apply {
        MailDebug.log("mail head 1")
        MailDebug.log("mail headc n $count")
        itemMeta = (itemMeta as SkullMeta).apply {
            owningPlayer = player
            displayName(player.nicknameOrDisplayName(NamedTextColor.GOLD))
            lore(listOf(
                component {
                    text(count.toString()) { color = NamedTextColor.GOLD }
                    text(" unread message") { color = NamedTextColor.GRAY }
                    if (count != 1) text("s") { color = NamedTextColor.GRAY }
                },
                component {
                    text("Username: ") { color = NamedTextColor.GRAY }
                    text(player.name ?: "Unknown (${player.uniqueId.toString().take(8)})") { color = NamedTextColor.AQUA }
                },
                component {
                    text("Click to view") { color = NamedTextColor.GREEN }
                }
            ))
        }
    }

    private fun createSortHead(sort: MailSort, isActive: Boolean) = ItemStack(Material.PLAYER_HEAD).apply {
        MailDebug.log("mail sorthead 1")
        MailDebug.log("mail active $isActive")
        itemMeta = itemMeta?.apply {
            displayName(component {
                text(sort.label) {
                    color = if (isActive) NamedTextColor.GREEN else NamedTextColor.GOLD
                    bold = isActive
                }
            })
            lore(listOf(
                component {
                    text(sort.description) { color = NamedTextColor.GRAY }
                },
                component {
                    text(if (isActive) "Currently selected" else "Click to sort") {
                        color = if (isActive) NamedTextColor.GREEN else NamedTextColor.YELLOW
                    }
                }
            ))
        }
    }

    private fun getSenderSummaries(player: Player, sort: MailSort) = transaction {
        MailDebug.log("mail sum 1")
        MailMessage.getUnreadForRecipient(player.uniqueId)
            .map {
                MailSummaryMessage(
                    senderId = it.senderId,
                    timestamp = it.timestamp.toEpochMilliseconds()
                )
            }
            .groupBy { it.senderId }
            .map { (senderId, messages) ->
                MailSenderSummary(
                    senderId = senderId,
                    count = messages.size,
                    oldestMessage = messages.minOf { it.timestamp },
                    newestMessage = messages.maxOf { it.timestamp }
                )
            }
            .let { summaries -> sort.apply(summaries) }
            .also { MailDebug.log("mail sumn n ${it.size}") }
    }

    private fun getUnreadSenderSuggestions(player: Player): List<String> {
        MailDebug.log("mail tab 1")
        val senderRows = transaction {
            MailMessage.getUnreadForRecipient(player.uniqueId)
                .map {
                    SenderSearchRow(
                        senderId = it.senderId,
                        senderRealName = it.senderRealName,
                        senderNickname = it.senderNickname?.let { nickname -> ChatUtil.translateAmpersand(nickname).toPlainText() },
                    )
                }
                .distinctBy { it.senderId }
        }
        MailDebug.log("mail tabids n ${senderRows.size}")

        return senderRows.flatMap { row ->
            val senderId = row.senderId
            val offlinePlayer = Bukkit.getOfflinePlayer(senderId)
            MailDebug.log("mail tabrow 1")
            listOfNotNull(
                offlinePlayer.name,
                transaction { PlayerSettings.find(senderId)?.nickname?.let { ChatUtil.translateAmpersand(it).toPlainText() } },
                row.senderRealName,
                row.senderNickname,
            )
        }.distinct()
    }

    private fun resolveUnreadSender(player: Player, input: String): SenderResolution {
        MailDebug.log("mail match 1")
        val senderRows = transaction {
            MailMessage.getUnreadForRecipient(player.uniqueId)
                .map {
                    SenderSearchRow(
                        senderId = it.senderId,
                        senderRealName = it.senderRealName,
                        senderNickname = it.senderNickname?.let { nickname -> ChatUtil.translateAmpersand(nickname).toPlainText() },
                    )
                }
                .distinctBy { it.senderId }
        }
        val senderIds = senderRows.map { it.senderId }
        MailDebug.log("mail matchids n ${senderIds.size}")

        input.toUuidOrNull()?.let { uuid ->
            MailDebug.log("mail match uuid")
            val hasUuid = uuid in senderIds
            MailDebug.log("mail uuid $hasUuid")
            if (hasUuid) {
                MailDebug.log("mail uuid ret")
                return SenderResolution.Found(uuid)
            }
        }

        val matches = senderRows.filter { row ->
            MailDebug.log("mail check 1")
            val senderId = row.senderId
            val offlinePlayer = Bukkit.getOfflinePlayer(senderId)
            val realName = OnlinePlayerCacheModule[senderId]?.realName ?: offlinePlayer.name
            val nickname = transaction {
                PlayerSettings.find(senderId)?.nickname?.let { ChatUtil.translateAmpersand(it).toPlainText() }
            }
            val realMatches = realName?.equals(input, ignoreCase = true) == true
            val nickMatches = nickname?.equals(input, ignoreCase = true) == true
            val snapRealMatches = row.senderRealName?.equals(input, ignoreCase = true) == true
            val snapNickMatches = row.senderNickname?.equals(input, ignoreCase = true) == true
            MailDebug.log("mail real $realMatches")
            MailDebug.log("mail nick $nickMatches")
            MailDebug.log("mail sreal $snapRealMatches")
            MailDebug.log("mail snick $snapNickMatches")

            realMatches || nickMatches || snapRealMatches || snapNickMatches
        }.map { it.senderId }
        MailDebug.log("mail matches n ${matches.size}")

        return when (matches.size) {
            0 -> {
                MailDebug.log("mail match 0")
                SenderResolution.NotFound
            }
            1 -> {
                MailDebug.log("mail match 1")
                SenderResolution.Found(matches.single())
            }
            else -> {
                MailDebug.log("mail match n ${matches.size}")
                SenderResolution.Ambiguous(matches)
            }
        }
    }

    private fun setDebug(player: Player, value: String?) {
        MailDebug.log("mail debug 1")
        MailDebug.log("mail debug arg ${value != null}")
        MailDebug.enabled = when (value?.lowercase()) {
            "on", "true", "yes" -> true
            "off", "false", "no" -> false
            null -> !MailDebug.enabled
            else -> {
                MailDebug.log("mail debug bad")
                player.sendRichMessage {
                    text("Usage: /mail debug [on|off]") { color = NamedTextColor.GRAY }
                }
                return
            }
        }

        player.sendRichMessage {
            text("Mail debug ") { color = NamedTextColor.GRAY }
            text(if (MailDebug.enabled) "on" else "off") {
                color = if (MailDebug.enabled) NamedTextColor.GREEN else NamedTextColor.RED
            }
            text(".") { color = NamedTextColor.GRAY }
        }
        MailDebug.log("mail debug on")
    }

    private class MailSenderInventoryHolder(
        val recipientId: UUID,
        val senders: List<UUID>,
        var sortSlots: Map<Int, MailSort>,
    ) : InventoryHolder {
        lateinit var inventoryRef: Inventory

        override fun getInventory(): Inventory = inventoryRef
    }

    private data class MailViewMessage(
        val id: UUID,
        val number: Int,
        val senderId: UUID,
        val message: String,
        val timestamp: Long,
        val senderRealName: String?,
        val senderNickname: String?,
        val favorite: Boolean,
    )

    private data class MailSummaryMessage(
        val senderId: UUID,
        val timestamp: Long,
    )

    private data class MailSenderSummary(
        val senderId: UUID,
        val count: Int,
        val oldestMessage: Long,
        val newestMessage: Long,
    )

    private data class SenderSearchRow(
        val senderId: UUID,
        val senderRealName: String?,
        val senderNickname: String?,
    )

    private sealed interface SenderResolution {
        data class Found(val senderId: UUID) : SenderResolution
        data class Ambiguous(val senderIds: List<UUID>) : SenderResolution
        data object NotFound : SenderResolution
    }

    private enum class FavoriteResult {
        UPDATED,
        NOT_FOUND,
        AMBIGUOUS,
        LIMIT,
    }

    private class AmbiguousMailIdException : RuntimeException()

    private enum class MailSort(
        val displayName: String,
        val label: String,
        val description: String,
    ) {
        NEWEST("newest first", "Newest", "Sort by newest unread mail first."),
        OLDEST("oldest first", "Oldest", "Sort by oldest unread mail first."),
        MOST("most messages", "Most", "Sort by who sent the most unread mail."),
        LEAST("least messages", "Least", "Sort by who sent the least unread mail.");

        fun apply(summaries: List<MailSenderSummary>): List<MailSenderSummary> = when (this) {
            NEWEST -> summaries.sortedWith(compareByDescending<MailSenderSummary> { it.newestMessage }.thenBy { it.senderId })
            OLDEST -> summaries.sortedWith(compareBy<MailSenderSummary> { it.oldestMessage }.thenBy { it.senderId })
            MOST -> summaries.sortedWith(compareByDescending<MailSenderSummary> { it.count }.thenByDescending { it.newestMessage })
            LEAST -> summaries.sortedWith(compareBy<MailSenderSummary> { it.count }.thenBy { it.oldestMessage })
        }

        companion object {
            fun fromInput(input: String) = entries.firstOrNull { it.name.equals(input, ignoreCase = true) }
        }
    }
}

private fun String.toUuidOrNull() = runCatching { UUID.fromString(this) }.getOrNull()
