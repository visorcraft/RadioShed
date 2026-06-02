# Third-Party License Documentation — Design

**Date:** 2026-06-02
**Status:** Approved (design); pending spec review → implementation plan
**Owner:** VisorCraft LLC

## 1. Problem & Goal

The proprietary `LICENSE` (§5.1) makes a binding promise to users:

> "The full list of Third-Party Components and the texts of their licenses are
> available in the Software's *About → Licenses* screen and in the
> `THIRD_PARTY_LICENSES` file accompanying each release."

Neither exists today. The only in-app surface is the `CreditsCard()` /
`CREDIT_ENTRIES` in `AppSettingsScreen.kt`: a hand-curated, **incomplete** list
of 10 "major" dependencies showing only a name + SPDX identifier, with **no
license texts**, no versions, and several shipped components (notably the
copyleft VLCJ and jaudiotagger) entirely absent. There is **no
`THIRD_PARTY_LICENSES` file** in the repo.

**Goal:** make third-party license documentation full, accurate, and accessible
in-app via an *About → Licenses* screen, and generate the `THIRD_PARTY_LICENSES`
release file — fulfilling LICENSE §5.1.

## 2. Chosen Approach (decisions locked)

- **Hand-curated single source of truth + bundled full license texts.** Keeps
  faith with the codebase's deliberate choice ("this is a UI string list, not a
  build-time generated manifest"); no new Gradle plugin or runtime dependency.
- **Dedicated Licenses screen** (list → detail), not an inline expandable card.
- **Coverage = shipped deps + notable bundled transitive**, split per platform;
  exclude test/tooling-only deps; note system libvlc as external (not bundled).
- **Generate `THIRD_PARTY_LICENSES`** from the same source of truth and wire it
  into packaged artifacts.
- **VLCJ/GPL handling:** document accurately, flag for separate legal/product
  review (see §8). Do **not** attempt to resolve the GPL-vs-proprietary
  compatibility question in this task.

## 3. Single Source of Truth

New package `com.visorcraft.radioshed.licenses` in `commonMain`:

- **`ThirdPartyComponent.kt`** — pure-Kotlin data class:

  ```kotlin
  data class ThirdPartyComponent(
      val name: String,             // "VLCJ"
      val coordinate: String,       // "uk.co.caprica:vlcj"
      val version: String,          // "4.10.1"
      val license: License,         // enum (see below)
      val copyright: String,        // "Copyright 2009-2024 Caprica Software Limited"
      val platforms: Set<Platform>, // ALL, ANDROID, DESKTOP, IOS
      val homepage: String,
      val transitive: Boolean = false,
      val notes: String? = null,    // e.g. "Links system libvlc (LGPL-2.1); not bundled"
  )

  enum class Platform { ALL, ANDROID, DESKTOP, IOS }

  enum class License(val spdx: String, val displayName: String, val textResource: String) {
      APACHE_2_0("Apache-2.0", "Apache License 2.0", "files/licenses/Apache-2.0.txt"),
      MIT("MIT", "MIT License", "files/licenses/MIT.txt"),
      LGPL_2_1("LGPL-2.1-or-later", "GNU LGPL v2.1 or later", "files/licenses/LGPL-2.1.txt"),
      GPL_3_0("GPL-3.0-or-later", "GNU GPL v3.0 or later", "files/licenses/GPL-3.0.txt"),
      BSD_2_CLAUSE("BSD-2-Clause", "BSD 2-Clause License", "files/licenses/BSD-2-Clause.txt"),
      // add BSD_3_CLAUSE / PUBLIC_DOMAIN if the resolved graph requires them
  }
  ```

- **`LicenseCatalog.kt`** — `val THIRD_PARTY_COMPONENTS: List<ThirdPartyComponent>`,
  hand-curated and ordered. A component that is dual-licensed (e.g. JNA) records
  the license we actually rely on and explains the choice in `notes`.

## 4. License Texts (bundled once, referenced many)

Canonical license bodies stored **once each** as Compose resources under
`composeApp/src/commonMain/composeResources/files/licenses/`:
`Apache-2.0.txt`, `MIT.txt`, `LGPL-2.1.txt`, `GPL-3.0.txt`, `BSD-2-Clause.txt`
(+ any additional license the resolved graph turns up).

