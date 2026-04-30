package com.visorcraft.radioshed

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform