package dev.xmuramasa.trismegistus

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.xmuramasa.trismegistus.core.log.Logger
import dev.xmuramasa.trismegistus.core.protocol.ApprovalChoice
import dev.xmuramasa.trismegistus.core.protocol.ClientMessage
import dev.xmuramasa.trismegistus.core.relay.ModelChoice
import dev.xmuramasa.trismegistus.core.relay.RelayRest
import dev.xmuramasa.trismegistus.core.state.ImageUpload
import dev.xmuramasa.trismegistus.core.state.PendingApproval
import dev.xmuramasa.trismegistus.core.state.SessionStore
import dev.xmuramasa.trismegistus.data.LogcatLogger
import dev.xmuramasa.trismegistus.data.NotificationPrefs
import dev.xmuramasa.trismegistus.data.OfflineCache
import dev.xmuramasa.trismegistus.data.Prefs
import dev.xmuramasa.trismegistus.data.RelaySession
import dev.xmuramasa.trismegistus.data.StoredPairing
import dev.xmuramasa.trismegistus.data.TokenCipher
import dev.xmuramasa.trismegistus.image.ImagePrep
import dev.xmuramasa.trismegistus.image.PreparedImage
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** An image waiting in a composer's tray. */
class TrayImage(val key: Long, val image: PreparedImage)

