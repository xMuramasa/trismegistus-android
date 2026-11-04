package dev.xmuramasa.trismegistus.data

import android.content.Context
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dev.xmuramasa.trismegistus.core.relay.ConnectionState
import dev.xmuramasa.trismegistus.core.relay.DeviceInfo
import dev.xmuramasa.trismegistus.core.relay.ModelCatalog
import dev.xmuramasa.trismegistus.core.relay.ModelChoice
import dev.xmuramasa.trismegistus.core.relay.RelayApiException
import dev.xmuramasa.trismegistus.core.relay.RelayClient
import dev.xmuramasa.trismegistus.core.relay.RelayRest
import dev.xmuramasa.trismegistus.core.state.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/**
 * Everything one pairing needs: the relay client, REST, the store and an image loader that sends the token to the relay
 * only. Built from the stored relay URL and token; [start] runs it in [scope] (the view model's, on the main thread,
 * where SessionStore lives).
 */
class RelaySession(context: Context, val relayUrl: String, token: String, val device: String, private val scope: CoroutineScope) {
    private val http = OkHttpClient()
    val client = RelayClient(relayUrl, token, http, logger = LogcatLogger)
    val rest = RelayRest(relayUrl, token, http)
    val store = SessionStore(client, rest, logger = LogcatLogger)
    val host: String = relayUrl.toHttpUrl().host

    private val mutableMe = MutableStateFlow<DeviceInfo?>(null)
    private val mutableCatalog = MutableStateFlow<ModelCatalog?>(null)
    private val mutableSessionModels = MutableStateFlow<Map<String, ModelChoice>>(emptyMap())
    private val mutableLinkedSince = MutableStateFlow<Long?>(null)

    /** Null until `/api/me` answers; a relay that predates roles (404) reads as a manager, as the Mac app does. */
    val me: StateFlow<DeviceInfo?> = mutableMe.asStateFlow()
    val catalog: StateFlow<ModelCatalog?> = mutableCatalog.asStateFlow()
    val sessionModels: StateFlow<Map<String, ModelChoice>> = mutableSessionModels.asStateFlow()

    /** Unix ms of the current connection's start; null while not connected. */
    val linkedSince: StateFlow<Long?> = mutableLinkedSince.asStateFlow()

    val images: ImageLoader = ImageLoader.Builder(context)
        .components {
            val authed = http.newBuilder().addInterceptor { chain ->
                val request = chain.request()
                chain.proceed(
                    if (request.url.host == host) request.newBuilder().header("Authorization", "Bearer $token").build() else request,
                )
            }.build()
            add(OkHttpNetworkFetcherFactory(callFactory = { authed }))
        }
        .build()

    fun attachmentUrl(id: String): String = relayUrl.trimEnd('/') + "/api/attachments/" + id

    fun start() {
        store.start(scope)
        scope.launch {
            store.connection.collect { state ->
                if (state is ConnectionState.Connected) {
                    if (mutableLinkedSince.value == null) mutableLinkedSince.value = System.currentTimeMillis()
                    refresh()
                } else {
                    mutableLinkedSince.value = null
                }
            }
        }
    }

    fun stop() {
        store.stop()
    }

    /** The list, the device's role and the models, after each (re)connect. Failures leave what was there. */
    suspend fun refresh() {
        quietly { store.refreshSessions() }
        quietly {
            mutableMe.value = try {
                rest.me()
            } catch (e: RelayApiException) {
                if (e.status == 404) DeviceInfo(device, "manager") else throw e
            }
        }
        quietly { mutableCatalog.value = rest.models() }
        quietly { mutableSessionModels.value = rest.sessionModels() }
    }

    suspend fun setModel(sessionId: String, choice: ModelChoice) {
        val stored = rest.setModel(sessionId, choice)
        mutableSessionModels.value += (sessionId to stored)
    }

    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
}
