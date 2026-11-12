package com.nuvio.tv.livetv.sync

import android.content.Context
import androidx.annotation.StringRes
import com.nuvio.tv.R
import com.nuvio.tv.core.util.withAppLocale
import android.os.Build
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.model.ServerConfiguration
import com.nuvio.tv.livetv.data.LiveTvPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.auth.Auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import com.nuvio.tv.livetv.data.ForkHttp

/** What the settings screen and the first-time question show for Setup Sync. */
data class CloudSyncState(
    /** Only on the official Nuvio server: that is the sign-in the sync service checks. */
    val available: Boolean = false,
    val signedIn: Boolean = false,
    val email: String? = null,
    val enabled: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val lastSyncMs: Long = 0L,
    /** A question waiting for the person (shown by [CloudSyncHost]). */
    val prompt: CloudPrompt? = null
)

enum class CloudPrompt {
    /** Nothing saved for this account yet, and this TV has a Live TV setup. */
    BackUp,
    /** This account already has a saved setup, and this TV has a different one. */
    Choose
}

enum class CloudChoice { UseSaved, Combine, Later }

/**
 * Keeps the Live TV setup the same on every TV signed in to the same Nuvio account.
 *
 * It uses the Nuvio sign-in this app already has: the access token is sent to our own small
 * service (the livetv-cloud Worker), which asks Nuvio whose token it is and keeps one saved copy
 * per account. Nothing is written to Nuvio's servers, and the token is never stored here.
 *
 * What is synced is exactly what the Google Drive backup syncs (LiveTvPreferences.exportForSync),
 * combined the same way (LiveTvDriveSync.merge: setting by setting against what both sides agreed
 * on last time, playlists and guides source by source). A TV never overwrites a copy it hasn't
 * seen: the service refuses a save made from an out-of-date copy, and the next round combines.
 *
 * When it asks, by situation:
 *  - signed in on a TV with no setup, and a saved copy exists: restored, no question;
 *  - an account with no saved copy and a TV with a setup: asks once whether to back it up;
 *  - an account with a saved copy and a TV with a different setup: asks once what to do;
 *  - after that: syncs by itself (when Live TV settings change, every 15 minutes, and at start).
 */