- Per-component **copyright line** and any upstream **`NOTICE`** text (Apache-2.0
  §4(d) — e.g. Media3, Coil where they ship one) render *above* the shared
  license body, so the shared body is not duplicated per component.
- Read lazily at view time via the generated `Res.readBytes("files/licenses/…")`
  (`compose.components.resources` is already a dependency). This ships the texts
  inside every artifact automatically (APK, desktop jar, iOS framework).

> Implementation note: confirm the generated `Res` accessor package and the
> `composeResources` root wiring in `composeApp/build.gradle.kts` before coding.

## 5. In-App UI

New file `ui/settings/LicensesScreen.kt`, mirroring the existing
`DbBrowserScreen` / `CrashLogScreen` sub-screen pattern (separate composable,
opened via an `onOpenLicenses: () -> Unit` callback threaded from `App.kt`).

One screen, internal `selectedComponent: ThirdPartyComponent?` state:

- **List view:** components grouped by platform section (All / Android / Desktop
  / iOS); each row shows `name · version` and a license badge. Copyleft
  components (GPL/LGPL) get a subtle marker so they're scannable.
- **Detail view:** tapping a row shows the component header (name, coordinate,
  version, copyright, homepage) + its full license text. `PlatformBackHandler`
  clears `selectedComponent` first, then a second back press pops the screen.

In `AppSettingsScreen` (About section):

- Replace the inline `CreditsCard()` body and remove `CREDIT_ENTRIES`. Keep a
  compact card that retains the **attributions blurb** (LRClib lyrics — CC0;
  Cover Art Archive — CC0/public domain; local-tags-only, no telemetry) and adds
  an **"Open-source licenses ›"** row that invokes `onOpenLicenses`.
- The existing proprietary-**License** link row stays; update its stale comment
  / subtitle that currently references "the CreditsCard above lists OSS deps."
- Wire `onOpenLicenses` in `App.kt` exactly like `onOpenDbBrowser` (new nav
  destination + callback).

## 6. `THIRD_PARTY_LICENSES` Release File

A Gradle `JavaExec` task `generateThirdPartyLicenses` runs a small generator
(JVM `main`, e.g. `desktopMain` or a dedicated generator source) that consumes
the **same** `LicenseCatalog` + text resources and renders an aggregated file:

- For each component: a header block (name, coordinate, version, copyright,
  license SPDX, homepage) followed by the full license body text, separated by a
  rule.
- Output written to **(a)** repo root `THIRD_PARTY_LICENSES` and **(b)**
  `composeApp/src/commonMain/composeResources/files/THIRD_PARTY_LICENSES.txt`
  (ships inside every binary on every platform).
- **Desktop side-car file:** wire the generated aggregate into Compose Desktop's
  `nativeDistributions { appResourcesRootDir }` so `jpackage` copies it into the
  app-image, `.deb`, and `.AppImage` next to the binary.
- Make the relevant package tasks depend on `generateThirdPartyLicenses` so the
  file can't go stale at release time.

Single source of truth is preserved: the Kotlin catalog + text files feed both
the in-app screen and the generated file.

## 7. Accurate Coverage (per platform)

Only artifacts that **actually ship** are documented. Test/tooling-only deps are
excluded: `kotlin-test`, `kotlin-test-junit`, `junit`, `androidx-test*`,
`espresso`, `compose ui-tooling` (debug), `kotlinx-coroutines-test`. (Note:
`sqldelight sqlite-driver` is used in `commonTest` *and* ships as a real desktop
runtime dependency — it is documented under Desktop, not excluded.)
Declared-but-unwired catalog entries (e.g. `androidx-core-ktx`,
`androidx-appcompat` appear in `libs.versions.toml` but in no `*.dependencies`
block) are excluded after verification.

Verification step during implementation: run
`./gradlew :composeApp:dependencies` (and the per-target variants) to pin exact
transitive versions and confirm the bundled set.

