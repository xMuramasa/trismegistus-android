package dev.xmuramasa.trismegistus.ui.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import dev.xmuramasa.trismegistus.AppViewModel
import dev.xmuramasa.trismegistus.TrayImage
import dev.xmuramasa.trismegistus.core.relay.DeviceRole
import dev.xmuramasa.trismegistus.image.ImageSizing
import dev.xmuramasa.trismegistus.ui.components.ButtonKind
import dev.xmuramasa.trismegistus.ui.components.EndfieldButton
import dev.xmuramasa.trismegistus.ui.components.EndfieldIconButton
import dev.xmuramasa.trismegistus.ui.components.Glyph
import dev.xmuramasa.trismegistus.ui.components.RoleText
import dev.xmuramasa.trismegistus.ui.components.SunkenField
import dev.xmuramasa.trismegistus.ui.theme.Glyphs
import dev.xmuramasa.trismegistus.ui.theme.Ink
import dev.xmuramasa.trismegistus.ui.theme.Sizes
import dev.xmuramasa.trismegistus.ui.theme.Space
import dev.xmuramasa.trismegistus.ui.theme.Strokes
import dev.xmuramasa.trismegistus.ui.theme.Type
import java.io.File

/** What the composer needs to know about the conversation. */
class ComposerState(val runId: String?, val approvalOpen: Boolean, val canAttach: Boolean, val isViewer: Boolean)

/**
 * 02a, 02d: the field grows to six lines, then scrolls (Enter is a newline); SEND, or STEER plus Stop during a run.
 * Images from the photo picker or the camera wait in a tray above the field.
 */
@Composable
fun Composer(model: AppViewModel, sessionId: String, state: ComposerState, tray: List<TrayImage>) {
    val context = LocalContext.current
    var draft by rememberSaveable(sessionId) { mutableStateOf("") }
    var capture by remember { mutableStateOf<Uri?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(AppViewModel.MAX_IMAGES)) {
        if (it.isNotEmpty()) model.addImages(sessionId, it)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        capture?.let { if (taken) model.addImages(sessionId, listOf(it)) }
    }
    fun shoot() {
        capture = captureUri(context).also(camera::launch)
    }
    // An app that declares CAMERA must hold it before asking another app to take a picture.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) shoot() else model.say("The camera is needed to take a photo.")
    }
    val steering = state.runId != null
    val text = draft.trim()
    val sendable = text.isNotEmpty() || (!steering && tray.isNotEmpty())

    Column(Modifier.fillMaxWidth().background(Ink.Surface.raised).navigationBarsPadding()) {
        HorizontalDivider(thickness = Strokes.hairline, color = Ink.Line.rule)
        Column(Modifier.padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            if (tray.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    for (image in tray) {
                        Box(Modifier.size(Sizes.thumbnail)) {
                            AsyncImage(
                                model = image.image.jpeg,
                                contentDescription = "Attached image",
                                modifier = Modifier.size(Sizes.thumbnail).background(Ink.Surface.sunken),
                                contentScale = ContentScale.Crop,
                            )
                            IconButton(
                                onClick = { model.removeImage(sessionId, image.key) },
                                modifier = Modifier.align(Alignment.TopEnd).size(Sizes.touch / 2).background(Ink.Surface.inverse),
                            ) { Glyph(Glyphs.close, size = Sizes.iconSmall, tint = Ink.Text.onDark, description = "Remove image") }
                        }
                    }
                }
                RoleText(
                    if (steering) {
                        "Steers are text only: the images go with the next new message."
                    } else {
                        "${tray.size} of ${AppViewModel.MAX_IMAGES} images // resized to ${ImageSizing.MAX_EDGE} px, location removed"
                    },
                    Type.labelSmall,
                    color = Ink.Text.secondary,
                )
            }
            SunkenField(
                draft,
                { draft = it },
                when {
                    state.approvalOpen -> "Steer or wait"
                    steering -> "Steer this run"
                    else -> "Message Hermes"
                },
                Modifier.fillMaxWidth(),
                maxLines = COMPOSER_LINES,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = state.canAttach) {
                    Glyph(Glyphs.clip, tint = if (state.canAttach) Ink.Text.primary else Ink.Text.muted, description = "Attach images")
                }
                IconButton(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            shoot()
                        } else {
                            permission.launch(Manifest.permission.CAMERA)
                        }
                    },
                    enabled = state.canAttach,
                ) { Glyph(Glyphs.camera, tint = if (state.canAttach) Ink.Text.primary else Ink.Text.muted, description = "Take a photo") }
                Spacer(Modifier.weight(1f))
                val runId = state.runId
                if (runId != null) {
                    EndfieldIconButton(Glyphs.stop, "Stop", { model.stop(runId) }, kind = ButtonKind.DANGER)
                    // While an approval is open, ONCE is the primary action.
                    EndfieldButton(
                        "Steer",
                        { if (model.submit(sessionId, text, runId)) draft = "" },
                        kind = if (state.approvalOpen) ButtonKind.PLAIN else ButtonKind.PRIMARY,
                        glyph = Glyphs.steer,
                        enabled = text.isNotEmpty(),
                    )
                } else {
                    EndfieldButton(
                        "Send",
                        { if (model.submit(sessionId, text, null)) draft = "" },
                        kind = ButtonKind.PRIMARY,
                        glyph = Glyphs.send,
                        enabled = sendable,
                    )
                }
            }
            if (state.isViewer) RoleText(DeviceRole.VIEWER_NOTICE, Type.caption, color = Ink.Text.secondary)
        }
    }
}

/** A new file for the camera under cache/captures (the FileProvider's one path); cleared on unpair. */
private fun captureUri(context: Context): Uri {
    val dir = File(context.cacheDir, "captures").apply { mkdirs() }
    val file = File(dir, "capture-${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, context.packageName + ".files", file)
}

private const val COMPOSER_LINES = 6
