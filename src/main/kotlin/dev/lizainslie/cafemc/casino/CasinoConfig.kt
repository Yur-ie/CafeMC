package dev.lizainslie.cafemc.casino

import dev.lizainslie.cafemc.CafeMC
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import kotlin.math.max

object CasinoConfig {
    private val configFolder = File(CafeMC.instance.dataFolder, "casino/config")
    private val mainFile = File(configFolder, "main.yml")
    private val files = mutableMapOf<String, File>()
    private val configs = mutableMapOf<String, YamlConfiguration>()
    private var mainConfig: YamlConfiguration? = null

    fun preGenerateAll(games: List<CasinoGame>) {
        if (!configFolder.exists()) configFolder.mkdirs()
        if (!mainFile.exists()) {
            CafeMC.instance.saveResource("casino/config/main.yml", false)
        }
        main()
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

    fun mainSettings(): CasinoMainSettings {
        val config = main()
        val modeName = config.getString("economy.mode", "money").orEmpty()
        val mode = CasinoEconomyMode.entries.firstOrNull { it.name.equals(modeName, ignoreCase = true) }
            ?: CasinoEconomyMode.MONEY
        return CasinoMainSettings(
            mode = mode,
            chipBuyInRate = config.getDouble("chips.buyInRate", 1.0).coerceAtLeast(0.0),
            chipCashoutRate = config.getDouble("chips.cashoutRate", 0.75).coerceAtLeast(0.0),
            chipCurrencyName = config.getString("chips.currencyName", "Chips").orEmpty().ifBlank { "Chips" },
            dailyBetLimit = config.getDouble("limits.dailyBet", 50_000.0).coerceAtLeast(0.0),
            dailyLossLimit = config.getDouble("limits.dailyLoss", 10_000.0).coerceAtLeast(0.0),
            weeklyBetLimit = config.getDouble("limits.weeklyBet", 250_000.0).coerceAtLeast(0.0),
            weeklyLossLimit = config.getDouble("limits.weeklyLoss", 50_000.0).coerceAtLeast(0.0),
            adaptiveCooldownStep = config.getInt("limits.adaptiveCooldownStepSeconds", 1).coerceAtLeast(0),
            adaptiveCooldownMax = config.getInt("limits.adaptiveCooldownMaxSeconds", 20).coerceAtLeast(0),
            edgeMin = config.getDouble("house.edgeMin", 0.45).coerceIn(0.0, 1.0),
            edgeMax = config.getDouble("house.edgeMax", 0.75).coerceIn(0.0, 1.0),
            bigBetConfirmAmount = config.getDouble("ui.bigBetConfirmAmount", 5_000.0).coerceAtLeast(0.0),
            itemBuyInRules = parseItems(config, "items.buyIn"),
            itemRedeemRules = parseItems(config, "items.redeem"),
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

    private fun main(): YamlConfiguration {
        mainConfig?.let { return it }
        val config = if (mainFile.exists()) YamlConfiguration.loadConfiguration(mainFile) else YamlConfiguration()
        applyMainDefaults(config)
        config.save(mainFile)
        mainConfig = config
        return config
    }

    private fun parseItems(config: YamlConfiguration, path: String): List<CasinoItemRule> {
        val section = config.getConfigurationSection(path) ?: return emptyList()
        return section.getKeys(false).mapNotNull { key ->
            val base = "$path.$key"
            val material = Material.matchMaterial(config.getString("$base.material").orEmpty()) ?: return@mapNotNull null
            val pdcRaw = config.getConfigurationSection("$base.requiredPdc")
                ?.getKeys(false)
                ?.associateWith { pdcKey -> config.getString("$base.requiredPdc.$pdcKey").orEmpty() }
                .orEmpty()
            CasinoItemRule(
                id = key,
                material = material,
                amount = config.getInt("$base.amount", 1).coerceAtLeast(1),
                chips = config.getDouble("$base.chips", 0.0),
                customModelData = if (config.contains("$base.customModelData")) config.getInt("$base.customModelData") else null,
                displayNameContains = config.getString("$base.displayNameContains"),
                loreContains = config.getString("$base.loreContains"),
                requiredPdc = pdcRaw,
                nbtLike = config.getString("$base.nbtLike"),
                entityDataLike = config.getString("$base.entityDataLike"),
            )
        }
    }

    private fun applyMainDefaults(config: YamlConfiguration) {
        config.addDefault("economy.mode", "money")
        config.addDefault("chips.buyInRate", 1.0)
        config.addDefault("chips.cashoutRate", 0.75)
        config.addDefault("chips.currencyName", "Chips")
        config.addDefault("limits.dailyBet", 50_000.0)
        config.addDefault("limits.dailyLoss", 10_000.0)
        config.addDefault("limits.weeklyBet", 250_000.0)
        config.addDefault("limits.weeklyLoss", 50_000.0)
        config.addDefault("limits.adaptiveCooldownStepSeconds", 1)
        config.addDefault("limits.adaptiveCooldownMaxSeconds", 20)
        config.addDefault("house.edgeMin", 0.45)
        config.addDefault("house.edgeMax", 0.75)
        config.addDefault("ui.bigBetConfirmAmount", 5_000.0)
        config.options().copyDefaults(true)
        config.options().header(
            """
            Main casino economy settings.
            mode: money | chips | items
            chips: buyInRate = money to chips multiplier, cashoutRate = chips to money multiplier.
            items: buyIn/redeem rules can match by material + optional model/name/lore/PDC.
            nbtLike/entityDataLike are freeform notes to document intent for custom item/entity data.
            """.trimIndent()
        )
    }
}

data class CasinoMainSettings(
    val mode: CasinoEconomyMode,
    val chipBuyInRate: Double,
    val chipCashoutRate: Double,
    val chipCurrencyName: String,
    val dailyBetLimit: Double,
    val dailyLossLimit: Double,
    val weeklyBetLimit: Double,
    val weeklyLossLimit: Double,
    val adaptiveCooldownStep: Int,
    val adaptiveCooldownMax: Int,
    val edgeMin: Double,
    val edgeMax: Double,
    val bigBetConfirmAmount: Double,
    val itemBuyInRules: List<CasinoItemRule>,
    val itemRedeemRules: List<CasinoItemRule>,
)

data class CasinoItemRule(
    val id: String,
    val material: Material,
    val amount: Int,
    val chips: Double,
    val customModelData: Int?,
    val displayNameContains: String?,
    val loreContains: String?,
    val requiredPdc: Map<String, String>,
    val nbtLike: String?,
    val entityDataLike: String?,
) {
    fun pdcNamespacedKeys() = requiredPdc.mapKeys { NamespacedKey.fromString(it.key, CafeMC.instance) ?: NamespacedKey.minecraft(it.key.lowercase()) }
}
