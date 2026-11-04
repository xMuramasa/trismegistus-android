package dev.xmuramasa.trismegistus.chat

import dev.xmuramasa.trismegistus.core.relay.SessionInfo
import dev.xmuramasa.trismegistus.core.state.ConversationKind
import dev.xmuramasa.trismegistus.core.state.SessionOverview
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToLong

/** One row of the conversation list. */
data class ConversationRow(
    val info: SessionInfo,
    val tags: List<String>,
    val attention: ConversationKind.Attention?,
    /** The run going, for the RUN mark's clock. */
    val run: SessionOverview.Run?,
    val lastActivity: Long?,
)

/** The list for a tab: the tab's conversations, filtered, pinned first, then the most recent. */
object ConversationList {
    fun rows(
        list: List<SessionInfo>,
        overview: Map<String, SessionOverview>,
        tags: Map<String, List<String>>,
        kind: ConversationKind,
        filter: String,
    ): List<ConversationRow> = list.distinctBy { it.id }
        .filter { kind.includes(it.source) && matches(it, tags[it.id].orEmpty(), filter) }
        .map { row(it, overview[it.id], tags[it.id].orEmpty()) }
        .sortedWith(compareByDescending<ConversationRow> { it.info.isPinned }.thenByDescending { it.lastActivity ?: 0L })

    /** Counts and badge marks per kind, over the whole list (not the filter). */
    fun summary(list: List<SessionInfo>, overview: Map<String, SessionOverview>): ConversationKind.Summary =
        ConversationKind.Summary(list.distinctBy { it.id }.map { it.source to attention(overview[it.id]) })

    /** The kinds of every conversation the filter matches, for the "n more in TELEGRAM" row. */
    fun matchingSources(list: List<SessionInfo>, tags: Map<String, List<String>>, filter: String): List<String?> =
        if (filter.isBlank()) emptyList() else list.filter { matches(it, tags[it.id].orEmpty(), filter) }.map { it.source }

    /** Title, preview, id or a tag contains every word of the filter, ignoring case. */
    fun matches(info: SessionInfo, tags: List<String>, filter: String): Boolean {
        val words = filter.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val haystack = listOfNotNull(info.title, info.preview, info.id).plus(tags).joinToString("\n")
        return words.all { haystack.contains(it, ignoreCase = true) }
    }

    fun title(info: SessionInfo): String = info.title?.takeIf { it.isNotBlank() } ?: info.preview?.takeIf { it.isNotBlank() }?.take(80) ?: "Untitled"

    private fun attention(o: SessionOverview?): ConversationKind.Attention? =
        o?.let { ConversationKind.Attention.of(it.isWaitingForApproval, it.run != null) }

    private fun row(info: SessionInfo, o: SessionOverview?, tags: List<String>) = ConversationRow(
        info = info,
        tags = tags,
        attention = attention(o),
        run = o?.run,
        lastActivity = o?.lastActivity ?: (info.lastActive ?: info.startedAt)?.let { (it * 1000).roundToLong() },
    )
}

/** The row's time: `now`, `3 min`, `2 h` today, then `yesterday`, a weekday within the week, then `2 Nov`. */
object RelativeTime {
    fun format(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val minutes = (now - at) / 60_000
        val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(day, today)
        return when {
            minutes < 1 -> "now"
            days == 0L && minutes < 60 -> "$minutes min"
            days == 0L -> "${minutes / 60} h"
            days == 1L -> "yesterday"
            days in 2..6 -> day.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
            else -> "${day.dayOfMonth} ${day.month.getDisplayName(TextStyle.SHORT, locale)}"
        }
    }

    /** A run's clock: `00:42`, `12:05`, `1:02:09`. */
    fun clock(millis: Long): String {
        val s = (millis / 1000).coerceAtLeast(0)
        val (h, m, sec) = Triple(s / 3600, s % 3600 / 60, s % 60)
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) else String.format(Locale.ROOT, "%02d:%02d", m, sec)
    }
}
