package com.visorcraft.radioshed.util

import java.security.MessageDigest

actual fun md5Hex(input: String): String {
    val md = MessageDigest.getInstance("MD5")
    return md.digest(input.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

actual fun generateSalt(): String {
    // Simple timestamp-based salt for v1 implementation.
    return com.visorcraft.radioshed.data.nowMillis().toString()
}
