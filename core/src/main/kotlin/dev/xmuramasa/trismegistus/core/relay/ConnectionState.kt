package dev.xmuramasa.trismegistus.core.relay

/** The relay socket as the app shows it. Connected only between `ready` and the moment the socket is known dead. */
public sealed interface ConnectionState {
    public data object Disconnected : ConnectionState

    public data object Connecting : ConnectionState

    public data class Connected(val device: String, val serverVersion: String) : ConnectionState

    /** Waiting out the backoff after a drop or a failed connect: [attempt] (1, 2, …) starts at [atMillis] (unix ms). */
    public data class Reconnecting(val attempt: Int, val atMillis: Long) : ConnectionState

    /** The relay refused the token (revoked, or never paired). The client stopped and won't retry on its own. */
    public data class AuthFailed(val message: String) : ConnectionState
}
