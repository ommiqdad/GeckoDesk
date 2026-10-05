package com.desktopbrowser.app

import android.content.Context
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

/**
 * GeckoRuntime hanya boleh dibuat SEKALI per proses, jadi disimpan di sini
 * dan dipakai bersama oleh semua tab (sesi).
 */
object GeckoProvider {

    @Volatile
    private var runtime: GeckoRuntime? = null

    /** Kategori pelacak/iklan yang diblokir saat "Blokir iklan" aktif. */
    fun antiTracking(adBlock: Boolean): Int =
        if (adBlock) {
            ContentBlocking.AntiTracking.AD or
                ContentBlocking.AntiTracking.ANALYTIC or
                ContentBlocking.AntiTracking.SOCIAL
        } else {
            ContentBlocking.AntiTracking.NONE
        }

    fun get(context: Context, adBlock: Boolean): GeckoRuntime {
        runtime?.let { return it }
        synchronized(this) {
            runtime?.let { return it }
            val contentBlocking = ContentBlocking.Settings.Builder()
                .antiTracking(antiTracking(adBlock))
                .safeBrowsing(ContentBlocking.SafeBrowsing.DEFAULT)
                .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS)
                .build()

            val settings = GeckoRuntimeSettings.Builder()
                .javaScriptEnabled(true)
                .remoteDebuggingEnabled(false)
                .aboutConfigEnabled(false)
                .contentBlocking(contentBlocking)
                .build()

            return GeckoRuntime.create(context.applicationContext, settings).also { runtime = it }
        }
    }
}
