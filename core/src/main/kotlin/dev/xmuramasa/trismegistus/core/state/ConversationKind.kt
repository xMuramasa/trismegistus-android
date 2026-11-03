package dev.xmuramasa.trismegistus.core.state

/**
 * Which tab of the conversation list a session is in, by Hermes' `source`. [rawValue] is what the app keeps as the
 * chosen tab.
 */
public sealed class ConversationKind(public val rawValue: String) {
    public data object App : ConversationKind("app")

    public data object Telegram : ConversationKind("telegram")

    public data object Terminal : ConversationKind("terminal")

    public data object OneShot : ConversationKind("oneshot")

    public data object Cron : ConversationKind("cron")

    /** Any other source (a new messaging platform), by its raw name. */
    public data class Other(val name: String) : ConversationKind("source:$name")

    /** Every kind: the MORE menu's last entry. */
    public data object All : ConversationKind("all")

    public val title: String
        get() = when (this) {
            App -> "App"
            Telegram -> "Telegram"
            Terminal -> "Terminal"
            OneShot -> "One-shots"
            Cron -> "Cron"
            is Other -> name
            All -> "All"
        }

    /** The empty tab's line. */
    public val emptyText: String
        get() = when (this) {
            App -> "No conversations from the app"
            Telegram -> "No conversations from Telegram"
            Terminal -> "No conversations from the terminal"
            OneShot -> "No one-shot conversations"
            Cron -> "No conversations from cron"
            is Other -> "No conversations from $name"
            All -> "No conversations"
        }

    public val isInMore: Boolean get() = this !in tabs

    public fun includes(source: String?): Boolean = this == All || this == of(source)

    /** How much a kind's conversations need you: an approval waits, or a run is going. Approval wins. */
    public enum class Attention {
        RUNNING,
        APPROVAL;

        public companion object {
            public fun of(isWaitingForApproval: Boolean, isRunning: Boolean): Attention? = when {
                isWaitingForApproval -> APPROVAL
                isRunning -> RUNNING
                else -> null
            }
        }
    }

    /** The counts and attention marks of a list: one item per listed conversation, its source and its attention. */
    public class Summary(items: List<Pair<String?, Attention?>>) {
        public data class Entry(val count: Int = 0, val attention: Attention? = null) {
            internal fun add(a: Attention?) = Entry(count + 1, listOfNotNull(attention, a).maxOrNull())
        }

        private val entries = mutableMapOf<ConversationKind, Entry>()
        public val total: Entry

        init {
            var total = Entry()
            for ((source, attention) in items) {
                val kind = of(source)
                entries[kind] = (entries[kind] ?: Entry()).add(attention)
                total = total.add(attention)
            }
            this.total = total
        }

        /** A kind's entry; [All] is the whole list. */
        public operator fun get(kind: ConversationKind): Entry = if (kind == All) total else entries[kind] ?: Entry()

        /** The MORE menu: One-shots and Cron always, the other sources present by name, then All. */
        public val more: List<ConversationKind>
            get() = listOf(OneShot, Cron) +
                entries.keys.filterIsInstance<Other>().sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }) +
                All

        /** The kind to show for a stored choice: an [Other] source that has left the list falls back to [App]. */
        public fun resolving(kind: ConversationKind): ConversationKind = if (kind is Other && kind !in more) App else kind

        /** What the MORE chip shows: the strongest mark among the entries it holds (not All, which repeats the tabs). */
        public val moreAttention: Attention? get() = more.filter { it != All }.mapNotNull { get(it).attention }.maxOrNull()
    }

    public companion object {
        /** The three kinds with a tab of their own; the rest are in the MORE menu. */
        public val tabs: List<ConversationKind> by lazy { listOf(App, Telegram, Terminal) }

        /** The kind of a conversation from `source`; never [All]. A missing source reads as `Other("unknown")`. */
        public fun of(source: String?): ConversationKind = when (source) {
            "api_server" -> App
            "telegram" -> Telegram
            "cli", "tui" -> Terminal
            "oneshot" -> OneShot
            "cron" -> Cron
            null, "" -> Other("unknown")
            else -> Other(source)
        }

        public fun fromRawValue(raw: String): ConversationKind? = when {
            raw == "app" -> App
            raw == "telegram" -> Telegram
            raw == "terminal" -> Terminal
            raw == "oneshot" -> OneShot
            raw == "cron" -> Cron
            raw == "all" -> All
            raw.startsWith("source:") && raw.length > 7 -> Other(raw.drop(7))
            else -> null
        }

        /**
         * The `2 more in TELEGRAM ›` row: the kind holding most of [matches] (sources of the conversations matching the
         * filter) and how many. Null when [current] has matches, none are elsewhere, or [current] is All.
         */
        public fun elsewhere(matches: List<String?>, current: ConversationKind): Pair<ConversationKind, Int>? {
            if (current == All || matches.any(current::includes)) return null
            return matches.groupingBy(::of).eachCount().entries
                .sortedWith(compareByDescending<Map.Entry<ConversationKind, Int>> { it.value }.thenBy { it.key.rawValue })
                .firstOrNull()?.let { it.key to it.value }
        }
    }
}
