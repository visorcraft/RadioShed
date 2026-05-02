package com.visorcraft.radioshed.crash

/** No-op iOS stub. Crash sharing on iOS happens via Xcode/TestFlight. */
class IosCrashLogSharer : CrashLogSharer {
    override suspend fun share(subject: String, body: String): Boolean = false
}
