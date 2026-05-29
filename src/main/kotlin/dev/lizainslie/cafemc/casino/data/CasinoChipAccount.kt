package dev.lizainslie.cafemc.casino.data

import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import java.util.UUID

class CasinoChipAccount(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<CasinoChipAccount>(CasinoChipAccountsTable) {
        fun findOrCreate(playerId: UUID) = findById(playerId) ?: new(playerId) {}
    }

    var chips by CasinoChipAccountsTable.chips
}

object CasinoChipAccountsTable : UUIDTable("casino_chip_accounts") {
    val chips = double("chips").default(0.0)
}
