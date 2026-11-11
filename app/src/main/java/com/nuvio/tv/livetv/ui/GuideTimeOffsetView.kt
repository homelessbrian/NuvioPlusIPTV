package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.model.EpgProgram

/** Offset views never walk the catalogue or copy its programmes on publication. */
internal class GuideTimeOffsetMemo(
    private val maxLists: Int = 96,
    private val maxProgramsPerList: Int = 4
) {
    private class IdentityKey(val channel: String, val list: List<EpgProgram>) {
        override fun equals(other: Any?) = other is IdentityKey && list === other.list
        override fun hashCode() = System.identityHashCode(list)
    }
    private val lists = LinkedHashMap<IdentityKey, List<EpgProgram>>(16, .75f, true)
    private var shiftMs = 0L
    private var latestSource: Map<String, List<EpgProgram>> = emptyMap()

    @Synchronized fun view(source: Map<String, List<EpgProgram>>, shift: Long): Map<String, List<EpgProgram>> {
        if (shift != shiftMs) { lists.clear(); shiftMs = shift }
        if (source !== latestSource) {
            // Inspect only the <=96 memo entries, never every channel in the catalogue.
            lists.keys.removeAll { source[it.channel] !== it.list }
            latestSource = source
        }
        return if (shift == 0L) source else OffsetMap(source, shift, this)
    }

    @Synchronized private fun shifted(channel: String, source: List<EpgProgram>, shift: Long): List<EpgProgram> {
        if (source.isEmpty()) return source
        // A retired view may still be read by the preceding frame. Never put its offset
        // into the current memo, or interpret its timestamps with the new offset.
        if (shift != shiftMs || latestSource[channel] !== source) return OffsetList(source, shift, maxProgramsPerList)
        val key = IdentityKey(channel, source)
        return lists[key] ?: OffsetList(source, shift, maxProgramsPerList).also {
            lists[key] = it
            while (lists.size > maxLists) lists.remove(lists.keys.first())
        }
    }

    @Synchronized internal fun cachedLists(): Int = lists.size
    @Synchronized internal fun cachedPrograms(): Int = lists.values.sumOf { (it as? OffsetList)?.cachedSize() ?: 0 }
    @Synchronized fun clear() { lists.clear(); latestSource = emptyMap() }

    private class OffsetList(
        private val source: List<EpgProgram>, private val shift: Long, private val capacity: Int
    ) : AbstractList<EpgProgram>() {
        private val moved = LinkedHashMap<Int, EpgProgram>(16, .75f, true)
        override val size get() = source.size
        @Synchronized override fun get(index: Int): EpgProgram = moved[index] ?: source[index].let {
            it.copy(startMs = it.startMs + shift, stopMs = it.stopMs + shift).also { program ->
                moved[index] = program
                while (moved.size > capacity) moved.remove(moved.keys.first())
            }
        }
        @Synchronized fun cachedSize() = moved.size
    }

    private class OffsetMap(
        val source: Map<String, List<EpgProgram>>, val shift: Long, val memo: GuideTimeOffsetMemo
    ) : AbstractMap<String, List<EpgProgram>>(), com.nuvio.tv.livetv.data.GuideBrowsePublicationView {
        override val underlyingGuidePrograms get() = source
        override val size get() = source.size
        override fun containsKey(key: String) = source.containsKey(key)
        override fun get(key: String): List<EpgProgram>? = source[key]?.let { memo.shifted(key, it, shift) }
        override val keys get() = source.keys
        override val entries: Set<Map.Entry<String, List<EpgProgram>>> get() = object : AbstractSet<Map.Entry<String, List<EpgProgram>>>() {
            override val size get() = source.size
            override fun iterator(): Iterator<Map.Entry<String, List<EpgProgram>>> {
                val keys = source.keys.iterator()
                return object : Iterator<Map.Entry<String, List<EpgProgram>>> {
                    override fun hasNext() = keys.hasNext()
                    override fun next(): Map.Entry<String, List<EpgProgram>> {
                        val key = keys.next()
                        return java.util.AbstractMap.SimpleImmutableEntry(key, getValue(key))
                    }
                }
            }
        }
        override fun equals(other: Any?): Boolean = when {
            other === this -> true
            other is OffsetMap && shift == other.shift -> source == other.source
            else -> super.equals(other)
        }
        override fun hashCode() = super.hashCode()
    }
}
