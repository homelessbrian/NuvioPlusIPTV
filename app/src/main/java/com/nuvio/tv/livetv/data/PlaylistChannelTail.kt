package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.LiveChannel
import java.util.BitSet

/** Tracks only positions while a cached M3U is streamed into the shared channel list. */
internal class PlaylistChannelTail(
    private val channels: MutableList<LiveChannel>,
    private val usedKeys: MutableSet<String>,
    private val firstNumber: Int,
) {
    private val from = channels.size
    private val unmarked = BitSet()
    var removed: Int = 0
        private set

    fun unmarkedEntry() { unmarked.set(channels.size - from) }

    /** A failed parse must not leave partially read rows or reserved keys behind. */
    fun rollback() {
        for (i in from until channels.size) usedKeys.remove(channels[i].key)
        channels.subList(from, channels.size).clear()
    }

    /**
     * The final whole-playlist ratio decides whether unmarked episode names are strays.
     * Only then repair this playlist's numbers and key suffixes, as if the strays never existed.
     */
    fun removeStrays(nextNumber: Int, episodeName: (String) -> Boolean): Int {
        var candidate = unmarked.nextSetBit(0)
        while (candidate >= 0) {
            if (!episodeName(channels[from + candidate].name)) unmarked.clear(candidate)
            candidate = unmarked.nextSetBit(candidate + 1)
        }
        removed = unmarked.cardinality()
        if (removed == 0) return nextNumber

        for (i in from until channels.size) usedKeys.remove(channels[i].key)
        var counter = firstNumber
        var write = from
        for (read in from until channels.size) {
            if (unmarked[read - from]) continue
            val channel = channels[read]
            val baseKey = "${channel.sourceId}|${channel.tvgId ?: ""}|${channel.name}"
            var key = baseKey
            var suffix = 2
            while (!usedKeys.add(key)) key = "$baseKey#${suffix++}"
            val number = if (channel.autoNumber) counter else channel.number
            counter = maxOf(counter, number) + 1
            channels[write++] = if (key == channel.key && number == channel.number) channel
                else channel.copy(key = key, number = number)
        }
        channels.subList(write, channels.size).clear()
        return counter
    }
}
