package com.desktopbrowser.app

import android.content.Context
import org.mozilla.geckoview.GeckoSession
import java.io.File

/**
 * Menyimpan state sesi GeckoView sebuah tab (halaman terakhir, history back/forward,
 * posisi scroll, data formulir) sebagai teks JSON di penyimpanan internal aplikasi.
 */
object SessionStore {
    private const val LEGACY_FILE = "session_state.bin"

    fun save(context: Context, state: GeckoSession.SessionState?, name: String) {
        try {
            val text = state?.toString() ?: return
            val tmp = File(context.filesDir, "$name.tmp")
            tmp.writeText(text)
            tmp.renameTo(File(context.filesDir, name))
        } catch (_: Throwable) {
            // best-effort
        }
    }

    fun restore(context: Context, name: String): GeckoSession.SessionState? {
        val file = File(context.filesDir, name)
        if (!file.exists()) return null
        return try {
            GeckoSession.SessionState.fromString(file.readText())
        } catch (_: Throwable) {
            null
        }
    }

    fun delete(context: Context, name: String) {
        File(context.filesDir, name).delete()
    }

    /** Hapus file sesi versi lama (format WebView) yang tidak kompatibel. */
    fun deleteLegacy(context: Context) {
        File(context.filesDir, LEGACY_FILE).delete()
    }

    fun clearAll(context: Context) {
        context.filesDir.listFiles()?.forEach {
            if ((it.name.startsWith("tab_") && it.name.endsWith(".bin")) || it.name == LEGACY_FILE) {
                it.delete()
            }
        }
    }
}
