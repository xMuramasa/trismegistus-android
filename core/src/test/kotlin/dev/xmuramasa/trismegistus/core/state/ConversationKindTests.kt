package dev.xmuramasa.trismegistus.core.state

import dev.xmuramasa.trismegistus.core.state.ConversationKind.All
import dev.xmuramasa.trismegistus.core.state.ConversationKind.App
import dev.xmuramasa.trismegistus.core.state.ConversationKind.Attention
import dev.xmuramasa.trismegistus.core.state.ConversationKind.Cron
import dev.xmuramasa.trismegistus.core.state.ConversationKind.OneShot
import dev.xmuramasa.trismegistus.core.state.ConversationKind.Other
import dev.xmuramasa.trismegistus.core.state.ConversationKind.Summary
import dev.xmuramasa.trismegistus.core.state.ConversationKind.Telegram
import dev.xmuramasa.trismegistus.core.state.ConversationKind.Terminal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversationKindTests {
    @Test
    fun everySourceHasItsKind() {
        assertEquals(App, ConversationKind.of("api_server"))
        assertEquals(Telegram, ConversationKind.of("telegram"))
        assertEquals(Terminal, ConversationKind.of("cli"))
        assertEquals(Terminal, ConversationKind.of("tui"))
        assertEquals(OneShot, ConversationKind.of("oneshot"))
        assertEquals(Cron, ConversationKind.of("cron"))
    }

    @Test
    fun anUnknownSourceKeepsItsName() {
        assertEquals(Other("discord"), ConversationKind.of("discord"))
        assertEquals("discord", ConversationKind.of("discord").title)
        assertEquals(Other("unknown"), ConversationKind.of(null))
        assertEquals(Other("unknown"), ConversationKind.of(""))
        assertTrue(Other("discord").isInMore)
        assertTrue(ConversationKind.tabs.none { it.isInMore })
    }

    @Test
    fun rawValuesRoundTrip() {
        for (kind in listOf(App, Telegram, Terminal, OneShot, Cron, Other("slack"), All)) {
            assertEquals(kind, ConversationKind.fromRawValue(kind.rawValue))
        }
        assertNull(ConversationKind.fromRawValue(""))
        assertNull(ConversationKind.fromRawValue("source:"))
    }

    @Test
    fun includesByKindAndAllTakesEverything() {
        assertTrue(Terminal.includes("tui") && Terminal.includes("cli"))
        assertFalse(Terminal.includes("api_server"))
        assertTrue(All.includes("discord") && All.includes(null))
    }

    @Test
    fun emptyTextNamesTheKind() {
        assertEquals("No conversations from the terminal", Terminal.emptyText)
        assertEquals("No conversations from discord", Other("discord").emptyText)
    }
}

class ConversationKindSummaryTests {
    private val items = listOf(
        "api_server" to null, "api_server" to Attention.RUNNING, "telegram" to null, "cli" to null, "tui" to Attention.APPROVAL,
        "oneshot" to null, "oneshot" to null, "discord" to null, "matrix" to Attention.RUNNING,
    )

    @Test
    fun countsPerKind() {
        val summary = Summary(items)
        assertEquals(listOf(2, 1, 2, 2, 0, 9), listOf(App, Telegram, Terminal, OneShot, Cron, All).map { summary[it].count })
    }

    @Test
    fun moreListsOneShotsCronAndTheSourcesPresentThenAll() {
        assertEquals(listOf(OneShot, Cron, Other("discord"), Other("matrix"), All), Summary(items).more)
        assertEquals(listOf(OneShot, Cron, All), Summary(emptyList()).more)
    }

    @Test
    fun aGoneSourceFallsBackToTheApp() {
        val summary = Summary(items)
        assertEquals(Other("discord"), summary.resolving(Other("discord")))
        assertEquals(App, summary.resolving(Other("slack")))
        assertTrue(listOf(Cron, Telegram, All).all { summary.resolving(it) == it })
    }

    @Test
    fun approvalBeatsRunOnAChip() {
        val summary = Summary(items)
        assertEquals(Attention.RUNNING, summary[App].attention)
        assertEquals(Attention.APPROVAL, summary[Terminal].attention)
        assertNull(summary[Telegram].attention)
        assertEquals(Attention.APPROVAL, summary[All].attention)
        val both = Summary(listOf("cli" to Attention.RUNNING, "cli" to Attention.APPROVAL, "cli" to Attention.RUNNING))
        assertEquals(Attention.APPROVAL, both[Terminal].attention)
    }

    @Test
    fun moreChipShowsTheStrongestMarkAmongItsEntries() {
        assertEquals(Attention.RUNNING, Summary(items).moreAttention)
        // An approval on a tab doesn't light MORE; All repeats the tabs and doesn't count.
        assertNull(Summary(listOf("tui" to Attention.APPROVAL)).moreAttention)
        assertEquals(Attention.APPROVAL, Summary(listOf("oneshot" to Attention.APPROVAL, "matrix" to Attention.RUNNING)).moreAttention)
    }

    @Test
    fun attentionFromARow() {
        assertEquals(Attention.APPROVAL, Attention.of(isWaitingForApproval = true, isRunning = true))
        assertEquals(Attention.RUNNING, Attention.of(isWaitingForApproval = false, isRunning = true))
        assertNull(Attention.of(isWaitingForApproval = false, isRunning = false))
    }

    @Test
    fun elsewhereNamesTheKindWithTheMostMatches() {
        assertEquals(Terminal to 2, ConversationKind.elsewhere(listOf("telegram", "cli", "tui"), current = App))
        assertEquals(Telegram, ConversationKind.elsewhere(listOf("telegram"), current = App)?.first)
        // The tab has matches, there are none anywhere, or the tab is All: no row.
        assertNull(ConversationKind.elsewhere(listOf("api_server", "telegram"), current = App))
        assertNull(ConversationKind.elsewhere(emptyList(), current = App))
        assertNull(ConversationKind.elsewhere(listOf("telegram"), current = All))
    }
}
