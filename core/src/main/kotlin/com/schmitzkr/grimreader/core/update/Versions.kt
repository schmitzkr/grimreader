package com.schmitzkr.grimreader.core.update

/** `1.2.3`, `v1.2.3` or `1.2.3-debug` → [1, 2, 3]; null when not a version. */
fun parseVersion(text: String): List<Int>? {
    val core = text.trim().removePrefix("v").substringBefore('-').substringBefore('+')
    if (core.isEmpty()) return null
    val parts = core.split('.').map { it.toIntOrNull() ?: return null }
    return if (parts.isEmpty()) null else parts
}

/** Numeric, component-wise; a missing component counts as zero. */
fun isNewerVersion(latest: List<Int>, current: List<Int>): Boolean {
    val n = maxOf(latest.size, current.size)
    for (i in 0 until n) {
        val l = latest.getOrElse(i) { 0 }
        val c = current.getOrElse(i) { 0 }
        if (l != c) return l > c
    }
    return false
}
