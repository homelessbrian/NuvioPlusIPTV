package com.nuvio.tv.livetv.data

import java.util.Collections

/**
 * One immutable publication: a shared overview generation plus a small private override snapshot.
 * Map structures are copied; programme/list identities remain shared, as with the former shallow
 * HashMap publication. Callers must continue treating the programme lists themselves as immutable.
 * Consumers mapping every entry (such as nonzero guide-offset conversion) still do catalogue-wide
 * work; this removes the full copy from publication itself.
 */
internal class GuideMapSnapshot<K, V>(
    private val base: Base<K, V>,
    overrides: Map<K, V>
) : AbstractMap<K, V>() {
    internal class Base<K, V> private constructor(source: Map<K, V>) {
        internal val snapshot: Map<K, V> = Collections.unmodifiableMap(HashMap(source))

        companion object {
            internal fun <K, V> capture(source: Map<K, V>): Base<K, V> = Base(source)
        }
    }

    // Never retain the repository's mutable windows/schedules map.
    private val overrides = HashMap(overrides)
    override val size: Int = base.snapshot.size + this.overrides.keys.count { !base.snapshot.containsKey(it) }

    override fun get(key: K): V? =
        if (overrides.containsKey(key)) overrides[key] else base.snapshot[key]

    override fun containsKey(key: K): Boolean = overrides.containsKey(key) || base.snapshot.containsKey(key)

    override fun containsValue(value: V): Boolean = overrides.containsValue(value) ||
        base.snapshot.keys.any { !overrides.containsKey(it) && base.snapshot[it] == value }

    /** Iterate keys directly: key/value consumers need no temporary entry object per base row. */
    private fun unionKeys(): Iterator<K> = object : Iterator<K> {
        private val changed = overrides.keys.iterator()
        private val unchanged = base.snapshot.keys.iterator()
        private var pending: K? = null
        private var hasPending = false

        override fun hasNext(): Boolean {
            if (changed.hasNext() || hasPending) return true
            while (unchanged.hasNext()) {
                val candidate = unchanged.next()
                if (!overrides.containsKey(candidate)) {
                    pending = candidate
                    hasPending = true
                    return true
                }
            }
            return false
        }

        override fun next(): K {
            if (changed.hasNext()) return changed.next()
            if (!hasNext()) throw NoSuchElementException()
            @Suppress("UNCHECKED_CAST")
            val result = pending as K // hasPending also distinguishes a legitimate null key.
            pending = null
            hasPending = false
            return result
        }
    }

    override val keys: Set<K> = object : AbstractSet<K>() {
        override val size: Int get() = this@GuideMapSnapshot.size
        override fun contains(element: K): Boolean = this@GuideMapSnapshot.containsKey(element)
        override fun iterator(): Iterator<K> = unionKeys()
    }

    override val values: Collection<V> = object : AbstractCollection<V>() {
        override val size: Int get() = this@GuideMapSnapshot.size
        override fun contains(element: V): Boolean = containsValue(element)
        override fun iterator(): Iterator<V> {
            val keys = unionKeys()
            return object : Iterator<V> {
                override fun hasNext(): Boolean = keys.hasNext()
                override fun next(): V = this@GuideMapSnapshot.getValue(keys.next())
            }
        }
    }

    override val entries: Set<Map.Entry<K, V>> = object : AbstractSet<Map.Entry<K, V>>() {
        override val size: Int get() = this@GuideMapSnapshot.size
        override fun contains(element: Map.Entry<K, V>): Boolean =
            mappingEquals(this@GuideMapSnapshot, element.key, element.value)

        override fun iterator(): Iterator<Map.Entry<K, V>> {
            val keys = unionKeys()
            return object : Iterator<Map.Entry<K, V>> {
                override fun hasNext(): Boolean = keys.hasNext()
                override fun next(): Map.Entry<K, V> {
                    val key = keys.next()
                    return java.util.AbstractMap.SimpleImmutableEntry(key, this@GuideMapSnapshot.getValue(key))
                }
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (other === this) return true
        if (other !is Map<*, *> || other.size != size) return false
        return try {
            if (other is GuideMapSnapshot<*, *> && other.base === base) {
                // StateFlow checks equality at every publication. A shared immutable base cannot
                // differ; compare only either side's overrides, including redundant base values.
                overrides.all { (key, value) -> mappingEquals(other, key, value) } &&
                    other.overrides.all { (key, value) -> overrides.containsKey(key) || mappingEquals(this, key, value) }
            } else {
                overrides.all { (key, value) -> mappingEquals(other, key, value) } &&
                    base.snapshot.keys.all { key -> overrides.containsKey(key) || mappingEquals(other, key, base.snapshot[key]) }
            }
        } catch (_: ClassCastException) {
            false
        } catch (_: NullPointerException) {
            false
        }
    }

    override fun hashCode(): Int {
        var result = 0
        overrides.forEach { (key, value) -> result += entryHash(key, value) }
        base.snapshot.keys.forEach { key ->
            if (!overrides.containsKey(key)) result += entryHash(key, base.snapshot[key])
        }
        return result // Do not cache hashes of shallowly shared values.
    }

    private fun mappingEquals(other: Map<*, *>, key: Any?, value: Any?): Boolean =
        value == other[key] && (value != null || other.containsKey(key))

    private fun entryHash(key: Any?, value: Any?): Int = (key?.hashCode() ?: 0) xor (value?.hashCode() ?: 0)
}