**Common (all targets):** Kotlin stdlib, kotlinx-coroutines-core,
kotlinx-serialization-json, Compose Multiplatform (runtime / foundation / ui /
components-resources / ui-tooling-preview), Compose Material3, Material Icons
Extended (1.7.3, frozen), AndroidX Lifecycle (viewmodel-compose,
runtime-compose), SQLDelight (coroutines-extensions, primitive-adapters,
runtime), Koin (core, compose), MaterialKolor (**MIT**; bundles
material-color-utilities, Apache-2.0), Multiplatform Settings (no-arg,
coroutines), Coil 3 (compose). — all Apache-2.0 except MaterialKolor.

**Android only:** AndroidX Activity Compose, SQLDelight Android Driver, requery
sqlite-android (Apache-2.0; bundles SQLite, public domain), Koin Android,
**jaudiotagger (LGPL-2.1-or-later)**, Media3 (exoplayer / session / common),
kotlinx-coroutines-android (transitive).

**Desktop only:** Compose Desktop currentOS (pulls Skiko, Apache-2.0, wrapping
Skia — BSD-3-Clause/Google), SQLDelight SQLite Driver, **sqlite-jdbc
(Apache-2.0 + BSD-2-Clause; SQLite public domain)** [transitive],
**jaudiotagger (LGPL-2.1)**, **VLCJ (GPL-3.0-or-later)** + its vlcj-natives
support libs (GPL-3.0) [transitive], **JNA + jna-platform (dual
LGPL-2.1+/Apache-2.0)** [transitive], kotlinx-coroutines-swing. **System libvlc
3.x (VideoLAN, LGPL-2.1+)** — external, user-installed, **not bundled**;
documented as a note only.

**iOS only:** SQLDelight Native Driver; Kotlin/Native + Compose/Skiko native
runtime. — Apache-2.0.

> Exact transitive coordinates/versions (sqlite-jdbc, jna, vlcj-natives, skiko)
> are filled from the resolved dependency graph during implementation, not
> guessed.

## 8. VLCJ / GPL Finding (flagged, not resolved here)

**VLCJ 4.10.1 is GPL-3.0-or-later** and is linked into the **desktop** build.
This task documents it accurately (catalog, screen, `THIRD_PARTY_LICENSES`).
Separately, a GPLv3 library linked into a binary distributed under the
proprietary `LICENSE` is a genuine **license-compatibility** conflict for the
desktop target — a product/legal decision outside this task's scope. Action:
add a note to `SPEC.md` (and surface in the design doc) that desktop GPL-vs-
proprietary compatibility needs separate legal/product review. No in-app
disclaimer beyond accurate documentation; the conflict is **not** resolved here.

## 9. Out of Scope

- Resolving the VLCJ/GPL compatibility conflict (backend swap, dual-licensing,
  etc.).
- Enumerating the *entire* transitive graph (only notable bundled transitive
  libs are documented; deep transitive Apache-2.0 leaves are not individually
  listed).
- Adopting a build-time license plugin (explicitly rejected in favor of the
  hand-curated approach).
- Legal review of the texts themselves.

## 10. Documentation / AGENTS.md

Add a short rule under "Build & Tooling" (dependency management): **when adding a
third-party dependency, also add it to `LicenseCatalog.kt` and rerun
`generateThirdPartyLicenses`.** (A broader AGENTS.md freshness audit is tracked
separately at the user's request.)

## 11. Acceptance Criteria

1. *About → Licenses* screen lists every shipped third-party component per
   platform, with name, version, license, copyright, and the **full license
   text** viewable in-app.
2. VLCJ (GPL-3.0), jaudiotagger (LGPL-2.1), JNA (dual), sqlite-jdbc (Apache +
   BSD-2), and MaterialKolor (MIT) are present and **correctly** licensed.
3. `THIRD_PARTY_LICENSES` exists at repo root, ships inside every artifact, and
   is copied beside the desktop binary by packaging.
4. Test/tooling-only and unwired deps are absent.
5. System libvlc is documented as external/not-bundled.
6. `SPEC.md` carries the VLCJ/GPL legal-review note; `AGENTS.md` carries the
   catalog-maintenance rule.
7. Existing tests pass; `./gradlew :composeApp:run` shows the screen and a
   desktop package contains the side-car file.
