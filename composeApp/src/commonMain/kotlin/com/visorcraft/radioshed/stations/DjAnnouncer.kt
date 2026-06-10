package com.visorcraft.radioshed.stations

import com.visorcraft.radioshed.audio.AudioEngine
import com.visorcraft.radioshed.audio.ClipPlayer
import com.visorcraft.radioshed.audio.Playable
import com.visorcraft.radioshed.audio.SleepTimer
import com.visorcraft.radioshed.audio.TtsEngine
import com.visorcraft.radioshed.data.DjCacheRepository
import com.visorcraft.radioshed.data.RadioShedJson
import com.visorcraft.radioshed.data.SettingsStore
import com.visorcraft.radioshed.data.formatClockTime
import com.visorcraft.radioshed.data.localMinutesOfDay
import com.visorcraft.radioshed.data.nowMillis
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Orchestrates radio-style DJ announcements between tracks.
 *
 * Contract: the DJ is purely decorative. EVERY side-effecting path is wrapped
 * in [runCatching] so a TTS failure, a missing voice, a clip-decode error, or
 * a corrupt template blob can NEVER break music playback. The worst outcome of
 * any failure is "no announcement this time".
 *
 * Flow per [announceIfDue]:
 *  1. cheap gates — DJ disabled, or no TTS engine on this platform.
 *  2. [shouldAnnounce] — the pure scheduling decision (frequency, quiet hours).
 *  3. pick a slot + render a template into copy.
 *  4. cache lookup keyed by (rendered text + voice); synthesize on miss.
 *  5. duck music → play clip → restore volume in a `finally`.
 *
 * Counters ([tracksSinceAnnounce], [lastAnnounceAtMs]) are mutated under [mutex]
 * because StationPlayback may launch announceIfDue on its background scope where
 * rapid transitions could otherwise interleave.
 */
