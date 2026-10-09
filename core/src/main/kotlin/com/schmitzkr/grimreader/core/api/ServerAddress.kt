package com.schmitzkr.grimreader.core.api

/** How much to trust a server address the user typed. */
enum class ServerSecurity {
    /** `https`: fine. */
    Secure,

    /** `http` to a local-network-like host: allowed only after an explicit "insecure connection" confirmation. */
    InsecureLocal,

    /** `http` to anything else, or not an http(s) address at all: refused. */
    Refused,
}

/**
 * Classifies a normalized server URL (scheme included). Plain `http` is
 * only ever acceptable on a network the user controls: `localhost`, a
 * literal private/link-local IPv4 address (10/8, 172.16/12, 192.168/16,
 * 169.254/16, 127/8) or a name under `.local`, `.lan`, `.home`,
 * `.internal` or `.home.arpa`.
 */
fun classifyServerUrl(url: String): ServerSecurity {
    val scheme = url.substringBefore("://", "").lowercase()
    if (scheme == "https") return ServerSecurity.Secure
    if (scheme != "http") return ServerSecurity.Refused
    val host = hostOf(url) ?: return ServerSecurity.Refused
    return if (isLocalNetworkHost(host)) ServerSecurity.InsecureLocal else ServerSecurity.Refused
}

fun isLocalNetworkHost(host: String): Boolean {
    val h = host.trim().trimEnd('.').lowercase()
    if (h.isEmpty()) return false
    if (h == "localhost") return true
    if (LOCAL_SUFFIXES.any { h.endsWith(it) }) return true
    val octets = h.split('.')
    if (octets.size != 4) return false
    val n = octets.map { part ->
        if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return false
        part.toInt().takeIf { it in 0..255 } ?: return false
    }
    return n[0] == 10 || n[0] == 127 ||
        (n[0] == 172 && n[1] in 16..31) ||
        (n[0] == 192 && n[1] == 168) ||
        (n[0] == 169 && n[1] == 254)
}

private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home", ".internal", ".home.arpa")

/** The host of an http(s) URL, without userinfo, port or path; null when there is none. */
private fun hostOf(url: String): String? {
    val authority = url.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#')
    if (authority.contains('@')) return null // userinfo hides the real host from a casual read
    val host = if (authority.startsWith("[")) return null else authority.substringBefore(':')
    return host.takeIf { it.isNotEmpty() }
}
