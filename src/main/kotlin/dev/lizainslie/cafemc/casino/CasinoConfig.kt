package dev.lizainslie.cafemc.casino

import dev.lizainslie.cafemc.CafeMC
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import kotlin.math.max

object CasinoConfig {
    private val configFolder = File(CafeMC.instance.dataFolder, "casino/config")
    private val files = mutableMapOf<String, File>()
    private val configs = mutableMapOf<String, YamlConfiguration>()

    fun preGenerateAll(games: List<CasinoGame>) {
        if (!configFolder.exists()) configFolder.mkdirs()
        games.forEach { game ->
            val relativePath = "casino/config/${game.id}.yml"
            val dataFile = File(configFolder, "${game.id}.yml")
            if (!dataFile.exists()) {
                CafeMC.instance.saveResource(relativePath, false)
            }
            config(game)
        }
    }

    fun settings(game: CasinoGame): CasinoGameSettings {
        val config = config(game)
        return CasinoGameSettings(
            enabled = config.getBoolean("enabled", true),
            defaultBet = config.getDouble("bet.default", 10.0).coerceAtLeast(0.01),
            maxBet = config.getDouble("bet.max", 1_000.0).coerceAtLeast(0.01),
            cooldownSeconds = config.getInt("cooldownSeconds", 3).coerceAtLeast(0),
            targetHouseWinRate = config.getDouble("house.targetWinRate", 0.55).coerceIn(0.0, 1.0),
            houseEdgeInfluence = config.getDouble("house.influence", 0.25).coerceIn(0.0, 1.0),
            postResultOverrideEnabled = config.getBoolean("postResultOverride.enabled", false),
            postResultOverrideEveryGames = config.getInt("postResultOverride.everyGames", 0).coerceAtLeast(0),
            postResultOverrideMaxRerolls = config.getInt("postResultOverride.maxRerolls", 25).coerceAtLeast(1),
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

    fun applyPostResultOverride(
        game: CasinoGame,
        settings: CasinoGameSettings,
        naturalRound: CasinoRound,
        reroll: () -> CasinoRound,
    ): CasinoRound {
        if (!settings.postResultOverrideEnabled) return naturalRound
        if (settings.postResultOverrideEveryGames <= 0) return naturalRound

        val config = config(game)
        val nextRound = config.getLong("stats.rounds", 0) + 1
        if (nextRound % settings.postResultOverrideEveryGames.toLong() != 0L) return naturalRound

        config.set("stats.postResultOverrides", config.getLong("stats.postResultOverrides", 0) + 1)
        if (naturalRound.houseWon) {
            save(game)
            return naturalRound
        }

        repeat(settings.postResultOverrideMaxRerolls) {
            val overrideRound = reroll()
            if (overrideRound.houseWon) {
                save(game)
                return overrideRound
            }
        }

        save(game)
        return naturalRound
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
        config.addDefault("postResultOverride.enabled", false)
        config.addDefault("postResultOverride.everyGames", 0)
        config.addDefault("postResultOverride.maxRerolls", 25)
        config.addDefault("stats.rounds", 0)
        config.addDefault("stats.houseWins", 0)
        config.addDefault("stats.playerWins", 0)
        config.addDefault("stats.moneyIn", 0.0)
        config.addDefault("stats.moneyOut", 0.0)
        config.addDefault("stats.postResultOverrides", 0)
        config.options().copyDefaults(true)
        config.options().header(
            """
            ${game.displayName} casino settings.
            targetWinRate is the approximate share of rounds the house should win.
            influence controls how strongly the game nudges outcomes toward that target.
            postResultOverride can force every Nth round through hidden rerolls before the player sees it.
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
