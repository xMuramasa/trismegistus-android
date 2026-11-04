package dev.xmuramasa.trismegistus.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xmuramasa.trismegistus.AppViewModel
import dev.xmuramasa.trismegistus.Screen
import dev.xmuramasa.trismegistus.ui.pairing.PairingScreen
import dev.xmuramasa.trismegistus.ui.shell.ChatShell
import dev.xmuramasa.trismegistus.ui.theme.Ink
import dev.xmuramasa.trismegistus.ui.theme.Sizes
import dev.xmuramasa.trismegistus.ui.theme.Space
import dev.xmuramasa.trismegistus.ui.theme.endfieldPaper

/** Picks the screen from the view model's back stack; Back pops it until a root. Notices show as snackbars. */
@Composable
fun AppRoot(model: AppViewModel) {
    val nav by model.nav.collectAsStateWithLifecycle()
    val session by model.session.collectAsStateWithLifecycle()
    val notice by model.notice.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(notice) {
        val text = notice ?: return@LaunchedEffect
        model.consumeNotice()
        snackbar.showSnackbar(text)
    }
    BackHandler(enabled = nav.stack.size > 1) { model.back() }
    Box(Modifier.fillMaxSize()) {
        val s = session
        when (val top = nav.top) {
            Screen.Loading -> Box(Modifier.fillMaxSize().endfieldPaper())
            Screen.Pairing -> PairingScreen(model::pair)
            else -> if (s != null) ChatShell(model, s, top) else Box(Modifier.fillMaxSize().endfieldPaper())
        }
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(bottom = Sizes.fab + Space.xxl),
        ) {
            Snackbar(it, shape = RectangleShape, containerColor = Ink.Surface.inverse, contentColor = Ink.Text.onDark, actionColor = Ink.selection)
        }
    }
}
