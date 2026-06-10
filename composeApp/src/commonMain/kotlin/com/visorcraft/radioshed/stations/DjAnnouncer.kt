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
import com.visorcraft.radioshed.source.WeatherFetcher
import com.visorcraft.radioshed.source.formatWeather
import com.visorcraft.radioshed.util.isInMinuteWindow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Orchestrates radio-style DJ announcements between tracks.
 *
 * Contract: the DJ is purely decorative. EVERY side-effecting path is wrapped
 * in [runCatching] so a TTS failure, a missing voice, a clip-decode error, or
 * a corrupt template blob can NEVER break music playback. The worst outcome of
 * any failure is "no announcement this time". The one deliberate exception:
 * [CancellationException] is rethrown out of every catch (runCatching catches
 * ALL Throwables, cancellation included), because swallowing it would leave a
 * cancelled caller running zombie work instead of stopping.
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
    private val weather: WeatherFetcher,
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

    // Process-lifetime epoch millis of the last stale-cache eviction; 0 = never
    // trimmed this session. Gates [maybeTrimStaleCache] to at most once per
    // [TRIM_INTERVAL_MS]. @Volatile because precacheUpcoming can run on a
    // background scope where two passes could otherwise both read 0 and trim.
    @Volatile private var lastTrimMs: Long = 0L

    /**
     * Decide whether to announce on this transition, then (if due) render,
     * synthesize-or-cache, duck, play, and restore. Safe to call on every
     * track transition — most calls are cheap no-ops.
     */
    suspend fun announceIfDue(transition: TrackTransition) {
        // Rethrow CancellationException: runCatching would otherwise convert
        // the caller's own cancellation into a silent "announcement failed".
        runCatching { announceIfDueInner(transition) }
            .onFailure { if (it is CancellationException) throw it }
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

        // 3. Slot pick. A station switch gets a station ID; otherwise [slotFor]
        //    chooses deterministically among UP_NEXT / RECAP / TIME from the
        //    transition alone, so this pick and the earlier precache pick for
        //    the same transition always agree (the cache key is the rendered
        //    text — a divergent slot would mean a guaranteed cache miss).
        val slot = slotFor(transition)

        // 4 + 5. Templates → variant → render → cache-or-synthesize, all through
        //    the shared helper so the clip is keyed and stored identically to the
        //    precache path. Passes the already-captured announce-time clock so the
        //    {time} token (and thus the cache key) matches whatever was rendered.
        //    A null result (no templates, blank render, or synthesis failure)
        //    aborts silently — no duck, no announcement. The helper hands back the
        //    EXACT variant it picked so we record precisely the line we'll play as
        //    [lastRenderedTemplate] — no second pick to drift from it.
        val templates = parseTemplatesJson(settings.getDjTemplates()) ?: DEFAULT_DJ_TEMPLATES
        val clip = renderAndEnsureCached(transition, slot, templates, formatClockTime(now)) ?: return
        val clipPath = clip.blobPath
        val template = clip.template

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

    /**
     * Pre-synthesize announcement clips for upcoming transitions so a due
     * announcement plays instantly instead of waiting on the TTS engine.
     * Best-effort: failures are swallowed (cancellation excepted — it rethrows);
     * cache hits are no-ops.
     */
    suspend fun precacheUpcoming(transitions: List<TrackTransition>) {
        // Cheap exits mirror the announce path's gates so we don't synthesize
        // clips the user will never hear. NOTE: we deliberately do NOT consult
        // shouldAnnounce / the every-n + minutes counters here — precaching a
        // clip that turns out not to be due is harmless (it just warms the
        // cache), whereas reading the counters would couple precache timing to
        // the announce schedule for no benefit.
        if (!settings.getDjEnabled()) return
        if (!tts.isAvailable()) return
        if (sleepTimer.state.value is SleepTimer.State.Running) return
        val templates = parseTemplatesJson(settings.getDjTemplates()) ?: DEFAULT_DJ_TEMPLATES
        for (transition in transitions) {
            // The TIME slot's copy is dominated by the {time} token, which
            // renders the CURRENT clock. The cache key is the rendered text, so
            // a precached TIME clip can never speak a stale time — the announce
            // render would produce different text and simply miss — but that
            // also means the precache is almost always wasted (the minute rolls
            // over before the announcement) and would strand one orphan blob
            // per pass until the stale trim. Skip it; TIME announcements
            // synthesize live instead.
            val slot = slotFor(transition)
            if (slot == DjSlots.TIME) continue
            // Precache renders against the current wall clock for any {time}
            // token in the remaining slots. A drift between now and announce
            // time only matters for time-bearing templates; a clock-token miss
            // simply re-synthesizes at announce time (still correct, just not
            // pre-warmed), so we accept it. No ducking, no playback, no counter
            // mutation — precache only warms the cache. The rethrow keeps a
            // cancelled precache loop from marching on through the remaining
            // transitions (runCatching catches CancellationException too).
            runCatching {
                renderAndEnsureCached(transition, slot, templates, formatClockTime(nowMillis()))
            }.onFailure { if (it is CancellationException) throw it }
        }
        // Piggy-back stale-cache eviction onto the precache pass: it already
        // runs off the hot path, and gating by [lastTrimMs] keeps it to at most
        // once per [TRIM_INTERVAL_MS] of process lifetime. runCatching so a trim
        // failure can never break precaching (which is itself best-effort);
        // cancellation still propagates.
        runCatching { maybeTrimStaleCache() }
            .onFailure { if (it is CancellationException) throw it }
    }

    /** A playable clip plus the variant that produced it. [renderAndEnsureCached]
     *  returns both so the announce path records exactly the line it plays as the
     *  next avoid value rather than re-picking. */
    private data class RenderedClip(val blobPath: String, val template: String)

    /**
     * Shared render → cache-key → blob-path → synthesize → put pipeline for both
     * the live announce path and [precacheUpcoming]. Picks the SAME deterministic
     * variant (seeded per (startingTrack.trackId, slot), avoiding the last
     * rendered phrasing) and the SAME cache key in both callers, so a
     * pre-synthesized clip is exactly the one the live lookup finds.
     *
     * Fast paths preserved from both callers: a cache HIT touches the row and
     * returns the existing blob path (the announce path's instant replay; the
     * precache no-op). A blank render or any synthesis failure returns null.
     *
     * @param timeText pre-formatted wall-clock string for the `{time}` token —
     *  the announce path passes its already-captured announce-time clock so the
     *  rendered text (and thus the cache key) matches; precache passes the
     *  current clock.
     * @return the cached or freshly synthesized blob path PLUS the variant that
     *  produced it (so the announce path can record exactly what it'll play as
     *  the next avoid value, without a second pick that could drift), or null on
     *  any failure / blank render. Counters, ducking, and playback stay OUTSIDE
     *  this helper — it only produces a playable clip.
     */
    private suspend fun renderAndEnsureCached(
        transition: TrackTransition,
        slot: String,
        templates: Map<String, List<String>>,
        timeText: String,
    ): RenderedClip? {
        val variants = templates[slot]?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_DJ_TEMPLATES[slot]
            ?: return null
        val template = DjTemplate.pickVariant(
            variants,
            seed = variantSeedFor(transition.startingTrack.trackId, slot),
            avoid = lastRenderedTemplate,
        )
        // Only pay the location + network round-trip when the variant we just
        // picked actually uses {weather}; the vast majority don't, so fetching
        // unconditionally would add latency + an API call to every announcement.
        // The variant is chosen BEFORE the fetch (pick → contains-check → fetch
        // → render) and the same check applies on the precache path (which runs
        // ALL transitions through this helper). A null snapshot leaves the token
        // null, and DjTemplate.render drops the weather sentence as before.
        // NOTE: weather-bearing clips have low cache-hit value — the rendered
        // text varies with temperature/condition so the key (over rendered text)
        // differs run to run. Acceptable: weather copy is rare and cheap to
        // re-synthesize.
        val weatherText = if (template.contains(WEATHER_TOKEN)) {
            weather.current()?.let { formatWeather(it) }
        } else {
            null
        }
        val rendered = DjTemplate.render(template, transition.toVars(slot, timeText, weatherText))
        if (rendered.isBlank()) return null
        // Key the clip by the rendered text + voice so identical copy re-uses the
        // existing synthesized file. "system" is the only voice id today (custom
        // DjVoice selection lands with the voice picker).
        val voiceId = "system"
        val contentHash = cacheKeyFor(rendered, voiceId)
        val existing = djCache.get(contentHash)
        if (existing != null) {
            djCache.touch(contentHash)
            return RenderedClip(existing.blobPath, template)
        }
        val dir = tts.cacheDir() ?: return null
        val outPath = "$dir/$contentHash.wav"
        val ok = tts.synthesizeToFile(rendered, voice = null, outPath = outPath)
        if (!ok) return null
        // Benign double-synthesis race: if the announce and precache callers ever
        // overlap on the same key, both synthesize to the SAME path (key → bytes
        // are deterministic) and last put wins — identical content, identical
        // pointer, so the overlap costs a redundant synth but never corrupts.
        djCache.put(
            cacheKey = contentHash,
            blobPath = outPath,
            templateHash = stableHash(template),
            voiceId = voiceId,
            variablesJson = encodeVars(transition),
            durationMs = 0L,
        )
        return RenderedClip(outPath, template)
    }

    /**
     * Evict DJ clips unused for [DJ_CACHE_MAX_AGE_MS], at most once per
     * [TRIM_INTERVAL_MS] of process lifetime (tracked by [lastTrimMs]). Driven
     * off [precacheUpcoming] so it never sits on the announce hot path. The
     * gate is a cheap millis compare, so calling it on every precache pass is
     * fine. Wrapped by its caller in [runCatching] — eviction is pure cleanup
     * and must never break anything.
     */
    private suspend fun maybeTrimStaleCache() {
        val now = nowMillis()
        val last = lastTrimMs
        if (last != 0L && now - last < TRIM_INTERVAL_MS) return
        lastTrimMs = now
        djCache.trimStale(olderThanMs = now - DJ_CACHE_MAX_AGE_MS)
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
         * The literal `{weather}` placeholder. A picked template is checked for
         * this substring before any weather fetch so the location + network
         * round-trip is skipped for the common weather-free announcement.
         */
        private const val WEATHER_TOKEN = "{weather}"

        /**
         * A DJ clip not played for this long is evicted by
         * [maybeTrimStaleCache]. 30 days: long enough that a track the user
         * still rotates through keeps its pre-synthesized line, short enough
         * that copy from a since-deleted station or a one-off skip doesn't
         * accrete blobs on disk forever. Regenerating an evicted clip is cheap
         * (one TTS pass on next play).
         */
        private const val DJ_CACHE_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000 // 30 days

        /**
         * Minimum process-lifetime gap between stale-cache sweeps. 6 hours: the
         * cache only grows by a clip per announcement, so sweeping more often
         * buys nothing, and a long-running session still trims a few times a
         * day. The gate is in-memory (not persisted), so each fresh launch
         * trims once on its first precache pass.
         */
        private const val TRIM_INTERVAL_MS = 6L * 60 * 60 * 1000 // 6 hours

        /**
         * The slot the announce path picks for [transition]: a station switch
         * always gets a station ID; otherwise one of UP_NEXT / RECAP / TIME is
         * chosen deterministically from a stable hash of the starting track's
         * identity. PURE — a function of the transition only, never of the
         * mutable counters or the clock — so the announce and precache paths
         * (which call it independently for the same transition) always agree,
         * and the precached clip is exactly the one the live lookup finds.
         *
         * Weighting: UP_NEXT appears twice in the pool (~2x) because teasing
         * the next track is the bread-and-butter DJ line; RECAP and TIME are
         * the variety sprinkled in. RECAP is only eligible when the ended
         * track AND its artist are known — the default recap copy speaks
         * "{track} by {artist}" of the track that just finished, and with a
         * null artist every default variant would render blank (sentence
         * dropped), turning a due announcement into silence.
         */
        fun slotFor(transition: TrackTransition): String {
            if (transition.stationChanged) return DjSlots.STATION_ID
            val pool = buildList {
                add(DjSlots.UP_NEXT)
                add(DjSlots.UP_NEXT)
                if (transition.endedTrack?.artist != null) add(DjSlots.RECAP)
                add(DjSlots.TIME)
            }
            // FNV-1a of (trackId + uri), not String.hashCode(): hashCode isn't
            // contractually stable across runtimes, and slot choice must be
            // reproducible between the precache and announce processes' calls.
            val hash = stableHash("slot|${transition.startingTrack.trackId}|${transition.startingTrack.uri}")
                .toULong(16)
            // FNV-1a's LOW bits disperse poorly across near-identical inputs
            // (library URIs share long prefixes and suffixes), and a small
            // modulo reads only the low bits — measured over sequential ids,
            // `% 4` hit only {0, 2}, silently making two pool slots
            // unreachable. Fold the well-mixed high half in before reducing.
            val mixed = hash xor (hash shr 32)
            return pool[(mixed % pool.size.toULong()).toInt()]
        }

        /**
         * Stable cache key for a synthesized clip: FNV-1a-64 of (rendered text +
         * voice). Single source of truth shared by the announce and precache
         * paths so a pre-synthesized clip is found by the live lookup.
         */
        fun cacheKeyFor(renderedText: String, voiceId: String): String =
            stableHash("$renderedText|$voiceId")

        /**
         * Deterministic per-(track, slot) seed for [DjTemplate.pickVariant].
         * Seeding by the starting track id (not the wall clock) lets precache
         * and announce agree on ONE variant. Distinct tracks get distinct seeds,
         * so phrasing still varies across the queue; the same track replayed
         * yields the same line (acceptable — and the avoid-repeat argument still
         * suppresses identical back-to-back phrasing).
         */
        fun variantSeedFor(trackId: Long, slot: String): Long =
            // Parse the full FNV-1a-64 hex directly into the seed instead of
            // funnelling it through String.hashCode() (which would collapse 64
            // bits of entropy into 32, raising collisions between distinct
            // tracks). stableHash emits UNSIGNED 64-bit hex, so values above
            // Long.MAX_VALUE would overflow a plain toLong(16); parse as ULong
            // and reinterpret the bit pattern to keep every value representable.
            stableHash("$trackId|$slot").toULong(16).toLong()

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
         * wraps across midnight (e.g. 22:00 → 07:00). Delegates to the shared
         * [isInMinuteWindow] helper, which encodes exactly these semantics.
         */
        private fun inQuietHours(minuteOfDay: Int, startMin: Int, endMin: Int): Boolean =
            minuteOfDay.isInMinuteWindow(startMin, endMin)

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

/** Maps the transition into the template variable bag. [slot] decides which
 *  track `{track}`/`{artist}` refer to: RECAP copy ("That was {track} by
 *  {artist}") speaks the track that just ENDED, every other slot speaks the
 *  track now starting. [time] is the already-formatted wall-clock string for
 *  the `{time}` token; [weather] is the pre-formatted weather string (e.g.
 *  "12 degrees and clear") or null when the picked template doesn't use
 *  {weather} or no snapshot was available. */
private fun TrackTransition.toVars(slot: String, time: String, weather: String?): DjTemplateVars {
    val subject = if (slot == DjSlots.RECAP) endedTrack else startingTrack
    return DjTemplateVars(
        station = stationName,
        track = subject?.title,
        artist = subject?.artist,
        nextTrack = nextTrack?.title,
        time = time,
        weather = weather,
    )
}
