package dev.xmuramasa.trismegistus.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay

/** The time (unix ms), ticking every second while [running]: run clocks and the reconnect countdown. */
@Composable
fun rememberNow(running: Boolean): State<Long> = produceState(System.currentTimeMillis(), running) {
    value = System.currentTimeMillis()
    while (running) {
        delay(TICK_MS)
        value = System.currentTimeMillis()
    }
}

private const val TICK_MS = 1000L
