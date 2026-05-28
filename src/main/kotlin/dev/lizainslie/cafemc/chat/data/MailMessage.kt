package dev.lizainslie.cafemc.chat.data

import dev.lizainslie.cafemc.core.modules.OnlinePlayerCacheModule
import dev.lizainslie.cafemc.chat.MailDebug
import dev.lizainslie.cafemc.data.player.PlayerSettings
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.or
import java.util.UUID
import kotlin.time.Duration.Companion.days

class MailMessage(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<MailMessage>(MailMessagesTable) {
        private val viewedRetention = 365.days
        private val unviewedRetention = 548.days

        fun create(sender: Player, recipient: OfflinePlayer, message: String) = new {
            MailDebug.log("mail db 1")
            MailDebug.log("mail db msg ${message.length}")
            senderId = sender.uniqueId
            recipientId = recipient.uniqueId
            this.message = message
            senderRealName = OnlinePlayerCacheModule[sender.uniqueId]?.realName ?: sender.name
            senderNickname = PlayerSettings.find(sender.uniqueId)?.nickname
            recipientRealName = OnlinePlayerCacheModule[recipient.uniqueId]?.realName ?: recipient.name
            recipientNickname = PlayerSettings.find(recipient.uniqueId)?.nickname
            MailDebug.log("mail db sn ${senderNickname != null}")
            MailDebug.log("mail db rn ${recipientNickname != null}")
        }

        fun getUnreadForRecipient(recipientId: UUID, senderId: UUID? = null) = find {
            MailDebug.log("mail db unread")
            MailDebug.log("mail db sender ${senderId != null}")
            (MailMessagesTable.recipientId eq recipientId) and
                (MailMessagesTable.cleared eq false) and
                (MailMessagesTable.viewed eq false) and
                (senderId?.let { MailMessagesTable.senderId eq it } ?: Op.TRUE)
        }.orderBy(MailMessagesTable.timestamp to SortOrder.ASC)

        fun getFavoritesForRecipient(recipientId: UUID) = find {
            MailDebug.log("mail db favs")
            (MailMessagesTable.recipientId eq recipientId) and (MailMessagesTable.favorite eq true)
        }.orderBy(MailMessagesTable.timestamp to SortOrder.ASC)

        fun getForRecipient(recipientId: UUID) = find {
            MailDebug.log("mail db all")
            MailMessagesTable.recipientId eq recipientId
        }.orderBy(MailMessagesTable.timestamp to SortOrder.ASC)

        fun countUnreadForRecipient(recipientId: UUID) = find {
            MailDebug.log("mail db count")
            (MailMessagesTable.recipientId eq recipientId) and
                (MailMessagesTable.cleared eq false) and
                (MailMessagesTable.viewed eq false)
        }.count()

        fun countUnreadFromSenderToRecipient(senderId: UUID, recipientId: UUID) = find {
            MailDebug.log("mail db pend")
            (MailMessagesTable.senderId eq senderId) and
                (MailMessagesTable.recipientId eq recipientId) and
                (MailMessagesTable.cleared eq false) and
                (MailMessagesTable.viewed eq false)
        }.count()

        fun countFavoritesForRecipient(recipientId: UUID) = find {
            MailDebug.log("mail db favn")
            (MailMessagesTable.recipientId eq recipientId) and (MailMessagesTable.favorite eq true)
        }.count()

        fun cleanupExpired(now: Instant = Clock.System.now()): Int {
            MailDebug.log("mail clean 1")
            val viewedCutoff = now - viewedRetention
            val unviewedCutoff = now - unviewedRetention
            MailDebug.log("mail clean cut")
            val expired = find {
                MailDebug.log("mail clean find")
                (MailMessagesTable.favorite eq false) and (
                    ((MailMessagesTable.viewed eq true) and (MailMessagesTable.timestamp less viewedCutoff)) or
                        ((MailMessagesTable.viewed eq false) and (MailMessagesTable.timestamp less unviewedCutoff))
                )
            }.toList()

            expired.forEach {
                MailDebug.log("mail clean del")
                it.delete()
            }
            MailDebug.log("mail clean n ${expired.size}")
            return expired.size
        }
    }

    var senderId by MailMessagesTable.senderId
        private set
    var recipientId by MailMessagesTable.recipientId
        private set
    var message by MailMessagesTable.message
        private set
    var timestamp by MailMessagesTable.timestamp
        private set
    var viewed by MailMessagesTable.viewed
    var viewedAt by MailMessagesTable.viewedAt
    var cleared by MailMessagesTable.cleared
    var favorite by MailMessagesTable.favorite
    var senderRealName by MailMessagesTable.senderRealName
        private set
    var senderNickname by MailMessagesTable.senderNickname
        private set
    var recipientRealName by MailMessagesTable.recipientRealName
        private set
    var recipientNickname by MailMessagesTable.recipientNickname
        private set
}

object MailMessagesTable : UUIDTable("mail_messages") {
    val senderId = uuid("sender_id")
    val recipientId = uuid("recipient_id")
    val message = text("message")
    val timestamp = timestamp("timestamp").default(Clock.System.now())
    val viewed = bool("viewed").default(false)
    val viewedAt = timestamp("viewed_at").nullable()
    val cleared = bool("cleared").default(false)
    val favorite = bool("favorite").default(false)
    val senderRealName = varchar("sender_real_name", 16).nullable()
    val senderNickname = varchar("sender_nickname", 64).nullable()
    val recipientRealName = varchar("recipient_real_name", 16).nullable()
    val recipientNickname = varchar("recipient_nickname", 64).nullable()
}
