package com.desktopbrowser.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Menyimpan daftar tab (urutan, URL, judul, tab aktif) ke tabs.json. */
object TabStore {
    private const val FILE_NAME = "tabs.json"

    class Snapshot(val tabs: MutableList<Tab>, val activeId: Long, val nextId: Long)

    fun save(context: Context, tabs: List<Tab>, activeId: Long, nextId: Long) {
        try {
            val arr = JSONArray()
            tabs.forEach {
                arr.put(JSONObject().put("id", it.id).put("url", it.url).put("title", it.title))
            }
            val root = JSONObject().put("active", activeId).put("next", nextId).put("tabs", arr)
            val tmp = File(context.filesDir, "$FILE_NAME.tmp")
            tmp.writeText(root.toString())
            tmp.renameTo(File(context.filesDir, FILE_NAME))
        } catch (_: Throwable) {
        }
    }

    fun load(context: Context): Snapshot? {
        return try {
            val f = File(context.filesDir, FILE_NAME)
            if (!f.exists()) return null
            val root = JSONObject(f.readText())
            val arr = root.getJSONArray("tabs")
            val list = ArrayList<Tab>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(Tab(o.getLong("id"), o.optString("url", ""), o.optString("title", "")))
            }
            val maxId = list.maxOfOrNull { it.id } ?: 0L
            Snapshot(list, root.optLong("active", -1L), maxOf(root.optLong("next", 1L), maxId + 1))
        } catch (_: Throwable) {
            null
        }
    }
}
