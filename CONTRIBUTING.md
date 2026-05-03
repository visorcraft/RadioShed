# Contributing to RadioShed

> **Private project notice.** RadioShed is a proprietary, single-developer
> project. The repository is private; the source is not for public
> redistribution; and this guide is **internal documentation** rather than
> an open-source community charter. It exists so that the future-self
> (and any small invited collaborator) has a consistent path back into
> the codebase after time away.
>
> See `LICENSE` for the formal terms. This file describes the working
> conventions, not the legal posture.

---

## Working environment

- **Primary OS:** Windows 11 with the Android side of the toolchain
  fully working there. iOS work needs macOS (see
  `docs/decisions/0001-ios-build-host.md`).
- **JDK:** 17 (matches `composeApp/build.gradle.kts` jvmTarget)
- **Android SDK:** `compileSdk` and `targetSdk` are pinned in
  `composeApp/build.gradle.kts`; install via Android Studio's SDK
  Manager. `minSdk = 30`.
- **IDE:** Android Studio is the primary one. IntelliJ IDEA Ultimate
  also works for the KMP module. PhpStorm is the parent project
  directory's IDE of record but the Kotlin tooling lives in the
  same workspace.
- **adb:** the project's `composeApp` install scripts assume
  `C:\android-sdk\platform-tools\adb.exe` on Windows.

## Build & install loop

```bash
# Build a debug APK
./gradlew :composeApp:assembleDebug

# Run unit tests (KMP common tests)
./gradlew :composeApp:testDebugUnitTest

# Install the freshly built debug APK on the connected device
adb install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk
```

iOS builds require macOS. See `docs/decisions/0001-ios-build-host.md`
for current strategy (GitHub Actions macOS runners on release tags;
revisit when iOS work picks up).

## Branching & commits

- Trunk-based on `master`. Short-lived feature branches when working
  in parallel.
- Direct commits to `master` are fine for solo iteration; branch +
  PR when collaborators are involved.
- Commit messages: imperative present tense ("Fix scrubber rounding"
  not "Fixed scrubber rounding"). Subject line ≤ 72 chars; wrap the
  body at 72.
- Reference SPEC items by their phased-plan number (`§20 Phase 7
  ReplayGain pre-amp`) when fixing or shipping a planned item, or
  by task id (`#180 pinned tracks per station`) when picking off an
  audit-list entry.

## Code style

- **Kotlin:** 4-space indent, trailing commas on multi-line
  parameter lists, KDoc on every public function/class that lives
  outside an `internal` module boundary. Prefer named arguments
  when calling a function with three or more parameters.
- **Compose:** screen composables in `ui/<feature>/`, named
  `<Feature>Screen.kt`. Reusable bits live in `ui/util/` or under
  the feature folder. Keep state hoisted — screens take
  callbacks and state, the App.kt root wires them together.
- **DB:** all schema changes go through SQLDelight (`*.sq` files in
  `composeApp/src/commonMain/sqldelight/`). Migrations live in
  `migrations/<n>.sqm`; verifyMigrations is enabled in CI.
- **Settings:** any new persistent flag goes through `SettingsStore`
  with a `KEY_*` constant in the companion object; never reach for
  `multiplatform-settings` directly from a screen.
- **Comments:** explain the *why*, not the *what*. The names should
  cover the *what*. Long-form rationale (race conditions, format
  compatibility, fallbacks) is welcome and encouraged.

## Testing

- Unit tests live in `commonTest`. Add tests for any pure-Kotlin
  domain logic — `SourceRule`, `PlaybackRule`, `CueParser`,
  `SearchDsl`, etc.
- Repository / DB integration tests are still deferred (SPEC §20
  Phase 2). Pure-domain coverage holds the line for now.
- UI tests are not part of the v1 critical path. SPEC §1.1 calls
  for a snapshot-test matrix once the form-factor work picks up.

## Updating SPEC.md

`SPEC.md` is the canonical product roadmap. The phased plan in §20
uses checkboxes. Convention:

- `- [ ]` not started / deferred
- `- [~]` partial — ship what works, list what's still missing
- `- [x]` shipped

When you flip a checkbox, also write a one-line cite of the
implementation: filename, function name, or task id. Stale
checkboxes are worse than missing ones — if you can't quickly
verify state, leave the box unchecked and add a TODO note.

## Pre-commit hooks

Currently none. Lint configs (ktlint, detekt) are still deferred.
Once they land they'll plug into a `pre-commit` config. Until then,
self-review before commit.

## License

`LICENSE` covers the formal terms. Contributors (read: future-self
and any invited collaborator) implicitly agree the contribution is
bound by the proprietary license — no copyright transfer paperwork
beyond commit attribution.
