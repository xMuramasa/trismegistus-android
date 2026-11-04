package dev.xmuramasa.trismegistus

/** The screens. No navigation library: the stack lives in the view model and Back pops it. */
sealed interface Screen {
    /** Reading the stored pairing; the splash stays up. */
    data object Loading : Screen

    data object Pairing : Screen

    data object Conversations : Screen

    data class Conversation(val id: String) : Screen

    data object Settings : Screen
}

/** The back stack as a value: the last entry shows. Pairing and Conversations are roots. */
@JvmInline
value class Nav(val stack: List<Screen>) {
    val top: Screen get() = stack.last()

    /** Back: null when the stack is at its root (the activity finishes). */
    fun back(): Nav? = if (stack.size > 1) Nav(stack.dropLast(1)) else null

    /** Opening the screen on top already is a no-op; opening another conversation replaces the open one. */
    fun open(screen: Screen): Nav = when {
        top == screen -> this
        screen is Screen.Conversation && top is Screen.Conversation -> Nav(stack.dropLast(1) + screen)
        else -> Nav(stack + screen)
    }

    companion object {
        val loading = Nav(listOf(Screen.Loading))
        val pairing = Nav(listOf(Screen.Pairing))
        val home = Nav(listOf(Screen.Conversations))
    }
}
