package com.desktopbrowser.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Riwayat halaman yang pernah dibuka, disimpan permanen di history.json. */
class HistoryStore(context: Context) {

    data class Entry(val url: String, val title: String, val time: Long)

    private val file = File(context.filesDir, "history.json")
    private val entries = ArrayList<Entry>()
    private val executor = Executors.newSingleThreadExecutor()

    companion object {
        private const val MAX_ENTRIES = 500
    }

    init {
        load()
    }

    @Synchronized
    fun add(url: String, title: String) {
        entries.removeAll { it.url == url }
        entries.add(0, Entry(url, title, System.currentTimeMillis()))
        while (entries.size > MAX_ENTRIES) entries.removeAt(entries.size - 1)
        persist()
    }

    @Synchronized
    fun all(): List<Entry> = ArrayList(entries)

    @Synchronized
    fun clear() {
        entries.clear()
        persist()
    }

    private fun persist() {
        val arr = JSONArray()
        entries.forEach {
            arr.put(JSONObject().put("u", it.url).put("t", it.title).put("d", it.time))
        }
        val json = arr.toString()
        executor.execute {
            try {
                val tmp = File(file.parentFile, "history.json.tmp")
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
                entries.add(Entry(o.getString("u"), o.optString("t", ""), o.optLong("d", 0L)))
            }
        } catch (_: Throwable) {
            entries.clear()
        }
    }
}
