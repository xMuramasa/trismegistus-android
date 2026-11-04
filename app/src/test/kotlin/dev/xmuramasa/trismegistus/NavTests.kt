package dev.xmuramasa.trismegistus

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NavTests {
    @Test
    fun backPopsUntilTheRoot() {
        val nav = Nav.home.open(Screen.Conversation("a")).open(Screen.Settings)
        assertEquals(Screen.Settings, nav.top)
        assertEquals(Screen.Conversation("a"), nav.back()?.top)
        assertEquals(Screen.Conversations, nav.back()?.back()?.top)
        assertNull(Nav.home.back())
        assertNull(Nav.pairing.back())
    }

    @Test
    fun anotherConversationReplacesTheOpenOne() {
        val nav = Nav.home.open(Screen.Conversation("a")).open(Screen.Conversation("b"))
        assertEquals(listOf(Screen.Conversations, Screen.Conversation("b")), nav.stack)
    }

    @Test
    fun openingTheTopAgainChangesNothing() {
        val nav = Nav.home.open(Screen.Settings)
        assertEquals(nav, nav.open(Screen.Settings))
    }
}
