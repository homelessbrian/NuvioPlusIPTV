package com.nuvio.tv.core.player

/** Sample only a thread currently opening the movie, reading its first bytes or starting video. */
internal class MovieStartupWorkers(private val start: Long, private val now: () -> Long) {
    private class Operation(val phase: MovieStartupStep, val thread: Thread, val began: Long) {
        var sampledAt: Long? = null
        var description: String? = null
    }

    private val active = ArrayList<Operation>(4)

    fun <T> during(phase: MovieStartupStep, work: () -> T): T {
        val operation = synchronized(active) {
            if (active.size >= 4) null else Operation(phase, Thread.currentThread(), now()).also(active::add)
        } ?: return work()
        try {
            return work()
        } finally {
            synchronized(active) { active.remove(operation) }
        }
    }

    // The watchdog calls this. Never hold a player or diagnostic lock while inspecting a thread.
    fun sample(time: Long): String? {
        val pending = synchronized(active) {
            if (active.isEmpty()) return null
            active.filter { time - it.began >= 1_500 }
        }
        if (pending.isEmpty()) return null
        val observations = pending.mapNotNull { operation ->
            val cached = synchronized(operation) {
                operation.description?.takeIf { time - (operation.sampledAt ?: time) < 5_000 }
            }
            val observation = cached ?: describe(operation).also { description ->
                synchronized(operation) {
                    operation.sampledAt = time
                    operation.description = description
                }
            }
            // A read or codec setup may return during collection. Its thread could already be
            // doing unrelated work, so discard that sample.
            observation.takeIf { synchronized(active) { operation in active } }
        }
        return observations.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun describe(operation: Operation): String {
        // Thread names can contain a request URL. Report only recognized code symbols.
        val frames = runCatching { operation.thread.stackTrace }.getOrNull().orEmpty().asSequence()
            .filter { frame -> allowedPackages.any { frame.className.startsWith(it) } }
            .map { frame -> frame.className.substringAfterLast('.') + "." + frame.methodName }
            .filter { symbol -> symbol.length <= 100 && symbol.all { it.isLetterOrDigit() || it in "_$." } }
            .take(3).joinToString(" ← ")
        return "Worker sample: ${operation.phase.label} (entered +${operation.began - start}ms) — " +
            frames.ifEmpty { "stack unavailable" }
    }

    private companion object {
        val allowedPackages = listOf("android.", "androidx.media3.", "java.", "sun.", "libcore.", "okhttp3.", "okio.", "com.nuvio.tv.")
    }
}
