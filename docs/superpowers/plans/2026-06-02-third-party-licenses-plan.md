# Third-Party License Documentation — Implementation Plan

**Date:** 2026-06-02
**Spec:** `docs/superpowers/specs/2026-06-02-third-party-licenses-design.md`
**Owner:** VisorCraft LLC
**Status:** Ready to implement

This plan is concrete and ordered. An implementer should be able to follow it
top-to-bottom without re-deriving any decision. Every path is repo-relative to
`/work/repos/visorcraft/RadioShed`.

---

## 0. Ground facts confirmed against the live tree (do not re-verify)

- **Primary verification loop:** `./gradlew :composeApp:compileKotlinDesktop
  --offline` compiles `commonMain` + `desktopMain` cleanly (verified EXIT 0).
  There is **no Android SDK** on this host — do **not** attempt to build/compile
  the Android target. All Kotlin in this plan lives in `commonMain`, so the
  desktop compile covers it.
- **`desktopTest` task exists** (`./gradlew :composeApp:desktopTest --offline`).
  `commonTest` sources execute on the desktop JVM test run. Use this for
  `LicenseCatalogTest`.
- **Compose resources accessor is live.** Generated `Res` is in package
  `radioshed.composeapp.generated.resources`, and `Res.readBytes(path)` is a
  `suspend fun` resolving `composeResources/radioshed.composeapp.generated.resources/<path>`
  (verified in the generated `Res.kt`: `val bytes = Res.readBytes("files/key.bin")`).
  Decode with `.decodeToString()`. Call it from a `LaunchedEffect` /
  `produceState`. `compose.components.resources` is already a `commonMain` dep
  (`build.gradle.kts:118`).
- **Back hook:** `nav/PlatformBackHandler.kt` —
  `expect fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit)`.
  Android wires `BackHandler`; iOS no-ops.
- **Navigation type:** `nav/AppScreen.kt` is a **`sealed interface AppScreen`**
  with `data object` cases (NOT an enum). `data object DbBrowser : AppScreen`
  at line 130; `data object CrashLog : AppScreen` at line 89;
  `data object AppSettings : AppScreen` at line 29. The `when(current)` dispatch
  is in `App.kt` (Library/DbBrowser/CrashLog branches ~lines 358-417), and the
  `when` is **exhaustive over the sealed interface** with a catch-all `-> Unit`
  group at lines 519-524 for screens handled above the `Column`. A new
  `data object Licenses` MUST get a real branch in the `when` (it is exhaustive).
- **`appResourcesRootDir` is NOT currently configured** in `build.gradle.kts`'s
  `nativeDistributions {}` block — it must be ADDED (§6 below).
- **`generateBuildInfo`** (`build.gradle.kts:544-586`) is the template to mirror
  for a generator task (`abstract class … : DefaultTask`, `@Input`/`@OutputDirectory`
  properties, registered via `tasks.register<…>("…")`, wired with
  `tasks.matching { … }.configureEach { dependsOn(…) }`).
- **The bogus License URL** `https://github.com/anthropics/radioshed/blob/master/LICENSE`
  is at `AppSettingsScreen.kt:766` and MUST be removed.
- **Proprietary LICENSE body** to ship in-app = `LICENSE` lines **1 through 321**
  (the `END OF AGREEMENT` banner block, lines 319-321, inclusive), **excluding**
  everything from line 324 onward (`REMINDERS FOR THE PROJECT MAINTAINER
  (DELETE BEFORE PUBLIC RELEASE):`). The "DRAFT — NOT YET LEGALLY REVIEWED"
  banner (lines 5-15) is **retained** per spec §5.

### Coding conventions to honor (from AGENTS.md)
- 4-space indent, 120-col max, LF, final newline, UTF-8.
- **Imports layout:** `*, java.**, javax.**, kotlin.**, ^` (third-party first,
  then `java.*`, `javax.*`, `kotlin.*`, then aliased). No star imports.
- **Trailing commas required** on multi-line parameter/argument/collection lists.
- Naming: composables `PascalCase` noun; top-level `val` lists / consts
  `SCREAMING_SNAKE_CASE`; data classes `PascalCase`.
- KDoc on non-trivial public APIs (top-level screen composables, the catalog).
- **No new abstractions beyond what the task needs** (three similar lines beat a
  premature helper). No error-handling for impossible states.
- Versions live in `gradle/libs.versions.toml` — but this task adds **no new
  runtime dependency**, so nothing changes there.

---

## 1. New package `com.visorcraft.radioshed.licenses` (commonMain)

Two new files under
`composeApp/src/commonMain/kotlin/com/visorcraft/radioshed/licenses/`.

### 1a. `ThirdPartyComponent.kt`

```kotlin
package com.visorcraft.radioshed.licenses

/**
 * One shipped third-party component, hand-curated. The catalog in
 * [LicenseCatalog] is the single source of truth for both the in-app
 * Licenses screen and the generated `THIRD_PARTY_LICENSES` file. When you
 * add or bump a dependency, update [THIRD_PARTY_COMPONENTS] and rerun
 * `./gradlew :composeApp:generateThirdPartyLicenses`.
 */
data class ThirdPartyComponent(
    val name: String,
    val coordinate: String,
    val version: String,
    val license: License,
    val copyright: String,
    val platforms: Set<Platform>,
    val homepage: String,
    val transitive: Boolean = false,
    val notes: String? = null,
)

/** Target a component is linked into. ALL == every shipped target. */
enum class Platform { ALL, ANDROID, DESKTOP, IOS }

/**
 * Distinct license texts bundled once each under
 * `composeResources/files/licenses/`. [textResource] is the path passed to
 * `Res.readBytes(...)`. [copyleft] flags GPL/LGPL so the UI can mark them.
 */
enum class License(
    val spdx: String,
    val displayName: String,
    val textResource: String,
    val copyleft: Boolean = false,
) {
    APACHE_2_0("Apache-2.0", "Apache License 2.0", "files/licenses/Apache-2.0.txt"),
    MIT("MIT", "MIT License", "files/licenses/MIT.txt"),
    BSD_2_CLAUSE("BSD-2-Clause", "BSD 2-Clause License", "files/licenses/BSD-2-Clause.txt"),
    BSD_3_CLAUSE("BSD-3-Clause", "BSD 3-Clause License", "files/licenses/BSD-3-Clause.txt"),
    LGPL_2_1_OR_LATER(
        "LGPL-2.1-or-later",
        "GNU LGPL v2.1 or later",
        "files/licenses/LGPL-2.1.txt",
        copyleft = true,
    ),
    GPL_3_0_OR_LATER(
        "GPL-3.0-or-later",
        "GNU GPL v3.0 or later",
        "files/licenses/GPL-3.0.txt",
        copyleft = true,
    ),

    /**
     * RadioShed's own proprietary license. Not a third-party OSS license; modeled
     * here so the proprietary text reuses the same list -> detail rendering path.
     * Its [textResource] is the bundled LICENSE body (see §2).
     */
    PROPRIETARY(
        "LicenseRef-RadioShed-Proprietary",
        "RadioShed Proprietary License",
        "files/licenses/RadioShed-Proprietary.txt",
    ),
}
```

Notes for the implementer:
- `License` is `spdx`-keyed and **deduplicated** — every Apache-2.0 component
  references one `Apache-2.0.txt`. Per-component copyright/NOTICE render *above*
  the shared body (the screen and the generator both do this), so the body is
  never duplicated.
