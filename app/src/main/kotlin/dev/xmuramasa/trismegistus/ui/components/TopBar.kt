package dev.xmuramasa.trismegistus.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import dev.xmuramasa.trismegistus.ui.theme.Gradient
import dev.xmuramasa.trismegistus.ui.theme.Ink
import dev.xmuramasa.trismegistus.ui.theme.Sizes
import dev.xmuramasa.trismegistus.ui.theme.Type

private val steel = Brush.horizontalGradient(*Gradient.steelStops.zip(Gradient.steel).toTypedArray())

/**
 * Material's TopAppBar on the steel gradient (behind the status bar too): Inter 700 19 title, mono subtitle, then the
 * title rule.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SteelTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigation: Pair<ImageVector, String>? = null,
    onNavigation: () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier.fillMaxWidth().background(steel)) {
        TopAppBar(
            title = {
                Column {
                    // White on steel, as the sidebar's titles (the draft).
                    RoleText(title, Type.heading, color = Ink.Text.onSidebar, maxLines = 1)
                    if (subtitle != null) RoleText(subtitle, Type.monoSmall, color = Ink.Text.onDark, maxLines = 1)
                }
            },
            navigationIcon = {
                if (navigation != null) {
                    IconButton(onClick = onNavigation) {
                        Glyph(navigation.first, tint = Ink.Text.onDark, description = navigation.second)
                    }
                }
            },
            actions = actions,
            expandedHeight = Sizes.appBar,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                titleContentColor = Ink.Text.onDark,
                actionIconContentColor = Ink.Text.onDark,
                navigationIconContentColor = Ink.Text.onDark,
            ),
        )
        TitleRule()
    }
}

/** Magenta, yellow, then green for the rest of the width. */
@Composable
fun TitleRule(modifier: Modifier = Modifier) {
    val (magenta, yellow, green) = Ink.titleRule
    Row(modifier.fillMaxWidth().height(Sizes.titleRule)) {
        Box(Modifier.width(Sizes.titleRuleMagenta).fillMaxHeight().background(magenta))
        Box(Modifier.width(Sizes.titleRuleYellow).fillMaxHeight().background(yellow))
        Box(Modifier.weight(1f).fillMaxHeight().background(green))
    }
}
