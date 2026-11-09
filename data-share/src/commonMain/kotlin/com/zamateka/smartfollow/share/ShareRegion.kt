package com.zamateka.smartfollow.share

/** Dexcom Share servers. The account's region is the one where the Dexcom account was created. */
enum class ShareRegion(val baseUrl: String, val applicationId: String) {
    US(
        baseUrl = "https://share2.dexcom.com/ShareWebServices/Services",
        applicationId = "d89443d2-327c-4a6f-89e5-496bbb0317db",
    ),
    OUTSIDE_US(
        baseUrl = "https://shareous1.dexcom.com/ShareWebServices/Services",
        applicationId = "d89443d2-327c-4a6f-89e5-496bbb0317db",
    ),
    JAPAN(
        baseUrl = "https://share.dexcom.jp/ShareWebServices/Services",
        applicationId = "d8665ade-9673-4e27-9ff6-92db4ce13d13",
    ),
}
