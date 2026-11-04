package dev.xmuramasa.trismegistus.ui.pairing

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.Text
import androidx.core.content.ContextCompat
import dev.xmuramasa.trismegistus.pairing.PairingInput
import dev.xmuramasa.trismegistus.ui.components.ButtonKind
import dev.xmuramasa.trismegistus.ui.components.Dot
import dev.xmuramasa.trismegistus.ui.components.EndfieldButton
import dev.xmuramasa.trismegistus.ui.components.Glyph
import dev.xmuramasa.trismegistus.ui.components.RoleText
import dev.xmuramasa.trismegistus.ui.components.SteelTopBar
import dev.xmuramasa.trismegistus.ui.components.SunkenField
import dev.xmuramasa.trismegistus.ui.theme.Glyphs
import dev.xmuramasa.trismegistus.ui.theme.Ink
import dev.xmuramasa.trismegistus.ui.theme.Sizes
import dev.xmuramasa.trismegistus.ui.theme.Space
import dev.xmuramasa.trismegistus.ui.theme.Strokes
import dev.xmuramasa.trismegistus.ui.theme.Type
import dev.xmuramasa.trismegistus.ui.theme.endfieldPaper
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// The relay as deployed (SPEC); the QR or the field replaces it.
private const val DEFAULT_RELAY = "https://hermes.lab.x-muramasa.dev"

/** 03b: scan the QR (the camera is asked for only then) or paste the code and the relay URL, then pair. */
@Composable
fun PairingScreen(pair: suspend (url: String, code: String) -> String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf(DEFAULT_RELAY) }
    var fromQr by rememberSaveable { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        scanning = granted
        if (!granted) error = "The camera is needed to scan; paste the code instead."
    }
    fun scan() {
        error = null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            scanning = true
        } else {
            permission.launch(Manifest.permission.CAMERA)
        }
    }
    fun submit() {
        val c = PairingInput.code(code)
        val u = PairingInput.url(url)
        error = when {
            c == null -> "The code looks like XXXX-XXXX-XXXX."
            u == null -> "The relay URL must be https://…"
            else -> null
        }
        if (c == null || u == null) return
        busy = true
        scope.launch {
            error = pair(u, c)
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().endfieldPaper()) {
        SteelTopBar("Pair with the relay")
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(Space.l),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            Box(
                Modifier.fillMaxWidth(SCANNER_WIDTH).aspectRatio(1f)
                    .background(Ink.Surface.terminal)
                    .border(Strokes.mark, Ink.selection)
                    .clickable(enabled = !scanning, onClickLabel = "Scan the QR code") { scan() },
                contentAlignment = Alignment.Center,
            ) {
                if (scanning) {
                    QrScanner(
                        onText = { text ->
                            val offer = PairingInput.fromQr(text)
                            if (offer != null) {
                                scanning = false
                                code = offer.code
                                url = offer.url
                                fromQr = true
                                submit()
                            }
                        },
                        modifier = Modifier.fillMaxSize().padding(Strokes.mark),
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.m)) {
                        Glyph(Glyphs.qr, size = Sizes.touch, tint = Ink.Text.onDark)
                        RoleText("Tap to scan the QR code", Type.label, color = Ink.Text.onDark)
                    }
                }
            }
            Text(
                "On optiplex: trismegistus-relay pair --name s21 --role manager",
                style = Type.monoSmall.style,
                color = Ink.Text.secondary,
                textAlign = TextAlign.Center,
            )
            RoleText("Or paste the code", Type.labelSmall, color = Ink.Text.secondary)
            SunkenField(
                code,
                { code = it; error = null },
                "XXXX-XXXX-XXXX",
                Modifier.fillMaxWidth(),
                textStyle = Type.mono.style,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
            )
            if (fromQr) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                    Dot(Ink.Status.online)
                    RoleText("${url.toHttpUrlOrNull()?.host ?: url} · from the QR", Type.monoSmall, color = Ink.Text.secondary)
                }
            } else {
                SunkenField(
                    url,
                    { url = it; error = null },
                    "Relay URL (https://…)",
                    Modifier.fillMaxWidth(),
                    textStyle = Type.monoSmall.style,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }
            error?.let { RoleText(it, Type.caption, Modifier.fillMaxWidth(), color = Ink.Text.error) }
        }
        EndfieldButton(
            if (busy) "Pairing…" else "Pair",
            ::submit,
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(Space.l),
            kind = ButtonKind.PRIMARY,
            glyph = Glyphs.check,
            enabled = !busy,
        )
    }
}

/** The scanner square's share of the width (the draft: 246 of 412). */
private const val SCANNER_WIDTH = 0.6f
