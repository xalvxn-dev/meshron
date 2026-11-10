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

    @Synchronized
    fun add(core: String, tag: String) {
        val list = load()
        for (i in 0 until list.length()) if (list.getJSONObject(i).optString("k") == core) return
        if (list.length() >= MAX) return
        list.put(JSONObject().put("k", core).put("t", tag))
        val tmp = File(file.path + ".tmp")
        tmp.writeText(list.toString())
        tmp.renameTo(file)
    }

    private companion object {
        const val MAX = 100
    }
}
