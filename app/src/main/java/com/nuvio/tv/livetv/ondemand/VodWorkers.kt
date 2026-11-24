package com.nuvio.tv.livetv.ondemand

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/** A fixed number of workers; waiting titles do not each allocate a coroutine. */
internal suspend fun <T> forEachVodWorker(items: List<T>, parallelism: Int, action: suspend (T) -> Unit) = coroutineScope {
    val next = AtomicInteger()
    repeat(minOf(parallelism, items.size)) {
        launch {
            while (true) {
                ensureActive()
                val index = next.getAndIncrement()
                if (index >= items.size) break
                action(items[index])
            }
        }
    }
}