- Dual-licensed JNA artifacts (`LGPL-2.1-or-later OR Apache-2.0`) record the
  license **we rely on** — map them to `License.APACHE_2_0` (the proprietary app
  relies on the Apache-2.0 option) and explain the choice in `notes`. Do **not**
  invent a combined enum constant.
- `sqlite-jdbc` is `Apache-2.0 AND BSD-2-Clause` plus a bundled public-domain
  SQLite engine. Model the **primary** license as `License.APACHE_2_0` and spell
  out the BSD-2-Clause (Zentus) and public-domain-engine layers in `notes`. (A
  single component maps to a single enum; the conjunctive detail lives in
  `notes`, keeping "no new abstractions".)
- Skia, shipped inside Skiko (BSD-3-Clause/Google), is documented as a `notes`
  line on the Skiko entry — Skiko itself maps to `License.APACHE_2_0`.
  `BSD_3_CLAUSE` is still added to the enum so the Skiko `notes` reference and a
  future standalone entry have a real text resource to point at, and so
  `BSD-3-Clause.txt` ships.

### 1b. `LicenseCatalog.kt`

```kotlin
package com.visorcraft.radioshed.licenses

import com.visorcraft.radioshed.licenses.License.APACHE_2_0
import com.visorcraft.radioshed.licenses.License.GPL_3_0_OR_LATER
import com.visorcraft.radioshed.licenses.License.LGPL_2_1_OR_LATER
import com.visorcraft.radioshed.licenses.License.MIT
import com.visorcraft.radioshed.licenses.License.PROPRIETARY
import com.visorcraft.radioshed.licenses.Platform.ALL
import com.visorcraft.radioshed.licenses.Platform.ANDROID
import com.visorcraft.radioshed.licenses.Platform.DESKTOP
import com.visorcraft.radioshed.licenses.Platform.IOS

/**
 * The proprietary RadioShed license, surfaced through the same Licenses
 * screen as the OSS components. Rendered from the bundled LICENSE body.
 */
val RADIOSHED_PROPRIETARY: ThirdPartyComponent = ThirdPartyComponent(
    name = "RadioShed",
    coordinate = "com.visorcraft:radioshed",
    version = "", // filled at render time from BuildInfo.VERSION_NAME
    license = PROPRIETARY,
    copyright = "Copyright (c) 2026 VisorCraft LLC. All rights reserved.",
    platforms = setOf(ALL),
    homepage = "https://github.com/visorcraft/RadioShed/blob/master/LICENSE",
    notes = "RadioShed itself is proprietary. The text below is the in-app copy " +
        "of the repository LICENSE; the canonical file ships as THIRD_PARTY_LICENSES' " +
        "sibling and in the repo.",
)

/**
 * Hand-curated, ordered list of every shipped third-party component, grouped
 * conceptually by platform. Single source of truth for the Licenses screen and
 * the generated THIRD_PARTY_LICENSES file. Test-only / tooling-only / unwired
 * deps are intentionally excluded (see LicenseCatalogTest).
 */
val THIRD_PARTY_COMPONENTS: List<ThirdPartyComponent> = listOf(
    // ---- Common (all shipped targets) ----
    ThirdPartyComponent(
        name = "Kotlin Standard Library",
        coordinate = "org.jetbrains.kotlin:kotlin-stdlib",
        version = "2.3.20",
        license = APACHE_2_0,
        copyright = "Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors",
        platforms = setOf(ALL),
        homepage = "https://kotlinlang.org/",
        transitive = true,
        notes = "Pulled in transitively by the Kotlin Multiplatform plugin (kotlin = 2.3.20).",
    ),
    ThirdPartyComponent(
        name = "kotlinx-coroutines-core",
        coordinate = "org.jetbrains.kotlinx:kotlinx-coroutines-core",
        version = "1.10.2",
        license = APACHE_2_0,
        copyright = "Copyright 2000-2020 JetBrains s.r.o. and Kotlin Programming Language contributors",
        platforms = setOf(ALL),
        homepage = "https://github.com/Kotlin/kotlinx.coroutines",
    ),
    ThirdPartyComponent(
        name = "kotlinx-serialization-json",
        coordinate = "org.jetbrains.kotlinx:kotlinx-serialization-json",
        version = "1.8.0",
        license = APACHE_2_0,
        copyright = "Copyright 2017-2019 JetBrains s.r.o.",
        platforms = setOf(ALL),
        homepage = "https://github.com/Kotlin/kotlinx.serialization",
    ),
    ThirdPartyComponent(
        name = "Compose Multiplatform Runtime",
        coordinate = "org.jetbrains.compose.runtime:runtime",
        version = "1.10.3",
        license = APACHE_2_0,
        copyright = "Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers",
        platforms = setOf(ALL),
        homepage = "https://github.com/JetBrains/compose-multiplatform",
    ),
    ThirdPartyComponent(
        name = "Compose Multiplatform Foundation",
        coordinate = "org.jetbrains.compose.foundation:foundation",
        version = "1.10.3",
        license = APACHE_2_0,
        copyright = "Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers",
        platforms = setOf(ALL),
        homepage = "https://github.com/JetBrains/compose-multiplatform",
    ),
    ThirdPartyComponent(
        name = "Compose Multiplatform UI",
        coordinate = "org.jetbrains.compose.ui:ui",
        version = "1.10.3",
        license = APACHE_2_0,
        copyright = "Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers",
        platforms = setOf(ALL),
        homepage = "https://github.com/JetBrains/compose-multiplatform",
        notes = "On Desktop the ui artifact transitively pulls Skiko. AndroidX-derived " +
            "sources carry 'The Android Open Source Project' headers.",
    ),
    ThirdPartyComponent(
        name = "Compose Multiplatform Components Resources",
        coordinate = "org.jetbrains.compose.components:components-resources",
        version = "1.10.3",
        license = APACHE_2_0,
        copyright = "Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers",
        platforms = setOf(ALL),
        homepage = "https://github.com/JetBrains/compose-multiplatform",
        notes = "Provides the generated Res accessor used to bundle the license texts.",
    ),
    ThirdPartyComponent(
        name = "Compose Multiplatform UI Tooling Preview",
        coordinate = "org.jetbrains.compose.ui:ui-tooling-preview",
        version = "1.10.3",
        license = APACHE_2_0,
        copyright = "Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers",
        platforms = setOf(ALL),
        homepage = "https://github.com/JetBrains/compose-multiplatform",
        notes = "The @Preview annotation/runtime artifact; ships. Distinct from compose " +
            "ui-tooling (debug/desktop inspector), which is tooling-only and excluded.",
    ),
    ThirdPartyComponent(
        name = "Compose Multiplatform Material3",
        coordinate = "org.jetbrains.compose.material3:material3",
        version = "1.10.0-alpha05",
        license = APACHE_2_0,
        copyright = "Copyright 2021 The Android Open Source Project",
        platforms = setOf(ALL),
        homepage = "https://github.com/JetBrains/compose-multiplatform",
        notes = "Versioned independently of Compose (material3 = 1.10.0-alpha05). JetBrains' " +
            "port of AndroidX Material3; source files carry AOSP headers.",
    ),
    ThirdPartyComponent(
        name = "Compose Material Icons Extended",
        coordinate = "org.jetbrains.compose.material:material-icons-extended",
        version = "1.7.3",
        license = APACHE_2_0,
        copyright = "Copyright The Android Open Source Project",
        platforms = setOf(ALL),
        homepage = "https://github.com/JetBrains/compose-multiplatform",
        notes = "Frozen at 1.7.3 (no longer shipped past that by Compose Multiplatform). " +
            "Generated Material icon sources are AOSP-derived.",
    ),
    ThirdPartyComponent(
        name = "AndroidX Lifecycle ViewModel Compose (multiplatform)",
        coordinate = "org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose",
        version = "2.10.0",
        license = APACHE_2_0,
        copyright = "Copyright 2023 The Android Open Source Project",
        platforms = setOf(ALL),
        homepage = "https://github.com/JetBrains/compose-multiplatform",
        notes = "JetBrains' KMP port of AndroidX Lifecycle; verbatim AOSP headers.",
    ),
    ThirdPartyComponent(
        name = "AndroidX Lifecycle Runtime Compose (multiplatform)",
        coordinate = "org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose",
        version = "2.10.0",
        license = APACHE_2_0,
        copyright = "Copyright 2023 The Android Open Source Project",
        platforms = setOf(ALL),
        homepage = "https://github.com/JetBrains/compose-multiplatform",
    ),
    ThirdPartyComponent(
        name = "SQLDelight Runtime",
        coordinate = "app.cash.sqldelight:runtime",
        version = "2.1.0",
        license = APACHE_2_0,
        copyright = "Copyright 2016 Square, Inc. (now Cash App / Block, Inc.)",
        platforms = setOf(ALL),
        homepage = "https://github.com/cashapp/sqldelight",
        transitive = true,
    ),
    ThirdPartyComponent(
        name = "SQLDelight Coroutines Extensions",
        coordinate = "app.cash.sqldelight:coroutines-extensions",
        version = "2.1.0",
        license = APACHE_2_0,
        copyright = "Copyright 2016 Square, Inc. (now Cash App / Block, Inc.)",
        platforms = setOf(ALL),
        homepage = "https://github.com/cashapp/sqldelight",
    ),
    ThirdPartyComponent(
        name = "SQLDelight Primitive Adapters",
        coordinate = "app.cash.sqldelight:primitive-adapters",
        version = "2.1.0",
        license = APACHE_2_0,
        copyright = "Copyright 2016 Square, Inc. (now Cash App / Block, Inc.)",
        platforms = setOf(ALL),
        homepage = "https://github.com/cashapp/sqldelight",
    ),
    ThirdPartyComponent(
        name = "Koin Core",
        coordinate = "io.insert-koin:koin-core",
        version = "4.1.0",
        license = APACHE_2_0,
        copyright = "Copyright 2017-present Arnaud Giuliani",
        platforms = setOf(ALL),
        homepage = "https://github.com/InsertKoinIO/koin",
    ),
    ThirdPartyComponent(
        name = "Koin Compose",
        coordinate = "io.insert-koin:koin-compose",
        version = "4.1.0",
        license = APACHE_2_0,
        copyright = "Copyright 2017-present Arnaud Giuliani",
        platforms = setOf(ALL),
        homepage = "https://github.com/InsertKoinIO/koin",
    ),
    ThirdPartyComponent(
        name = "MaterialKolor",
        coordinate = "com.materialkolor:material-kolor",
        version = "4.1.1",
        license = MIT,
        copyright = "Copyright (c) 2025 Jordon de Hoog",
        platforms = setOf(ALL),
        homepage = "https://github.com/jordond/MaterialKolor",
        notes = "Bundles Google's material-color-utilities (Apache-2.0), which retains its " +
            "own attribution.",
    ),
    ThirdPartyComponent(
        name = "Multiplatform Settings (no-arg)",
        coordinate = "com.russhwolf:multiplatform-settings-no-arg",
        version = "1.3.0",
        license = APACHE_2_0,
        copyright = "Copyright 2018-2023 Russell Wolf",
        platforms = setOf(ALL),
        homepage = "https://github.com/russhwolf/multiplatform-settings",
    ),
    ThirdPartyComponent(
        name = "Multiplatform Settings Coroutines",
        coordinate = "com.russhwolf:multiplatform-settings-coroutines",
        version = "1.3.0",
        license = APACHE_2_0,
        copyright = "Copyright 2018-2023 Russell Wolf",
        platforms = setOf(ALL),
        homepage = "https://github.com/russhwolf/multiplatform-settings",
    ),
    ThirdPartyComponent(
        name = "Coil 3 Compose",
        coordinate = "io.coil-kt.coil3:coil-compose",
        version = "3.3.0",
        license = APACHE_2_0,
        copyright = "Copyright 2025 Coil Contributors",
        platforms = setOf(ALL),
        homepage = "https://github.com/coil-kt/coil",
        notes = "Transitively pulls coil, coil-core, coil-compose-core (all Apache-2.0, " +
            "Coil Contributors) and okio (Apache-2.0, Square).",
    ),

    // ---- Desktop only ----
    ThirdPartyComponent(
        name = "kotlinx-coroutines-swing",
        coordinate = "org.jetbrains.kotlinx:kotlinx-coroutines-swing",
        version = "1.10.2",
        license = APACHE_2_0,
        copyright = "Copyright 2000-2020 JetBrains s.r.o. and Kotlin Programming Language contributors",
        platforms = setOf(DESKTOP),
        homepage = "https://github.com/Kotlin/kotlinx.coroutines",
        notes = "Provides Dispatchers.Swing; marshals VLCJ media-event callbacks onto the UI thread.",
    ),
    ThirdPartyComponent(
        name = "Skiko (Kotlin Skia bindings)",
        coordinate = "org.jetbrains.skiko:skiko",
        version = "0.9.37.4",
        license = APACHE_2_0,
        copyright = "Copyright JetBrains s.r.o. and respective authors and developers",
        platforms = setOf(DESKTOP, IOS),
        homepage = "https://github.com/JetBrains/skiko",
        transitive = true,
        notes = "Transitive via Compose. Bundles Google's Skia graphics engine, licensed " +
            "BSD-3-Clause: 'Copyright (c) 2011 Google Inc. All rights reserved.' — see " +
            "files/licenses/BSD-3-Clause.txt. (Copyright year range corrected per adversary review.)",
    ),
    ThirdPartyComponent(
        name = "SQLDelight SQLite Driver (JVM)",
        coordinate = "app.cash.sqldelight:sqlite-driver",
        version = "2.1.0",
        license = APACHE_2_0,
        copyright = "Copyright 2016 Square, Inc. (now Cash App / Block, Inc.)",
        platforms = setOf(DESKTOP),
        homepage = "https://github.com/cashapp/sqldelight",
        notes = "Pulls org.xerial:sqlite-jdbc transitively (separate entry).",
    ),
    ThirdPartyComponent(
        name = "SQLite JDBC (sqlite-jdbc)",
        coordinate = "org.xerial:sqlite-jdbc",
        version = "3.49.1.0",
        license = APACHE_2_0,
        copyright = "Copyright (c) Taro L. Saito (xerial) and contributors",
        platforms = setOf(DESKTOP),
        homepage = "https://github.com/xerial/sqlite-jdbc",
        transitive = true,
        notes = "DUAL/LAYERED: the wrapper is Apache-2.0; the derived Zentus SQLite JDBC code is " +
            "BSD-2-Clause ('Copyright (c) 2006 David Crawshaw'); the bundled native SQLite engine " +
            "is PUBLIC DOMAIN. SPDX: Apache-2.0 AND BSD-2-Clause. See files/licenses/BSD-2-Clause.txt.",
    ),
    ThirdPartyComponent(
        name = "jaudiotagger",
        coordinate = "net.jthink:jaudiotagger",
        version = "3.0.1",
        license = LGPL_2_1_OR_LATER,
        copyright = "Copyright (C) Paul Taylor / JThink Ltd (jthink.net)",
        platforms = setOf(ANDROID, DESKTOP),
        homepage = "https://bitbucket.org/ijabz/jaudiotagger",
        notes = "Declared in both androidMain and desktopMain. Source header states LGPL 2.1 " +
            "'or (at your option) any later version'.",
    ),
    ThirdPartyComponent(
        name = "vlcj",
        coordinate = "uk.co.caprica:vlcj",
        version = "4.10.1",
        license = GPL_3_0_OR_LATER,
        copyright = "Copyright 2009-2025 Caprica Software Limited.",
        platforms = setOf(DESKTOP),
        homepage = "https://github.com/caprica/vlcj",
        notes = "DESKTOP only; never linked into the Android APK. GPL-3.0-or-later (source headers: " +
            "'version 3 of the License, or (at your option) any later version'). See §8 legal note. " +
            "(Copyright corrected per adversary review.)",
    ),
    ThirdPartyComponent(
        name = "vlcj-natives",
        coordinate = "uk.co.caprica:vlcj-natives",
        version = "4.8.3",
        license = GPL_3_0_OR_LATER,
        copyright = "Copyright 2009-2025 Caprica Software Limited.",
        platforms = setOf(DESKTOP),
        homepage = "https://github.com/caprica/vlcj-natives",
        transitive = true,
        notes = "Transitive via vlcj 4.10.1; JNA bindings to libvlc.",
    ),
    ThirdPartyComponent(
        name = "JNA (jna-jpms)",
        coordinate = "net.java.dev.jna:jna-jpms",
        version = "5.14.0",
        license = APACHE_2_0,
        copyright = "Copyright (c) Timothy Wall and the JNA Development Team (incl. Matthias " +
            "Bläsing) and contributors",
        platforms = setOf(DESKTOP),
        homepage = "https://github.com/java-native-access/jna",
        transitive = true,
        notes = "Transitive via vlcj-natives. Dual-licensed 'LGPL-2.1-or-later OR Apache-2.0'; " +
            "RadioShed relies on the Apache-2.0 option. (Copyright corrected per adversary review: " +
            "upstream asserts no formal copyright line; names from POM developers.)",
    ),
    ThirdPartyComponent(
        name = "JNA Platform (jna-platform-jpms)",
        coordinate = "net.java.dev.jna:jna-platform-jpms",
        version = "5.14.0",
        license = APACHE_2_0,
        copyright = "Copyright (c) Timothy Wall and the JNA Development Team (incl. Matthias " +
            "Bläsing) and contributors",
        platforms = setOf(DESKTOP),
        homepage = "https://github.com/java-native-access/jna",
        transitive = true,
        notes = "Transitive via vlcj-natives. Dual-licensed 'LGPL-2.1-or-later OR Apache-2.0'; " +
            "RadioShed relies on the Apache-2.0 option.",
    ),
    ThirdPartyComponent(
        name = "libvlc",
        coordinate = "org.videolan:libvlc",
        version = "3.x (system / external)",
        license = LGPL_2_1_OR_LATER,
        copyright = "Copyright (C) VideoLAN and the VLC authors",
        platforms = setOf(DESKTOP),
        homepage = "https://www.videolan.org/vlc/libvlc.html",
        transitive = true,
        notes = "EXTERNAL, NOT BUNDLED. User/OS-installed native runtime discovered at startup via " +
            "vlcj NativeDiscovery(). LGPL obligations satisfied by the user's own system install.",
    ),

    // ---- Android only ----
    ThirdPartyComponent(
        name = "androidx.activity:activity-compose",
        coordinate = "androidx.activity:activity-compose",
        version = "1.13.0",
        license = APACHE_2_0,
        copyright = "Copyright The Android Open Source Project / Google LLC",
        platforms = setOf(ANDROID),
        homepage = "https://developer.android.com/jetpack/androidx/releases/activity",
    ),
    ThirdPartyComponent(
        name = "SQLDelight Android Driver",
        coordinate = "app.cash.sqldelight:android-driver",
        version = "2.1.0",
        license = APACHE_2_0,
        copyright = "Copyright 2016 Square, Inc. (now Cash App / Block, Inc.)",
        platforms = setOf(ANDROID),
        homepage = "https://github.com/cashapp/sqldelight",
    ),
    ThirdPartyComponent(
        name = "requery sqlite-android",
        coordinate = "com.github.requery:sqlite-android",
        version = "3.45.0",
        license = APACHE_2_0,
        copyright = "Copyright (c) 2017-2025 requery.io (Nikhil Purushe); portions Copyright (C) " +
            "2005-2012 The Android Open Source Project",
        platforms = setOf(ANDROID),
        homepage = "https://github.com/requery/sqlite-android",
        notes = "Wrapper is Apache-2.0; BUNDLES the native SQLite engine, which is PUBLIC DOMAIN.",
    ),
    ThirdPartyComponent(
        name = "Koin Android",
        coordinate = "io.insert-koin:koin-android",
        version = "4.1.0",
        license = APACHE_2_0,
        copyright = "Copyright 2017-present Arnaud Giuliani",
        platforms = setOf(ANDROID),
        homepage = "https://github.com/InsertKoinIO/koin",
    ),
    ThirdPartyComponent(
        name = "AndroidX Media3 ExoPlayer",
        coordinate = "androidx.media3:media3-exoplayer",
        version = "1.6.1",
        license = APACHE_2_0,
        copyright = "Copyright The Android Open Source Project",
        platforms = setOf(ANDROID),
        homepage = "https://github.com/androidx/media",
        notes = "Media3 ships a NOTICE aggregating third-party attribution; reproduce alongside the LICENSE.",
    ),
    ThirdPartyComponent(
        name = "AndroidX Media3 Session",
        coordinate = "androidx.media3:media3-session",
        version = "1.6.1",
        license = APACHE_2_0,
        copyright = "Copyright The Android Open Source Project",
        platforms = setOf(ANDROID),
        homepage = "https://github.com/androidx/media",
    ),
    ThirdPartyComponent(
        name = "AndroidX Media3 Common",
        coordinate = "androidx.media3:media3-common",
        version = "1.6.1",
        license = APACHE_2_0,
        copyright = "Copyright The Android Open Source Project",
        platforms = setOf(ANDROID),
        homepage = "https://github.com/androidx/media",
    ),

    // ---- iOS only ----
    ThirdPartyComponent(
        name = "SQLDelight Native Driver",
        coordinate = "app.cash.sqldelight:native-driver",
        version = "2.1.0",
        license = APACHE_2_0,
        copyright = "Copyright 2016 Square, Inc. (now Cash App / Block, Inc.)",
        platforms = setOf(IOS),
        homepage = "https://github.com/cashapp/sqldelight",
        notes = "Kotlin/Native SqlDriver; pulls co.touchlab sqliter-driver + stately (Apache-2.0).",
    ),
)
```

