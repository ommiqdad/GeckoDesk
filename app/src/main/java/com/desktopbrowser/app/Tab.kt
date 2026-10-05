package com.desktopbrowser.app

import android.graphics.Bitmap
import org.mozilla.geckoview.GeckoSession

/**
 * Satu tab. [session] bernilai null bila tab sedang "dibekukan"
 * (state-nya disimpan di disk untuk menghemat RAM dan dipulihkan saat dibuka).
 */
class Tab(val id: Long, var url: String = "", var title: String = "") {
    var session: GeckoSession? = null
    var sessionState: GeckoSession.SessionState? = null
    var thumbnail: Bitmap? = null
    var parentId: Long = -1L
    var lastUsed: Long = 0L

    // GeckoView melaporkan status secara asinkron lewat delegate; disimpan di sini.
    var canGoBack: Boolean = false
    var canGoForward: Boolean = false
    var progress: Int = 0
}
