package com.nuvio.stalker

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.MalformedJsonException
import kotlinx.coroutines.CancellationException
import java.io.EOFException
import java.io.PushbackReader
import java.io.Reader

/** JVM-only streaming reader. Retains at most one programme and never builds a JSON DOM. */
object StalkerGuideReader {
    private class ProtocolError(val error: StalkerError) : RuntimeException()
    private val authMessages = setOf("authorization failed", "authentication failed", "not authorized",
        "unauthorized", "invalid token", "token expired", "access denied")

    /** Does not close the supplied reader; the protected transport owns the response lifecycle. */
    fun read(reader: Reader, onRow: (channelId: String?, row: StalkerGuideRow) -> Unit): StalkerError? {
        val input = PushbackReader(reader, 1)
        try {
            var first = input.read()
            var whitespace = 0
            while (first >= 0 && first.toChar().isWhitespace()) {
                if (++whitespace > 4096) return StalkerError.INVALID_RESPONSE
                first = input.read()
            }
            if (first < 0) return StalkerError.INVALID_RESPONSE
            if (first.toChar() != '{') {
                val prefix = StringBuilder().append(first.toChar())
                repeat(128) {
                    val next = input.read()
                    if (next < 0) return if (isAuth(prefix.toString())) StalkerError.AUTHENTICATION_FAILED else StalkerError.INVALID_RESPONSE
                    prefix.append(next.toChar())
                }
                return StalkerError.INVALID_RESPONSE
            }
            input.unread(first)
            val json = JsonReader(input)
            json.isLenient = false
            var seenJs = false
            json.beginObject()
            while (json.hasNext()) {
                when (json.nextName()) {
                    "js" -> {
                        if (seenJs) fail()
                        seenJs = true
                        container(json, null, 0, onRow)
                    }
                    "error", "message" -> classifyError(scalar(json))
                    else -> json.skipValue()
                }
            }
            json.endObject()
            if (!seenJs || json.peek() != JsonToken.END_DOCUMENT) fail()
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProtocolError) {
            return e.error
        } catch (_: MalformedJsonException) {
            return StalkerError.INVALID_RESPONSE
        } catch (_: EOFException) {
            return StalkerError.INVALID_RESPONSE
        } catch (_: IllegalStateException) {
            return StalkerError.INVALID_RESPONSE
        }
    }

    private fun container(json: JsonReader, channel: String?, depth: Int, onRow: (String?, StalkerGuideRow) -> Unit) {
        if (depth > 16) fail()
        when (json.peek()) {
            JsonToken.BEGIN_ARRAY -> {
                json.beginArray()
                while (json.hasNext()) onRow(channel, row(json))
                json.endArray()
            }
            JsonToken.BEGIN_OBJECT -> {
                json.beginObject()
                var members = 0
                var containers = 0
                while (json.hasNext()) {
                    val key = json.nextName()
                    members++
                    when (key) {
                        "data", "epg", "items" -> { containers++; container(json, channel, depth + 1, onRow) }
                        "error", "message" -> classifyError(scalar(json))
                        "total_items", "max_page_items", "selected_item", "cur_page", "name", "ch_id", "channel_id" -> json.skipValue()
                        else -> {
                            if (channel != null) json.skipValue()
                            else when (json.peek()) {
                                JsonToken.BEGIN_ARRAY, JsonToken.BEGIN_OBJECT -> { containers++; container(json, key, depth + 1, onRow) }
                                else -> fail()
                            }
                        }
                    }
                }
                json.endObject()
                if (members != 0 && containers == 0) fail()
            }
            JsonToken.STRING -> {
                val message = scalar(json)
                if (message != null && isAuth(message)) throw ProtocolError(StalkerError.AUTHENTICATION_FAILED)
                fail()
            }
            else -> fail()
        }
    }

    private fun row(json: JsonReader): StalkerGuideRow {
        if (json.peek() != JsonToken.BEGIN_OBJECT) fail()
        var chId: String? = null; var channelId: String? = null
        var id: String? = null; var programId: String? = null
        var name: String? = null; var title: String? = null
        var startTimestamp: String? = null; var stopTimestamp: String? = null; var endTimestamp: String? = null
        var start: String? = null; var time: String? = null; var stop: String? = null; var timeTo: String? = null
        var descr: String? = null; var description: String? = null; var plot: String? = null; var category: String? = null
        var markArchive: Boolean? = null; var isArchive: Boolean? = null; var archive: Boolean? = null
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "ch_id" -> chId = text(json)
                "channel_id" -> channelId = text(json)
                "id" -> id = text(json)
                "program_id" -> programId = text(json)
                "name" -> name = text(json)
                "title" -> title = text(json)
                "start_timestamp" -> startTimestamp = text(json)
                "stop_timestamp" -> stopTimestamp = text(json)
                "end_timestamp" -> endTimestamp = text(json)
                "start" -> start = text(json)
                "time" -> time = text(json)
                "stop" -> stop = text(json)
                "time_to" -> timeTo = text(json)
                "descr" -> descr = text(json)
                "description" -> description = text(json)
                "plot" -> plot = text(json)
                "category" -> category = text(json)
                "mark_archive" -> markArchive = flag(json)
                "is_archive" -> isArchive = flag(json)
                "archive" -> archive = flag(json)
                else -> json.skipValue()
            }
        }
        json.endObject()
        return StalkerGuideRow(chId ?: channelId, id ?: programId, name ?: title, startTimestamp,
            stopTimestamp ?: endTimestamp, start ?: time, stop ?: timeTo, descr ?: description ?: plot,
            category, markArchive ?: isArchive ?: archive)
    }

    private fun text(json: JsonReader) = scalar(json)?.takeIf { it.isNotBlank() }
    private fun flag(json: JsonReader): Boolean? = when (scalar(json)?.lowercase()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> null
    }

    private fun scalar(json: JsonReader): String? = when (json.peek()) {
        JsonToken.NULL -> { json.nextNull(); null }
        JsonToken.STRING, JsonToken.NUMBER -> json.nextString().also {
            if (it.length > 1024 * 1024) throw ProtocolError(StalkerError.TOO_LARGE)
        }
        JsonToken.BOOLEAN -> json.nextBoolean().toString()
        else -> { json.skipValue(); null }
    }

    private fun classifyError(value: String?) {
        if (value.isNullOrBlank() || value == "0") return
        val normalized = value.trim().trimEnd('.').lowercase()
        when {
            normalized in authMessages -> throw ProtocolError(StalkerError.AUTHENTICATION_FAILED)
            normalized in setOf("unknown action", "unsupported action", "not implemented") -> throw ProtocolError(StalkerError.UNSUPPORTED)
            else -> fail()
        }
    }
    private fun isAuth(value: String) = value.trim().trimEnd('.').lowercase() in authMessages
    private fun fail(): Nothing = throw ProtocolError(StalkerError.INVALID_RESPONSE)
}
