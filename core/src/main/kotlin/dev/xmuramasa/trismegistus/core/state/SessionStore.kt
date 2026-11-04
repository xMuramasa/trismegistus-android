package dev.xmuramasa.trismegistus.core.state

import dev.xmuramasa.trismegistus.core.log.Logger
import dev.xmuramasa.trismegistus.core.log.info
import dev.xmuramasa.trismegistus.core.protocol.ClientMessage
import dev.xmuramasa.trismegistus.core.protocol.ErrorCode
import dev.xmuramasa.trismegistus.core.relay.ConnectionState
import dev.xmuramasa.trismegistus.core.relay.RelayApiException
import dev.xmuramasa.trismegistus.core.relay.RelayClient
import dev.xmuramasa.trismegistus.core.relay.RelayRest
import dev.xmuramasa.trismegistus.core.relay.SessionInfo
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The app's model: connection state, the session list with a live row for each, every open session's timeline and
 * the approvals waiting anywhere, fed by [RelayClient]'s outputs and REST history. Send approve/steer/stop through
 * [client] directly; prompts go through [sendPrompt]. Use it from one thread (the app's main dispatcher), as the
 * Mac app's SessionStore lives on the main actor.
 */
public class SessionStore(
    public val client: RelayClient,
    public val rest: RelayRest,
    private val now: () -> Long = System::currentTimeMillis,
    private val logger: Logger = Logger.None,
) {
    private val mutableConnection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private val mutableSessionList = MutableStateFlow<List<SessionInfo>>(emptyList())
    private val mutableTags = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    private val mutableSessions = MutableStateFlow<Map<String, SessionState>>(emptyMap())
    private val mutableOverview = MutableStateFlow<Map<String, SessionOverview>>(emptyMap())
    private val mutableApprovals = MutableStateFlow<List<PendingApproval>>(emptyList())

    public val connection: StateFlow<ConnectionState> = mutableConnection.asStateFlow()
    public val sessionList: StateFlow<List<SessionInfo>> = mutableSessionList.asStateFlow()

    /** Tags by session id, from the relay (they follow every device). May hold ids the list doesn't have. */
    public val tags: StateFlow<Map<String, List<String>>> = mutableTags.asStateFlow()

    /** Open sessions by id: history, live events, run, approvals, outbox. */
    public val sessions: StateFlow<Map<String, SessionState>> = mutableSessions.asStateFlow()

    /**
     * A row for every listed or open session. The open ones and the [RelayClient.MAX_SUBSCRIPTIONS] most recent are
     * subscribed and kept live; the others only have their list info.
     */
    public val overview: StateFlow<Map<String, SessionOverview>> = mutableOverview.asStateFlow()

    /** Every approval waiting in a followed session, open or not. */
    public val pendingApprovals: StateFlow<List<PendingApproval>> = mutableApprovals.asStateFlow()

    /** A run is going in any followed session. */
    public val isWorking: Boolean
        get() = mutableSessions.value.values.any { it.run != null } || mutableOverview.value.values.any { it.run != null }

    private var feed: Job? = null
    private val loading = mutableSetOf<String>()
    private var wasConnected = false

    /** Starts the client and applies its outputs in [scope] (the app's main scope). */
    public fun start(scope: CoroutineScope) {
        if (feed != null) return
        client.start()
        feed = scope.launch { for (output in client.outputs) handle(output) }
    }

    public fun stop() {
        feed?.cancel()
        feed = null
        client.stop()
    }

    /**
     * Applies one client output, then reloads the history of open sessions that lost track. Awaiting the reload here
     * keeps the next live events behind it.
     */
    public suspend fun handle(output: RelayClient.Output) {
        when (output) {
            is RelayClient.Output.State -> {
                if (output.state is ConnectionState.Connected) {
                    // After a drop, sessions with no seq yet were resubscribed live only: what happened meanwhile is
                    // only in the history.
                    if (wasConnected) {
                        setSessions(mutableSessions.value.mapValues { (_, s) -> if (s.lastSeq == 0L) s.copy(historyLoaded = false) else s })
                    }
                    wasConnected = true
                    // Resubscribed live only: the relay re-sends the status of a run that is still going.
                    setOverview(mutableOverview.value.mapValues { (_, o) -> if (o.lastSeq == 0L) o.lostTrack() else o })
                }
                mutableConnection.value = output.state
            }
            is RelayClient.Output.Message -> {
                setSessions(mutableSessions.value.mapValues { (_, s) -> s.apply(output.message) })
                val at = now()
                setOverview(mutableOverview.value.mapValues { (_, o) -> o.apply(output.message, at) })
            }
        }
        for ((id, s) in mutableSessions.value) {
            if ((!s.historyLoaded || s.historyStale) && s.historyError == null && id !in loading) loadHistory(id)
        }
    }

    /** Subscribes to a session (unless its row already is) and loads its history. */
    public suspend fun open(id: String) {
        if (id in mutableSessions.value) return
        // Already followed: the relay won't send the run's status again, so start from what the row knows.
        val row = mutableOverview.value[id]
        val state = SessionState(id, run = row?.run?.let { ActiveRun(it.id, it.status) }, approvals = row?.approvals.orEmpty())
        setSessions(mutableSessions.value + (id to state))
        follow()
        loadHistory(id)
    }

    /** The session stays subscribed while it is among the listed ones followed. */
    public fun close(id: String) {
        setSessions(mutableSessions.value - id)
        follow()
    }

    /** Also the retry after a historyError. */
    public suspend fun loadHistory(id: String) {
        if (id !in mutableSessions.value || !loading.add(id)) return
        try {
            val messages = rest.messages(id)
            updateSession(id) { it.applyHistory(messages) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            updateSession(id) { it.copy(historyError = message(e)) }
        } finally {
            loading.remove(id)
        }
    }

    /**
     * Sends a prompt to an open session; it waits in the session's outbox until the history has it. Images not uploaded
     * yet are uploaded first. One run per session: during a run the relay answers `busy` (offer to send it as a steer).
     * Returns the client_msg_id.
     */
    public suspend fun sendPrompt(sessionId: String, text: String, images: List<ImageUpload> = emptyList()): String {
        val id = UUID.randomUUID().toString()
        updateSession(sessionId) { it.copy(outbox = it.outbox + OutgoingPrompt(id, text, OutgoingPrompt.Status.Pending, images)) }
        fun failed(code: ErrorCode?, message: String) =
            updateOutbox(sessionId, id) { it.copy(status = OutgoingPrompt.Status.Failed(code, message)) }
        try {
            val ids = images.mapIndexed { n, image ->
                image.attachmentId ?: rest.uploadAttachment(image.data, image.mime).id.also { uploaded ->
                    updateOutbox(sessionId, id) { p ->
                        p.copy(images = p.images.mapIndexed { k, im -> if (k == n) ImageUpload(im.data, im.mime, uploaded) else im })
                    }
                }
            }
            client.send(ClientMessage.Prompt(id, sessionId, text, ids.ifEmpty { null }))
        } catch (e: RelayApiException) {
            failed(e.code, e.message)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed(null, "Not connected to the relay.")
        }
        return id
    }

    /**
     * Steers [runId] with [text] and keeps it in the session's outbox until the history has it (steers are text only).
     * Throws when the client can't send it (not connected): nothing is added then.
     */
    public fun sendSteer(sessionId: String, runId: String, text: String): String {
        client.send(ClientMessage.Steer(runId, text))
        val id = UUID.randomUUID().toString()
        updateSession(sessionId) { it.steerSent(id, runId, text) }
        return id
    }

    /**
     * A failed prompt again, in place of the old entry. Its images are uploaded again: the relay deletes uploads no
     * prompt used within an hour, and the same bytes answer the same id. Null when it isn't a failed prompt.
     */
    public suspend fun retryPrompt(sessionId: String, promptId: String): String? {
        val prompt = mutableSessions.value[sessionId]?.outbox?.firstOrNull { it.id == promptId }
            ?.takeIf { it.status is OutgoingPrompt.Status.Failed } ?: return null
        updateSession(sessionId) { s -> s.copy(outbox = s.outbox.filter { it.id != promptId }) }
        return sendPrompt(sessionId, prompt.text, prompt.images.map { ImageUpload(it.data, it.mime) })
    }

    /** Also adds and drops subscriptions to match the list. */
    public suspend fun refreshSessions() {
        mutableSessionList.value = rest.listSessions()
        follow()
        refreshTags()
    }

    /** Keeps the tags it has when the relay can't be reached; a relay without the endpoint has none. */
    public suspend fun refreshTags() {
        try {
            mutableTags.value = rest.tags()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    public suspend fun createSession(): SessionInfo {
        val session = rest.createSession()
        mutableSessionList.value = listOf(session) + mutableSessionList.value
        follow()
        return session
    }

    /** The row shows the new title at once; it goes back, and the error is thrown, if the relay refuses. */
    public suspend fun renameSession(id: String, title: String) {
        val old = mutableSessionList.value.firstOrNull { it.id == id }?.title
        update(id) { it.copy(title = title) }
        try {
            val session = rest.renameSession(id, title)
            update(id) { it.copy(title = session.title ?: title) }
        } catch (e: Exception) {
            update(id, ifTitle = title) { it.copy(title = old) }
            throw e
        }
    }

    /** Optimistic like [renameSession]. */
    public suspend fun setPinned(id: String, pinned: Boolean) {
        val old = mutableSessionList.value.firstOrNull { it.id == id }?.pinned
        update(id) { it.copy(pinned = pinned) }
        try {
            rest.setPinned(id, pinned)
        } catch (e: Exception) {
            update(id) { it.copy(pinned = old) }
            throw e
        }
    }

    /** Replaces the session's tags; optimistic like [renameSession], and keeps what the relay stored. */
    public suspend fun setTags(id: String, tags: List<String>) {
        val old = mutableTags.value[id]
        mutableTags.value += (id to tags)
        try {
            mutableTags.value += (id to rest.setTags(id, tags))
        } catch (e: Exception) {
            if (mutableTags.value[id] == tags) { // not if a refresh already replaced it
                mutableTags.value = if (old == null) mutableTags.value - id else mutableTags.value + (id to old)
            }
            throw e
        }
    }

    public suspend fun deleteSession(id: String) {
        rest.deleteSession(id)
        mutableSessionList.value = mutableSessionList.value.filter { it.id != id }
        mutableTags.value -= id
        setSessions(mutableSessions.value - id)
        follow()
        logger.info("store: deleted $id")
    }

    public companion object {
        /** One line for the UI: the relay's own message, or why the app could not ask. */
        public fun message(error: Throwable): String = (error as? RelayApiException)?.message ?: "Can't reach the relay."
    }

    private fun setSessions(value: Map<String, SessionState>) {
        mutableSessions.value = value
        derive()
    }

    private fun setOverview(value: Map<String, SessionOverview>) {
        mutableOverview.value = value
        derive()
    }

    private fun derive() {
        mutableApprovals.value = (mutableSessions.value.values.flatMap { it.approvals } + mutableOverview.value.values.flatMap { it.approvals })
            .distinctBy { it.id }
    }

    private fun updateSession(id: String, change: (SessionState) -> SessionState) {
        val s = mutableSessions.value[id] ?: return
        setSessions(mutableSessions.value + (id to change(s)))
    }

    private fun updateOutbox(sessionId: String, promptId: String, change: (OutgoingPrompt) -> OutgoingPrompt) =
        updateSession(sessionId) { s -> s.copy(outbox = s.outbox.map { if (it.id == promptId) change(it) else it }) }

    private fun update(id: String, ifTitle: String? = null, change: (SessionInfo) -> SessionInfo) {
        val list = mutableSessionList.value
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return
        if (ifTitle != null && list[i].title != ifTitle) return // a refresh already replaced it
        val changed = change(list[i])
        mutableSessionList.value = list.toMutableList().also { it[i] = changed }
        mutableOverview.value[id]?.let { setOverview(mutableOverview.value + (id to it.copy(info = changed))) }
    }

    /**
     * Follows the open sessions, then the listed ones most recent first (the client keeps the first
     * [RelayClient.MAX_SUBSCRIPTIONS]), and keeps a row for each.
     */
    private fun follow() {
        val list = mutableSessionList.value
        val byRecency = list.withIndex()
            .sortedWith(compareByDescending<IndexedValue<SessionInfo>> { it.value.lastActive ?: it.value.startedAt ?: 0.0 }.thenBy { it.index })
            .map { it.value.id }
        val info = list.distinctBy { it.id }.associateBy { it.id }
        val ids = (mutableSessions.value.keys.sorted() + byRecency).distinct()
        val old = mutableOverview.value
        setOverview(
            ids.withIndex().associate { (i, id) ->
                val row = (old[id] ?: SessionOverview(id)).copy(info = info[id])
                id to if (i >= RelayClient.MAX_SUBSCRIPTIONS) row.lostTrack() else row // not subscribed: nothing live
            },
        )
        client.follow(ids)
    }
}
