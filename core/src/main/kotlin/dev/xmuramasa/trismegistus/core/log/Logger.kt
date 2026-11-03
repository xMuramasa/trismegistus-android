package dev.xmuramasa.trismegistus.core.log

/**
 * Where :core writes its log lines; the app backs it with Logcat. Lines carry ids, kinds, codes and counts: never
 * message text, approval commands or tokens.
 */
public fun interface Logger {
    public fun log(level: Level, message: String)

    public enum class Level { DEBUG, INFO, WARN }

    public companion object {
        public val None: Logger = Logger { _, _ -> }
    }
}

internal fun Logger.debug(message: String): Unit = log(Logger.Level.DEBUG, message)

internal fun Logger.info(message: String): Unit = log(Logger.Level.INFO, message)

internal fun Logger.warn(message: String): Unit = log(Logger.Level.WARN, message)
