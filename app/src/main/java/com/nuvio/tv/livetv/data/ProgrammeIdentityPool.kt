package com.nuvio.tv.livetv.data

/** A small, read-local table: guide rows share provenance text without retaining it globally. */
internal class ProgrammeIdentityPool {
    private val values = HashMap<String, String>()

    fun share(value: String?): String? {
        if (value == null || value.length > 128) return value
        values[value]?.let { return it }
        if (values.size < 128) values[value] = value
        return value
    }
}
