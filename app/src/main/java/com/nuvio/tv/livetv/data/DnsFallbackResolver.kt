package com.nuvio.tv.livetv.data

import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.util.concurrent.TimeUnit

internal data class DnsFallbackAnswer(val service: DnsChoice, val addresses: List<InetAddress>, val ttlSeconds: Long)

/** One deadline for all DoH fallback requests, including alternate endpoints and IPv6. */
internal class DnsFallbackResolver(
    private val client: OkHttpClient,
    private val endpoints: (DnsChoice) -> List<String>,
    private val budgetMs: Long = 8_000L
) {
    init { require(budgetMs > 0) }

    fun resolve(services: List<DnsChoice>, hostname: String): DnsFallbackAnswer? {
        val choices = services.distinct().filter { it != DnsChoice.SYSTEM }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs)
        for ((serviceIndex, service) in choices.withIndex()) {
            // Reserve time for another permitted service instead of letting the first use
            // the whole budget. A selected custom service still gets the entire budget.
            val serviceDeadline = share(deadline, choices.size - serviceIndex) ?: break
            val urls = endpoints(service)
            for ((endpointIndex, endpoint) in urls.withIndex()) {
                val endpointDeadline = share(serviceDeadline, urls.size - endpointIndex) ?: break
                val v4 = query(endpoint, hostname, 1, endpointDeadline) ?: continue
                val found = if (v4.addresses.isNotEmpty()) v4 else query(endpoint, hostname, 28, endpointDeadline) ?: v4
                if (found.addresses.isNotEmpty()) {
                    return DnsFallbackAnswer(service, found.addresses, found.ttlSeconds.coerceIn(60L, 600L))
                }
                // A real empty answer is not a transport failure. Preserve the existing
                // policy: try the next permitted service (or return to the system resolver).
                break
            }
        }
        return null
    }

    private fun share(deadline: Long, remaining: Int): Long? {
        val now = System.nanoTime()
        val left = deadline - now
        return if (left <= 0L || remaining <= 0 || Thread.currentThread().isInterrupted) null else now + left / remaining
    }

    private data class Answer(val addresses: List<InetAddress>, val ttlSeconds: Long)

    private fun query(endpoint: String, hostname: String, type: Int, deadline: Long): Answer? {
        if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted) return null
        return try {
            val url = endpoint.toHttpUrl().newBuilder().addQueryParameter("name", hostname)
                .addQueryParameter("type", type.toString()).build()
            val call = client.newCall(Request.Builder().url(url).header("Accept", "application/dns-json").build())
            // Call timeout covers connection, headers and a trickling/chunked body together.
            call.timeout().deadlineNanoTime(deadline)
            call.execute().use { response ->
                if (!response.isSuccessful) return null
                val source = response.body?.source() ?: return null
                source.request(MAX_JSON_BYTES + 1)
                if (source.buffer.size > MAX_JSON_BYTES || System.nanoTime() >= deadline) return null
                val json = JSONObject(source.readUtf8())
                val status = json.optInt("Status", -1)
                if (status != 0 && status != 3) return null
                val records = json.optJSONArray("Answer")
                val addresses = ArrayList<InetAddress>()
                var ttl = 600L
                if (records != null) for (i in 0 until records.length()) {
                    val record = records.optJSONObject(i) ?: continue
                    if (record.optInt("type") != type) continue
                    val data = record.optString("data")
                    val literal = when (type) {
                        1 -> data.takeIf { value -> value.isNotEmpty() && value.all { it.isDigit() || it == '.' } }
                        else -> data.takeIf { value -> value.isNotEmpty() && ':' in value && value.all { it.isLetterOrDigit() || it == ':' || it == '.' } }
                    } ?: continue
                    val parsed = runCatching { InetAddress.getByName(literal) }.getOrNull() ?: continue
                    addresses += InetAddress.getByAddress(hostname, parsed.address)
                    ttl = minOf(ttl, record.optLong("TTL", 600L))
                }
                Answer(addresses, ttl)
            }
        } catch (_: Exception) { null }
    }

    private companion object { const val MAX_JSON_BYTES = 64L * 1024 }
}