Implementer notes on the catalog:
- **Adversary corrections applied** (copyrights for Skiko, vlcj, jna-jpms,
  jna-platform-jpms, and the Coil family already reflect the corrected strings;
  versions use the resolved dependency-graph values, e.g. coroutines 1.10.2,
  sqlite-jdbc 3.49.1.0, skiko 0.9.37.4).
- **Deep transitive Apache-2.0 leaves intentionally folded into a parent's
  `notes`** (per spec §9): `coil` / `coil-core` / `coil-compose-core` / `okio`
  under Coil; `kotlinx-serialization-core` / `atomicfu` / `kotlinx-datetime`
  under serialization/coroutines; `material-color-utilities` under MaterialKolor;
  `co.touchlab` stately/sqliter under the iOS native driver;
  `androidx.media3` database/datasource/decoder/extractor/container under the
  three declared Media3 modules; `jdbc-driver` under sqlite-driver;
  `skiko-awt` / `skiko-awt-runtime-*` under Skiko. The test in §8 asserts every
  **declared/standalone** shipped coordinate is represented — folded leaves are
  whitelisted there.
- **Excluded** (test/tooling/unwired, per spec §7): `kotlin-test`,
  `kotlin-test-junit`, `junit`, `androidx-test*`, `espresso`,
  `kotlinx-coroutines-test`, `compose ui-tooling` (debug/desktop inspector),
  `sqldelight sqlite-3-38-dialect`, and the declared-but-unwired
  `androidx-core-ktx` / `androidx-appcompat` catalog entries. (`sqlite-driver`
  in `commonTest` is NOT excluded — the same coordinate ships on desktop and is
  documented under Desktop.)