/** The app's state: pairing, the relay session, the back stack, the composer trays and the offline cache. */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val cipher = TokenCipher()
    private val prep = ImagePrep(app.contentResolver)
    private val cache = OfflineCache(File(app.filesDir, "offline.json"))
    private val keys = AtomicLong()
    private var cacheWriter: Job? = null

    private val mutableNav = MutableStateFlow(Nav.loading)
    private val mutableSession = MutableStateFlow<RelaySession?>(null)
    private val mutableCached = MutableStateFlow<OfflineCache.Snapshot?>(null)
    private val mutableTrays = MutableStateFlow<Map<String, List<TrayImage>>>(emptyMap())
    private val mutableShared = MutableStateFlow<List<PreparedImage>>(emptyList())
    private val mutableNotice = MutableStateFlow<String?>(null)

    val nav: StateFlow<Nav> = mutableNav.asStateFlow()
    val session: StateFlow<RelaySession?> = mutableSession.asStateFlow()

    /** The last list and timelines seen, shown while the relay can't be reached. */
    val cached: StateFlow<OfflineCache.Snapshot?> = mutableCached.asStateFlow()

    /** Images per conversation, for its next prompt (steers are text only, so they wait during a run). */
    val trays: StateFlow<Map<String, List<TrayImage>>> = mutableTrays.asStateFlow()

    /** Shared to the app, waiting for a conversation to be opened. */
    val shared: StateFlow<List<PreparedImage>> = mutableShared.asStateFlow()

    /** One line for the snackbar. */
    val notice: StateFlow<String?> = mutableNotice.asStateFlow()
    val notifications: StateFlow<NotificationPrefs> = prefs.notifications.stateIn(viewModelScope, SharingStarted.Eagerly, NotificationPrefs())
    val tab: StateFlow<String?> = prefs.tab.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch {
            mutableCached.value = withContext(Dispatchers.IO) { cache.read() }
            val stored = prefs.pairing()
            val token = stored?.let { withContext(Dispatchers.IO) { cipher.open(it.sealedToken) } }
            if (stored != null && token != null) {
                begin(stored.relayUrl, token, stored.device)
                mutableNav.value = Nav.home
            } else {
                mutableNav.value = Nav.pairing
            }
        }
    }

    private fun begin(url: String, token: String, device: String) {
        val s = RelaySession(getApplication(), url, token, device, viewModelScope)
        mutableSession.value = s
        s.start()
        cacheWriter = viewModelScope.launch {
            combine(s.store.sessionList, s.store.sessions) { list, open -> list to open }.collectLatest { (list, open) ->
                if (list.isEmpty()) return@collectLatest
                delay(CACHE_DELAY_MS) // a burst of events writes once
                if (mutableSession.value !== s) return@collectLatest // unpaired meanwhile
                val snapshot = OfflineCache.snapshot(list, open, mutableCached.value)
                mutableCached.value = snapshot
                withContext(Dispatchers.IO) { cache.write(snapshot) }
            }
        }
    }

    /** Trades the code for a token, seals and stores it, and connects. Returns the error to show, or null. */
    suspend fun pair(url: String, code: String): String? {
        return try {
            val pairing = RelayRest.pair(url, code)
            val sealed = withContext(Dispatchers.IO) { cipher.seal(pairing.token) }
            prefs.savePairing(StoredPairing(url, sealed, pairing.device))
            LogcatLogger.log(Logger.Level.INFO, "pairing: paired as ${pairing.device}")
            begin(url, pairing.token, pairing.device)
            mutableNav.value = Nav.home
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SessionStore.message(e)
        }
    }

    /** Local only: the token, the cache and the images go; the relay still knows the device until it's revoked. */
    fun unpair() {
        val s = mutableSession.value ?: return
        mutableSession.value = null
        cacheWriter?.cancel()
        s.stop()
        s.images.memoryCache?.clear()
        s.images.diskCache?.clear()
        mutableTrays.value = emptyMap()
        mutableShared.value = emptyList()
        mutableCached.value = null
        mutableNav.value = Nav.pairing
        viewModelScope.launch {
            prefs.clear()
            withContext(Dispatchers.IO) {
                cache.clear()
                cipher.forget()
                File(getApplication<Application>().cacheDir, "captures").deleteRecursively()
            }
        }
    }

    fun open(screen: Screen) {
        mutableNav.value = mutableNav.value.open(screen)
        val readOnly = (screen as? Screen.Conversation)?.let { c ->
            mutableSession.value?.store?.sessionList?.value?.firstOrNull { it.id == c.id }?.isReadOnly
        } ?: false
        if (screen is Screen.Conversation && !readOnly && mutableShared.value.isNotEmpty()) {
            val waiting = mutableShared.value
            mutableShared.value = emptyList()
            addPrepared(screen.id, waiting)
        }
    }

    /** False at a root: the activity handles Back. */
    fun back(): Boolean {
        val previous = mutableNav.value.back() ?: return false
        mutableNav.value = previous
        return true
    }

    fun onForeground() {
        mutableSession.value?.client?.reconnectNow()
    }

    fun reconnectNow() = onForeground()

    fun consumeNotice() {
        mutableNotice.value = null
    }

    fun say(text: String) {
        mutableNotice.value = text
    }

    fun setTab(raw: String) {
        viewModelScope.launch { prefs.setTab(raw) }
    }

    fun setNotifications(n: NotificationPrefs) {
        viewModelScope.launch { prefs.setNotifications(n) }
    }

    // Conversations

    fun createConversation() = act {
        val s = mutableSession.value ?: return@act
        open(Screen.Conversation(s.store.createSession().id))
    }

    fun rename(id: String, title: String) = act { mutableSession.value?.store?.renameSession(id, title) }

    fun setPinned(id: String, pinned: Boolean) = act { mutableSession.value?.store?.setPinned(id, pinned) }

    fun setTags(id: String, tags: List<String>) = act { mutableSession.value?.store?.setTags(id, tags) }

    fun delete(id: String) = act {
        mutableSession.value?.store?.deleteSession(id)
        if (mutableNav.value.top == Screen.Conversation(id)) back()
    }

    fun setModel(id: String, choice: ModelChoice) = act { mutableSession.value?.setModel(id, choice) }

    // One conversation

    fun approve(approval: PendingApproval, choice: ApprovalChoice) =
        sendNow(ClientMessage.Approve(approval.runId, approval.id, choice))

    fun stop(runId: String) = sendNow(ClientMessage.Stop(runId))

    /** A steer during a run (text only: the tray waits), else a prompt with the tray's images. False if nothing went. */
    fun submit(sessionId: String, text: String, runId: String?): Boolean {
        val s = mutableSession.value ?: return false
        if (runId != null) {
            return try {
                s.store.sendSteer(sessionId, runId, text)
                true
            } catch (_: Exception) {
                say("Not connected to the relay. Try again.")
                false
            }
        }
        val images = mutableTrays.value[sessionId].orEmpty()
        mutableTrays.value -= sessionId
        viewModelScope.launch { s.store.sendPrompt(sessionId, text, images.map { ImageUpload(it.image.jpeg, "image/jpeg") }) }
        return true
    }

    fun retry(sessionId: String, promptId: String) {
        viewModelScope.launch { mutableSession.value?.store?.retryPrompt(sessionId, promptId) }
    }

    fun addImages(sessionId: String, uris: List<Uri>) {
        viewModelScope.launch { addPrepared(sessionId, prepare(uris)) }
    }

    fun removeImage(sessionId: String, key: Long) {
        mutableTrays.value += sessionId to mutableTrays.value[sessionId].orEmpty().filter { it.key != key }
    }

    /** Share to Trismegistus: into the open conversation's tray, else waiting for the next one opened. */
    fun share(uris: List<Uri>) {
        viewModelScope.launch {
            val images = prepare(uris)
            when (val top = mutableNav.value.top) {
                is Screen.Conversation -> addPrepared(top.id, images)
                else -> {
                    mutableShared.value = (mutableShared.value + images).take(MAX_IMAGES)
                    if (images.isNotEmpty()) say("${mutableShared.value.size} shared images: open a conversation to send them.")
                }
            }
        }
    }

    private suspend fun prepare(uris: List<Uri>): List<PreparedImage> = uris.take(MAX_IMAGES).mapNotNull { uri ->
        try {
            prep.prepare(uri)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            say("Couldn't read an image.")
            null
        }
    }

    private fun addPrepared(sessionId: String, images: List<PreparedImage>) {
        val now = mutableTrays.value[sessionId].orEmpty()
        val room = MAX_IMAGES - now.size
        if (images.size > room) say("Up to $MAX_IMAGES images per message.")
        mutableTrays.value += sessionId to now + images.take(room.coerceAtLeast(0)).map { TrayImage(keys.incrementAndGet(), it) }
    }

    private fun sendNow(message: ClientMessage): Boolean {
        val s = mutableSession.value ?: return false
        return try {
            s.client.send(message)
            true
        } catch (_: Exception) {
            say("Not connected to the relay. Try again.")
            false
        }
    }

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say(SessionStore.message(e))
            }
        }
    }

    override fun onCleared() {
        mutableSession.value?.stop()
    }

    companion object {
        /** The relay's limit per prompt (and the Mac app's). */
        const val MAX_IMAGES: Int = 4
        private const val CACHE_DELAY_MS = 2000L
    }
}
