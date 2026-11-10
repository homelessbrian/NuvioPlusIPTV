package com.nuvio.tv.livetv.multiview

import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Coordinates are fractions of the available video area; IDs identify the current visible slots. */
data class MultiviewPaneBounds(
    val id: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

enum class MultiviewDirection { LEFT, RIGHT, UP, DOWN }

fun multiviewPaneBounds(layout: MultiviewLayout): List<MultiviewPaneBounds> = when (layout) {
    MultiviewLayout.ONE -> listOf(MultiviewPaneBounds(0, 0f, 0f, 1f, 1f))
    MultiviewLayout.TWO -> listOf(
        MultiviewPaneBounds(0, 0f, 0f, .5f, 1f),
        MultiviewPaneBounds(1, .5f, 0f, 1f, 1f)
    )
    MultiviewLayout.THREE -> listOf(
        MultiviewPaneBounds(0, 0f, 0f, .5f, .5f),
        MultiviewPaneBounds(1, .5f, 0f, 1f, .5f),
        MultiviewPaneBounds(2, 0f, .5f, 1f, 1f)
    )
    MultiviewLayout.FOUR -> listOf(
        MultiviewPaneBounds(0, 0f, 0f, .5f, .5f),
        MultiviewPaneBounds(1, .5f, 0f, 1f, .5f),
        MultiviewPaneBounds(2, 0f, .5f, .5f, 1f),
        MultiviewPaneBounds(3, .5f, .5f, 1f, 1f)
    )
}

/** Focus stays on a visible pane when a smaller layout removes the current pane. */
fun multiviewVisiblePane(layout: MultiviewLayout, paneId: Int): Int =
    paneId.coerceIn(0, layout.paneCount - 1)

/** Remote navigation cannot escape the video area or wrap unexpectedly at its edges. */
fun multiviewNextPane(layout: MultiviewLayout, fromPane: Int, direction: MultiviewDirection): Int {
    val pane = multiviewVisiblePane(layout, fromPane)
    return when (layout) {
        MultiviewLayout.ONE -> pane
        MultiviewLayout.TWO -> when (direction) {
            MultiviewDirection.LEFT -> 0
            MultiviewDirection.RIGHT -> 1
            else -> pane
        }
        MultiviewLayout.THREE -> when (direction) {
            MultiviewDirection.LEFT -> if (pane == 1) 0 else pane
            MultiviewDirection.RIGHT -> if (pane == 0) 1 else pane
            MultiviewDirection.UP -> if (pane == 2) 0 else pane
            MultiviewDirection.DOWN -> 2
        }
        MultiviewLayout.FOUR -> when (direction) {
            MultiviewDirection.LEFT -> if (pane % 2 == 1) pane - 1 else pane
            MultiviewDirection.RIGHT -> if (pane % 2 == 0) pane + 1 else pane
            MultiviewDirection.UP -> if (pane >= 2) pane - 2 else pane
            MultiviewDirection.DOWN -> if (pane < 2) pane + 2 else pane
        }
    }
}

/** Picker data deliberately has no stream URL, provider headers, or credentials. */
data class MultiviewChannelOption(
    val key: String,
    val title: String,
    val number: Int,
    val group: String
)

/**
 * The caller supplies only currently allowed channels. A new query/list cancels the previous
 * search, including scans through large playlists that have no matching channel.
 */
suspend fun searchMultiviewChannels(
    options: List<MultiviewChannelOption>,
    query: String,
    limit: Int = 200
): List<MultiviewChannelOption> = withContext(Dispatchers.Default) {
    require(limit >= 0)
    currentCoroutineContext().ensureActive()
    if (limit == 0) return@withContext emptyList()
    val terms = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter(String::isNotEmpty)
    val matches = ArrayList<MultiviewChannelOption>(minOf(options.size, limit))
    for (index in options.indices) {
        if (index % 64 == 0) currentCoroutineContext().ensureActive()
        val channel = options[index]
        val searchable = if (terms.isEmpty()) "" else
            "${channel.title}\n${channel.number}\n${channel.group}".lowercase(Locale.ROOT)
        if (terms.all { searchable.contains(it) }) {
            matches.add(channel)
            if (matches.size == limit) break
        }
    }
    currentCoroutineContext().ensureActive()
    matches
}