---

## 2. License text resources

Create directory `composeApp/src/commonMain/composeResources/files/licenses/`
and add the following verbatim text files (fetched from canonical upstream
sources **at implementation time** — do not paraphrase):

| File | Source of truth |
| --- | --- |
| `Apache-2.0.txt` | https://www.apache.org/licenses/LICENSE-2.0.txt (full body) |
| `MIT.txt` | SPDX MIT template (the `<year>`/`<holder>` line generic; per-component copyright renders above) |
| `BSD-2-Clause.txt` | SPDX BSD-2-Clause template |
| `BSD-3-Clause.txt` | SPDX BSD-3-Clause template (for Skia attribution shipped under Skiko) |
| `LGPL-2.1.txt` | https://www.gnu.org/licenses/old-licenses/lgpl-2.1.txt |
| `GPL-3.0.txt` | https://www.gnu.org/licenses/gpl-3.0.txt |
| `RadioShed-Proprietary.txt` | The repo `LICENSE` body, lines 1-321 (through `END OF AGREEMENT`), excluding the maintainer-reminders footer (line 324+). Keep the DRAFT banner. |

Rules:
- One file per **distinct** license body; components reference them via
  `License.textResource`. The shared body is never duplicated per component.
- These ship automatically inside every artifact (APK/jar/iOS framework) because
  they live under `composeResources/files/` and `compose.components.resources`
  is already configured.
