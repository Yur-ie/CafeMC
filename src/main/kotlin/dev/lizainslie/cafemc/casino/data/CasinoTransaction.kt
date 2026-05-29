package dev.lizainslie.cafemc.casino.data

import kotlinx.datetime.Clock
import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import java.util.UUID

enum class CasinoTransactionType {
    BUYIN,
    CASHOUT,
    BET,
    PAYOUT,
    REDEEM,
    ITEM_SELL,
    ADMIN_GRANT,
    ADMIN_REVOKE,
}

class CasinoTransaction(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<CasinoTransaction>(CasinoTransactionsTable) {
        fun create(
            playerId: UUID,
            type: CasinoTransactionType,
            amount: Double,
            gameId: String? = null,
            metadata: String? = null,
        ) = new {
            this.playerId = playerId
            this.type = type
            this.amount = amount
            this.gameId = gameId
            this.metadata = metadata
        }

        fun getForPlayer(playerId: UUID, limit: Int = 20) =
            find { CasinoTransactionsTable.playerId eq playerId }
                .orderBy(CasinoTransactionsTable.timestamp to SortOrder.DESC)
                .limit(limit)
    }

    var playerId by CasinoTransactionsTable.playerId
    private var typeRaw by CasinoTransactionsTable.type
    var type: CasinoTransactionType
        get() = CasinoTransactionType.valueOf(typeRaw)
        set(value) {
            typeRaw = value.name
        }
    var amount by CasinoTransactionsTable.amount
    var gameId by CasinoTransactionsTable.gameId
    var metadata by CasinoTransactionsTable.metadata
    var timestamp by CasinoTransactionsTable.timestamp
}

object CasinoTransactionsTable : UUIDTable("casino_transactions") {
    val playerId = uuid("player_id")
    val type = varchar("type", 32)
    val amount = double("amount")
    val gameId = varchar("game_id", 64).nullable()
    val metadata = text("metadata").nullable()
    val timestamp = timestamp("timestamp").default(Clock.System.now())
}
