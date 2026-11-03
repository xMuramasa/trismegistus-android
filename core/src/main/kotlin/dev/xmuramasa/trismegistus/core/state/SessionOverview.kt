package dev.xmuramasa.trismegistus.core.state

import dev.xmuramasa.trismegistus.core.protocol.ErrorCode
import dev.xmuramasa.trismegistus.core.protocol.EventPayload
import dev.xmuramasa.trismegistus.core.protocol.RunStatus
import dev.xmuramasa.trismegistus.core.protocol.ServerMessage
import dev.xmuramasa.trismegistus.core.relay.SessionInfo
import kotlin.math.roundToLong

/**
 * One conversation's row in the list: what the relay says about it live, for every followed session whether open or
 * not (RUN / APPROVAL marks, tab badges). A pure value; SessionStore feeds it. Times are unix milliseconds.
 */
public data class SessionOverview(
    val id: String,
    /** From the REST list; null for an open session the list doesn't have (yet). */
    val info: SessionInfo? = null,
    val run: Run? = null,
    val approvals: List<PendingApproval> = emptyList(),
    /** Highest seq applied; 0 before the first event and after the relay lost track (replay_gap, live resubscribe). */
    val lastSeq: Long = 0,
    /** The newest event's time. */
    val lastEventAt: Long? = null,
    /** The last run that ended while followed. A lost track (reconnect, replay_gap) is not an ending. */
    val lastOutcome: Outcome? = null,
) {
    /** [startedAt]: when the app first saw the run. [toolCount]: tools started since. */
    public data class Run(val id: String, val status: RunStatus, val startedAt: Long, val toolCount: Int = 0)

    /** How a run ended: [status] is completed, failed or cancelled. */
    public data class Outcome(val runId: String, val status: RunStatus, val at: Long)

    public val source: String? get() = info?.source
    public val isReadOnly: Boolean get() = info?.isReadOnly ?: false
    public val pendingApproval: PendingApproval? get() = approvals.firstOrNull()

    /** True also when the relay says a run waits but its request came before the app subscribed (no request id). */
    public val isWaitingForApproval: Boolean get() = approvals.isNotEmpty() || run?.status == RunStatus.WAITING_FOR_APPROVAL

    public val lastActivity: Long?
        get() = listOfNotNull(info?.lastActive?.let { (it * 1000).roundToLong() }, lastEventAt).maxOrNull()

    /** [at] is now: the time of a `run` message, which carries none. */
    public fun apply(message: ServerMessage, at: Long): SessionOverview = when (message) {
        is ServerMessage.Event -> if (message.sessionId == id) apply(message) else this
        is ServerMessage.Run -> when {
            message.sessionId != id -> this
            message.status.isTerminal -> end(message.runId, message.status, at)
            else -> copy(
                run = run?.takeIf { it.id == message.runId }?.copy(status = message.status)
                    ?: Run(message.runId, message.status, at),
            )
        }
        is ServerMessage.Error -> if (message.code == ErrorCode.REPLAY_GAP && message.ref == id) lostTrack() else this
        else -> this
    }

    private fun apply(e: ServerMessage.Event): SessionOverview {
        if (e.seq <= lastSeq) return this // a repeat
        val at = e.ts
        val seen = copy(lastSeq = e.seq, lastEventAt = maxOf(lastEventAt ?: at, at))
        when (e.payload) {
            EventPayload.RunCompleted -> return seen.end(e.runId, RunStatus.COMPLETED, at)
            is EventPayload.RunFailed -> return seen.end(e.runId, RunStatus.FAILED, at)
            is EventPayload.RunCancelled -> return seen.end(e.runId, RunStatus.CANCELLED, at)
            else -> Unit
        }
        // Replayed events are older than the `run` message that started the row.
        var run = run?.takeIf { it.id == e.runId }?.let { if (at < it.startedAt) it.copy(startedAt = at) else it }
            ?: Run(e.runId, RunStatus.RUNNING, at)
        var approvals = approvals
        when (val p = e.payload) {
            is EventPayload.ToolStarted -> run = run.copy(toolCount = run.toolCount + 1)
            is EventPayload.ApprovalRequested -> {
                if (approvals.none { it.id == p.requestId }) approvals = approvals + PendingApproval(id, e.runId, p)
                run = run.copy(status = RunStatus.WAITING_FOR_APPROVAL)
            }
            is EventPayload.ApprovalResolved -> {
                approvals = approvals.filter { it.id != p.requestId }
                if (approvals.isEmpty()) run = run.copy(status = RunStatus.RUNNING)
            }
            else -> Unit
        }
        return seen.copy(run = run, approvals = approvals)
    }

    /** The session was (re)subscribed without a seq: what was live may be over; the relay re-sends the active run. */
    internal fun lostTrack(): SessionOverview = copy(lastSeq = 0, run = null, approvals = emptyList())

    // The event and the relay's `run` message both report an end; the event comes first and has the time.
    private fun end(runId: String, status: RunStatus, at: Long): SessionOverview = copy(
        run = run?.takeIf { it.id != runId },
        lastOutcome = lastOutcome?.takeIf { it.runId == runId } ?: Outcome(runId, status, at),
        approvals = approvals.filter { it.runId != runId },
    )
}