- `RadioShed-Proprietary.txt` is produced by copying `LICENSE` lines 1-321
  exactly (UTF-8, LF, final newline). Do not edit wording.
- Because `.editorconfig` trims trailing whitespace on non-`*.md` files, verify
  the license bodies still match canonical sources after any IDE save; if an
  editor would mangle them, keep them as-is (they are reference texts). The
  `LicenseCatalogTest` only checks they are non-empty and resolvable, not byte
  equality, so trimming is acceptable.

---

## 3. `ui/settings/LicensesScreen.kt` (commonMain)

New file
`composeApp/src/commonMain/kotlin/com/visorcraft/radioshed/ui/settings/LicensesScreen.kt`,
mirroring `CrashLogScreen.kt` / `DbBrowserScreen.kt` (Scaffold + TopAppBar +
`onBack`, `@OptIn(ExperimentalMaterial3Api::class)`, `TooltipIconButton` /
`ArrowBack` back affordance).

### Behavior
- Single public composable `LicensesScreen(onBack: () -> Unit)`. No Koin deps
  needed; reads `BuildInfo.VERSION_NAME` for the proprietary entry version.
- Internal state `var selected by remember { mutableStateOf<ThirdPartyComponent?>(null) }`.
- `PlatformBackHandler`: when `selected != null`, first back **clears selection**
  (returns to list); when `selected == null`, back calls `onBack` (pops screen).
  ```kotlin
  PlatformBackHandler {
      if (selected != null) selected = null else onBack()
  }
  ```
- **List view** (`selected == null`): a `LazyColumn` grouped by platform section.
  Build sections in this fixed order: All, Android, Desktop, iOS. For grouping,
  treat `ALL` as the "All platforms" section and a component belongs to a
  platform section if that `Platform` is in its `platforms` set. Render a
  `SectionHeader`-style label per group (reuse the private `SectionHeader`
  pattern locally — small `Text`; do not import the private one from
  AppSettingsScreen). The proprietary entry (`RADIOSHED_PROPRIETARY`, version
  patched from `BuildInfo.VERSION_NAME`) is pinned as its **own first section**
  ("RadioShed") above the OSS groups.
  - Each row: `Surface(onClick = { selected = component })` showing
    `"${name} · ${version}"` (or just name for the proprietary entry where
    version is injected) and a trailing license **badge** (a small pill `Text`
    with `license.spdx`). If `license.copyleft`, render a subtle marker
    (e.g. a small "copyleft" label or a distinct badge container color via
    `MaterialTheme.colorScheme.tertiaryContainer`) so GPL/LGPL rows are scannable.
- **Detail view** (`selected != null`): a `Column(verticalScroll(...))` with:
  - Header block: `name`, `coordinate`, `version`, `copyright`,
    `license.displayName` + `license.spdx`, `homepage`, and `notes` if non-null.
  - Optional "View online" affordance opening `homepage` via
    `com.visorcraft.radioshed.source.openWebUrl(homepage)` (same call used in
    `AppSettingsScreen`).
  - The full license body, loaded lazily:
    ```kotlin
    val body by produceState(initialValue = "", selected) {
        value = selected?.license?.textResource
            ?.let { Res.readBytes(it).decodeToString() }
            ?: ""
    }
    ```
    Render `body` in a monospaced `Text` inside a scrolling `Surface` (mirror the
    crash-trace block in `CrashLogScreen.kt` for `FontFamily.Monospace` styling).
  - Import `radioshed.composeapp.generated.resources.Res` and
    `org.jetbrains.compose.resources.*` is NOT needed for `readBytes` (it's a
    member of the generated `Res`); just import `Res`.
- Top app bar title: `"Licenses"` in list mode, the component name in detail mode.
  Back nav icon calls the same `PlatformBackHandler` logic (a `TextButton("Back")`
  or `ArrowBack` icon button that does `if (selected != null) selected = null else onBack()`).

### Imports / structure
- Follow the import layout in `CrashLogScreen.kt` exactly (third-party Compose
  imports first, then project imports, then `kotlinx.*`, then `org.koin`-style).
- Keep helper composables (`LicenseRow`, `LicenseBadge`, `ComponentDetail`,
  `PlatformSection`) `private` at the bottom of the same file (AGENTS.md file-org
  rule: tightly coupled helpers live in the same file).
- KDoc the top-level `LicensesScreen` (it is a top-level screen composable).

---

## 4. `App.kt` wiring

In `composeApp/src/commonMain/kotlin/com/visorcraft/radioshed/App.kt`:

