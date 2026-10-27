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
 * 169.254/16, 127/8), a Tailscale address (100.64/10, fd7a:115c:a1e0::/48
 * or a `.ts.net` name; the tunnel itself is encrypted) or a name under
 * `.local`, `.lan`, `.home`, `.internal` or `.home.arpa`.
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
    if (':' in h) return isTailscaleIpv6(h)
    val octets = h.split('.')
    if (octets.size != 4) return false
    val n = octets.map { part ->
        if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return false
        part.toInt().takeIf { it in 0..255 } ?: return false
    }
    return n[0] == 10 || n[0] == 127 ||
        (n[0] == 172 && n[1] in 16..31) ||
        (n[0] == 192 && n[1] == 168) ||
        (n[0] == 169 && n[1] == 254) ||
        (n[0] == 100 && n[1] in 64..127)
}

/** Whether [h] (an unbracketed IPv6 literal) lies in Tailscale's fd7a:115c:a1e0::/48. */
private fun isTailscaleIpv6(h: String): Boolean {
    if (h.count { it == ':' } < 2 || ":::" in h) return false
    val halves = h.split("::")
    if (halves.size > 2) return false
    fun groups(part: String): List<Int>? {
        if (part.isEmpty()) return emptyList()
        return part.split(':').map { g ->
            if (g.isEmpty() || g.length > 4 || !g.all { it in '0'..'9' || it in 'a'..'f' }) return null
            g.toInt(16)
        }
    }
    val head = groups(halves[0]) ?: return false
    val tail = if (halves.size == 2) groups(halves[1]) ?: return false else emptyList()
    val full = if (halves.size == 2) {
        if (head.size + tail.size > 7) return false
        head + List(8 - head.size - tail.size) { 0 } + tail
    } else {
        if (head.size != 8) return false
        head
    }
    return full[0] == 0xfd7a && full[1] == 0x115c && full[2] == 0xa1e0
}

private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home", ".internal", ".home.arpa", ".ts.net")

/**
 * The host of an http(s) URL, without userinfo, brackets, port or path; null
 * when there is none or the authority is malformed.
 */
fun hostOf(url: String): String? {
    val authority = url.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#')
    if (authority.contains('@')) return null // userinfo hides the real host from a casual read
    if (authority.startsWith("[")) {
        val close = authority.indexOf(']')
        if (close < 0) return null
        val rest = authority.substring(close + 1)
        if (rest.isNotEmpty() && !(rest.startsWith(":") && rest.drop(1).all { it in '0'..'9' })) return null
        return authority.substring(1, close).takeIf { it.isNotEmpty() }
    }
    return authority.substringBefore(':').takeIf { it.isNotEmpty() }
}
