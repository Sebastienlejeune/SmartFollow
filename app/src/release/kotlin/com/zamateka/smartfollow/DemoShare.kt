package com.zamateka.smartfollow

import com.zamateka.smartfollow.settings.ShareAccount
import io.ktor.client.HttpClient

/** Release builds have no demo account: every account goes to Dexcom Share (see the debug version). */
object DemoShare {
    @Suppress("UNUSED_PARAMETER")
    fun httpClientFor(account: ShareAccount): HttpClient? = null
}