1. **`nav/AppScreen.kt`** — add a new case alongside `DbBrowser`
   (after line 130):
   ```kotlin
   /** Open-source + proprietary license documentation (list -> detail). */
   data object Licenses : AppScreen
   ```
2. **`App.kt` `when(current)`** — add a branch next to the `DbBrowser` branch
   (~line 358). The `when` is exhaustive over the sealed interface, so this is
   required:
   ```kotlin
   AppScreen.Licenses -> {
       PlatformBackHandler { current = AppScreen.AppSettings }
       com.visorcraft.radioshed.ui.settings.LicensesScreen(
           onBack = { current = AppScreen.AppSettings },
       )
   }
   ```
   (Note: `LicensesScreen` installs its own `PlatformBackHandler` for the
   list/detail toggle; the outer one here is harmless because the inner one wins
   while the screen is composed. To avoid two handlers fighting, OMIT the outer
   `PlatformBackHandler` in this branch and rely on the screen's own — match
   whichever the implementer finds cleaner; the `FolderBrowser` branch at
   App.kt:462 is the precedent for "screen owns its own back handler, no outer
   one". Prefer the FolderBrowser precedent: do NOT add an outer
   `PlatformBackHandler` here.)

   Final form (FolderBrowser precedent):
   ```kotlin
   AppScreen.Licenses -> com.visorcraft.radioshed.ui.settings.LicensesScreen(
       onBack = { current = AppScreen.AppSettings },
   )
   ```
3. **`AppSettingsScreen(...)` call site** in `App.kt` (~line 372-395) — add the
   new callback after `onOpenDbBrowser`:
   ```kotlin
   onOpenLicenses = { current = AppScreen.Licenses },
   ```

---

## 5. `AppSettingsScreen.kt` edits

File:
`composeApp/src/commonMain/kotlin/com/visorcraft/radioshed/ui/settings/AppSettingsScreen.kt`.

### 5a. New parameter (after `onOpenDbBrowser`, line 107)
```kotlin
    onOpenDbBrowser: () -> Unit = {},
    onOpenLicenses: () -> Unit = {},
    onOpenSubsonic: () -> Unit = {},
```
(Default `= {}` keeps the call sites and `@Preview` happy.)

### 5b. Replace `CreditsCard()` body and remove `CREDIT_ENTRIES`
- Change the call `CreditsCard()` at line 721 to `CreditsCard(onOpenLicenses = onOpenLicenses)`.
- Rewrite `CreditsCard` (lines 1175-1238) to a **compact, non-expandable** card
  that keeps the attributions blurb and adds an "Open-source licenses" row:
  ```kotlin
  @Composable
  private fun CreditsCard(onOpenLicenses: () -> Unit) {
      Surface(
          shape = RoundedCornerShape(12.dp),
          color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
          modifier = Modifier.fillMaxWidth(),
      ) {
          Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
              Text(
                  "Credits & open-source",
                  style = MaterialTheme.typography.titleSmall,
                  fontWeight = FontWeight.Medium,
              )
              Spacer(Modifier.height(4.dp))
              Text(
                  text = "Lyrics from LRClib (CC0). Album art via the Cover Art " +
                      "Archive (CC0 / public domain). Genre + rating data from local " +
                      "file tags only — no online fingerprinting, no telemetry; " +
                      "internet usage is opt-in via the Connectivity policy above.",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
              )
              Spacer(Modifier.height(8.dp))
              Surface(
                  modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenLicenses),
                  color = MaterialTheme.colorScheme.surface,
                  shape = RoundedCornerShape(10.dp),
              ) {
                  Row(
                      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                      verticalAlignment = Alignment.CenterVertically,
                  ) {
                      Text(
                          "Open-source licenses",
                          style = MaterialTheme.typography.bodyLarge,
                          modifier = Modifier.weight(1f),
                      )
                      Icon(
                          imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                          contentDescription = null,
                      )
                  }
              }
          }
      }
  }
  ```
  (Add the `Icons.AutoMirrored.Filled.KeyboardArrowRight` import; `Icons` is
  already imported. If the auto-mirrored chevron pulls a new import path, follow
  the `material-icons-extended` package; the artifact already ships every icon.)
- **Delete `CREDIT_ENTRIES`** (lines 1240-1255) entirely. After deletion, confirm
  no other reference remains (`grep -n CREDIT_ENTRIES`). The `ExpandLess` /
  `ExpandMore` icon imports (lines 26-27) may become unused — remove them only if
  unused elsewhere (run the desktop compile, which warns/fails on unused only if
  configured; otherwise leave and let "Optimize Imports" clean up).

### 5c. Rewire the proprietary License row (lines 761-769)
Replace the `openWebUrl("https://github.com/anthropics/radioshed/...")` row with
one that opens the in-app proprietary license. The cleanest path: route through
the same Licenses screen. Two options — implementer picks ONE:

- **Option A (preferred, lowest friction):** the License row also calls
  `onOpenLicenses` (the Licenses screen already lists the proprietary entry as
  its first section). Update subtitle to "Proprietary — tap to read in-app.":
  ```kotlin
  AboutLinkRow(
      title = "License",
      subtitle = "Proprietary — tap to read in-app.",
      onClick = onOpenLicenses,
  )
  ```
- **Option B:** add a dedicated route that opens the Licenses screen with the
  proprietary entry pre-selected. This needs an `initialComponent` param on
  `LicensesScreen` and a parameterized `AppScreen.Licenses` — MORE surface for
  little gain. Default to Option A.

Either way: **the `anthropics` URL is removed.** The corrected canonical URL
`https://github.com/visorcraft/RadioShed/blob/master/LICENSE` lives in the
proprietary entry's `homepage` and is reachable as the optional "View online"
affordance inside the detail view (§3). Do not add a separate outbound link in
About.

---

## 6. `build.gradle.kts` — `generateThirdPartyLicenses` task + packaging wiring

### 6a. Single-source-of-truth strategy (decision, with rationale)

The catalog is **Kotlin in `commonMain`** (`LicenseCatalog.kt`). The Gradle
generator must read the *same* data without a second copy. Three options were
weighed:

1. **Re-declare the data in Groovy/Kotlin DSL** — rejected, two sources of truth.
2. **JavaExec a JVM `main` that references the compiled `commonMain` classes** —
   would require compiling the desktop classpath as a task input and a dedicated
   `main`; heavier wiring, and `data class`/enum classes are simple but pulling
   the Compose-dependent `commonMain` runtime onto a plain `JavaExec` classpath
   is fragile (the catalog file itself has no Compose imports, but it lives in a
   source set that does).
3. **CHOSEN — keep the catalog pure-data and read it from BOTH sides via a tiny
   self-contained generator source set.** The catalog and `ThirdPartyComponent` /
   `License` / `Platform` have **zero Compose / platform imports** (verify: they
   only use stdlib). Therefore put a **thin renderer** next to the generator and
   feed it the catalog by compiling exactly these three files on the generator's
   classpath.

Concretely, the **lowest-friction** implementation that keeps ONE source of
truth:

- Add a new JVM-only Gradle source directory `composeApp/src/licenseGen/kotlin`
  registered as its own `JavaExec`-run set, OR (simpler) put a small renderer
  object `LicenseFileRenderer` + a `fun main()` **inside `desktopMain`** in
  `com/visorcraft/radioshed/licenses/LicenseFileRenderer.kt`. Since `desktopMain`
  already compiles `commonMain` (incl. the catalog) and is a plain JVM target,
  `JavaExec` can run a `main` from the desktop runtime classpath
  (`kotlin.sourceSets.desktopMain` output + runtime). This reuses the catalog
  **directly** — no data duplication. This is the chosen approach.

  > Why this is acceptable under "no new abstractions": `LicenseFileRenderer` is a
  > pure string-builder over the existing catalog (one small file, JVM `main`),
  > not a new runtime abstraction. It reads the license texts from the resources
  > on disk at generation time (it runs at build time, not in-app), so it reads
  > the files directly from
  > `composeApp/src/commonMain/composeResources/files/licenses/<X>.txt` via a
  > path passed as a program argument — it does NOT use the Compose `Res`
  > accessor (which only exists at app runtime).

### 6b. `LicenseFileRenderer.kt` (desktopMain)

`composeApp/src/desktopMain/kotlin/com/visorcraft/radioshed/licenses/LicenseFileRenderer.kt`:

```kotlin
package com.visorcraft.radioshed.licenses

import java.io.File

/**
 * Build-time renderer for THIRD_PARTY_LICENSES. Consumes the same
 * [THIRD_PARTY_COMPONENTS] catalog the in-app Licenses screen uses, so the file
 * and the screen can never drift. Run via the generateThirdPartyLicenses Gradle
 * task; NOT used at app runtime.
 *
 * args[0] = licenses resource dir (…/composeResources/files/licenses)
 * args[1] = repo-root output file (THIRD_PARTY_LICENSES)
 * args[2] = bundled output file (…/composeResources/files/THIRD_PARTY_LICENSES.txt)
 */
fun main(args: Array<String>) {
    val licensesDir = File(args[0])
    val rendered = render(licensesDir)
    File(args[1]).writeText(rendered)
    File(args[2]).apply { parentFile.mkdirs() }.writeText(rendered)
}

internal fun render(licensesDir: File): String = buildString {
    appendLine("RadioShed — Third-Party Licenses")
    appendLine("Generated from LicenseCatalog.kt. Do not edit by hand.")
    appendLine("=".repeat(78))
    appendLine()
    (listOf(RADIOSHED_PROPRIETARY) + THIRD_PARTY_COMPONENTS).forEach { c ->
        appendLine(c.name)
        appendLine("  Coordinate: ${c.coordinate}")
        if (c.version.isNotBlank()) appendLine("  Version:    ${c.version}")
        appendLine("  License:    ${c.license.displayName} (${c.license.spdx})")
        appendLine("  Copyright:  ${c.copyright}")
        appendLine("  Homepage:   ${c.homepage}")
        appendLine("  Platforms:  ${c.platforms.joinToString(", ")}")
        c.notes?.let { appendLine("  Notes:      $it") }
        appendLine()
        val body = File(licensesDir, c.license.textResource.substringAfterLast('/'))
            .takeIf { it.exists() }?.readText().orEmpty()
        appendLine(body.trimEnd())
        appendLine()
        appendLine("-".repeat(78))
        appendLine()
    }
}
```

