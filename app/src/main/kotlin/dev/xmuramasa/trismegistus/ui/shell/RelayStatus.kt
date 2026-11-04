package dev.xmuramasa.trismegistus.ui.shell

import androidx.compose.ui.graphics.Color
import dev.xmuramasa.trismegistus.core.relay.ConnectionState
import dev.xmuramasa.trismegistus.ui.theme.Ink

/** How the relay connection reads in the top bar, the drawer and settings. */
data class RelayStatus(val word: String, val color: Color, val isLinked: Boolean) {
    companion object {
        fun of(state: ConnectionState): RelayStatus = when (state) {
            is ConnectionState.Connected -> RelayStatus("linked", Ink.Status.online, true)
            ConnectionState.Connecting -> RelayStatus("connecting", Ink.Status.warn, false)
            is ConnectionState.Reconnecting -> RelayStatus("offline", Ink.Status.warn, false)
            is ConnectionState.AuthFailed -> RelayStatus("token refused", Ink.Status.bad, false)
            ConnectionState.Disconnected -> RelayStatus("offline", Ink.Text.muted, false)
        }

        /** `hermes.lab.x-muramasa.dev` as `hermes.lab`: the drawer's short name. */
        fun shortHost(host: String): String = host.split('.').take(2).joinToString(".")
    }
}
