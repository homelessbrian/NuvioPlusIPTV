package com.nuvio.tv.livetv.ondemand

import com.nuvio.tv.domain.repository.WatchProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adult On Demand titles never stay in Continue watching (Nuvio's row and Continue Watching On
 * Demand), and their saved place isn't synced: takes out any that were saved, including ones
 * saved by earlier versions, whenever they turn up (any profile, any TV).
 */
@Singleton
class OnDemandAdultGuard @Inject constructor(
    private val repository: OnDemandRepository,
    private val watchProgressRepository: WatchProgressRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Titles already checked (uid → adult). Titles not imported yet are checked again later. */
    private val checked = ConcurrentHashMap<String, Boolean>()

    @Volatile private var started = false

    @OptIn(FlowPreview::class)
    fun start() {
        if (started) return
        started = true
        scope.launch {
            watchProgressRepository.allProgress
                .map { list -> list.mapNotNull { p -> VodHomeIds.uidOf(p.contentId)?.let { p.contentId } }.distinct() }
                .distinctUntilChanged()
                .debounce(1_500)
                .collect { ids ->
                    for (contentId in ids) {
                        val uid = VodHomeIds.uidOf(contentId) ?: continue
                        val adult = checked[uid] ?: run {
                            val item = runCatching { repository.itemByUid(uid) }.getOrNull() ?: return@run null
                            runCatching { repository.isAdult(item) }.getOrDefault(false).also { checked[uid] = it }
                        }
                        if (adult == true) runCatching { watchProgressRepository.removeProgress(contentId) }
                    }
                }
        }
    }
}
