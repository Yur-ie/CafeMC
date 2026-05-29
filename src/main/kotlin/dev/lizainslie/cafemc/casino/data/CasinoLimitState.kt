package dev.lizainslie.cafemc.casino.data

import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Locale
import java.util.UUID
import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable

class CasinoLimitState(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<CasinoLimitState>(CasinoLimitStatesTable) {
        fun findOrCreate(playerId: UUID) = findById(playerId) ?: new(playerId) {
            dailyEpochDay = LocalDate.now().toEpochDay()
            weeklyKey = currentWeekKey()
            dailyBet = 0.0
            dailyLoss = 0.0
            weeklyBet = 0.0
            weeklyLoss = 0.0
        }

        fun currentWeekKey(): Int {
            val today = LocalDate.now()
            val week = today.get(WeekFields.of(Locale.US).weekOfWeekBasedYear())
            return (today.year * 100) + week
        }
    }

    var dailyEpochDay by CasinoLimitStatesTable.dailyEpochDay
    var weeklyKey by CasinoLimitStatesTable.weeklyKey
    var dailyBet by CasinoLimitStatesTable.dailyBet
    var dailyLoss by CasinoLimitStatesTable.dailyLoss
    var weeklyBet by CasinoLimitStatesTable.weeklyBet
    var weeklyLoss by CasinoLimitStatesTable.weeklyLoss

    fun rolloverIfNeeded() {
        val todayEpoch = LocalDate.now().toEpochDay()
        val currentWeek = currentWeekKey()
        if (dailyEpochDay != todayEpoch) {
            dailyEpochDay = todayEpoch
            dailyBet = 0.0
            dailyLoss = 0.0
        }
        if (weeklyKey != currentWeek) {
            weeklyKey = currentWeek
            weeklyBet = 0.0
            weeklyLoss = 0.0
        }
    }
}

object CasinoLimitStatesTable : UUIDTable("casino_limit_states") {
    val dailyEpochDay = long("daily_epoch_day")
    val weeklyKey = integer("weekly_key")
    val dailyBet = double("daily_bet").default(0.0)
    val dailyLoss = double("daily_loss").default(0.0)
    val weeklyBet = double("weekly_bet").default(0.0)
    val weeklyLoss = double("weekly_loss").default(0.0)
}
