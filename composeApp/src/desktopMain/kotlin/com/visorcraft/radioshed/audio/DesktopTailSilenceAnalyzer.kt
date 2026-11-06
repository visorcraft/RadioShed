package com.visorcraft.radioshed.audio

/** Desktop uses the shared JVM PCM scanner (WAV/AIFF). */
class DesktopTailSilenceAnalyzer : TailSilenceAnalyzer by JvmTailSilenceAnalyzer()