class DjAnnouncer(
    private val settings: SettingsStore,
    private val djCache: DjCacheRepository,
    private val tts: TtsEngine,
    private val clipPlayer: ClipPlayer,
    private val audioEngine: AudioEngine,
    private val sleepTimer: SleepTimer,
) {

    private val mutex = Mutex()

    // Serializes the whole duck → play → restore section so two announcements
    // (e.g. from rapid skip-next) can't overlap. Without it, a second clip
    // would capture the already-ducked level as its "prior volume" and restore
    // music to the duck fraction, and two clips would play at once. A separate
    // lock from [mutex] (which only guards the counters) because this one is
    // held for the full clip duration, not just a couple of field reads.
    private val playbackMutex = Mutex()

    // Completed transitions since the last successful announcement. Drives the
    // "every_n" frequency mode.
    private var tracksSinceAnnounce: Int = 0

    // Epoch millis of the last successful announcement, null = never. Drives
    // the "minutes" frequency mode.
    private var lastAnnounceAtMs: Long? = null

    // Last rendered copy, fed to DjTemplate.pickVariant as the avoid value so
    // back-to-back announcements don't pick the identical phrasing.
    private var lastRenderedTemplate: String? = null

    /**
     * Decide whether to announce on this transition, then (if due) render,
     * synthesize-or-cache, duck, play, and restore. Safe to call on every
     * track transition — most calls are cheap no-ops.
     */
    suspend fun announceIfDue(transition: TrackTransition) {
        runCatching { announceIfDueInner(transition) }
    }

    private suspend fun announceIfDueInner(transition: TrackTransition) {
        // 1. Cheap exits. Bumping the per-transition counter happens under the
        //    lock alongside the scheduling read so a concurrent transition
        //    can't lose an increment or double-fire.
        if (!settings.getDjEnabled()) return
        if (!tts.isAvailable()) return
        // Don't talk over a winding-down sleep timer. A user drifting off does
        // not want a DJ voice, and — more concretely — the timer's fade ticker
        // and final pause+restore drive engine volume on their own schedule. If
        // we ducked/restored across the timer's expiry our finally could repaint
        // the volume the timer just reset, leaving the next resume near-silent.
        // Stepping aside entirely keeps the two volume drivers from fighting.
        if (sleepTimer.state.value is SleepTimer.State.Running) return

        val now = nowMillis()
        val due = mutex.withLock {
            tracksSinceAnnounce += 1
            // Single source of truth for "station changed": StationPlayback
            // already computes this by station-id diff, so trust it rather than
            // re-deriving from a name comparison here.
            shouldAnnounce(
                frequency = settings.getDjFrequency(),
                frequencyN = settings.getDjFrequencyN(),
                frequencyMinutes = settings.getDjFrequencyMinutes(),
                tracksSinceAnnounce = tracksSinceAnnounce,
                lastAnnounceAtMs = lastAnnounceAtMs,
                nowMs = now,
                nowMinutesOfDay = localMinutesOfDay(now),
                quietStartMin = settings.getDjQuietStartMin(),
                quietEndMin = settings.getDjQuietEndMin(),
                stationChanged = transition.stationChanged,
            )
        }
        if (!due) return

        // 3. Slot pick. A station switch gets a station ID; otherwise we tease
        //    the up-next track. RECAP / TIME slots stay reachable through
        //    user-authored custom templates (a later UI), so we keep the
        //    automatic selection to these two for now.
        val slot = if (transition.stationChanged) DjSlots.STATION_ID else DjSlots.UP_NEXT

        // 4. Templates: user blob (JSON map of slot -> variants) or defaults.
        val templates = parseTemplates(settings.getDjTemplates())
        val variants = templates[slot]?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_DJ_TEMPLATES[slot]
            ?: return
        // Seed with the wall clock so selection is reproducible within a tick
        // yet varies across announcements; avoid the last phrasing.
        val template = DjTemplate.pickVariant(variants, seed = now, avoid = lastRenderedTemplate)
        val rendered = DjTemplate.render(template, transition.toVars(formatClockTime(now)))
        // Empty render (e.g. every sentence referenced a null variable) is not
        // an announcement — bail without touching counters.
        if (rendered.isBlank()) return

        // 5. Cache. Key the clip by the rendered text + voice so identical copy
        //    re-uses the existing synthesized file. "system" is the only voice
        //    id today (custom DjVoice selection lands with the voice picker).
        val voiceId = "system"
        val contentHash = stableHash("$rendered|$voiceId")
        val existing = djCache.get(contentHash)
        val clipPath = if (existing != null) {
            djCache.touch(contentHash)
            existing.blobPath
        } else {
            val dir = tts.cacheDir() ?: return
            val outPath = "$dir/$contentHash.wav"
            // Synthesis failure aborts silently — no duck, no announcement.
            val ok = tts.synthesizeToFile(rendered, voice = null, outPath = outPath)
            if (!ok) return
            djCache.put(
                cacheKey = contentHash,
                blobPath = outPath,
                templateHash = stableHash(template),
                voiceId = voiceId,
                variablesJson = encodeVars(transition),
                durationMs = 0L,
            )
            outPath
        }

        // 6. Duck → play → restore. Capture the level we ducked FROM so the
        //    restore returns to whatever the rest of the pipeline (ReplayGain,
        //    user slider) had set, not a hardcoded 1.0. The whole duck →
        //    play → restore runs under [playbackMutex] so a concurrent
        //    announcement can't capture the ducked level as its "prior" and
        //    restore music to the duck fraction (and so two clips don't
        //    overlap). The prior volume is captured INSIDE the lock for the
        //    same reason — outside, a racing announcement could have ducked
        //    already.
        playbackMutex.withLock {
            val priorVolume = audioEngine.volume.value
            val duck = settings.getDjDuckFraction()
            try {
                audioEngine.setVolume(duck)
                // We reached the duck, so this counts as an announcement: reset
                // the counters before playback in case the caller cancels
                // mid-clip.
                mutex.withLock {
                    tracksSinceAnnounce = 0
                    lastAnnounceAtMs = now
                    lastRenderedTemplate = template
                }
                clipPlayer.playFile(clipPath)
            } finally {
                // Defensive restore: only return to priorVolume if the engine is
                // still sitting at the duck level WE set. If the sleep timer's
                // fade ticker or its expiry pause+restore repainted the volume
                // mid-clip (the Running guard above closes the common case, but
                // the timer can be armed while a clip is already in flight), the
                // value we'd "restore" is stale — blindly writing priorVolume
                // would clobber the timer's own baseline. Leaving it alone lets
                // whoever last touched the volume own it.
                if (kotlin.math.abs(audioEngine.volume.value - duck) <= VOLUME_EPSILON) {
                    audioEngine.setVolume(priorVolume)
                }
            }
        }
    }

    private fun parseTemplates(raw: String): Map<String, List<String>> {
        if (raw.isBlank()) return DEFAULT_DJ_TEMPLATES
        return runCatching {
            RadioShedJson.decodeFromString(
                MapSerializer(String.serializer(), ListSerializer(String.serializer())),
                raw,
            )
        }.getOrDefault(DEFAULT_DJ_TEMPLATES)
    }

    private fun encodeVars(transition: TrackTransition): String = runCatching {
        RadioShedJson.encodeToString(
            MapSerializer(String.serializer(), String.serializer()),
            buildMap {
                transition.stationName?.let { put("station", it) }
                transition.startingTrack.title.let { put("track", it) }
                transition.startingTrack.artist?.let { put("artist", it) }
                transition.nextTrack?.title?.let { put("next_track", it) }
            },
        )
    }.getOrDefault("{}")

    companion object {

        /**
         * Tolerance for "is the engine still at the level we ducked to?" in the
         * finally restore. setVolume round-trips through platform mixers that may
         * quantise, so an exact compare is unreliable; this is well below any
         * audible step yet comfortably above float rounding.
         */
        private const val VOLUME_EPSILON = 0.001f

        /**
         * Pure, deterministic scheduling decision — no clock reads, no DI, all
         * inputs explicit so it is trivially unit-testable.
         *
         * @param frequency           "off" | "every" | "every_n" | "minutes" | "station_change".
         * @param frequencyN          N for "every_n" — announce every Nth transition.
         * @param frequencyMinutes    Minutes for "minutes" mode.
         * @param tracksSinceAnnounce Completed transitions since the last announcement (>= 1 on a fresh transition).
         * @param lastAnnounceAtMs    Epoch millis of the last announcement, or null if never.
         * @param nowMs               Current epoch millis.
         * @param nowMinutesOfDay     Current local wall-clock minute-of-day (0..1439).
         * @param quietStartMin       Quiet-window start (minute-of-day); window may cross midnight.
         * @param quietEndMin         Quiet-window end (minute-of-day, exclusive).
         * @param stationChanged      Whether this transition is also a station switch.
         */
        fun shouldAnnounce(
            frequency: String,
            frequencyN: Int,
            frequencyMinutes: Int,
            tracksSinceAnnounce: Int,
            lastAnnounceAtMs: Long?,
            nowMs: Long,
            nowMinutesOfDay: Int,
            quietStartMin: Int,
            quietEndMin: Int,
            stationChanged: Boolean,
        ): Boolean {
            if (frequency == "off") return false
            // Quiet hours win over everything except "off": no announcement
            // inside the window regardless of the frequency rule.
            if (inQuietHours(nowMinutesOfDay, quietStartMin, quietEndMin)) return false
            return when (frequency) {
                "every" -> true
                "every_n" -> tracksSinceAnnounce >= frequencyN.coerceAtLeast(1)
                "minutes" -> {
                    val last = lastAnnounceAtMs ?: return true // never announced → due now
                    (nowMs - last) >= frequencyMinutes.coerceAtLeast(1) * 60_000L
                }
                "station_change" -> stationChanged
                else -> false
            }
        }

        /**
         * True when [minuteOfDay] falls inside the quiet window. An empty
         * window (start == end) suppresses nothing. A window where start > end
         * wraps across midnight (e.g. 22:00 → 07:00).
         */
        private fun inQuietHours(minuteOfDay: Int, startMin: Int, endMin: Int): Boolean {
            if (startMin == endMin) return false
            return if (startMin < endMin) {
                minuteOfDay in startMin until endMin
            } else {
                // Crosses midnight: inside if at/after start OR before end.
                minuteOfDay >= startMin || minuteOfDay < endMin
            }
        }

        /**
         * FNV-1a 64-bit hash rendered as hex — a stable, platform-independent
         * cache key (kotlin's `String.hashCode()` is not contractually stable
         * across runtimes, and there's no shared MessageDigest in commonMain).
         */
        private fun stableHash(input: String): String {
            var hash = -0x340d631b7bdddcdbL // FNV offset basis (14695981039346656037)
            val prime = 0x100000001b3L
            for (char in input) {
                hash = hash xor char.code.toLong()
                hash *= prime
            }
            return hash.toULong().toString(16)
        }
    }
}

/** What just happened in the queue — everything a template can mention. */
data class TrackTransition(
    val stationName: String?,
    val endedTrack: Playable?,
    val startingTrack: Playable,
    val nextTrack: Playable?,
    val stationChanged: Boolean,
)

/** Maps the transition into the template variable bag. [time] is the
 *  already-formatted wall-clock string for the `{time}` token. */
private fun TrackTransition.toVars(time: String): DjTemplateVars = DjTemplateVars(
    station = stationName,
    track = startingTrack.title,
    artist = startingTrack.artist,
    nextTrack = nextTrack?.title,
    time = time,
    weather = null, // Open-Meteo weather variable lands with the weather client (SPEC §10).
)
