package dev.xmuramasa.trismegistus.ui.shell

import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xmuramasa.trismegistus.AppViewModel
import dev.xmuramasa.trismegistus.Screen
import dev.xmuramasa.trismegistus.data.RelaySession
import dev.xmuramasa.trismegistus.ui.chat.ConversationScreen
import dev.xmuramasa.trismegistus.ui.chat.ConversationsScreen
import dev.xmuramasa.trismegistus.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

/** The paired app: the drawer around Chat (the one v1 section), a conversation, or settings. */
@Composable
fun ChatShell(model: AppViewModel, session: RelaySession, screen: Screen) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val connection by session.store.connection.collectAsStateWithLifecycle()
    val me by session.me.collectAsStateWithLifecycle()
    val approvals by session.store.pendingApprovals.collectAsStateWithLifecycle()
    val overview by session.store.overview.collectAsStateWithLifecycle()
    val status = RelayStatus.of(connection)
    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = screen == Screen.Conversations || drawer.isOpen,
        drawerContent = {
            Drawer(
                status = status,
                host = session.host,
                deviceLine = listOfNotNull(session.device, "paired", me?.role).joinToString(" · "),
                mark = ChatMark(approvals.size, overview.values.count { it.run != null }),
                onChat = { scope.launch { drawer.close() } },
                onSettings = {
                    scope.launch { drawer.close() }
                    model.open(Screen.Settings)
                },
            )
        },
    ) {
        when (screen) {
            is Screen.Conversation -> ConversationScreen(model, session, screen.id)
            Screen.Settings -> SettingsScreen(model, session)
            else -> ConversationsScreen(model, session, status, onMenu = { scope.launch { drawer.open() } })
        }
    }
}
