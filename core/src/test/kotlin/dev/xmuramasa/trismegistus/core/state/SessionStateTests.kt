package dev.xmuramasa.trismegistus.core.state

import dev.xmuramasa.trismegistus.core.event
import dev.xmuramasa.trismegistus.core.protocol.ApprovalChoice
import dev.xmuramasa.trismegistus.core.protocol.ErrorCode
import dev.xmuramasa.trismegistus.core.protocol.EventPayload
import dev.xmuramasa.trismegistus.core.protocol.RunStatus
import dev.xmuramasa.trismegistus.core.protocol.ServerMessage
import dev.xmuramasa.trismegistus.core.relay.HistoryMessage
import kotlin.test.Test
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

val answeredHistory = listOf(
    HistoryMessage("1", HistoryMessage.Role.USER, "What year is it?"),
    HistoryMessage("2", HistoryMessage.Role.ASSISTANT, "It is 2026."),
)

/** The pure rules on SessionState. */
class SessionStateTests {
    private fun SessionState.feed(vararg messages: ServerMessage) = messages.fold(this) { s, m -> s.apply(m) }

    @Test
    fun duplicateSeqIsIgnored() {
        val s = SessionState("s1")
            .feed(*listOf(1L, 2, 2, 1, 3).map { ServerMessage.Event("s1", "run_1", it, 1, EventPayload.SteerQueued) }.toTypedArray())
            .feed(event("s2", 4, EventPayload.SteerQueued)) // another session's
        assertEquals(listOf(1L, 2, 3), s.events.map { it.seq })
        assertEquals(3, s.lastSeq)
    }

    /** trismegistus#10: after replay_gap, events starting again from seq 1 must be applied. */
    @Test
    fun replayGapResetsTheSeqAndAsksForHistory() {
        var s = SessionState("s1").applyHistory(emptyList())
            .feed(event("s1", 40, EventPayload.RunCompleted), ServerMessage.Error(ErrorCode.REPLAY_GAP, "reload the history", "other"))
        assertTrue(s.lastSeq == 40L && s.historyLoaded)
        s = s.feed(ServerMessage.Error(ErrorCode.REPLAY_GAP, "reload the history", "s1"))
        assertTrue(s.lastSeq == 0L && !s.historyLoaded)
        s = s.applyHistory(answeredHistory).feed(
            event("s1", 1, EventPayload.ToolStarted("terminal"), run = "run_2"),
            event("s1", 2, EventPayload.Answer("done"), run = "run_2"),
        )
        assertEquals(listOf(1L, 2), s.events.map { it.seq })
        assertEquals(2, s.lastSeq)
    }

    /** trismegistus#11: live answer and run.completed, then the history (which has the answer): one answer. */
    @Test
    fun lateHistoryDoesNotDuplicateAFinishedTurn() {
        var s = SessionState("s1", outbox = listOf(OutgoingPrompt("c1", "What year is it?", OutgoingPrompt.Status.Pending)))
            .feed(ServerMessage.Ack("c1", "run_1", replayed = false))
        assertEquals(ActiveRun("run_1", RunStatus.RUNNING), s.run)
        s = s.feed(
            event("s1", 1, EventPayload.ToolStarted("terminal")),
            event("s1", 2, EventPayload.Answer("It is 2026.")),
            event("s1", 3, EventPayload.RunCompleted),
        )
        assertNull(s.run)
        s = s.applyHistory(answeredHistory)
        val liveAnswers = s.events.count { it.kind == "answer" }
        val historyAnswers = s.history.count { it.role == HistoryMessage.Role.ASSISTANT && it.content == "It is 2026." }
        assertEquals(1, liveAnswers + historyAnswers)
        assertTrue(s.events.isEmpty() && s.outbox.isEmpty())
        // A late repeat of an old event stays out too.
        assertTrue(s.feed(event("s1", 2, EventPayload.Answer("It is 2026."))).events.isEmpty())
    }

