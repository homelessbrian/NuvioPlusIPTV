package com.nuvio.tv.livetv.ondemand

import com.nuvio.tv.domain.model.MetaPreview

internal fun homeMetaChars(meta: MetaPreview): Long =
    meta.id.length.toLong() + meta.rawType.length + meta.name.length + meta.description.orEmpty().length + meta.poster.orEmpty().length +
        meta.background.orEmpty().length + meta.logo.orEmpty().length + meta.rawPosterUrl.orEmpty().length +
    meta.releaseInfo.orEmpty().length + meta.runtime.orEmpty().length + meta.status.orEmpty().length +
    meta.ageRating.orEmpty().length + meta.language.orEmpty().length + meta.released.orEmpty().length +
    meta.country.orEmpty().length + meta.imdbId.orEmpty().length + meta.slug.orEmpty().length + meta.sourceAddonBaseUrl.orEmpty().length +
        meta.landscapePoster.orEmpty().length + meta.genres.sumOf { it.length.toLong() } +
        meta.director.sumOf { it.length.toLong() } + meta.writer.sumOf { it.length.toLong() } +
    meta.trailerYtIds.sumOf { it.length.toLong() } + meta.mdbListRatingOrder.sumOf { it.length.toLong() } +
    meta.links.sumOf { it.name.length.toLong() + it.category.length + it.url.length } +
    meta.trailers.sumOf { it.source.orEmpty().length.toLong() + it.type.orEmpty().length + it.name.orEmpty().length + it.ytId.orEmpty().length + it.lang.orEmpty().length } +
    meta.behaviorHints?.defaultVideoId.orEmpty().length

/** Keep card positions and already shown artwork; rehydrate full hero/details from SQLite. */
internal fun homeCardShell(meta: MetaPreview): MetaPreview = MetaPreview(
    id = meta.id, type = meta.type, rawType = meta.rawType, name = meta.name, poster = meta.poster,
    posterShape = meta.posterShape, background = meta.background, logo = meta.logo, description = null,
    releaseInfo = meta.releaseInfo, imdbRating = meta.imdbRating, genres = emptyList(),
    imdbId = meta.imdbId, landscapePoster = meta.landscapePoster, rawPosterUrl = meta.rawPosterUrl
)

/** A duplicate provider id can appear in different rows with different labels/copy counts. */
internal fun homeItemWithDetails(anchor: VodItem, full: VodItem): VodItem {
    if (anchor === full || (anchor.categoryId == full.categoryId && anchor.copies == full.copies &&
        anchor.name == full.name && anchor.icon == full.icon && anchor.ext == full.ext && anchor.tmdbId == full.tmdbId &&
        anchor.year == full.year && anchor.rating == full.rating && anchor.addedSec == full.addedSec && anchor.genre == full.genre)) return full
    return anchor.copy(stalkerRef = full.stalkerRef, stalkerSeriesVariant = full.stalkerSeriesVariant,
        providerPlot = full.providerPlot, providerDurationSeconds = full.providerDurationSeconds)
}

/** Bounded optional detail data. The focused title may exceed the byte estimate by itself. */
internal class VodHomeCache<V>(
    private val count: Int,
    private val chars: Long,
    private val weight: (V) -> Long = { 0 },
    private val pinned: () -> String? = { null },
    private val removed: (String) -> Unit = {},
    private val lock: Any = Any()
) {
    private val values = LinkedHashMap<String, V>(16, .75f, true)
    private var sizeChars = 0L

    operator fun get(key: String): V? = synchronized(lock) { values[key] }
    fun containsKey(key: String): Boolean = synchronized(lock) { values.containsKey(key) }
    operator fun set(key: String, value: V) {
        synchronized(lock) {
            values.remove(key)?.let { sizeChars -= key.length + weight(it) }
            values[key] = value
            sizeChars += key.length + weight(value)
            rebalanceLocked()
        }
    }
    fun remove(key: String): V? = synchronized(lock) {
        values.remove(key)?.also { sizeChars -= key.length + weight(it); removed(key) }
    }
    fun retain(keys: Set<String>) = synchronized(lock) {
        values.keys.filter { it !in keys && it != pinned() }.forEach { remove(it) }
        rebalanceLocked()
    }
    fun clear() = synchronized(lock) { values.keys.toList().forEach { remove(it) } }
    fun rebalance(): Boolean = synchronized(lock) { rebalanceLocked() }
    private fun rebalanceLocked(): Boolean {
        if (values.size <= count && sizeChars <= chars) return false
        val dropped = ArrayList<String>()
        val keep = pinned()
        val it = values.entries.iterator()
        while ((values.size > count || sizeChars > chars) && it.hasNext()) {
            val entry = it.next()
            if (entry.key == keep) continue
            sizeChars -= entry.key.length + weight(entry.value)
            dropped += entry.key
            it.remove()
        }
        dropped.forEach(removed)
        return dropped.isNotEmpty()
    }
    internal val size: Int get() = synchronized(lock) { values.size }
    internal val retainedChars: Long get() = synchronized(lock) { sizeChars }
}

