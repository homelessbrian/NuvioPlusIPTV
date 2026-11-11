package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.EpgProgram

/**
 * Equality-based UI memos may retain the preceding frame's equal programme objects.
 * Reuse those identities in the next bounded override, whose publication lease then
 * keeps exactly those objects paid. Cache/memo owners remain independent.
 */
internal fun reusePublishedGuideProgramIdentities(
    published: Map<String, List<EpgProgram>>,
    overrides: MutableMap<String, List<EpgProgram>>
) {
    // Only requested display overrides are visited; never scan the catalogue-wide base.
    for (entry in overrides.entries) {
        val previous = published[entry.key] ?: continue
        val next = entry.value
        if (previous === next) continue
        if (previous == next) {
            entry.setValue(previous)
            continue
        }
        if (previous.isEmpty() || next.isEmpty()) continue
        // Full value equality includes row ID, projection flag and every programme field.
        // Retain no cross-publication history or index after this call returns.
        val reusable = HashMap<EpgProgram, EpgProgram>(previous.size)
        for (program in previous) reusable.putIfAbsent(program, program)
        var replacement: ArrayList<EpgProgram>? = null
        for (index in next.indices) {
            val program = next[index]
            val prior = reusable[program] ?: continue
            if (prior !== program) {
                val result = replacement ?: ArrayList(next).also { replacement = it }
                result[index] = prior
            }
        }
        replacement?.let { entry.setValue(it) }
    }
}
