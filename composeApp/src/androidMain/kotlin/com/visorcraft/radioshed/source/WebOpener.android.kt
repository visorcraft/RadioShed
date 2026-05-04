package com.visorcraft.radioshed.source

import android.content.Intent
import android.net.Uri
import org.koin.core.context.GlobalContext

internal actual fun openWebUrl(url: String): Boolean {
    return runCatching {
        val ctx = GlobalContext.get().get<android.content.Context>()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
        true
    }.getOrDefault(false)
}
