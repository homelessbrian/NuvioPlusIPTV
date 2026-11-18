package com.nuvio.tv.livetv.model

/**
 * For the start-up report: playlists whose server address changed when another device's setup
 * was applied (sync), with the old and new address. Only the address (scheme, host and port):
 * never the rest of the link or the login, which can hold the password.
 */
object PlaylistServerChanges {
    fun lines(before: List<PlaylistSource>, after: List<PlaylistSource>): List<String> {
        val old = before.associateBy { it.id }
        return after.mapNotNull { now ->
            val was = old[now.id] ?: return@mapNotNull null
            val a = serverOf(was)
            val b = serverOf(now)
            when {
                a != b -> "Sync: playlist \"${now.name}\" server changed: ${a.ifBlank { "none" }} → ${b.ifBlank { "none" }}"
                linkOf(was) != linkOf(now) -> "Sync: playlist \"${now.name}\" link or login changed (same server ${b.ifBlank { "none" }})"
                else -> null
            }
        }
    }

    private fun linkOf(p: PlaylistSource): String = p.stalkerConfig?.portalUrl ?: if (p.xtreamServer.isNotBlank()) p.xtreamServer + "|" + p.xtreamUsername else p.url

    private fun serverOf(p: PlaylistSource): String =
        addressOf(p.stalkerConfig?.portalUrl ?: p.xtreamServer.ifBlank { p.url })

    /** "http://user:pass@host:8080/get.php?..." -> "http://host:8080". */
    fun addressOf(link: String): String {
        val t = link.trim()
        if (t.isEmpty()) return ""
        val scheme = if ("://" in t) t.substringBefore("://").lowercase() + "://" else ""
        val authority = t.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
        return if (authority.isBlank()) "" else scheme + authority.lowercase()
    }
}
