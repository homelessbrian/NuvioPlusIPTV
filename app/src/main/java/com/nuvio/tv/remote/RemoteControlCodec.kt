package com.nuvio.tv.remote

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Bounded device settings. Parsing never invokes a platform key action. */
object RemoteControlCodec {
    const val KEY = "remote_buttons_v1"
    const val MAX_BYTES = 65_536
    const val MAX_EXTRAS = 96
    const val MAX_BINDINGS = 256
    const val MAX_CONFIRMED = 128

    fun validate(raw: String) { decode(raw) }
    fun decodeOrDefault(raw: String): RemoteConfiguration = runCatching { decode(raw) }.getOrDefault(RemoteConfiguration())
    fun canonicalize(configuration: RemoteConfiguration): RemoteConfiguration = RemoteConfiguration(
        configuration.extras.map { it.copy(button = it.button.canonical()) },
        configuration.bindings.map { it.copy(button = it.button.canonical()) },
        configuration.confirmedButtons.map { it.canonical() }
    )
    /** Backups preserve choices, but observing a key on one TV proves nothing about another remote. */
    fun forRestoredDevice(raw: String): String = encode(decode(raw).copy(confirmedButtons = emptyList()))

    fun decode(raw: String): RemoteConfiguration {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Remote configuration is too large" }
        if (raw.isBlank()) return RemoteConfiguration()
        checkDepth(raw)
        try {
            val tokenizer = JSONTokener(raw)
            val value = tokenizer.nextValue() as? JSONObject ?: throw IllegalArgumentException("Invalid remote document")
            require(tokenizer.nextClean() == '\u0000') { "Trailing remote data" }
            require(integer(value, "version") == 1) { "Unsupported remote configuration" }
            val extras = value.getJSONArray("extras")
            val bindings = value.getJSONArray("bindings")
            val confirmed = if (value.has("confirmedButtons")) value.getJSONArray("confirmedButtons") else JSONArray()
            require(extras.length() <= MAX_EXTRAS && bindings.length() <= MAX_BINDINGS) { "Remote configuration has too many entries" }
            require(confirmed.length() <= MAX_CONFIRMED) { "Remote configuration has too many observations" }
            val configuration = RemoteConfiguration(
                extras = (0 until extras.length()).map { index ->
                    val entry = extras.getJSONObject(index)
                    require(!entry.has("label") || entry.get("label") is String) { "Invalid remote label" }
                    RemoteExtraButton(button(entry.getJSONObject("button")), entry.optString("label", ""))
                },
                bindings = (0 until bindings.length()).map { index ->
                    val entry = bindings.getJSONObject(index)
                    RemoteBinding(
                        RemoteScreen.valueOf(entry.getString("screen")), RemotePress.valueOf(entry.getString("press")),
                        button(entry.getJSONObject("button")), RemoteAction.valueOf(entry.getString("action"))
                    )
                },
                confirmedButtons = (0 until confirmed.length()).map { button(confirmed.getJSONObject(it)) }
            )
            validate(configuration)
            return configuration
        } catch (failure: IllegalArgumentException) { throw failure }
        catch (failure: Exception) { throw IllegalArgumentException("Invalid remote configuration", failure) }
    }

    fun encode(configuration: RemoteConfiguration): String {
        validate(configuration)
        val canonical = canonicalize(configuration)
        val value = JSONObject().put("version", 1)
            .put("extras", JSONArray().apply { canonical.extras.forEach { put(JSONObject()
                .put("button", encodeButton(it.button)).put("label", it.label)) } })
            .put("bindings", JSONArray().apply { canonical.bindings.forEach { put(JSONObject()
                .put("screen", it.screen.name).put("press", it.press.name)
                .put("button", encodeButton(it.button)).put("action", it.action.name)) } })
            .put("confirmedButtons", JSONArray().apply { canonical.confirmedButtons.forEach { put(encodeButton(it)) } })
            .toString()
        require(value.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Remote configuration is too large" }
        return value
    }

    fun validate(configuration: RemoteConfiguration) {
        require(configuration.extras.size <= MAX_EXTRAS && configuration.bindings.size <= MAX_BINDINGS)
        require(configuration.confirmedButtons.size <= MAX_CONFIRMED)
        require(configuration.confirmedButtons.map { it.canonical().identity }.distinct().size == configuration.confirmedButtons.size) { "Duplicate remote observation" }
        configuration.confirmedButtons.forEach(::validateButton)
        require(configuration.extras.map { it.button.canonical().identity }.distinct().size == configuration.extras.size) { "Duplicate remote button" }
        configuration.extras.forEach {
            validateButton(it.button)
            require(it.label.length <= 48 && it.label.none { ch -> ch.isISOControl() }) { "Invalid button label" }
        }
        val identities = configuration.bindings.map { "${it.screen}/${it.press}/${it.button.canonical().identity}" }
        require(identities.distinct().size == identities.size) { "Duplicate remote assignment" }
        configuration.bindings.forEach {
            validateButton(it.button)
            require(!RemoteCatalog.isProtected(it.screen, it.button)) { "Protected navigation button" }
            require(it.action in RemoteCatalog.supportedActions(it.screen)) { "Unsupported remote action" }
        }
    }

    private fun validateButton(button: RemoteButton) {
        require(button.keyCode in 0..4096 && !RemoteCatalog.isSystemReserved(button.keyCode)) { "Unsupported remote button" }
        if (button.keyCode == 0) {
            require(button.scanCode in 1..65535 && button.deviceDescriptor.isNotBlank() && button.deviceDescriptor.length <= 256)
            require(button.deviceDescriptor.none { it.isISOControl() })
        } else require(button.scanCode == 0 && button.deviceDescriptor.isEmpty()) { "Noncanonical remote button" }
    }

    private fun button(value: JSONObject): RemoteButton {
        require(!value.has("device") || value.get("device") is String)
        return RemoteButton(integer(value, "keyCode"), if (value.has("scanCode")) integer(value, "scanCode") else 0,
            value.optString("device", "")).also(::validateButton).canonical()
    }

    private fun integer(value: JSONObject, name: String): Int {
        val number = value.get(name)
        require(number is Int || number is Long) { "Invalid remote number" }
        val long = (number as Number).toLong()
        require(long in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
        return long.toInt()
    }

    /** Reject pathological nesting before handing data to the platform JSON parser. */
    private fun checkDepth(raw: String) {
        var depth = 0; var quoted = false; var escaped = false
        raw.forEach { char ->
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 8) { "Remote configuration is too deeply nested" } }
                '}', ']' -> { depth--; require(depth >= 0) { "Invalid remote nesting" } }
            }
        }
        require(depth == 0 && !quoted) { "Invalid remote nesting" }
    }

    private fun encodeButton(button: RemoteButton): JSONObject = JSONObject().put("keyCode", button.keyCode).apply {
        if (button.keyCode == 0) put("scanCode", button.scanCode).put("device", button.deviceDescriptor)
    }
}
