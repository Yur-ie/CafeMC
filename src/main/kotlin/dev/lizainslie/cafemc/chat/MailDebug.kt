package dev.lizainslie.cafemc.chat

import dev.lizainslie.cafemc.CafeMC

object MailDebug {
    private const val CONFIG_KEY = "mailDebug"

    var enabled: Boolean
        get() = CafeMC.instance.config.getBoolean(CONFIG_KEY, false)
        set(value) {
            CafeMC.instance.config.set(CONFIG_KEY, value)
            CafeMC.instance.saveConfig()
        }

    fun log(message: String) {
        if (enabled) CafeMC.instance.logger.info(message)
    }
}