@Singleton
class LiveTvCloudSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: LiveTvPreferences,
    private val drive: LiveTvDriveSync,
    private val authManager: AuthManager,
    private val auth: Auth,
    private val serverConfiguration: ServerConfiguration
) {
    private fun text(@StringRes id: Int, vararg args: Any): String = context.withAppLocale().getString(id, *args)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = context.applicationContext.getSharedPreferences("livetv_cloud_sync", Context.MODE_PRIVATE)
    private val parts = SyncParts(store)
    private val mutex = Mutex()
    private val http = ForkHttp.builder()
        .dns(com.nuvio.tv.core.network.IPv4FirstDns())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()
    private val json = "application/json; charset=utf-8".toMediaType()

    private val available: Boolean =
        !serverConfiguration.isCustom &&
            serverConfiguration.backendUrl.toHttpUrlOrNull()?.host.equals("api.nuvio.tv", ignoreCase = true)

    private val _state = MutableStateFlow(
        CloudSyncState(available = available, lastSyncMs = store.getLong(KEY_LAST_SYNC, 0L))
    )
    val state: StateFlow<CloudSyncState> = _state.asStateFlow()

    @Volatile private var started = false
    @Volatile private var userId: String? = null
    /** True while a saved setup is being written here, so it isn't saved straight back. */
    @Volatile private var applying = false

    private val deviceId: String
        get() = store.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            store.edit().putString(KEY_DEVICE_ID, it).apply()
        }

    // ------------------------------------------------------------------ start

    /** Starts watching the Nuvio sign-in. Called once when the app opens. */
    @Synchronized
    fun start() {
        if (started || !available) return
        started = true
        com.nuvio.vpn.android.VpnPlaybackProtection.observeBackupChanges {
            scope.launch { delay(8_000); if (!applying && isOn()) syncIfChanged() }
        }
        drive.importListener = { scope.launch { runImport() } }
        // A QR sign-in, or a TV that was already signed in when the app opened: both arrive here.
        scope.launch { authManager.authState.collect { onAuthState(it) } }
        // This TV's Live TV settings changed: sync shortly after (not for per-TV things like the
        // last channel watched, which aren't part of the setup).
        scope.launch {
            prefs.changes.drop(1).debounce(8_000).collect {
                if (!applying && isOn()) syncIfChanged()
            }
        }
        scope.launch {
            while (isActive) {
                delay(15 * 60_000L)
                com.nuvio.tv.livetv.data.BackgroundWork.awaitLiveHold()
                if (isOn()) syncNow()
            }
        }
    }

    private suspend fun onAuthState(authState: AuthState) {
        when (authState) {
            is AuthState.FullAccount -> {
                // "Start in Full Screen": the start-up check waits until the channel is playing.
                kotlinx.coroutines.withTimeoutOrNull(60_000L) { com.nuvio.tv.livetv.data.BackgroundWork.awaitLiveHold() }
                userId = authState.userId
                val on = isEnabled(authState.userId)
                _state.value = _state.value.copy(signedIn = true, email = authState.email, enabled = on)
                mutex.withLock {
                    if (on) reconcileAndReport(authState.userId) else decideFirstTime(authState.userId, explicit = false)
                }
            }
            AuthState.SignedOut -> {
                userId = null
                // This TV keeps its setup; it just stops syncing until someone signs in again.
                _state.value = _state.value.copy(signedIn = false, email = null, enabled = false, prompt = null)
            }
            AuthState.Loading -> Unit
        }
    }

    // ------------------------------------------------------------------ what the settings screen does

    fun syncNow() {
        scope.launch {
            mutex.withLock {
                val uid = userId ?: return@withLock
                if (isEnabled(uid)) reconcileAndReport(uid, doneMessage = text(R.string.iptv_status_sync_up_to_date))
            }
        }
    }

    /** "Turn On" in settings: the same as accepting the question. */
    fun turnOn() {
        scope.launch {
            mutex.withLock {
                val uid = userId ?: return@withLock
                store.edit().putBoolean(KEY_DECLINED + uid, false).apply()
                _state.value = _state.value.copy(prompt = null, busy = true, message = null)
                decideFirstTime(uid, explicit = true)
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    /** Stops syncing on this TV. The saved copy stays in the account. */
    fun turnOff() {
        val uid = userId ?: return
        setEnabled(uid, false)
        store.edit().putBoolean(KEY_DECLINED + uid, true).apply()
        _state.value = _state.value.copy(enabled = false, prompt = null, message = text(R.string.iptv_status_sync_off))
    }

    /** Removes the saved copy from the cloud and stops syncing here. This TV keeps its setup. */
    fun deleteCloudCopy() {
        scope.launch {
            mutex.withLock {
                val uid = userId ?: return@withLock
                _state.value = _state.value.copy(busy = true, message = null)
                val message = runCatching {
                    val reply = call("DELETE", "/v1/setup")
                    if (reply.code == 200) {
                        setEnabled(uid, false)
                        store.edit().putBoolean(KEY_DECLINED + uid, true).apply()
                        baseFile().delete()
                        text(R.string.iptv_status_sync_deleted)
                    } else failText(reply.code)
                }.getOrElse { text(R.string.iptv_status_sync_unreachable) }
                _state.value = _state.value.copy(busy = false, enabled = isEnabled(uid), message = message)
            }
        }
    }

    /** Brings back the copy from before the last save (a safety net if a sync went wrong). */
    fun restorePrevious() {
        scope.launch {
            mutex.withLock {
                val uid = userId ?: return@withLock
                _state.value = _state.value.copy(busy = true, message = null)
                val message = runCatching {
                    val reply = call("GET", "/v1/setup/previous")
                    val data = reply.json?.optJSONObject("data")
                    when {
                        reply.code == 404 -> text(R.string.iptv_status_sync_no_earlier_copy)
                        reply.code != 200 || data == null -> failText(reply.code)
                        else -> {
                            applyShared(data)
                            when (val outcome = put(reply.json?.optInt("currentRevision") ?: 0, data)) {
                                is Put.Saved -> { saved(uid, data, emptySet()); text(R.string.iptv_status_sync_restored_earlier_copy) }
                                else -> putText(outcome)
                            }
                        }
                    }
                }.getOrElse { text(R.string.iptv_status_sync_unreachable) }
                _state.value = _state.value.copy(busy = false, lastSyncMs = store.getLong(KEY_LAST_SYNC, 0L), message = message)
            }
        }
    }

    // ------------------------------------------------------------------ bringing a Google Drive backup over

    /** Whether Google Drive was already connected on this TV when the import began (then it stays connected). */
    @Volatile private var driveWasConnected = false

    /**
     * "Import from Google Drive" in settings: takes the Live TV setup saved in Google Drive, combines
     * it with this TV's setup, and saves the result to the Nuvio account (turning sync on). Without a
     * Google Drive connection it signs in first (a code on the phone) and disconnects again afterwards.
     * Nothing in Google Drive is changed or deleted.
     */
    fun importFromDrive() {
        scope.launch {
            if (userId == null) return@launch
            driveWasConnected = drive.state.value.connected
            if (driveWasConnected) runImport()
            else {
                _state.value = _state.value.copy(message = null)
                drive.beginSignIn(forImport = true)
            }
        }
    }

    private suspend fun runImport() {
        mutex.withLock {
            val uid = userId ?: return@withLock
            _state.value = _state.value.copy(busy = true, message = null)
            val message = runCatching {
                val driveData = drive.backupData()
                if (!driveWasConnected) drive.disconnect()
                when {
                    driveData == null -> text(R.string.iptv_status_sync_drive_no_backup)
                    !prefs.syncHasSources(driveData) -> text(R.string.iptv_status_sync_drive_empty)
                    else -> {
                        val local = prefs.exportForSync()
                        val removedHere = prefs.removedSourceIds()
                        // The Drive setup's settings win; playlists and guides from both are kept.
                        val imported = if (prefs.syncHasSources(local)) drive.merge(null, local, driveData, false, removedHere) else driveData
                        if (imported.toString() != local.toString()) apply(imported)
                        store.edit().putBoolean(KEY_DECLINED + uid, false).apply()
                        setEnabled(uid, true)
                        val updatedText = text(R.string.iptv_status_updated_other_tv)
                        val note = reconcile(uid, updatedText)
                        if (note == null || note == updatedText) text(R.string.iptv_status_sync_drive_imported)
                        else note
                    }
                }
            }.getOrElse { text(R.string.iptv_status_sync_unreachable) }
            _state.value = _state.value.copy(
                busy = false,
                enabled = isEnabled(uid),
                lastSyncMs = store.getLong(KEY_LAST_SYNC, 0L),
                message = message
            )
        }
    }

    // ------------------------------------------------------------------ answers to the questions

    fun answerBackUp(yes: Boolean) {
        scope.launch {
            mutex.withLock {
                val uid = userId ?: return@withLock
                _state.value = _state.value.copy(prompt = null)
                if (!yes) {
                    store.edit().putBoolean(KEY_DECLINED + uid, true).apply()
                    return@withLock
                }
                _state.value = _state.value.copy(busy = true, message = null)
                decideFirstTime(uid, explicit = true)
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    fun answerChoose(choice: CloudChoice) {
        scope.launch {
            mutex.withLock {
                val uid = userId ?: return@withLock
                _state.value = _state.value.copy(prompt = null)
                if (choice == CloudChoice.Later) {
                    store.edit().putBoolean(KEY_DECLINED + uid, true).apply()
                    return@withLock
                }
                _state.value = _state.value.copy(busy = true, message = null)
                val message = runCatching {
                    val remote = fetch() as? Remote.Found ?: return@runCatching text(R.string.iptv_status_sync_saved_unreachable)
                    val removedHere = prefs.removedSourceIds()
                    if (choice == CloudChoice.UseSaved) {
                        applyShared(remote.data)
                        saved(uid, remote.data, emptySet())
                        setEnabled(uid, true)
                        text(R.string.iptv_status_sync_using_saved)
                    } else {
                        val local = localView()
                        val remoteView = parts.strip(remote.data)
                        val merged = drive.merge(null, local, remoteView, false, removedHere)
                        if (merged.toString() != local.toString()) applyShared(merged)
                        if (merged.toString() == remoteView.toString()) {
                            saved(uid, remote.data, emptySet())
                            setEnabled(uid, true)
                            text(R.string.iptv_status_sync_combined)
                        } else when (val result = put(remote.revision, parts.keep(merged, remote.data))) {
                            is Put.Saved -> {
                                saved(uid, merged, removedHere)
                                setEnabled(uid, true)
                                text(R.string.iptv_status_sync_combined)
                            }
                            else -> putText(result)
                        }
                    }
                }.getOrElse { text(R.string.iptv_status_sync_unreachable) }
                _state.value = _state.value.copy(
                    busy = false,
                    enabled = isEnabled(uid),
                    lastSyncMs = store.getLong(KEY_LAST_SYNC, 0L),
                    message = message
                )
            }
        }
    }

    // ------------------------------------------------------------------ deciding and syncing

    private fun isEnabled(uid: String) = store.getBoolean(KEY_ENABLED + uid, false)

    private fun isOn(): Boolean = userId?.let { isEnabled(it) } == true

    private fun setEnabled(uid: String, on: Boolean) {
        store.edit().putBoolean(KEY_ENABLED + uid, on).apply()
        if (userId == uid) _state.value = _state.value.copy(enabled = on)
    }

    /**
     * An account this TV hasn't synced with yet. Looks at the saved copy and this TV's setup and
     * either does the obvious thing, or sets the question to ask. With [explicit] (the person
     * said yes, or turned it on in settings) it goes ahead instead of asking again.
     * Must be called with the lock held.
     */
    private suspend fun decideFirstTime(uid: String, explicit: Boolean) {
        if (!explicit && store.getBoolean(KEY_DECLINED + uid, false)) return
        runCatching {
            val local = prefs.exportForSync()
            val hasLocal = prefs.syncHasSources(local)
            val remote = fetch()
            when {
                remote is Remote.Failed -> {
                    if (explicit) _state.value = _state.value.copy(message = failText(remote.code))
                }
                remote is Remote.Found && usable(remote.data) -> {
                    if (!hasLocal) {
                        // A TV with nothing set up, signing in to an account that has a setup.
                        applyShared(remote.data)
                        saved(uid, remote.data, emptySet())
                        setEnabled(uid, true)
                        _state.value = _state.value.copy(
                            lastSyncMs = store.getLong(KEY_LAST_SYNC, 0L),
                            message = text(R.string.iptv_status_sync_restored)
                        )
                    } else {
                        _state.value = _state.value.copy(prompt = CloudPrompt.Choose)
                    }
                }
                else -> {
                    // Nothing usable saved for this account (none, or one with no playlists or guides).
                    val savedRevision = (remote as? Remote.Found)?.revision ?: 0
                    when {
                        !hasLocal -> if (explicit) setEnabled(uid, true)
                        explicit -> backUpFirst(uid, savedRevision, parts.strip(local))
                        else -> _state.value = _state.value.copy(prompt = CloudPrompt.BackUp)
                    }
                }
            }
            Unit
        }.onFailure {
            if (explicit) _state.value = _state.value.copy(message = text(R.string.iptv_status_sync_unreachable))
        }
    }

    private suspend fun backUpFirst(uid: String, baseRevision: Int, local: JSONObject) {
        val removedHere = prefs.removedSourceIds()
        when (val result = put(baseRevision, local)) {
            is Put.Saved -> {
                saved(uid, local, removedHere)
                setEnabled(uid, true)
                _state.value = _state.value.copy(
                    lastSyncMs = store.getLong(KEY_LAST_SYNC, 0L),
                    message = text(R.string.iptv_status_sync_saved)
                )
            }
            else -> _state.value = _state.value.copy(message = putText(result))
        }
    }

    private suspend fun syncIfChanged() {
        val hash = sha(localView().toString())
        if (hash == store.getString(KEY_LOCAL_HASH, null)) return
        mutex.withLock {
            val uid = userId ?: return@withLock
            if (isEnabled(uid)) reconcileAndReport(uid)
        }
    }

    private suspend fun reconcileAndReport(uid: String, doneMessage: String? = null) {
        _state.value = _state.value.copy(busy = true)
        val result = runCatching { reconcile(uid) }
        _state.value = _state.value.copy(
            busy = false,
            lastSyncMs = store.getLong(KEY_LAST_SYNC, 0L),
            message = result.fold(onSuccess = { it ?: doneMessage }, onFailure = { text(R.string.iptv_status_sync_unreachable) })
        )
    }

    /**
     * One round: take the saved copy, combine it with this TV's changes, apply what's new here and
     * save what's new there. Tries again if another TV saved in between. Returns a message to
     * show, or null when there is nothing to say.
     */
    private suspend fun reconcile(uid: String, updatedText: String = text(R.string.iptv_status_updated_other_tv)): String? {
        repeat(3) {
            val remote = fetch()
            val localFull = prefs.exportForSync()
            val local = parts.strip(localFull)
            val removedHere = prefs.removedSourceIds()
            when {
                remote is Remote.Failed -> return failText(remote.code)
                remote is Remote.Found && usable(remote.data) -> {
                    val remoteView = parts.strip(remote.data)
                    val merged = drive.merge(readBase(uid), parts.freshen(local, remoteView), remoteView, false, removedHere)
                    val changedHere = merged.toString() != local.toString()
                    if (changedHere) apply(parts.keep(merged, localFull))
                    val news = if (changedHere) updatedText else null
                    val mergedFull = parts.keep(merged, remote.data)
                    if (merged.toString() == remoteView.toString() || !usable(mergedFull)) {
                        saved(uid, remote.data, emptySet())
                        return news
                    }
                    when (val result = put(remote.revision, mergedFull)) {
                        is Put.Saved -> { saved(uid, merged, removedHere); return news }
                        Put.Conflict -> Unit // another TV saved first: look again
                        else -> return putText(result)
                    }
                }
                else -> {
                    // Nothing usable saved: never let that empty this TV; save this TV's setup if it has one.
                    if (!prefs.syncHasSources(localFull)) return null
                    val savedRevision = (remote as? Remote.Found)?.revision ?: 0
                    when (val result = put(savedRevision, local)) {
                        is Put.Saved -> { saved(uid, local, removedHere); return null }
                        Put.Conflict -> Unit
                        else -> return putText(result)
                    }
                }
            }
        }
        return text(R.string.iptv_status_sync_incomplete)
    }

    /** This TV's setup without the parts Setup Sync is switched off for. */
    private suspend fun localView(): JSONObject = parts.strip(prefs.exportForSync())

    /**
     * A saved setup counts when it has a playlist or guide. When this TV leaves playlists out,
     * any saved setup counts: another device's copy may be the only one that has them.
     */
    private fun usable(data: JSONObject): Boolean =
        prefs.syncHasSources(data) || (parts.isOff(SyncPart.LOGINS) && data.length() > 0)

    /** Writes a saved setup into this TV, leaving the parts switched off as they are here. */
    private suspend fun applyShared(data: JSONObject) = apply(parts.keep(parts.strip(data), prefs.exportForSync()))

    /** The parts of the setup this TV syncs (for the settings screen). */
    private val _enabledParts = MutableStateFlow(parts.enabled())
    val enabledParts: StateFlow<Set<SyncPart>> = _enabledParts.asStateFlow()

    /** Switches a part of the setup on or off on this TV and syncs soon, so the saved copy catches up. */
    fun setPart(part: SyncPart, on: Boolean) {
        parts.set(part, on)
        _enabledParts.value = parts.enabled()
        scope.launch { if (userId?.let { isEnabled(it) } == true) syncIfChanged() }
    }

    /** Writes a saved setup into this TV, without it being saved straight back. */
    private suspend fun apply(data: JSONObject) {
        applying = true
        try {
            drive.applyLocally(data)
        } finally {
            applying = false
        }
    }

    /** Remembers what both sides now agree on: the starting point for the next combine. */
    private suspend fun saved(uid: String, data: JSONObject, removedHere: Set<String>) {
        runCatching { baseFile().writeText(com.nuvio.vpn.android.VpnBackup.sealCache(parts.strip(data).toString())) }
        store.edit()
            .putString(KEY_BASE_USER, uid)
            .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
            .putString(KEY_LOCAL_HASH, sha(localView().toString()))
            .apply()
        parts.clearFresh()
        if (removedHere.isNotEmpty()) prefs.forgetRemovedSources(removedHere)
    }

    private fun baseFile() = File(context.filesDir, "livetv_cloud_base.json")

    /** What both sides last agreed on, but only if it was for this account. */
    private fun readBase(uid: String): JSONObject? {
        if (store.getString(KEY_BASE_USER, null) != uid) return null
        return runCatching { JSONObject(com.nuvio.vpn.android.VpnBackup.openCache(baseFile().readText())) }.getOrNull()
    }

    // ------------------------------------------------------------------ the service

    private sealed interface Remote {
        data object None : Remote
        data class Found(val revision: Int, val data: JSONObject) : Remote
        data class Failed(val code: Int) : Remote
    }

    private sealed interface Put {
        data class Saved(val revision: Int) : Put
        data object Conflict : Put
        data object TooBig : Put
        data object Limit : Put
        data class Failed(val code: Int) : Put
    }

    private class Reply(val code: Int, val json: JSONObject?)

    private suspend fun fetch(): Remote {
        val reply = call("GET", "/v1/setup")
        val data = reply.json?.optJSONObject("data")
        return when {
            reply.code == 200 && data != null -> Remote.Found(reply.json?.optInt("revision") ?: 0, data)
            reply.code == 404 -> Remote.None
            else -> Remote.Failed(reply.code)
        }
    }

    private suspend fun put(baseRevision: Int, data: JSONObject): Put {
        val body = JSONObject()
            .put("baseRevision", baseRevision)
            .put("device", Build.MODEL ?: "TV")
            .put("deviceId", deviceId)
            .put("data", data)
        val reply = call("PUT", "/v1/setup", body)
        return when (reply.code) {
            200 -> Put.Saved(reply.json?.optInt("revision") ?: (baseRevision + 1))
            409 -> Put.Conflict
            413 -> Put.TooBig
            429 -> Put.Limit
            else -> Put.Failed(reply.code)
        }
    }

    /** Calls the service with the Nuvio sign-in; if the sign-in has just expired, refreshes it once and retries. */
    private suspend fun call(method: String, path: String, body: JSONObject? = null): Reply {
        var reply = callOnce(method, path, body)
        if (reply.code == 401 && authManager.refreshSessionIfJwtExpired(Exception("JWT expired"))) {
            reply = callOnce(method, path, body)
        }
        return reply
    }

    private suspend fun callOnce(method: String, path: String, body: JSONObject?): Reply = withContext(Dispatchers.IO) {
        val token = auth.currentAccessTokenOrNull()?.takeIf { it.isNotBlank() }
            ?: return@withContext Reply(401, null)
        val request = Request.Builder()
            .url(CLOUD_URL + path)
            .header("Authorization", "Bearer $token")
            .header("apikey", serverConfiguration.publishableKey)
            .method(method, body?.toString()?.toRequestBody(json))
            .build()
        http.newCall(request).execute().use { response ->
            Reply(response.code, runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull())
        }
    }

    private fun failText(code: Int) = when (code) {
        401 -> text(R.string.iptv_status_sync_sign_in_again)
        else -> text(R.string.iptv_status_sync_unreachable)
    }

    private fun putText(result: Put) = when (result) {
        Put.Conflict -> text(R.string.iptv_status_sync_conflict)
        Put.TooBig -> text(R.string.iptv_status_sync_too_big)
        Put.Limit -> text(R.string.iptv_status_sync_limit)
        is Put.Failed -> failText(result.code)
        is Put.Saved -> ""
    }

    private fun sha(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        /** Our own small service (the livetv-cloud Worker). */
        const val CLOUD_URL = "https://livetv-cloud.nuvioplusiptv.workers.dev"
        private const val KEY_ENABLED = "enabled_"
        private const val KEY_DECLINED = "declined_"
        private const val KEY_BASE_USER = "base_user"
        private const val KEY_LAST_SYNC = "last_sync"
        private const val KEY_LOCAL_HASH = "local_hash"
        private const val KEY_DEVICE_ID = "device_id"
    }
}
