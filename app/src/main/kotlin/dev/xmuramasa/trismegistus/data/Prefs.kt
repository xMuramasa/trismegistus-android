package dev.xmuramasa.trismegistus.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("settings")

/** What pairing stored: the relay, the sealed token, the name the relay gave this device. */
data class StoredPairing(val relayUrl: String, val sealedToken: String, val device: String)

/** The notification switches, kept now for #5. Sound for approvals is on by default (SPEC). */
data class NotificationPrefs(val approvals: Boolean = true, val runFinished: Boolean = true, val approvalSound: Boolean = true)

/** The app's private DataStore (not backed up: allowBackup=false and the extraction rules). */
class Prefs(private val context: Context) {
    private object Keys {
        val relayUrl = stringPreferencesKey("relay_url")
        val token = stringPreferencesKey("token_sealed")
        val device = stringPreferencesKey("device")
        val tab = stringPreferencesKey("conversation_tab")
        val notifyApprovals = booleanPreferencesKey("notify_approvals")
        val notifyRuns = booleanPreferencesKey("notify_run_finished")
        val approvalSound = booleanPreferencesKey("approval_sound")
    }

    suspend fun pairing(): StoredPairing? {
        val p = context.store.data.first()
        return StoredPairing(p[Keys.relayUrl] ?: return null, p[Keys.token] ?: return null, p[Keys.device] ?: return null)
    }

    suspend fun savePairing(pairing: StoredPairing) {
        context.store.edit {
            it[Keys.relayUrl] = pairing.relayUrl
            it[Keys.token] = pairing.sealedToken
            it[Keys.device] = pairing.device
        }
    }

    /** Unpair: the token and everything else go. */
    suspend fun clear() {
        context.store.edit { it.clear() }
    }

    val tab: Flow<String?> = context.store.data.map { it[Keys.tab] }

    suspend fun setTab(raw: String) {
        context.store.edit { it[Keys.tab] = raw }
    }

    val notifications: Flow<NotificationPrefs> = context.store.data.map { p ->
        NotificationPrefs(p[Keys.notifyApprovals] ?: true, p[Keys.notifyRuns] ?: true, p[Keys.approvalSound] ?: true)
    }

    suspend fun setNotifications(n: NotificationPrefs) {
        context.store.edit {
            it[Keys.notifyApprovals] = n.approvals
            it[Keys.notifyRuns] = n.runFinished
            it[Keys.approvalSound] = n.approvalSound
        }
    }
}
