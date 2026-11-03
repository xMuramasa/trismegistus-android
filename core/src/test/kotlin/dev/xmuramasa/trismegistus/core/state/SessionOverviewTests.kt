package dev.xmuramasa.trismegistus.core.state

import dev.xmuramasa.trismegistus.core.event
import dev.xmuramasa.trismegistus.core.protocol.ApprovalChoice
import dev.xmuramasa.trismegistus.core.protocol.ErrorCode
import dev.xmuramasa.trismegistus.core.protocol.EventPayload
import dev.xmuramasa.trismegistus.core.protocol.RunStatus
import dev.xmuramasa.trismegistus.core.protocol.ServerMessage
import dev.xmuramasa.trismegistus.core.relay.SessionInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Before every event() timestamp, in unix ms. */
private const val T0 = 1_792_801_000_000L

/** The pure row rules on SessionOverview. */
class SessionOverviewTests {
    private fun SessionOverview.feed(vararg messages: ServerMessage, at: Long = T0) = messages.fold(this) { o, m -> o.apply(m, at) }

    @Test
    fun runStartsCountsToolsAndEnds() {
        var o = SessionOverview("s1").feed(
            ServerMessage.Run("run_1", "s1", RunStatus.RUNNING),
            event("s1", 1, EventPayload.ToolStarted("terminal")),
            event("s1", 2, EventPayload.ToolCompleted("terminal", 0.1, false)),
            event("s1", 3, EventPayload.ToolStarted("read_file")),
            event("s1", 3, EventPayload.ToolStarted("read_file")), // repeat
            event("s2", 9, EventPayload.ToolStarted("terminal")), // another session's
        )
        assertEquals(SessionOverview.Run("run_1", RunStatus.RUNNING, T0, toolCount = 2), o.run)
        assertEquals(1_792_801_004_586, o.lastActivity)
        o = o.feed(event("s1", 4, EventPayload.RunCompleted))
        assertTrue(o.run == null && o.lastSeq == 4L)
        o = o.feed(ServerMessage.Run("run_2", "s1", RunStatus.RUNNING), ServerMessage.Run("run_2", "s1", RunStatus.CANCELLED))
        assertNull(o.run)
    }

    @Test
    fun approvalWaitsUntilResolvedOrTheRunEnds() {
        val request = EventPayload.ApprovalRequested("req_1", "rm -rf x", choices = listOf(ApprovalChoice.ONCE, ApprovalChoice.DENY))
        var o = SessionOverview("s1").feed(event("s1", 1, request))
        assertTrue(o.pendingApproval?.id == "req_1" && o.run?.status == RunStatus.WAITING_FOR_APPROVAL && o.isWaitingForApproval)
        o = o.feed(event("s1", 2, EventPayload.ApprovalResolved("req_1", "once")))
        assertTrue(o.pendingApproval == null && o.run?.status == RunStatus.RUNNING && !o.isWaitingForApproval)
        o = o.feed(
            event("s1", 3, EventPayload.ApprovalRequested("req_2", "x", choices = listOf(ApprovalChoice.DENY))),
            event("s1", 4, EventPayload.RunFailed("provider error")),
        )
        assertTrue(o.approvals.isEmpty() && o.run == null)
    }

    /** Subscribed mid-run without a seq: the relay's `run` status is all there is. */
    @Test
    fun runStatusFromSubscribeAndReplayGap() {
        var o = SessionOverview("s1", info = SessionInfo("s1", source = "telegram", lastActive = 1_792_801_100.0))
            .feed(ServerMessage.Run("run_1", "s1", RunStatus.WAITING_FOR_APPROVAL))
        assertTrue(o.isWaitingForApproval && o.pendingApproval == null)
        assertTrue(o.isReadOnly && o.source == "telegram" && o.lastActivity == 1_792_801_100_000)
        o = o.feed(event("s1", 40, EventPayload.SteerQueued), ServerMessage.Error(ErrorCode.REPLAY_GAP, "reload the history", "s1"))
        assertTrue(o.lastSeq == 0L && o.run == null)
        o = o.feed(event("s1", 1, EventPayload.ToolStarted("terminal"), run = "run_2"))
        assertEquals("run_2" to 1, o.run?.let { it.id to it.toolCount })
    }

    @Test
    fun replayedEventsDateTheRunStart() {
        val o = SessionOverview("s1").feed(ServerMessage.Run("run_1", "s1", RunStatus.RUNNING), at = T0 + 60_000)
            .feed(event("s1", 5, EventPayload.ToolStarted("terminal")))
        assertEquals(1_792_801_004_588, o.run?.startedAt)
    }

    @Test
    fun keepsHowTheLastRunEnded() {
        var o = SessionOverview("s1").feed(event("s1", 1, EventPayload.ToolStarted("terminal")), event("s1", 2, EventPayload.RunFailed("provider error")))
        val failed = SessionOverview.Outcome("run_1", RunStatus.FAILED, 1_792_801_004_585)
        assertEquals(failed, o.lastOutcome)
        o = o.feed(ServerMessage.Run("run_1", "s1", RunStatus.FAILED), at = T0 + 60_000) // the relay's echo
        assertEquals(failed, o.lastOutcome)

        o = o.feed(event("s1", 3, EventPayload.RunCancelled(), run = "run_2"))
        assertEquals("run_2" to RunStatus.CANCELLED, o.lastOutcome?.let { it.runId to it.status })
        o = o.feed(event("s1", 4, EventPayload.RunCompleted, run = "run_3"))
        assertEquals("run_3" to RunStatus.COMPLETED, o.lastOutcome?.let { it.runId to it.status })
        // Only the relay's `run` message: ended now.
        o = o.feed(ServerMessage.Run("run_4", "s1", RunStatus.RUNNING)).feed(ServerMessage.Run("run_4", "s1", RunStatus.COMPLETED), at = T0 + 5_000)
        assertEquals(SessionOverview.Outcome("run_4", RunStatus.COMPLETED, T0 + 5_000), o.lastOutcome)
    }

    @Test
    fun lostTrackIsNotAnEnding() {
        var o = SessionOverview("s1").feed(event("s1", 1, EventPayload.RunCompleted), event("s1", 2, EventPayload.ToolStarted("terminal"), run = "run_2"))
            .lostTrack() // reconnect
        assertTrue(o.run == null && o.lastSeq == 0L && o.lastOutcome?.runId == "run_1")
        o = o.feed(
            event("s1", 3, EventPayload.ToolStarted("terminal"), run = "run_2"),
            ServerMessage.Error(ErrorCode.REPLAY_GAP, "reload the history", "s1"),
        )
        assertTrue(o.run == null && o.lastOutcome?.runId == "run_1")
    }
}
