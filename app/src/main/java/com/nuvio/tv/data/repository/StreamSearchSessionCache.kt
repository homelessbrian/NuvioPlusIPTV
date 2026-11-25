package com.nuvio.tv.data.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class StreamSearchRequestKey(
    val profileId: Int,
    val type: String,
    val videoId: String,
    val season: Int?,
    val episode: Int?,
    val sourceConfiguration: String,
    val searchMode: String = "",
    val priorityBingeGroup: String = ""
)

/** Retry only failed source tasks; identities are request-local positions, never provider URLs. */
internal class StreamSearchAttempt(
    private val retryTargets: Set<String>?,
    private val failed: (String?) -> Unit
) {
    fun includes(target: String): Boolean = retryTargets == null || target in retryTargets
    operator fun invoke(target: String? = null) = failed(target)
}

/**
 * Keeps a small set of source searches alive independently of UI collectors.
 * Completed results are memory-only and short-lived because addon URLs may expire.
 */
internal class StreamSearchSessionCache(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val completedTtlMs: Long = DEFAULT_COMPLETED_TTL_MS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val networkGeneration: () -> Long = { 0L }
) {
    private data class Snapshot(
        val result: NetworkResult<List<AddonStreams>>,
        val version: Long,
        val isComplete: Boolean
    )

    private class Session(
        val state: MutableStateFlow<Snapshot>,
        val networkGeneration: Long,
        val retainedCompletedAtMs: Long?
    ) {
        lateinit var job: Job
        @Volatile var invalidated: Boolean = false
        var completedAtMs: Long? = null
        var lastSuccessfulResult: NetworkResult.Success<List<AddonStreams>>? = null
        @Volatile var requestFailed: Boolean = false
        val failedTargets: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        @Volatile var retryAll: Boolean = false
    }

    private val mutex = Mutex()
    private val sessions = LinkedHashMap<StreamSearchRequestKey, Session>(16, 0.75f, true)

    fun observe(
        key: StreamSearchRequestKey,
        forceRefresh: Boolean,
        producer: (StreamSearchAttempt) -> Flow<NetworkResult<List<AddonStreams>>>
    ): Flow<NetworkResult<List<AddonStreams>>> = flow {
        val session = acquireSession(key, forceRefresh, producer)
        var emittedVersion = -1L
        emitAll(
            session.state.transformWhile { snapshot ->
                if (snapshot.version != emittedVersion) {
                    emittedVersion = snapshot.version
                    emit(snapshot.result)
                }
                !snapshot.isComplete
            }
        )
    }

    /**
     * Streams already held for this episode, without starting a search.
     * The addon, plugin, and debrid setup must match. A different search mode for the
     * same preferred plugin is still visible. When the caller names a plugin priority,
     * another plugin's cache is not returned. When the caller does not, a single
     * cached priority is used; several competing priorities are left unresolved.
     */
    suspend fun peek(key: StreamSearchRequestKey): List<AddonStreams>? = mutex.withLock {
        removeExpiredLocked()
        val matches = sessions.filterKeys { it.matchesCachedRequest(key) }
        if (matches.isEmpty()) return@withLock null
        val samePriority = matches.filterKeys { it.priorityBingeGroup == key.priorityBingeGroup }
        val candidates = when {
            samePriority.isNotEmpty() -> samePriority
            key.priorityBingeGroup.isNotEmpty() -> return@withLock null
            matches.keys.map { it.priorityBingeGroup }.distinct().size == 1 -> matches
            else -> return@withLock null
        }
        val ordered = candidates.entries.sortedWith(
            compareByDescending<Map.Entry<StreamSearchRequestKey, Session>> { it.key == key }
                .thenByDescending { it.key.searchMode.isEmpty() }
        )
        ordered.firstNotNullOfOrNull { snapshotData(it.value)?.takeIf { streams -> streams.isNotEmpty() } }
            ?: ordered.firstNotNullOfOrNull { snapshotData(it.value) }
    }

    suspend fun needsRecovery(key: StreamSearchRequestKey): Boolean = mutex.withLock {
        // An active failed picker may outlive the cache TTL. Let its next recovery start a fresh
        // search, then discard expired URLs/failure markers just as for a closed picker.
        val needed = sessions[key]?.needsRecovery() == true
        removeExpiredLocked()
        needed
    }

    private fun Session.needsRecovery(): Boolean = state.value.isComplete && requestFailed &&
        networkGeneration < this@StreamSearchSessionCache.networkGeneration()

    private fun mergeRetained(previous: List<AddonStreams>, current: List<AddonStreams>): List<AddonStreams> =
        LinkedHashMap<String, AddonStreams>().apply {
            previous.forEach { put(it.addonName, it) }
            current.forEach { incoming ->
                val existing = get(incoming.addonName)
                put(incoming.addonName, if (existing == null) incoming else existing.copy(
                    streams = LinkedHashMap<String, Stream>().apply {
                        existing.streams.forEach { put(it.sourceDedupKey(), it) }
                        incoming.streams.forEach { put(it.sourceDedupKey(), it) }
                    }.values.toList()
                ))
            }
        }.values.toList()

    /** Matches the aggregate producer's stream identity when configured sources share a name. */
    private fun Stream.sourceDedupKey(): String {
        val base = infoHash?.lowercase()?.let { hash -> "$hash:${fileIdx ?: ""}" }
            ?: clientResolve?.infoHash?.lowercase()?.let { hash -> "$hash:${clientResolve.fileIdx}" }
            ?: url ?: externalUrl ?: ytId ?: serverTarget?.key() ?: "${addonName}:${name}:${title}"
        val nameSuffix = if (base == url) name?.takeIf { it.isNotBlank() }?.let { "|$it" }.orEmpty() else ""
        return "$base$nameSuffix"
    }

    private suspend fun acquireSession(
        key: StreamSearchRequestKey,
        forceRefresh: Boolean,
        producer: (StreamSearchAttempt) -> Flow<NetworkResult<List<AddonStreams>>>
    ): Session {
        var created: Session? = null
        val selected = mutex.withLock {
            removeExpiredLocked()
            removeObsoleteSessionsLocked(key)

            var retained: NetworkResult.Success<List<AddonStreams>>? = null
            var retryTargets: Set<String>? = null
            var retainedCompletedAtMs: Long? = null
            if (forceRefresh) {
                sessions.remove(key)?.cancelAndComplete()
            } else {
                sessions[key]?.let { existing ->
                    val failedWithoutResults = existing.state.value.isComplete && existing.requestFailed &&
                        existing.lastSuccessfulResult == null
                    if (!failedWithoutResults && !existing.needsRecovery()) return@withLock existing
                    retained = existing.lastSuccessfulResult
                    if (retained != null) retainedCompletedAtMs = existing.completedAtMs
                    if (!existing.retryAll) retryTargets = existing.failedTargets.toSet()
                    sessions.remove(key)?.cancelAndComplete()
                }
            }

            createSession(key, producer, retained, retryTargets, retainedCompletedAtMs).also { session ->
                sessions[key] = session
                trimToSizeLocked()
                created = session
            }
        }
        withContext(NonCancellable) {
            created?.job?.start()
        }
        return selected
    }

    private fun createSession(
        key: StreamSearchRequestKey,
        producer: (StreamSearchAttempt) -> Flow<NetworkResult<List<AddonStreams>>>,
        retained: NetworkResult.Success<List<AddonStreams>>?,
        retryTargets: Set<String>?,
        retainedCompletedAtMs: Long?
    ): Session {
        val session = Session(
            MutableStateFlow(
                Snapshot(
                    result = retained ?: NetworkResult.Loading,
                    version = 0L,
                    isComplete = false
                )
            ),
            networkGeneration = networkGeneration(),
            retainedCompletedAtMs = retainedCompletedAtMs
        )
        session.lastSuccessfulResult = retained
        session.job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val attempt = StreamSearchAttempt(retryTargets) { target ->
                    session.requestFailed = true
                    if (target == null) session.retryAll = true else session.failedTargets.add(target)
                }
                producer(attempt).collect { incoming ->
                    val result = if (retained != null && incoming is NetworkResult.Success) {
                        NetworkResult.Success(mergeRetained(retained.data, incoming.data))
                    } else incoming
                    if (result is NetworkResult.Success && result.data.isNotEmpty()) {
                        session.lastSuccessfulResult = result
                    }
                    session.state.update { current ->
                        Snapshot(
                            result = result,
                            version = current.version + 1,
                            isComplete = false
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                session.state.update { current ->
                    Snapshot(
                        result = NetworkResult.Error(error.message ?: "Failed to fetch streams"),
                        version = current.version + 1,
                        isComplete = false
                    )
                }
            } finally {
                completeSession(key, session)
            }
        }
        return session
    }

    private suspend fun completeSession(key: StreamSearchRequestKey, session: Session) {
        if (session.invalidated) return
        session.state.update { current ->
            val lastSuccess = session.lastSuccessfulResult
            if (lastSuccess != null && current.result !is NetworkResult.Success) {
                Snapshot(
                    result = lastSuccess,
                    version = current.version + 1,
                    isComplete = true
                )
            } else {
                current.copy(isComplete = true)
            }
        }
        mutex.withLock {
            if (sessions[key] !== session) return@withLock
            if (session.lastSuccessfulResult != null || session.requestFailed) {
                // Retrying a sibling source must not extend the lifetime of retained provider URLs.
                session.completedAtMs = session.retainedCompletedAtMs ?: nowMs()
            } else {
                sessions.remove(key)
            }
        }
    }

    private fun removeObsoleteSessionsLocked(requestedKey: StreamSearchRequestKey) {
        val iterator = sessions.entries.iterator()
        while (iterator.hasNext()) {
            val (existingKey, session) = iterator.next()
            val belongsToAnotherProfile = existingKey.profileId != requestedKey.profileId
            // Search mode and the preferred plugin are different sessions for the same
            // episode, not a new addon setup. Replacing one must not abort the other.
            val sourceConfigurationChanged = existingKey.matchesMediaRequest(requestedKey) &&
                existingKey.sourceConfiguration != requestedKey.sourceConfiguration
            if (belongsToAnotherProfile || sourceConfigurationChanged) {
                iterator.remove()
                session.cancelAndComplete()
            }
        }
    }

    private fun removeExpiredLocked() {
        val now = nowMs()
        val iterator = sessions.entries.iterator()
        while (iterator.hasNext()) {
            val session = iterator.next().value
            val completedAt = session.completedAtMs ?: continue
            if (now - completedAt >= completedTtlMs) {
                iterator.remove()
                session.cancelAndComplete()
            }
        }
    }

    private fun trimToSizeLocked() {
        while (sessions.size > maxEntries) {
            val iterator = sessions.entries.iterator()
            if (!iterator.hasNext()) return
            val session = iterator.next().value
            iterator.remove()
            session.cancelAndComplete()
        }
    }

    private fun Session.cancelAndComplete() {
        invalidated = true
        state.update { current -> current.copy(isComplete = true) }
        job.cancel()
    }

    private fun snapshotData(session: Session): List<AddonStreams>? =
        (session.state.value.result as? NetworkResult.Success)?.data
            ?: session.lastSuccessfulResult?.data

    private fun StreamSearchRequestKey.matchesMediaRequest(other: StreamSearchRequestKey): Boolean =
        profileId == other.profileId &&
            type == other.type &&
            videoId == other.videoId &&
            season == other.season &&
            episode == other.episode

    private fun StreamSearchRequestKey.matchesCachedRequest(other: StreamSearchRequestKey): Boolean =
        matchesMediaRequest(other) && sourceConfiguration == other.sourceConfiguration

    private companion object {
        const val DEFAULT_COMPLETED_TTL_MS = 15 * 60 * 1_000L
        const val DEFAULT_MAX_ENTRIES = 12
    }
}
