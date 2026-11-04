package dev.xmuramasa.trismegistus.data

import android.util.Log
import dev.xmuramasa.trismegistus.core.log.Logger

/** :core's log lines (ids, kinds, counts; never text or tokens) to Logcat. The app's own lines follow the same rule. */
val LogcatLogger: Logger = Logger { level, message ->
    val priority = when (level) {
        Logger.Level.DEBUG -> Log.DEBUG
        Logger.Level.INFO -> Log.INFO
        Logger.Level.WARN -> Log.WARN
    }
    Log.println(priority, "Trismegistus", message)
}
