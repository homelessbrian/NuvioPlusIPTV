package com.nuvio.tv.livetv.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/** Cache projections are not new listings. Legacy guides still use their actual model map. */
internal fun <T : Any> Flow<T>.guideContentChanges(storedRevisions: Flow<Long?>): Flow<Any> =
    combine(this, storedRevisions) { model, revision -> revision ?: model }.distinctUntilChanged()

/** Coalesce staged guide files until publication; archive/remap writes outside a build stay immediate. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal fun Flow<Long>.storedGuideRevisions(
    storedMode: Flow<Boolean>, building: StateFlow<Boolean>, revision: () -> Long
): Flow<Long?> = combine(this, storedMode) { _, stored -> stored }.transformLatest { stored ->
    if (stored) { building.first { !it }; emit(revision()) } else emit(null)
}
