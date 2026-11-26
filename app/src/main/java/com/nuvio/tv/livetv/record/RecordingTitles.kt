package com.nuvio.tv.livetv.record

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Editing a label never moves the recording, its media, or its series rule. */
internal object RecordingTitles {
    const val MAX_LENGTH = 200

    fun normalized(value: String): String = value.trim().also {
        require(it.isNotEmpty()) { "Enter a recording name" }
        require(it.length <= MAX_LENGTH) { "Use $MAX_LENGTH characters or fewer" }
        require(it.none(Char::isISOControl)) { "Use a single line for the recording name" }
    }

    /** Patch the latest metadata, retaining fields a newer device may have written. */
    @Synchronized
    fun rename(
        expected: LiveTvRecording,
        value: String,
        read: () -> String,
        replace: (String, () -> Unit) -> Unit,
        stillCurrent: () -> Boolean,
    ): String {
        val title = normalized(value)
        check(!expected.isRecording) { "Wait until this recording has finished" }
        val before = read()
        val info = JSONObject(before)
        check(matches(info, expected) && stillCurrent()) { "This recording changed. Close this dialog and try again" }
        if (title == expected.title) return before
        val changed = info.put("title", title).toString()
        replace(changed) {
            check(stillCurrent() && read() == before) { "This recording changed. Close this dialog and try again" }
        }
        return changed
    }

    fun matches(info: JSONObject, expected: LiveTvRecording): Boolean =
        info.optString("id") == expected.id && info.optLong("startedAtMs") == expected.startedAtMs &&
            info.optString("title") == expected.title && info.optString("status") == expected.status.name

    /** Same-directory replacement: a failed write leaves the existing info.json intact. */
    fun replaceLocal(dir: File, text: String, beforeCommit: () -> Unit) {
        val info = File(dir, "info.json")
        check(info.isFile) { "This recording is no longer available" }
        val tmp = File.createTempFile(".info-rename-", ".tmp", dir)
        try {
            FileOutputStream(tmp).use { stream ->
                stream.write(text.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            beforeCommit()
            if (!tmp.renameTo(info)) throw IOException("The recording name could not be saved")
        } finally { tmp.delete() }
    }
}
