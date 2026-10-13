package com.jmsbrain.mailcal

import java.io.File
import org.json.JSONObject

/** Small settings file. Tools can't reach SharedPreferences, and these reads need to be synchronous. */
class Prefs(private val file: File) {
    private val map: JSONObject = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())

    @Synchronized fun contains(key: String) = map.has(key)
    @Synchronized fun getString(key: String, default: String?): String? = if (map.has(key)) map.getString(key) else default
    @Synchronized fun getLong(key: String, default: Long): Long = map.optLong(key, default)
    @Synchronized fun getBoolean(key: String, default: Boolean): Boolean = map.optBoolean(key, default)

    /** Sets each key, or removes it when the value is null. */
    @Synchronized fun put(vararg values: Pair<String, Any?>) {
        values.forEach { (k, v) -> if (v == null) map.remove(k) else map.put(k, v) }
        save()
    }

    @Synchronized fun clear() {
        map.keys().asSequence().toList().forEach { map.remove(it) }
        save()
    }

    private fun save() {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(map.toString())
        tmp.renameTo(file)
    }
}
