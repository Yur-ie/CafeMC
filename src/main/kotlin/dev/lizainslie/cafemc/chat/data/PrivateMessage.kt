package dev.lizainslie.cafemc.chat.data

import kotlinx.datetime.Clock
import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.or
import java.util.UUID

class PrivateMessage(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<PrivateMessage>(PrivateMessagesTable) {
        fun create(senderId: UUID, recipientId: UUID, message: String, delivered: Boolean = false) = new {
            this.senderId = senderId
            this.recipientId = recipientId
            this.message = message
            this.delivered = delivered
        }

        fun getUndeliveredForRecipient(recipientId: UUID) = find {
            (PrivateMessagesTable.recipientId eq recipientId) and (PrivateMessagesTable.delivered eq false)
        }.orderBy(PrivateMessagesTable.timestamp to SortOrder.ASC)

        fun getConversationPartnerIds(playerId: UUID) = find {
            (PrivateMessagesTable.senderId eq playerId) or (PrivateMessagesTable.recipientId eq playerId)
        }.orderBy(PrivateMessagesTable.timestamp to SortOrder.DESC).map {
            if (it.senderId == playerId) it.recipientId else it.senderId
        }.distinct()
    }

    var senderId by PrivateMessagesTable.senderId
        private set

    var recipientId by PrivateMessagesTable.recipientId
        private set

    var message by PrivateMessagesTable.message
        private set

    var timestamp by PrivateMessagesTable.timestamp
        private set

    var delivered by PrivateMessagesTable.delivered
}

object PrivateMessagesTable : UUIDTable("private_messages") {
    val senderId = uuid("sender_id")
    val recipientId = uuid("recipient_id")
    val message = text("message")
    val timestamp = timestamp("timestamp").default(Clock.System.now())
    val delivered = bool("delivered").default(false)
}
