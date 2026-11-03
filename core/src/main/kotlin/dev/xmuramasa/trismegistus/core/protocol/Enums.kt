package dev.xmuramasa.trismegistus.core.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Approval choices the app may send. Hermes' `always` is hidden on purpose: there is no case for it. */
@Serializable
public enum class ApprovalChoice {
    @SerialName("once") ONCE,
    @SerialName("session") SESSION,
    @SerialName("deny") DENY,
}

/** A run's status in the relay's `run` message. */
@Serializable
public enum class RunStatus {
    @SerialName("running") RUNNING,
    @SerialName("waiting_for_approval") WAITING_FOR_APPROVAL,
    @SerialName("completed") COMPLETED,
    @SerialName("failed") FAILED,
    @SerialName("cancelled") CANCELLED;

    public val isTerminal: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELLED
}

/** The relay's error codes, WebSocket and REST. */
@Serializable
public enum class ErrorCode {
    @SerialName("unauthorized") UNAUTHORIZED,
    @SerialName("bad_request") BAD_REQUEST,
    @SerialName("too_large") TOO_LARGE,
    @SerialName("rate_limited") RATE_LIMITED,
    @SerialName("busy") BUSY,
    @SerialName("not_found") NOT_FOUND,
    @SerialName("read_only") READ_ONLY,
    @SerialName("replay_gap") REPLAY_GAP,
    @SerialName("hermes_unavailable") HERMES_UNAVAILABLE,
    @SerialName("hermes_error") HERMES_ERROR,
    @SerialName("internal") INTERNAL,

    /** REST only (HTTP 403): a write from a `viewer` device. Not one of schema.json's WebSocket codes. */
    @SerialName("forbidden") FORBIDDEN,

    /** REST only (HTTP 503): a host helper the endpoint needs isn't set up. Not one of schema.json's codes. */
    @SerialName("not_configured") NOT_CONFIGURED,
}

/** Why the relay marked a gap with a `reconnected` event. */
@Serializable
public enum class ReconnectReason {
    @SerialName("stream_lost") STREAM_LOST,
    @SerialName("relay_restart") RELAY_RESTART,
}