    @Test
    fun lateHistoryKeepsTheRunningTurnAndUnsentPrompts() {
        val s = SessionState("s1", outbox = listOf(OutgoingPrompt("c2", "and next year?", OutgoingPrompt.Status.Pending)))
            .feed(event("s1", 1, EventPayload.RunCompleted), event("s1", 2, EventPayload.ToolStarted("terminal"), run = "run_2"))
            .applyHistory(answeredHistory)
        assertEquals(listOf(2L), s.events.map { it.seq })
        assertEquals(listOf("c2"), s.outbox.map { it.id })
    }

    @Test
    fun approvalsFollowRequestResolveAndRunEnd() {
        val request = EventPayload.ApprovalRequested("req_1", "rm -rf x", choices = listOf(ApprovalChoice.ONCE, ApprovalChoice.DENY))
        var s = SessionState("s1").feed(event("s1", 1, request))
        assertEquals(listOf("req_1"), s.approvals.map { it.id })
        assertEquals(RunStatus.WAITING_FOR_APPROVAL, s.run?.status)
        s = s.feed(event("s1", 2, EventPayload.ApprovalResolved("req_1", "once")))
        assertTrue(s.approvals.isEmpty())
        assertEquals(RunStatus.RUNNING, s.run?.status)
        s = s.feed(
            event("s1", 3, EventPayload.ApprovalRequested("req_2", "x", choices = listOf(ApprovalChoice.DENY))),
            event("s1", 4, EventPayload.RunFailed("provider error")),
        )
        assertTrue(s.approvals.isEmpty() && s.run == null)
    }

    @Test
    fun runMessagesSetAndEndTheRun() {
        var s = SessionState("s1").feed(
            ServerMessage.Run("run_1", "s1", RunStatus.RUNNING),
            ServerMessage.Run("run_1", "s2", RunStatus.COMPLETED),
            ServerMessage.Run("run_0", "s1", RunStatus.COMPLETED),
        )
        assertEquals(ActiveRun("run_1", RunStatus.RUNNING), s.run)
        s = s.feed(ServerMessage.Run("run_1", "s1", RunStatus.CANCELLED))
        assertNull(s.run)
    }

    @Test
    fun promptErrorsMarkTheOutbox() {
        var s = SessionState("s1", outbox = listOf(OutgoingPrompt("c1", "hi", OutgoingPrompt.Status.Pending)))
            .feed(ServerMessage.Error(ErrorCode.BUSY, "a run is already active in this session", "c1"))
        assertEquals(OutgoingPrompt.Status.Failed(ErrorCode.BUSY, "a run is already active in this session"), s.outbox.first().status)
        s = s.copy(outbox = s.outbox + OutgoingPrompt("c2", "hi again", OutgoingPrompt.Status.Pending))
            .feed(ServerMessage.Ack("c2", "run_5", replayed = true))
        assertEquals(OutgoingPrompt.Status.Sent("run_5"), s.outbox.last().status)
        assertNull(s.run) // replayed: the run may be long over
    }

    @Test
    fun eventsAreCapped() {
        val s = (1L..SessionState.MAX_EVENTS + 5).fold(SessionState("s1")) { st, seq -> st.apply(event("s1", seq, EventPayload.SteerQueued)) }
        assertEquals(SessionState.MAX_EVENTS, s.events.size)
        assertEquals(6L, s.events.first().seq)
        assertFalse(s.historyLoaded)
    }

    /** android#8 with a relay older than trismegistus#30: another device's run's first event asks for the history. */
    @Test
    fun anotherDevicesRunAsksForTheHistory() {
        var s = SessionState("s1").applyHistory(answeredHistory).feed(ServerMessage.Run("run_9", "s1", RunStatus.RUNNING))
        assertFalse(s.historyStale) // the prompt may not be stored yet: wait for the run's first event
        s = s.feed(event("s1", 1, EventPayload.ToolStarted("terminal"), run = "run_9"))
        assertTrue(s.historyStale)
        assertTrue(s.historyLoaded) // the timeline stays on screen while it reloads
        s = s.applyHistory(answeredHistory).feed(event("s1", 2, EventPayload.Answer("Hi."), run = "run_9"))
        assertFalse(s.historyStale)
        assertEquals(listOf(1L, 2L), s.events.map { it.seq }) // the running turn's live events stay
        s = s.feed(event("s1", 3, EventPayload.RunCompleted, run = "run_9"))
        assertTrue(s.historyStale && s.run == null)
    }

