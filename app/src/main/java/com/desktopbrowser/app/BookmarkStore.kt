package com.desktopbrowser.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Bookmark, disimpan permanen di bookmarks.json (tidak ikut terhapus oleh "Hapus data penjelajahan"). */
class BookmarkStore(context: Context) {

    data class Entry(val url: String, val title: String)

    private val file = File(context.filesDir, "bookmarks.json")
    private val entries = ArrayList<Entry>()
    private val executor = Executors.newSingleThreadExecutor()

    init {
        load()
    }

    @Synchronized
    fun contains(url: String): Boolean = entries.any { it.url == url }

    @Synchronized
    fun add(url: String, title: String) {
        entries.removeAll { it.url == url }
        entries.add(0, Entry(url, title))
        persist()
    }

    @Synchronized
    fun remove(url: String) {
        entries.removeAll { it.url == url }
        persist()
    }

    @Synchronized
    fun all(): List<Entry> = ArrayList(entries)

    private fun persist() {
        val arr = JSONArray()
        entries.forEach { arr.put(JSONObject().put("u", it.url).put("t", it.title)) }
        val json = arr.toString()
        executor.execute {
            try {
                val tmp = File(file.parentFile, "bookmarks.json.tmp")
                tmp.writeText(json)
                tmp.renameTo(file)
            } catch (_: Throwable) {
            }
        }
    }

    private fun load() {
        try {
            if (!file.exists()) return
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                entries.add(Entry(o.getString("u"), o.optString("t", "")))
            }
        } catch (_: Throwable) {
            entries.clear()
        }
    }
}
