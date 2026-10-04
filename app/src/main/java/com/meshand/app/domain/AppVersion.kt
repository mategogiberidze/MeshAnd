package com.meshand.app.domain

/** Compares release versions like "0.2.0" or tags like "v0.2.0". Pure, unit-tested. */
object AppVersion {
    /** "v1.2.3-beta" → [1, 2, 3]; null if it isn't a dotted number. */
    fun parse(version: String): List<Int>? {
        val parts = version.trim().removePrefix("v").substringBefore('-').split('.')
        return parts.map { it.toIntOrNull() ?: return null }
    }

    /** True if [candidate] is a higher version than [current]. Unparseable versions are never newer. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parse(candidate) ?: return false
        val b = parse(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