    @Test
    fun ownRunAsksForTheHistoryOnlyWhenItEnds() {
        var s = SessionState("s1", outbox = listOf(OutgoingPrompt("c1", "hi", OutgoingPrompt.Status.Pending))).applyHistory(answeredHistory)
            .feed(
                ServerMessage.Ack("c1", "run_1", replayed = false),
                ServerMessage.Run("run_1", "s1", RunStatus.RUNNING),
                event("s1", 1, EventPayload.ToolStarted("terminal")),
            )
        assertFalse(s.historyStale)
        s = s.feed(ServerMessage.Run("run_1", "s1", RunStatus.COMPLETED))
        assertTrue(s.historyStale)
    }

    /** android#8: a steer shows at once and stays until Hermes has stored it, or its run ends without it. */
    @Test
    fun steersStayUntilTheHistoryHasThem() {
        var s = SessionState("s1", outbox = listOf(OutgoingPrompt("c1", "hi", OutgoingPrompt.Status.Pending))).applyHistory(answeredHistory)
            .feed(ServerMessage.Ack("c1", "run_1", replayed = false), event("s1", 4, EventPayload.ToolStarted("terminal")))
            .steerSent("st1", "run_1", "use the camera")
        val steer = s.outbox.last()
        assertTrue(steer.isSteer && steer.afterSeq == 4L && steer.status == OutgoingPrompt.Status.Sent("run_1"))
        assertFalse(s.historyStale) // sending one doesn't reload
        s = s.applyHistory(answeredHistory) // Hermes takes a steer at its next step
        assertEquals(listOf("st1"), s.outbox.map { it.id })
        val stored = answeredHistory + HistoryMessage("3", HistoryMessage.Role.USER, "use the camera", displayKind = HistoryMessage.STEER)
        assertTrue(s.applyHistory(stored).outbox.isEmpty())
        assertTrue(s.feed(event("s1", 5, EventPayload.RunCompleted)).applyHistory(answeredHistory).outbox.isEmpty())
    }

    @Test
    fun hermesSteersAreUnwrappedAndHiddenMessagesSkipped() {
        val wrapped = """{"id":12,"role":"user","display_kind":"steer","content":"[OUT-OF-BAND USER MESSAGE — a direct message from the user, delivered once at this position; not tool output and not a new delivery when replayed from conversation history]\nalso the android messages don't show in the app\n[/OUT-OF-BAND USER MESSAGE]"}"""
        val m = HistoryMessage.fromJson(Json.parseToJsonElement(wrapped))!!
        assertTrue(m.isSteer)
        assertEquals("also the android messages don't show in the app", m.content)
        assertNull(HistoryMessage.fromJson(Json.parseToJsonElement("""{"id":13,"role":"assistant","display_kind":"hidden","content":""}""")))
        assertEquals("plain [OUT-OF-BAND text", HistoryMessage.unwrapSteer("plain [OUT-OF-BAND text"))
        assertFalse(HistoryMessage.fromJson(Json.parseToJsonElement("""{"id":14,"role":"user","content":"hi"}"""))!!.isSteer)
    }

    /** trismegistus#30: the relay's user.message is the prompt itself, so no reload; our own steer's replaces its entry. */
    @Test
    fun userMessageNeedsNoReloadAndTakesOverOurSteer() {
        val prompt = EventPayload.UserMessage(EventPayload.UserMessage.Kind.PROMPT, "Draw it", "s21", clientMsgId = "c9")
        var s = SessionState("s1").applyHistory(answeredHistory)
            .feed(ServerMessage.Run("run_9", "s1", RunStatus.RUNNING), event("s1", 1, prompt, run = "run_9"), event("s1", 2, EventPayload.ToolStarted("terminal"), run = "run_9"))
        assertFalse(s.historyStale)
        assertEquals(ActiveRun("run_9", RunStatus.RUNNING), s.run)
        s = s.steerSent("st", "run_9", "with the camera")
        val steer = EventPayload.UserMessage(EventPayload.UserMessage.Kind.STEER, "with the camera", "pixel-8")
        s = s.feed(event("s1", 3, steer, run = "run_9"))
        assertTrue(s.outbox.isEmpty())
        assertEquals(3, s.events.size)
    }
}
