package dev.xmuramasa.trismegistus.chat

import dev.xmuramasa.trismegistus.core.protocol.RunStatus
import dev.xmuramasa.trismegistus.core.relay.SessionInfo
import dev.xmuramasa.trismegistus.core.state.ConversationKind
import dev.xmuramasa.trismegistus.core.state.SessionOverview
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class ConversationListTests {
    private val list = listOf(
        SessionInfo("old", source = "api_server", title = "Homelab runbook", lastActive = 10.0, pinned = true),
        SessionInfo("new", source = "api_server", title = "Release notes", lastActive = 50.0),
        SessionInfo("mid", source = "api_server", title = "Penpot setup", lastActive = 30.0),
        SessionInfo("tg", source = "telegram", title = "Groceries", lastActive = 40.0),
        SessionInfo("cli", source = "cli", title = "Clean build cache", lastActive = 20.0),
    )
    private val overview = mapOf(
        "mid" to SessionOverview("mid", run = SessionOverview.Run("r", RunStatus.WAITING_FOR_APPROVAL, 0)),
        "tg" to SessionOverview("tg", run = SessionOverview.Run("r2", RunStatus.RUNNING, 0)),
    )

    @Test
    fun pinnedFirstThenMostRecent() {
        val rows = ConversationList.rows(list, overview, emptyMap(), ConversationKind.App, "")
        assertEquals(listOf("old", "new", "mid"), rows.map { it.info.id })
        assertEquals(ConversationKind.Attention.APPROVAL, rows[2].attention)
    }

    @Test
    fun theFilterMatchesTitlesAndTagsInAnyCase() {
        val tags = mapOf("new" to listOf("release"))
        assertEquals(listOf("cli"), ConversationList.rows(list, overview, tags, ConversationKind.All, "BUILD cache").map { it.info.id })
        assertEquals(listOf("new"), ConversationList.rows(list, overview, tags, ConversationKind.App, "release").map { it.info.id })
    }

    @Test
    fun summaryCountsAndMarksEachKind() {
        val summary = ConversationList.summary(list, overview)
        assertEquals(ConversationKind.Summary.Entry(3, ConversationKind.Attention.APPROVAL), summary[ConversationKind.App])
        assertEquals(ConversationKind.Summary.Entry(1, ConversationKind.Attention.RUNNING), summary[ConversationKind.Telegram])
        assertEquals(1, summary[ConversationKind.Terminal].count)
        assertEquals(5, summary[ConversationKind.All].count)
    }

    @Test
    fun aFilterWithNoMatchHerePointsElsewhere() {
        val sources = ConversationList.matchingSources(list, emptyMap(), "groceries")
        assertEquals(ConversationKind.Telegram to 1, ConversationKind.elsewhere(sources, ConversationKind.App))
    }

    @Test
    fun relativeTimes() {
        val zone = ZoneId.of("UTC")
        val now = ZonedDateTime.of(2026, 11, 5, 12, 0, 0, 0, zone) // a Thursday
        fun at(day: Int, hour: Int, minute: Int = 0) = ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()
        val n = now.toInstant().toEpochMilli()
        fun f(t: Long) = RelativeTime.format(t, n, zone, Locale.ENGLISH)
        assertEquals("now", f(n - 20_000))
        assertEquals("3 min", f(at(5, 11, 57)))
        assertEquals("2 h", f(at(5, 9, 30)))
        assertEquals("yesterday", f(at(4, 23)))
        assertEquals("Mon", f(at(2, 8)))
        assertEquals("28 Oct", RelativeTime.format(ZonedDateTime.of(2026, 10, 28, 8, 0, 0, 0, zone).toInstant().toEpochMilli(), n, zone, Locale.ENGLISH))
    }

    @Test
    fun runClocks() {
        assertEquals("00:42", RelativeTime.clock(42_000))
        assertEquals("12:05", RelativeTime.clock(725_000))
        assertEquals("1:02:09", RelativeTime.clock(3_729_000))
        assertEquals("00:00", RelativeTime.clock(-5))
    }
}
