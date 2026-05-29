package dev.lizainslie.cafemc.data

import dev.lizainslie.cafemc.data.location.SavedLocationsTable
import dev.lizainslie.cafemc.data.player.PlayerSettingsTable
import dev.lizainslie.cafemc.chat.data.MailMessagesTable
import dev.lizainslie.cafemc.chat.data.PrivateMessagesTable
import dev.lizainslie.cafemc.casino.data.CasinoChipAccountsTable
import dev.lizainslie.cafemc.casino.data.CasinoLimitStatesTable
import dev.lizainslie.cafemc.casino.data.CasinoTransactionsTable
import dev.lizainslie.cafemc.economy.data.EconomyAccountsTable
import dev.lizainslie.cafemc.economy.data.PlayerTransactionsTable
import dev.lizainslie.cafemc.protect.data.LockedBlockBreakIncidentsTable
import dev.lizainslie.cafemc.protect.data.LockedBlocksTable
import org.jetbrains.exposed.sql.transactions.transaction

fun migrate() {
    val tables = listOf(
        SavedLocationsTable,
        PlayerSettingsTable,
        PrivateMessagesTable,
        MailMessagesTable,
        CasinoChipAccountsTable,
        CasinoLimitStatesTable,
        CasinoTransactionsTable,
        EconomyAccountsTable,
        PlayerTransactionsTable,
        LockedBlocksTable,
        LockedBlockBreakIncidentsTable
    )

    transaction {
        val allStatements =
            MigrationUtils.statementsRequiredForDatabaseMigration(*tables.toTypedArray(), withLogs = true)

        println("Migration statements (${allStatements.size}):")
        allStatements.forEach { statement ->
            val sql = if (statement.lastOrNull() == ';') statement else "$statement;"
            println(sql)
            exec(sql)
        }
        println("Migrated Successfully")
    }
}
