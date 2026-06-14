package com.visorcraft.radioshed.audio

import com.visorcraft.radioshed.domain.Crossfade
import com.visorcraft.radioshed.domain.CrossfadeCurve

/**
 * Resolved, platform-agnostic crossfade parameters: a concrete duration in
 * milliseconds plus a curve shape. This is the form the audio engines consume
 * — the per-station [Crossfade] rule (Off / Fixed / Smart) is reduced to one of
 * these (or null, meaning "no crossfade") by [resolveCrossfadePlan].
 *
 * Keeping the resolution here (commonMain) rather than in platform code means
 * the Off/Fixed/Smart → duration mapping and the arming-window math are tested
 * once in `commonTest` and behave identically on Android, desktop, and iOS.
 */
data class CrossfadePlan(
    val durationMs: Long,
    val curve: CrossfadeCurve,
    /** When true, [effectiveCrossfadeDurationMs] adjusts duration per transition. */
    val smart: Boolean = false,
)

/**
 * Default crossfade duration (seconds) used when the station rule is
 * [Crossfade.Smart]. Content-aware duration is derived from outgoing/incoming
 * track lengths via [computeSmartFadeDurationMs] at arm time.
 */
const val SMART_CROSSFADE_DEFAULT_SEC = 6

/** Upper bound on a resolved crossfade, mirroring the SPEC's 1–12s fixed range. */
const val CROSSFADE_MAX_SEC = 12

/**
 * Reduce a per-station [Crossfade] rule to a concrete [CrossfadePlan], or null
 * when no crossfade should run ([Crossfade.Off], or a [Crossfade.Fixed] of
 * zero/negative seconds).
 *
 * - [Crossfade.Off] → null (caller falls back to gapless / hard-cut behaviour).
 * - [Crossfade.Fixed] → that many seconds (coerced into 1..[CROSSFADE_MAX_SEC]),
 *   with the rule's curve.
 * - [Crossfade.Smart] → [SMART_CROSSFADE_DEFAULT_SEC] with the equal-power
 *   curve (the musically-neutral default) — see [SMART_CROSSFADE_DEFAULT_SEC].
 */
fun resolveCrossfadePlan(crossfade: Crossfade): CrossfadePlan? =
    when (crossfade) {
        is Crossfade.Off -> null
        is Crossfade.Fixed -> {
            val sec = crossfade.seconds
            if (sec <= 0) {
                null
            } else {
                CrossfadePlan(
                    durationMs = sec.coerceIn(1, CROSSFADE_MAX_SEC) * 1_000L,
                    curve = crossfade.curve,
                )
            }
        }
        is Crossfade.Smart ->
            CrossfadePlan(
                durationMs = SMART_CROSSFADE_DEFAULT_SEC * 1_000L,
                curve = CrossfadeCurve.EqualPower,
                smart = true,
            )
    }

/**
 * Derives a crossfade length from track durations. Shorter tracks get tighter
 * overlaps; longer tracks allow up to [CROSSFADE_MAX_SEC]. Falls back to
 * [SMART_CROSSFADE_DEFAULT_SEC] when either duration is unknown.
 */
fun computeSmartFadeDurationMs(
    outgoingDurationMs: Long,
    incomingDurationMs: Long,
): Long {
    if (outgoingDurationMs <= 0L || incomingDurationMs <= 0L) {
        return SMART_CROSSFADE_DEFAULT_SEC * 1_000L
    }
    val shorterSec = minOf(outgoingDurationMs, incomingDurationMs) / 1_000.0
    val targetSec =
        when {
            shorterSec < 30 -> 2.0
            shorterSec < 120 -> (shorterSec * 0.08).coerceIn(2.0, 6.0)
            else -> (shorterSec * 0.10).coerceIn(3.0, CROSSFADE_MAX_SEC.toDouble())
        }
    return (targetSec * 1_000).toLong()
}

/** Resolves the duration to use for arming or executing a crossfade. */
fun effectiveCrossfadeDurationMs(
    plan: CrossfadePlan,
    outgoingDurationMs: Long,
    incomingDurationMs: Long,
): Long =
    if (plan.smart) {
        computeSmartFadeDurationMs(outgoingDurationMs, incomingDurationMs)
    } else {
        plan.durationMs
    }

/**
 * True when playback has entered the crossfade arming window: the track is the
 * right length to overlap (longer than the fade itself, so we don't fade the
 * whole thing), there's a known duration, and the remaining time has dropped to
 * at or below [durationMs].
 *
 * @param positionMs current playhead position.
 * @param durationMs total track duration; non-positive means "unknown" → never arm.
 * @param crossfadeMs resolved crossfade length; non-positive → never arm.
 */
fun shouldArmCrossfade(
    positionMs: Long,
    durationMs: Long,
    crossfadeMs: Long,
): Boolean {
    if (durationMs <= 0L || crossfadeMs <= 0L) return false
    // Refuse to crossfade a track shorter than (or barely longer than) the fade
    // window — fading the entire body of a 5-second jingle is worse than a cut.
    // Require the track to be at least twice the fade length so each side of the
    // overlap still plays the bulk of its own track unattenuated.
    if (durationMs < crossfadeMs * 2L) return false
    val remaining = durationMs - positionMs
    return remaining in 1L..crossfadeMs
}
