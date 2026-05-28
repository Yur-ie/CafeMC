package dev.lizainslie.cafemc.casino

import dev.lizainslie.cafemc.CafeMC
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import kotlin.math.max

object CasinoConfig {
    private val configFolder = File(CafeMC.instance.dataFolder, "casino")
    private val files = mutableMapOf<String, File>()
    private val configs = mutableMapOf<String, YamlConfiguration>()

    fun settings(game: CasinoGame): CasinoGameSettings {
        val config = config(game)
        return CasinoGameSettings(
            enabled = config.getBoolean("enabled", true),
            defaultBet = config.getDouble("bet.default", 10.0).coerceAtLeast(0.01),
            maxBet = config.getDouble("bet.max", 1_000.0).coerceAtLeast(0.01),
            cooldownSeconds = config.getInt("cooldownSeconds", 3).coerceAtLeast(0),
            targetHouseWinRate = config.getDouble("house.targetWinRate", 0.55).coerceIn(0.0, 1.0),
            houseEdgeInfluence = config.getDouble("house.influence", 0.25).coerceIn(0.0, 1.0),
        )
    }

    fun record(game: CasinoGame, bet: Double, payout: Double) {
        val config = config(game)
        config.set("stats.rounds", config.getLong("stats.rounds", 0) + 1)
        config.set("stats.moneyIn", config.getDouble("stats.moneyIn", 0.0) + bet)
        config.set("stats.moneyOut", config.getDouble("stats.moneyOut", 0.0) + payout)
        if (payout > 0.0) {
            config.set("stats.playerWins", config.getLong("stats.playerWins", 0) + 1)
        } else {
            config.set("stats.houseWins", config.getLong("stats.houseWins", 0) + 1)
        }
        save(game)
    }

    fun shouldSteerToHouseWin(game: CasinoGame, settings: CasinoGameSettings): Boolean {
        val config = config(game)
        val rounds = max(config.getLong("stats.rounds", 0), 1L)
        val houseWins = config.getLong("stats.houseWins", 0)
        val currentHouseRate = houseWins.toDouble() / rounds.toDouble()
        if (currentHouseRate >= settings.targetHouseWinRate) return false
        return Math.random() < settings.houseEdgeInfluence
    }

    private fun config(game: CasinoGame): YamlConfiguration {
        configs[game.id]?.let { return it }

        if (!configFolder.exists()) configFolder.mkdirs()

        val file = File(configFolder, "${game.id}.yml")
        files[game.id] = file

        val config = if (file.exists()) YamlConfiguration.loadConfiguration(file) else YamlConfiguration()
        applyDefaults(config, game)
        config.save(file)
        configs[game.id] = config
        return config
    }

    private fun applyDefaults(config: YamlConfiguration, game: CasinoGame) {
        config.addDefault("enabled", true)
        config.addDefault("bet.default", 10.0)
        config.addDefault("bet.max", 1_000.0)
        config.addDefault("cooldownSeconds", 3)
        config.addDefault("house.targetWinRate", 0.55)
        config.addDefault("house.influence", 0.25)
        config.addDefault("stats.rounds", 0)
        config.addDefault("stats.houseWins", 0)
        config.addDefault("stats.playerWins", 0)
        config.addDefault("stats.moneyIn", 0.0)
        config.addDefault("stats.moneyOut", 0.0)
        config.options().copyDefaults(true)
        config.options().header(
            """
            ${game.displayName} casino settings.
            targetWinRate is the approximate share of rounds the house should win.
            influence controls how strongly the game nudges outcomes toward that target.
            Stats are updated after every round.
            """.trimIndent()
        )
    }

    private fun save(game: CasinoGame) {
        val config = configs[game.id] ?: return
        val file = files[game.id] ?: return
        config.save(file)
    }
}
