# 0001 — iOS build host strategy

- **Status:** accepted
- **Date:** 2026-05-03
- **Deciders:** Project owner (sole developer)
- **Tags:** build, ios, infrastructure, cost

## Context

RadioShed is a private Kotlin Multiplatform / Compose Multiplatform
project developed primarily on Windows. The Android side builds and
tests fully on Windows; the iOS side genuinely requires macOS (Xcode,
the iOS simulator, the Swift compiler, code-signing chain, App Store
submission). This is a hard Apple constraint — there is no supported
path to produce a signed iOS IPA without macOS.

Until iOS development becomes frequent we still need an answer for:
- Occasional verification builds during Android-only work
- ADR / SPEC items in Phase 1 that gate on "iOS framework builds"
- Eventual TestFlight + App Store submission

The repository is private, which constrains free GitHub Actions
minutes (currently 2,000 Linux-equivalent minutes/month on the free
tier; macOS counts **10× Linux minutes**). A 30-minute macOS build
burns 300 of those minutes — so seven full iOS CI runs in a month
exhaust the free tier.

## Options considered

### Option A — GitHub Actions macOS runners

- Pros: No setup, zero physical infrastructure, `macos-latest` images
  are kept up to date with current Xcode releases, identical CI
  surface to the existing Android jobs.
- Cons: Private-repo macOS minutes are 10× cost; budget vanishes fast
  during heavy iteration. Latency between push and result is in the
  minutes-per-build range. Caching across runs needs explicit setup.

### Option B — Self-hosted Mac runner (Mac mini at home)

- Pros: Zero marginal cost per build; latency is local-network fast;
  build cache persists naturally across runs; same machine can host
  Xcode for interactive work when needed.
- Cons: ~$600 capital outlay (M2 Mac mini); needs to stay powered and
  connected to the network; private-repo runners need a small amount
  of GitHub Actions config; physical maintenance (OS updates, Xcode
  CLI drift) becomes a personal responsibility.

### Option C — Cloud Mac (MacStadium / MacInCloud / AWS EC2 Mac)

- Pros: No physical setup; pay-as-you-go for occasional use; can SSH
  in for interactive Xcode sessions; no upfront cost.
- Cons: Per-hour pricing climbs quickly under continuous use; no
  marginal benefit over Option A's `macos-latest` runner for pure
  CI; AWS EC2 Mac in particular has a 24-hour minimum dedicated
  allocation and higher cost than macOS runners on Actions.

### Option D — Skip iOS entirely until v1.1

- Pros: Zero infrastructure spend during the heaviest Android-only
  iteration. Clear separation between v1 (Android shipped) and v1.1
  (iOS).
- Cons: SPEC §1 and the platform-parity matrix describe iOS as a
  co-equal release target. Decoupling carries the risk of design
  drift — every Android-only feature decision is implicitly an iOS
  decision once the Compose Multiplatform UI is shared. Better to
  keep the iOS build green even if no iOS-specific code is being
  written.

## Decision

**Phased**, currently in **Phase 1 (Option A — GitHub Actions macOS
runners)**:

- The Android build remains primary and runs on every push.
- iOS framework builds run on `macos-latest` GitHub Actions runners
  for **release-tagged builds and weekly verification only**, not
  per-PR. This keeps the iOS build green without burning the
  per-month macOS minute budget.
- Common (KMP) tests run on the Linux runner since they have no
  iOS-specific dependencies.

**Phase 2 trigger:** when iOS-specific code is being written
multiple times a week and per-PR CI feedback becomes valuable, move
to **Option B (self-hosted Mac mini)**. The capital outlay pays for
itself quickly at that cadence, and the latency win matters for
real iOS development.

**Phase 3 (eventual):** TestFlight + App Store submission still
runs on macOS — either the same self-hosted runner or a manual
local-Xcode session for the actual submit step (fastlane optional
later).

## Consequences

- **Positive:** No infrastructure spend in the current Android-heavy
  phase. The iOS CI surface stays valid by running weekly even
  though the iOS code is mostly stubs. Decision deferred until the
  cadence justifies the spend.
- **Negative:** iOS regressions might land on master and not surface
  until the weekly run. Acceptable while iOS code is mostly stubs;
  re-evaluate if more iOS impl lands.
- **Neutral / accepted trade-offs:** Android development on Windows
  remains the primary loop. Swift can be authored on Windows in
  IntelliJ / VS Code without simulator access — fine for stub
  scaffolding and small fixes; not fine for real UIKit / SwiftUI
  work. When iOS picks up, expect a couple of weeks of dedicated
  Mac time before the iOS feature parity catches up.

## Follow-ups

- Re-evaluate at Phase 2 trigger (frequent iOS commits) — write a
  follow-up ADR if Option B is adopted, with the Mac mini's runner
  registration instructions.
- Once Option B lands, consider whether a CocoaPods → SPM migration
  for the shared framework is worth bundling with that work.

## References

- SPEC §2.4 "Building from Windows" — already documents Options A,
  B, C qualitatively. This ADR is the formal commitment.
- SPEC §20 Phase 1: "Configure shared framework export: SPM
  (preferred) or CocoaPods (fallback)" — depends on the macOS host
  to verify either path actually works. Pinned for re-evaluation
  alongside Phase 2.
