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

/**
 * A prompt the app sent, shown until the history has it. [id] is its client_msg_id. A steer ([isSteer]) is [Status.Sent]
 * from the start (the relay doesn't acknowledge steers) and stays until the history has its text or its run ends;
 * [afterSeq] is the session's seq when it was sent, so the timeline places it among the run's events.
 */
public data class OutgoingPrompt(
    val id: String,
    val text: String,
    val status: Status,
    val images: List<ImageUpload> = emptyList(),
    val isSteer: Boolean = false,
    val afterSeq: Long = 0,
) {
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
    /**
     * The history misses something another device sent or a run just finished: the store reloads it, keeping the
     * timeline on screen meanwhile (unlike [historyLoaded] false). The relay's stream carries no event for a prompt
     * or a steer, so a run this device didn't start is the only sign of one.
     */
    val historyStale: Boolean = false,
    /** The run this device didn't start whose first event already asked for a reload (Hermes stores its prompt late). */
    val rechecked: String? = null,
) {
    public companion object {
        public const val MAX_EVENTS: Int = 1000
    }

    /** This device's prompt started [runId] (its ack named it). */
    public fun isOwnRun(runId: String): Boolean =
        outbox.any { !it.isSteer && (it.status as? OutgoingPrompt.Status.Sent)?.runId == runId }

    /** A steer sent into the running turn: shown at once, until the history has it. */
    public fun steerSent(id: String, runId: String, text: String): SessionState =
        copy(outbox = outbox + OutgoingPrompt(id, text, OutgoingPrompt.Status.Sent(runId), isSteer = true, afterSeq = lastSeq))

    public fun apply(message: ServerMessage): SessionState = when (message) {
        is ServerMessage.Event -> if (message.sessionId == id) apply(message) else this
        is ServerMessage.Run -> when {
            message.sessionId != id -> this
            message.status.isTerminal -> end(message.runId)
            else -> copy(
                run = ActiveRun(message.runId, message.status),
                // A run that appears without our ack: another device's prompt, which only the history has.
                historyStale = historyStale || (run?.id != message.runId && !isOwnRun(message.runId)),
            )
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
            EventPayload.RunCompleted, is EventPayload.RunFailed, is EventPayload.RunCancelled -> return s.end(e.runId)
            is EventPayload.ToolStarted, is EventPayload.ToolCompleted, is EventPayload.SubagentStarted,
            is EventPayload.SubagentCompleted, EventPayload.SteerQueued ->
                if (run?.id != e.runId) s.copy(run = ActiveRun(e.runId, RunStatus.RUNNING)) else s
            is EventPayload.Answer, is EventPayload.Reconnected, is EventPayload.Unknown -> s
        }.recheck(e.runId)
    }

    /**
     * The first event of a run this device didn't start asks for the history once more: Hermes stores the prompt a few
     * ms after the relay announces the run, so the reload the announcement caused may have missed it.
     */
    private fun recheck(runId: String): SessionState =
        if (rechecked == runId || isOwnRun(runId)) this else copy(historyStale = true, rechecked = runId)

    /** A run ended: the history has the finished turn (and any prompt or steer this device didn't see). */
    private fun end(runId: String): SessionState = copy(
        run = run?.takeIf { it.id != runId },
        approvals = approvals.filter { it.runId != runId },
        historyStale = true,
    )

    /**
     * trismegistus #11: history that arrives after live events already holds every finished turn. Keep only the
     * live events of the run still going and the prompts the relay hasn't acknowledged, so nothing shows twice.
     */
    public fun applyHistory(messages: List<HistoryMessage>): SessionState {
        val steers = messages.filter { it.isSteer }.map { it.content.trim() }.toSet()
        return copy(
            history = messages,
            historyLoaded = true,
            historyError = null,
            historyStale = false,
            events = events.filter { it.runId == run?.id },
            outbox = outbox.filter { p ->
                val sent = p.status as? OutgoingPrompt.Status.Sent
                when {
                    sent == null -> true
                    // A steer is stored when Hermes takes it (the next step), not when sent: keep it until then.
                    p.isSteer -> sent.runId == run?.id && p.text.trim() !in steers
                    else -> false
                }
            },
        )
    }
}
