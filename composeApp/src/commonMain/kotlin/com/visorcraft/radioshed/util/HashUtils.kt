package com.visorcraft.radioshed.util

/**
 * Computes the MD5 hash of the given [input] and returns it as a lowercase hex string.
 */
expect fun md5Hex(input: String): String

/**
 * Generates a random salt for Subsonic authentication.
 * In v1, this is just a timestamp-based string to ensure uniqueness per call.
 */
expect fun generateSalt(): String
