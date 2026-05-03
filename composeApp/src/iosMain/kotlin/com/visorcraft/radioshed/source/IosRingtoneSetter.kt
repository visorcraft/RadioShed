package com.visorcraft.radioshed.source

class IosRingtoneSetter : RingtoneSetter {
    override suspend fun setAsRingtone(
        absolutePath: String,
        displayName: String,
    ): RingtoneSetter.Result = RingtoneSetter.Result.NotSupported
}
