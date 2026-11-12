package com.xalvxnlab.meshron.network

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The mesh logs this phone carries for other people (see app.js, "Mesh"). Each entry is an
 * encrypted log's public key plus an opaque tag; neither says who the log belongs to.
 * Kept in a small file so carried messages survive a restart. At most [MAX] entries.
 */
class CarriedLogs(private val file: File) {

    @Synchronized
    fun load(): JSONArray = runCatching { JSONArray(file.readText()) }.getOrDefault(JSONArray())

    /** [urgentUntil]: someone's urgent message, carried even past [MAX] until then (ms). */
    @Synchronized
    fun add(core: String, tag: String, urgentUntil: Long = 0) {
        val list = load()
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            if (o.optString("k") != core) continue
            if (urgentUntil > o.optLong("u")) {
                o.put("u", urgentUntil)
                save(list)
            }
            return
        }
        if (list.length() >= MAX + URGENT_EXTRA || (list.length() >= MAX && urgentUntil == 0L)) return
        list.put(JSONObject().put("k", core).put("t", tag).put("u", urgentUntil))
        save(list)
    }

    private fun save(list: JSONArray) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(list.toString())
        tmp.renameTo(file)
    }

    private companion object {
        const val MAX = 200
        const val URGENT_EXTRA = 100
    }
}