internal class VodHomeSet(count: Int) {
    private val cache = VodHomeCache<Boolean>(count, Long.MAX_VALUE)
    operator fun contains(key: String) = cache.containsKey(key)
    @Synchronized fun add(key: String): Boolean { val new = key !in this; cache[key] = true; return new }
    operator fun plusAssign(key: String) { add(key) }
    fun remove(key: String): Boolean = cache.remove(key) != null
    fun retain(keys: Set<String>) = cache.retain(keys)
}

/**
 * Prefix positions remain stable for host layouts and See all. Full provider details live in
 * SQLite/a bounded working cache; old cards retain only the fields needed to draw and dedupe.
 * A primitive UID index and one scalar match identity preserve positions and watched marks,
 * without a map of retained full models.
 */
internal class VodHomeItems private constructor(
    private val anchors: List<VodItem>,
    private val full: VodHomeCache<VodItem>,
    private val index: LongArray,
    private val matches: Array<String?>
) : AbstractList<VodItem>() {
    override val size: Int get() = anchors.size
    override fun get(index: Int): VodItem = anchors[index].let { anchor ->
        val item = full[anchor.uid] ?: return@let anchor
        homeItemWithDetails(anchor, item)
    }
    fun position(uid: String): Int {
        val hash = uid.hashCode()
        var lo = 0; var hi = index.size
        val target = hash.toLong() shl 32
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (index[mid] < target) lo = mid + 1 else hi = mid
        }
        while (lo < index.size && (index[lo] shr 32).toInt() == hash) {
            val at = index[lo++].toInt()
            if (anchors[at].uid == uid) return at
        }
        return -1
    }
    fun find(uid: String): VodItem? = position(uid).takeIf { it >= 0 }?.let(::get)
    fun containsUid(uid: String) = position(uid) >= 0
    fun rememberMatch(uid: String, match: String) { val at = position(uid); if (at >= 0) matches[at] = match }
    fun forgetMatch(uid: String) { val at = position(uid); if (at >= 0) matches[at] = null }
    fun matchId(uid: String): String? = position(uid).takeIf { it >= 0 }?.let { matches[it] }
    fun append(items: List<VodItem>): VodHomeItems {
        val next = compact(items, full)
        val incoming = makeIndex(next, anchors.size)
        val joined = LongArray(index.size + incoming.size)
        var a = 0; var b = 0; var out = 0
        while (a < index.size || b < incoming.size) {
            joined[out++] = if (b == incoming.size || (a < index.size && index[a] <= incoming[b])) index[a++] else incoming[b++]
        }
        return VodHomeItems(anchors + next, full, joined, matches.copyOf(anchors.size + next.size))
    }
    companion object {
        fun create(items: List<VodItem>, full: VodHomeCache<VodItem>): VodHomeItems {
            val anchors = compact(items, full)
            return VodHomeItems(anchors, full, makeIndex(anchors), arrayOfNulls(anchors.size))
        }
        private fun compact(items: List<VodItem>, full: VodHomeCache<VodItem>) = items.map { item ->
            full[item.uid] = item
            if (item.providerPlot == null && item.stalkerRef == null && item.stalkerSeriesVariant == null && item.providerDurationSeconds == null) item
            else item.copy(providerPlot = null, stalkerRef = null, stalkerSeriesVariant = null, providerDurationSeconds = null)
        }
        private fun makeIndex(items: List<VodItem>, offset: Int = 0) = LongArray(items.size) { i ->
            (items[i].uid.hashCode().toLong() shl 32) or ((offset + i).toLong() and 0xffffffffL)
        }.also { it.sort() }
    }
}