(Bodies are de-duplicated visually by the header-per-component layout; the spec
explicitly wants "header block … followed by the full license body text,
separated by a rule" — this matches.)

### 6c. Gradle task (mirror `generateBuildInfo`)

Add near the `generateBuildInfo` block (build.gradle.kts ~line 579), using a
`JavaExec` so it runs the desktop `main`:

```kotlin
val licensesResDir = layout.projectDirectory.dir(
    "src/commonMain/composeResources/files/licenses",
)
val thirdPartyLicensesRoot = rootProject.layout.projectDirectory.file("THIRD_PARTY_LICENSES")
val thirdPartyLicensesBundled = layout.projectDirectory.file(
    "src/commonMain/composeResources/files/THIRD_PARTY_LICENSES.txt",
)

val generateThirdPartyLicenses = tasks.register<JavaExec>("generateThirdPartyLicenses") {
    group = "build"
    description = "Render THIRD_PARTY_LICENSES from LicenseCatalog.kt (single source of truth)."
    // Run the desktop main; reuses the compiled commonMain catalog directly.
    val desktopMain = kotlin.targets.getByName("desktop")
        .compilations.getByName("main")
    dependsOn(desktopMain.compileTaskProvider)
    classpath = files(
        desktopMain.output.allOutputs,
        desktopMain.runtimeDependencyFiles,
    )
    mainClass.set("com.visorcraft.radioshed.licenses.LicenseFileRendererKt")
    args(
        licensesResDir.asFile.absolutePath,
        thirdPartyLicensesRoot.asFile.absolutePath,
        thirdPartyLicensesBundled.asFile.absolutePath,
    )
    inputs.dir(licensesResDir)
    // Catalog source is an input so edits re-trigger generation.
    inputs.file("src/commonMain/kotlin/com/visorcraft/radioshed/licenses/LicenseCatalog.kt")
    outputs.file(thirdPartyLicensesRoot)
    outputs.file(thirdPartyLicensesBundled)
}
```

> Implementer: the exact accessor for the desktop compilation's runtime classpath
> may differ across the Kotlin MPP Gradle DSL version
> (`runtimeDependencyFiles` vs `compileDependencyFiles` + output). If the typed
> accessor is awkward, fall back to
> `classpath = configurations.getByName("desktopRuntimeClasspath") + desktopMain.output.allOutputs`.
> Verify with `./gradlew :composeApp:generateThirdPartyLicenses --offline`.

### 6d. Bundled side-car ships in every binary
`thirdPartyLicensesBundled` lives under `composeResources/files/`, so it is
packaged into the APK / desktop jar / iOS framework automatically (same path
mechanism as the license texts). The in-app screen does NOT need to read it;
it renders from the live catalog. The bundled `.txt` exists to satisfy LICENSE
§5.1's "ships inside each release".

### 6e. Desktop side-car next to the binary (`appResourcesRootDir`)
`appResourcesRootDir` is **not currently set**. Add it inside
`nativeDistributions {}` (build.gradle.kts ~line 287, after `targetFormats(...)`):
```kotlin
            appResourcesRootDir.set(project.layout.projectDirectory.dir("desktopAppResources"))
```
Then have the generator ALSO write the aggregate into
`composeApp/desktopAppResources/THIRD_PARTY_LICENSES` so jpackage copies it
beside the binary into the app-image / `.deb` / `.AppImage`. Simplest: add a
4th `args` entry + write target in `main`, OR add a `doLast { copy { … } }` on
the task copying `thirdPartyLicensesRoot` into `desktopAppResources/`. Prefer the
`doLast` copy to avoid threading a 4th arg:
```kotlin
    doLast {
        copy {
            from(thirdPartyLicensesRoot)
            into(project.layout.projectDirectory.dir("desktopAppResources"))
        }
    }
```
Create the `desktopAppResources/` dir (with a `.gitkeep` or the generated file
committed — generated file is fine since it's deterministic).

### 6f. Make packaging depend on the generator
After the task is registered, wire dependencies so the file can't go stale:
```kotlin
tasks.matching {
    it.name in setOf(
        "packageDistributionForCurrentOS",
        "packageReleaseDistributionForCurrentOS",
        "packageDeb", "packageReleaseDeb",
        "packageAppImage", "packageReleaseAppImage",
        "packageMsi", "packageReleaseMsi",
        "packageDmg", "packageReleaseDmg",
    )
}.configureEach { dependsOn(generateThirdPartyLicenses) }
```
Also make `packageSingleFileAppImage` (registered at build.gradle.kts:506)
transitively pick it up — it already `dependsOn("packageAppImage", …)`, which now
depends on the generator, so no extra wiring is needed there.

> Do NOT add the generator as a dependency of `compile*Kotlin*` tasks (unlike
> `generateBuildInfo`): the bundled `.txt` and the texts are checked in, and the
> generator depends on the desktop compile — wiring it upstream of compile would
> create a cycle. The bundled resource is regenerated explicitly / at package
> time, and is committed so the in-app build always has it.

---

## 7. Documentation updates

### 7a. `AGENTS.md` — catalog maintenance rule
Under **"Build & Tooling"** (the bullet list around line 366-384, next to the
"Versions live in `gradle/libs.versions.toml`" bullet), add:
```md
- When adding or bumping a third-party dependency, also update
  `composeApp/src/commonMain/kotlin/com/visorcraft/radioshed/licenses/LicenseCatalog.kt`
  (and add its license text under
  `composeApp/src/commonMain/composeResources/files/licenses/` if a new SPDX id
  appears), then rerun `./gradlew :composeApp:generateThirdPartyLicenses`. The
  catalog is the single source of truth for the in-app About → Licenses screen
  and the generated `THIRD_PARTY_LICENSES` file (LICENSE §5.1). `LicenseCatalogTest`
  fails the build if a shipped coordinate is undocumented.
```

### 7b. `SPEC.md` — VLCJ/GPL legal-review note + feature entry
In **"Appendix C — License posture"** (SPEC.md line 4249-4271), after the
existing bullets and before the closing "A draft proprietary `LICENSE`…"
paragraph, add:
```md
- **About → Licenses screen + `THIRD_PARTY_LICENSES`** fulfil LICENSE §5.1.
  Hand-curated catalog in `licenses/LicenseCatalog.kt` is the single source of
  truth; `./gradlew :composeApp:generateThirdPartyLicenses` renders the release
  file.
- **VLCJ / GPL finding (DESKTOP — flagged for legal/product review):** VLCJ
  4.10.1 (+ vlcj-natives) is **GPL-3.0-or-later** and is linked into the desktop
  build. A GPLv3 library linked into a binary distributed under the proprietary
  LICENSE is a genuine license-compatibility conflict for the desktop target.
  This is a product/legal decision **outside the scope of the licensing-docs
  task**, which only documents it accurately. Resolution options (backend swap,
  dual-licensing from Caprica, etc.) are deferred — do not ship a public desktop
  build until reviewed. Android/iOS are unaffected (VLCJ is desktop-only).
```
If the §20 phased checklist is the tracking mechanism, also add a
`- [x]`/`- [~]` line under the current batch with a one-line cite
(`licenses/LicenseCatalog.kt`, `ui/settings/LicensesScreen.kt`,
`generateThirdPartyLicenses`) per the AGENTS.md SPEC-checkbox convention.

---

## 8. Verification steps (in order)

1. **Primary compile loop (covers all new Kotlin):**
   ```bash
   ./gradlew :composeApp:compileKotlinDesktop --offline
   ```
   Expect EXIT 0. This compiles `commonMain` (catalog, screen, `App.kt`,
   `AppSettingsScreen`, `AppScreen`) + `desktopMain` (`LicenseFileRenderer`).
2. **Run the generator and inspect output:**
   ```bash
   ./gradlew :composeApp:generateThirdPartyLicenses --offline
   ```
   Then read `THIRD_PARTY_LICENSES` at repo root and
   `composeApp/src/commonMain/composeResources/files/THIRD_PARTY_LICENSES.txt`;
   confirm VLCJ (GPL-3.0-or-later), jaudiotagger (LGPL-2.1), JNA (Apache-2.0
   w/ dual note), sqlite-jdbc (Apache + BSD-2 note), MaterialKolor (MIT), and the
   proprietary entry are present with full bodies. Confirm
   `composeApp/desktopAppResources/THIRD_PARTY_LICENSES` was also written.
3. **Catalog test:** add
   `composeApp/src/commonTest/kotlin/com/visorcraft/radioshed/licenses/LicenseCatalogTest.kt`
   asserting:
   - Every component's `license.textResource` resolves to a non-empty file:
     `Res.readBytes(component.license.textResource).decodeToString().isNotBlank()`
     for each distinct `License` (wrap in `runTest` / `runBlocking` — `readBytes`
     is `suspend`; use `kotlinx-coroutines-test` already on the `commonTest`
     classpath).
   - Every distinct `License` enum value's text resource exists and is non-empty.
   - **Coverage assertion:** a hardcoded `EXPECTED_SHIPPED_COORDINATES` set
     (the `shipped[]` graph minus `excludedNotShipped` minus the whitelisted
     folded-transitive leaves enumerated in §1b) is a subset of
     `THIRD_PARTY_COMPONENTS.map { it.coordinate }.toSet()`. This fails the build
     if a shipped dependency is added without a catalog entry.
   - No `excludedNotShipped` coordinate appears in the catalog (test the negative).
   Run:
   ```bash
   ./gradlew :composeApp:desktopTest --offline
   ```
   (`desktopTest` confirmed to exist; `commonTest` runs under it.) If
   `Res.readBytes` is not resolvable from the test JVM in this offline env, fall
   back to asserting catalog invariants only (every component maps to a
   non-PROPRIETARY-or-known `License`, coverage subset holds) and keep the
   resource-existence check as a filesystem check against
   `src/commonMain/composeResources/files/licenses/`.
4. **Grep guards:**
   ```bash
   grep -rn "anthropics/radioshed" composeApp/ ; # expect: no matches
   grep -rn "CREDIT_ENTRIES" composeApp/ ;        # expect: no matches
   ```
5. (Optional, env-permitting) `./gradlew :composeApp:run` to eyeball the
   About → Open-source licenses screen and the proprietary License detail.
   Desktop packaging (`packageDeb` / `packageSingleFileAppImage`) to confirm the
   side-car lands beside the binary — only if `dpkg-deb`/`appimagetool` available.

---

## 9. Open questions

- **Proprietary-license in-app copy scope (spec §5.4 default, flagged for
  confirmation):** plan ships `LICENSE` lines 1-321 (through `END OF AGREEMENT`),
  excluding the maintainer-reminders footer, keeping the DRAFT banner. Confirm
  this is the intended user-facing scope before implementing
  `RadioShed-Proprietary.txt`.
- **License row routing (§5c):** Option A (License row reuses `onOpenLicenses`,
  proprietary entry pinned first in the list) vs Option B (dedicated
  pre-selected route). Plan defaults to Option A; confirm.
- **NOTICE files (Apache-2.0 §4(d)):** Media3 and Coil ship upstream `NOTICE`
  files. Spec §4 says render NOTICE above the shared body where present. This
  plan folds NOTICE acknowledgment into `notes` strings rather than bundling the
  raw upstream NOTICE files. Confirm whether verbatim NOTICE files must also be
  bundled (would add `files/notices/<artifact>-NOTICE.txt` resources + a
  `noticeResource` field on the affected components).
- **VLCJ/GPL desktop conflict (spec §8):** documented and flagged for separate
  legal/product review; explicitly NOT resolved here. Confirm no public desktop
  release ships before that review.
- **`appResourcesRootDir` interaction with existing packaging:** the dir is newly
  introduced; verify it does not disturb the existing icon/jlink config in
  `nativeDistributions {}` (it should only ADD a side-car copy step).
