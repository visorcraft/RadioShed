package com.visorcraft.radioshed.source

import platform.Foundation.NSURL
import platform.UIKit.UIApplication

internal actual fun openWebUrl(url: String): Boolean {
    val nsUrl = NSURL.URLWithString(url) ?: return false
    val app = UIApplication.sharedApplication
    if (!app.canOpenURL(nsUrl)) return false
    app.openURL(nsUrl)
    return true
}
