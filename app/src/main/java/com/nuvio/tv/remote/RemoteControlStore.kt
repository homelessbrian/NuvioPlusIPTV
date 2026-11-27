package com.nuvio.tv.remote

import android.content.Context
import com.nuvio.tv.livetv.data.LiveTvPreferences
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class RemoteControlStore internal constructor(private val persistence: RemoteControlPersistence, scope: CoroutineScope) {
    @Inject constructor(preferences: LiveTvPreferences) : this(object : RemoteControlPersistence {
        override val raw: Flow<String> = preferences.rawRemoteButtons
        override suspend fun compareAndSet(expected: String, value: String): Boolean = preferences.compareAndSetRawRemoteButtons(expected, value)
    }, CoroutineScope(SupervisorJob() + Dispatchers.IO))
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(RemoteControlState())
    val state: StateFlow<RemoteControlState> = mutableState.asStateFlow()
    private var undoConfiguration: RemoteConfiguration? = null
    private var observedRaw: String = ""

    init {
        scope.launch {
            try {
                persistence.raw.collect { raw -> mutex.withLock {
                    // A slow collector can receive an older emission after a newer local save.
                    if (raw != persistence.raw.first()) return@withLock
                    observedRaw = raw
                    val decoded = runCatching { RemoteControlCodec.decode(raw) }
                    val current = mutableState.value
                    if (decoded.isFailure) {
                        undoConfiguration = null
                        mutableState.value = RemoteControlState(loading = false, error = RemoteStoreError.LOAD_FAILED, revision = current.revision + 1)
                    } else {
                        val configuration = decoded.getOrThrow()
                        // Our writes publish before the DataStore observer; don't clear their undo on echo.
                        if (current.loading || configuration != current.configuration || current.error == RemoteStoreError.LOAD_FAILED) {
                            undoConfiguration = null
                            mutableState.value = RemoteControlState(configuration, loading = false, revision = current.revision + 1)
                        }
                    }
                } }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutex.withLock {
                mutableState.value = mutableState.value.copy(loading = false, error = RemoteStoreError.LOAD_FAILED)
            } }
        }
    }

    suspend fun applyConfiguration(configuration: RemoteConfiguration, expectedRevision: Long): RemoteMutationResult = mutex.withLock {
        val current = mutableState.value
        if (current.loading || current.revision != expectedRevision) return@withLock RemoteMutationResult.STALE
        write(configuration, rememberUndo = true)
    }

    suspend fun saveBinding(screen: RemoteScreen, press: RemotePress, button: RemoteButton, action: RemoteAction?): RemoteMutationResult = mutex.withLock {
        if (mutableState.value.loading) return@withLock RemoteMutationResult.STALE
        write(mutableState.value.configuration.withBinding(screen, press, button, action), rememberUndo = true)
    }

    suspend fun addButton(button: RemoteButton, label: String = ""): RemoteMutationResult = mutex.withLock {
        val current = mutableState.value
        if (current.loading) return@withLock RemoteMutationResult.STALE
        val canonical = button.canonical()
        val confirmed = current.configuration.confirmButton(canonical)
        val next = if (canonical in RemoteCatalog.standardButtons || confirmed.extras.any { it.button.canonical() == canonical }) confirmed
            else confirmed.copy(extras = confirmed.extras + RemoteExtraButton(canonical, label.trim()))
        write(next, rememberUndo = true)
    }

    suspend fun renameButton(button: RemoteButton, label: String): RemoteMutationResult = mutex.withLock {
        val current = mutableState.value
        if (current.loading) return@withLock RemoteMutationResult.STALE
        write(current.configuration.copy(extras = current.configuration.extras.map {
            if (it.button.canonical() == button.canonical()) it.copy(label = label.trim()) else it
        }), rememberUndo = true)
    }

    suspend fun restoreButton(screen: RemoteScreen, button: RemoteButton): RemoteMutationResult = mutex.withLock {
        val current = mutableState.value
        if (current.loading) return@withLock RemoteMutationResult.STALE
        write(current.configuration.copy(bindings = current.configuration.bindings.filterNot {
            it.screen == screen && it.button.canonical() == button.canonical()
        }), rememberUndo = true)
    }

    suspend fun undo(): RemoteMutationResult = mutex.withLock {
        val previous = undoConfiguration ?: return@withLock RemoteMutationResult.UNCHANGED
        val result = write(previous, rememberUndo = false)
        if (result == RemoteMutationResult.SAVED || result == RemoteMutationResult.UNCHANGED) {
            undoConfiguration = null
            mutableState.value = mutableState.value.copy(canUndo = false)
        }
        result
    }

    suspend fun reset(): RemoteMutationResult = mutex.withLock {
        if (mutableState.value.loading) return@withLock RemoteMutationResult.STALE
        write(mutableState.value.configuration.copy(bindings = emptyList()), rememberUndo = true)
    }

    /** Called only while holding mutex. Failed writes never publish a successful state. */
    private suspend fun write(requested: RemoteConfiguration, rememberUndo: Boolean): RemoteMutationResult {
        val encoded = try { RemoteControlCodec.encode(requested) }
        catch (_: IllegalArgumentException) { return RemoteMutationResult.INVALID }
        val configuration = RemoteControlCodec.canonicalize(requested)
        val current = mutableState.value
        if (configuration == current.configuration && current.error == null) return RemoteMutationResult.UNCHANGED
        // Once the durable mutation starts, cancellation must not separate it from cached state/Undo.
        return withContext(NonCancellable) { try {
            if (!persistence.compareAndSet(observedRaw, encoded)) {
                val latest = persistence.raw.first()
                observedRaw = latest
                undoConfiguration = null
                val decoded = runCatching { RemoteControlCodec.decode(latest) }
                mutableState.value = RemoteControlState(decoded.getOrDefault(RemoteConfiguration()), loading = false,
                    error = if (decoded.isFailure) RemoteStoreError.LOAD_FAILED else null, revision = current.revision + 1)
                return@withContext RemoteMutationResult.STALE
            }
            observedRaw = encoded
            if (rememberUndo) undoConfiguration = current.configuration.takeUnless { current.error == RemoteStoreError.LOAD_FAILED }
            mutableState.value = RemoteControlState(configuration, loading = false, revision = current.revision + 1,
                canUndo = undoConfiguration != null)
            RemoteMutationResult.SAVED
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            mutableState.value = current.copy(error = RemoteStoreError.SAVE_FAILED)
            RemoteMutationResult.FAILED
        } }
    }
}

internal interface RemoteControlPersistence {
    val raw: Flow<String>
    suspend fun compareAndSet(expected: String, value: String): Boolean
}

object RemoteControls {
    @EntryPoint @InstallIn(SingletonComponent::class)
    interface Entry { fun remoteControlStore(): RemoteControlStore }
    fun store(context: Context): RemoteControlStore = EntryPointAccessors.fromApplication(context.applicationContext, Entry::class.java).remoteControlStore()
}
