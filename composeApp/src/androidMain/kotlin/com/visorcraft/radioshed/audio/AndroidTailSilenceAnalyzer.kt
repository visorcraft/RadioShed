package com.visorcraft.radioshed.audio

/**
 * Android: no tail PCM analysis yet — javax.sound.sampled is unavailable on
 * device. Smart Fades fall back to the track-length heuristic until a
 * MediaExtractor-based analyzer lands.
 */
class AndroidTailSilenceAnalyzer : TailSilenceAnalyzer by NullTailSilenceAnalyzer()
