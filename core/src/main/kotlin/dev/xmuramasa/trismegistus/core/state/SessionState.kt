package dev.xmuramasa.trismegistus.core.state

import dev.xmuramasa.trismegistus.core.protocol.ErrorCode
import dev.xmuramasa.trismegistus.core.protocol.EventPayload
import dev.xmuramasa.trismegistus.core.protocol.RunStatus
import dev.xmuramasa.trismegistus.core.protocol.ServerMessage
import dev.xmuramasa.trismegistus.core.relay.HistoryMessage

/** The run going in an open session. */
public data class ActiveRun(val id: String, val status: RunStatus)

/** An approval waiting for an answer; answer it with `approve` and [runId], [id]. */
public data class PendingApproval(val sessionId: String, val runId: String, val request: EventPayload.ApprovalRequested) {
    public val id: String get() = request.requestId
}

/** An image for a prompt; [attachmentId] once uploaded. The bytes are kept so a retry can upload them again. */
public class ImageUpload(public val data: ByteArray, public val mime: String, public val attachmentId: String? = null)

/** A prompt the app sent, shown until the history has it. [id] is its client_msg_id. */
public data class OutgoingPrompt(val id: String, val text: String, val status: Status, val images: List<ImageUpload> = emptyList()) {
    public sealed interface Status {
        public data object Pending : Status

        public data class Sent(val runId: String) : Status

        /** [code] is null when it never reached the relay. */
        public data class Failed(val code: ErrorCode?, val message: String) : Status
    }
}

/**
 * One open session's timeline: REST history plus the live events the history doesn't have yet, the run, approvals
 * and the outbox. A pure value; SessionStore feeds it, and the rules (dedupe, trismegistus #10 and #11) are tested
 * on it directly.
 */
public data class SessionState(
    val id: String,
    val history: List<HistoryMessage> = emptyList(),
    /** False until the history arrives, and again when it must be reloaded (replay_gap, a reconnect without a seq). */
    val historyLoaded: Boolean = false,
    val historyError: String? = null,
    /** Live events, oldest first. Events of finished runs leave when the history arrives (it has them). */
    val events: List<ServerMessage.Event> = emptyList(),
    /** Highest seq applied; 0 before the first event and after a replay_gap. */
    val lastSeq: Long = 0,
    val run: ActiveRun? = null,
    val approvals: List<PendingApproval> = emptyList(),
    val outbox: List<OutgoingPrompt> = emptyList(),
) {
    public companion object {
        public const val MAX_EVENTS: Int = 1000
    }

    public fun apply(message: ServerMessage): SessionState = when (message) {
        is ServerMessage.Event -> if (message.sessionId == id) apply(message) else this
        is ServerMessage.Run -> when {
            message.sessionId != id -> this
            message.status.isTerminal -> end(message.runId)
            else -> copy(run = ActiveRun(message.runId, message.status))
        }
        is ServerMessage.Ack -> {
            if (outbox.none { it.id == message.clientMsgId }) {
                this
            } else {
                copy(
                    outbox = outbox.map { if (it.id == message.clientMsgId) it.copy(status = OutgoingPrompt.Status.Sent(message.runId)) else it },
                    // A replayed ack may name a run that already ended; the relay sends `run` for live ones.
                    run = if (!message.replayed && run?.id != message.runId) ActiveRun(message.runId, RunStatus.RUNNING) else run,
                )
            }
        }
        // trismegistus #10: the relay no longer has the events after our seq (pruned, or its database was reset and
        // seqs start again at 1). Forget the seq so new events aren't dropped as repeats, and reload the history.
        is ServerMessage.Error -> if (message.code == ErrorCode.REPLAY_GAP && message.ref == id) {
            copy(lastSeq = 0, historyLoaded = false)
        } else {
            copy(
                outbox = outbox.map {
                    if (it.id == message.ref && it.status == OutgoingPrompt.Status.Pending) {
                        it.copy(status = OutgoingPrompt.Status.Failed(message.code, message.message))
                    } else {
                        it
                    }
                },
            )
        }
        is ServerMessage.Ready, ServerMessage.Pong -> this
    }

    private fun apply(e: ServerMessage.Event): SessionState {
        // The relay sends a session's events in seq order (replay, then live): at or below lastSeq is a repeat.
        if (e.seq <= lastSeq) return this
        val s = copy(lastSeq = e.seq, events = (events + e).takeLast(MAX_EVENTS))
        return when (val p = e.payload) {
            is EventPayload.ApprovalRequested -> s.copy(
                approvals = if (approvals.any { it.id == p.requestId }) approvals else approvals + PendingApproval(id, e.runId, p),
                run = ActiveRun(e.runId, RunStatus.WAITING_FOR_APPROVAL),
            )
            is EventPayload.ApprovalResolved -> s.copy(
                approvals = approvals.filter { it.id != p.requestId },
                run = run?.takeIf { it.id == e.runId }?.copy(status = RunStatus.RUNNING) ?: run,
            )
            EventPayload.RunCompleted, is EventPayload.RunFailed, is EventPayload.RunCancelled -> s.end(e.runId)
            is EventPayload.ToolStarted, is EventPayload.ToolCompleted, is EventPayload.SubagentStarted,
            is EventPayload.SubagentCompleted, EventPayload.SteerQueued ->
                if (run?.id != e.runId) s.copy(run = ActiveRun(e.runId, RunStatus.RUNNING)) else s
            is EventPayload.Answer, is EventPayload.Reconnected, is EventPayload.Unknown -> s
        }
    }

    private fun end(runId: String): SessionState =
        copy(run = run?.takeIf { it.id != runId }, approvals = approvals.filter { it.runId != runId })

    /**
     * trismegistus #11: history that arrives after live events already holds every finished turn. Keep only the
     * live events of the run still going and the prompts the relay hasn't acknowledged, so nothing shows twice.
     */
    public fun applyHistory(messages: List<HistoryMessage>): SessionState = copy(
        history = messages,
        historyLoaded = true,
        historyError = null,
        events = events.filter { it.runId == run?.id },
        outbox = outbox.filter { it.status !is OutgoingPrompt.Status.Sent },
    )
}
