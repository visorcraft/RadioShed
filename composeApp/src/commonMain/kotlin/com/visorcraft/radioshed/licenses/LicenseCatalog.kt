package com.visorcraft.radioshed.licenses

import com.visorcraft.radioshed.licenses.License.APACHE_2_0
import com.visorcraft.radioshed.licenses.License.BSD_2_CLAUSE
import com.visorcraft.radioshed.licenses.License.BSD_3_CLAUSE
import com.visorcraft.radioshed.licenses.License.GPL_3_0_ONLY
import com.visorcraft.radioshed.licenses.License.LGPL_2_1_OR_LATER
import com.visorcraft.radioshed.licenses.License.MIT
import com.visorcraft.radioshed.licenses.Platform.ALL
import com.visorcraft.radioshed.licenses.Platform.ANDROID
import com.visorcraft.radioshed.licenses.Platform.DESKTOP
import com.visorcraft.radioshed.licenses.Platform.IOS

/**
 * RadioShed's GPL-3.0-only license, surfaced through the same Licenses
 * screen as the third-party components. Rendered from the bundled GPL text.
 */
val RADIOSHED: ThirdPartyComponent =
    ThirdPartyComponent(
        name = "RadioShed",
        coordinate = "com.visorcraft:radioshed",
        version = "", // filled at render time from BuildInfo.VERSION_NAME
        license = GPL_3_0_ONLY,
        copyright = "Copyright (C) 2026 VisorCraft LLC",
        platforms = setOf(ALL),
        homepage = "https://github.com/visorcraft/RadioShed/blob/master/LICENSE",
        notes =
            "RadioShed is GPL-3.0-only. The text below is the GNU General Public " +
                "License version 3. The canonical file is LICENSE in the repository.",
    )

/**
 * Hand-curated, ordered list of every shipped third-party component, grouped
 * conceptually by platform. Single source of truth for the Licenses screen and
 * the generated THIRD_PARTY_LICENSES file. Test-only / tooling-only / unwired
 * deps are intentionally excluded (see LicenseCatalogTest).
 */
val THIRD_PARTY_COMPONENTS: List<ThirdPartyComponent> =
    listOf(
        // ---- Common (all shipped targets) ----
        ThirdPartyComponent(
            name = "Kotlin Standard Library",
            coordinate = "org.jetbrains.kotlin:kotlin-stdlib",
            version = "2.4.21",
            license = APACHE_2_0,
            copyright = "Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors",
            platforms = setOf(ALL),
            homepage = "https://kotlinlang.org/",
            transitive = true,
            notes = "Pulled in transitively by the Kotlin Multiplatform plugin (kotlin = 2.4.21).",
        ),
        ThirdPartyComponent(
            name = "kotlinx-coroutines-core",
            coordinate = "org.jetbrains.kotlinx:kotlinx-coroutines-core",
            version = "1.11.0",
            license = APACHE_2_0,
            copyright = "Copyright 2000-2020 JetBrains s.r.o. and Kotlin Programming Language contributors",
            platforms = setOf(ALL),
            homepage = "https://github.com/Kotlin/kotlinx.coroutines",
        ),
        ThirdPartyComponent(
            name = "kotlinx-serialization-json",
            coordinate = "org.jetbrains.kotlinx:kotlinx-serialization-json",
            version = "1.12.0",
            license = APACHE_2_0,
            copyright = "Copyright 2017-2019 JetBrains s.r.o.",
            platforms = setOf(ALL),
            homepage = "https://github.com/Kotlin/kotlinx.serialization",
        ),
        ThirdPartyComponent(
            name = "Compose Multiplatform Runtime",
            coordinate = "org.jetbrains.compose.runtime:runtime",
            version = "1.12.2",
            license = APACHE_2_0,
            copyright = "Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers",
            platforms = setOf(ALL),
            homepage = "https://github.com/JetBrains/compose-multiplatform",
        ),
        ThirdPartyComponent(
            name = "Compose Multiplatform Foundation",
            coordinate = "org.jetbrains.compose.foundation:foundation",
            version = "1.12.2",
            license = APACHE_2_0,
            copyright = "Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers",
            platforms = setOf(ALL),
            homepage = "https://github.com/JetBrains/compose-multiplatform",
        ),
        ThirdPartyComponent(
            name = "Compose Multiplatform UI",
            coordinate = "org.jetbrains.compose.ui:ui",
            version = "1.12.2",
            license = APACHE_2_0,
            copyright = "Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers",
            platforms = setOf(ALL),
            homepage = "https://github.com/JetBrains/compose-multiplatform",
            notes =
                "On Desktop the ui artifact transitively pulls Skiko. AndroidX-derived " +
                    "sources carry 'The Android Open Source Project' headers.",
        ),
        ThirdPartyComponent(
            name = "Compose Multiplatform Components Resources",
            coordinate = "org.jetbrains.compose.components:components-resources",
            version = "1.12.2",
            license = APACHE_2_0,
            copyright = "Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers",
            platforms = setOf(ALL),
            homepage = "https://github.com/JetBrains/compose-multiplatform",
            notes = "Provides the generated Res accessor used to bundle the license texts.",
        ),
        ThirdPartyComponent(
            name = "Compose Multiplatform UI Tooling Preview",
            coordinate = "org.jetbrains.compose.ui:ui-tooling-preview",
            version = "1.12.2",
            license = APACHE_2_0,
            copyright = "Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers",
            platforms = setOf(ALL),
            homepage = "https://github.com/JetBrains/compose-multiplatform",
            notes =
                "The @Preview annotation/runtime artifact; ships. Distinct from compose " +
                    "ui-tooling (debug/desktop inspector), which is tooling-only and excluded.",
        ),
        ThirdPartyComponent(
            name = "Compose Multiplatform Material3",
            coordinate = "org.jetbrains.compose.material3:material3",
            version = "1.12.0-alpha03",
            license = APACHE_2_0,
            copyright = "Copyright 2021 The Android Open Source Project",
            platforms = setOf(ALL),
            homepage = "https://github.com/JetBrains/compose-multiplatform",
            notes =
                "Versioned independently of Compose (material3 = 1.12.0-alpha03). JetBrains' " +
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
            notes =
                "Frozen at 1.7.3 (no longer shipped past that by Compose Multiplatform). " +
                    "Generated Material icon sources are AOSP-derived.",
        ),
        ThirdPartyComponent(
            name = "AndroidX Lifecycle ViewModel Compose (multiplatform)",
            coordinate = "org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose",
            version = "2.11.0",
            license = APACHE_2_0,
            copyright = "Copyright 2023 The Android Open Source Project",
            platforms = setOf(ALL),
            homepage = "https://github.com/JetBrains/compose-multiplatform",
            notes = "JetBrains' KMP port of AndroidX Lifecycle; verbatim AOSP headers.",
        ),
        ThirdPartyComponent(
            name = "AndroidX Lifecycle Runtime Compose (multiplatform)",
            coordinate = "org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose",
            version = "2.11.0",
            license = APACHE_2_0,
            copyright = "Copyright 2023 The Android Open Source Project",
            platforms = setOf(ALL),
            homepage = "https://github.com/JetBrains/compose-multiplatform",
        ),
        ThirdPartyComponent(
            name = "SQLDelight Runtime",
            coordinate = "app.cash.sqldelight:runtime",
            version = "2.4.1",
            license = APACHE_2_0,
            copyright = "Copyright 2016 Square, Inc. (now Cash App / Block, Inc.)",
            platforms = setOf(ALL),
            homepage = "https://github.com/cashapp/sqldelight",
            transitive = true,
        ),
        ThirdPartyComponent(
            name = "SQLDelight Coroutines Extensions",
            coordinate = "app.cash.sqldelight:coroutines-extensions",
            version = "2.4.1",
            license = APACHE_2_0,
            copyright = "Copyright 2016 Square, Inc. (now Cash App / Block, Inc.)",
            platforms = setOf(ALL),
            homepage = "https://github.com/cashapp/sqldelight",
        ),
        ThirdPartyComponent(
            name = "SQLDelight Primitive Adapters",
            coordinate = "app.cash.sqldelight:primitive-adapters",
            version = "2.4.1",
            license = APACHE_2_0,
            copyright = "Copyright 2016 Square, Inc. (now Cash App / Block, Inc.)",
            platforms = setOf(ALL),
            homepage = "https://github.com/cashapp/sqldelight",
        ),
        ThirdPartyComponent(
            name = "Koin Core",
            coordinate = "io.insert-koin:koin-core",
            version = "4.2.2",
            license = APACHE_2_0,
            copyright = "Copyright 2017-present Arnaud Giuliani",
            platforms = setOf(ALL),
            homepage = "https://github.com/InsertKoinIO/koin",
        ),
        ThirdPartyComponent(
            name = "Koin Compose",
            coordinate = "io.insert-koin:koin-compose",
            version = "4.2.2",
            license = APACHE_2_0,
            copyright = "Copyright 2017-present Arnaud Giuliani",
            platforms = setOf(ALL),
            homepage = "https://github.com/InsertKoinIO/koin",
        ),
        ThirdPartyComponent(
            name = "MaterialKolor",
            coordinate = "com.materialkolor:material-kolor",
            version = "5.0.1",
            license = MIT,
            copyright = "Copyright (c) 2025 Jordon de Hoog",
            platforms = setOf(ALL),
            homepage = "https://github.com/jordond/MaterialKolor",
            notes =
                "Bundles Google's material-color-utilities (Apache-2.0), which retains its " +
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
            version = "3.6.3",
            license = APACHE_2_0,
            copyright = "Copyright 2025 Coil Contributors",
            platforms = setOf(ALL),
            homepage = "https://github.com/coil-kt/coil",
            notes =
                "Transitively pulls coil, coil-core, coil-compose-core (all Apache-2.0, " +
                    "Coil Contributors) and okio (Apache-2.0, Square).",
        ),
        // ---- Desktop only ----
        ThirdPartyComponent(
            name = "kotlinx-coroutines-swing",
            coordinate = "org.jetbrains.kotlinx:kotlinx-coroutines-swing",
            version = "1.11.0",
            license = APACHE_2_0,
            copyright = "Copyright 2000-2020 JetBrains s.r.o. and Kotlin Programming Language contributors",
            platforms = setOf(DESKTOP),
            homepage = "https://github.com/Kotlin/kotlinx.coroutines",
            notes = "Provides Dispatchers.Swing on the desktop target.",
        ),
        ThirdPartyComponent(
            name = "Skiko (Kotlin Skia bindings)",
            coordinate = "org.jetbrains.skiko:skiko",
            version = "0.150.2",
            license = APACHE_2_0,
            copyright = "Copyright JetBrains s.r.o. and respective authors and developers",
            platforms = setOf(DESKTOP, IOS),
            homepage = "https://github.com/JetBrains/skiko",
            transitive = true,
            notes =
                "Transitive via Compose. Bundles Google's Skia graphics engine, licensed " +
                    "BSD-3-Clause: 'Copyright (c) 2011 Google Inc. All rights reserved.' — see " +
                    "files/licenses/BSD-3-Clause.txt.",
            additionalLicenses = listOf(BSD_3_CLAUSE),
        ),
        ThirdPartyComponent(
            name = "Compose Desktop Runtime (Linux x86_64)",
            coordinate = "org.jetbrains.compose.desktop:desktop-jvm-linux-x64",
            version = "1.12.2",
            license = APACHE_2_0,
            copyright = "Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers",
            platforms = setOf(DESKTOP),
            homepage = "https://github.com/JetBrains/compose-multiplatform",
            notes =
                "OS-specific desktop runtime artifact selected by compose.desktop.currentOs. " +
                    "Provides the Swing window, application lifecycle, and menu bar on Linux.",
        ),
        ThirdPartyComponent(
            name = "Compose Desktop Runtime (Windows x86_64)",
            coordinate = "org.jetbrains.compose.desktop:desktop-jvm-windows-x64",
            version = "1.12.2",
            license = APACHE_2_0,
            copyright = "Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers",
            platforms = setOf(DESKTOP),
            homepage = "https://github.com/JetBrains/compose-multiplatform",
            notes =
                "OS-specific desktop runtime artifact selected by compose.desktop.currentOs. " +
                    "Provides the Swing window, application lifecycle, and menu bar on Windows.",
        ),
        ThirdPartyComponent(
            name = "Compose Desktop Runtime (macOS x86_64)",
            coordinate = "org.jetbrains.compose.desktop:desktop-jvm-macos-x64",
            version = "1.12.2",
            license = APACHE_2_0,
            copyright = "Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers",
            platforms = setOf(DESKTOP),
            homepage = "https://github.com/JetBrains/compose-multiplatform",
            notes =
                "OS-specific desktop runtime artifact selected by compose.desktop.currentOs. " +
                    "Provides the Swing window, application lifecycle, and menu bar on Intel Macs.",
        ),
        ThirdPartyComponent(
            name = "Compose Desktop Runtime (macOS ARM64)",
            coordinate = "org.jetbrains.compose.desktop:desktop-jvm-macos-arm64",
            version = "1.12.2",
            license = APACHE_2_0,
            copyright = "Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers",
            platforms = setOf(DESKTOP),
            homepage = "https://github.com/JetBrains/compose-multiplatform",
            notes =
                "OS-specific desktop runtime artifact selected by compose.desktop.currentOs. " +
                    "Provides the Swing window, application lifecycle, and menu bar on Apple Silicon Macs.",
        ),
        ThirdPartyComponent(
            name = "SQLDelight SQLite Driver (JVM)",
            coordinate = "app.cash.sqldelight:sqlite-driver",
            version = "2.4.1",
            license = APACHE_2_0,
            copyright = "Copyright 2016 Square, Inc. (now Cash App / Block, Inc.)",
            platforms = setOf(DESKTOP),
            homepage = "https://github.com/cashapp/sqldelight",
            notes = "Pulls org.xerial:sqlite-jdbc transitively (separate entry).",
        ),
        ThirdPartyComponent(
            name = "SQLite JDBC (sqlite-jdbc)",
            coordinate = "org.xerial:sqlite-jdbc",
            version = "3.53.4.0",
            license = APACHE_2_0,
            copyright = "Copyright (c) Taro L. Saito (xerial) and contributors",
            platforms = setOf(DESKTOP),
            homepage = "https://github.com/xerial/sqlite-jdbc",
            transitive = true,
            notes =
                "DUAL/LAYERED: the wrapper is Apache-2.0; the derived Zentus SQLite JDBC code is " +
                    "BSD-2-Clause ('Copyright (c) 2006 David Crawshaw'); the bundled native SQLite engine " +
                    "is PUBLIC DOMAIN. SPDX: Apache-2.0 AND BSD-2-Clause. See files/licenses/BSD-2-Clause.txt.",
            additionalLicenses = listOf(BSD_2_CLAUSE),
        ),
        ThirdPartyComponent(
            name = "jaudiotagger",
            coordinate = "net.jthink:jaudiotagger",
            version = "3.0.1",
            license = LGPL_2_1_OR_LATER,
            copyright = "Copyright (C) Paul Taylor / JThink Ltd (jthink.net)",
            platforms = setOf(ANDROID, DESKTOP),
            homepage = "https://bitbucket.org/ijabz/jaudiotagger",
            notes =
                "Declared in both androidMain and desktopMain. Source header states LGPL 2.1 " +
                    "'or (at your option) any later version'.",
        ),
        ThirdPartyComponent(
            name = "JNA (jna-jpms)",
            coordinate = "net.java.dev.jna:jna-jpms",
            version = "5.19.1",
            license = APACHE_2_0,
            copyright =
                "Copyright (c) Timothy Wall and the JNA Development Team (incl. Matthias " +
                    "Bläsing) and contributors",
            platforms = setOf(DESKTOP),
            homepage = "https://github.com/java-native-access/jna",
            notes =
                "DESKTOP only; never linked into the Android APK. Direct dependency: the " +
                    "foreign-function binding in NativeLibVlc.kt loads the system libvlc 3.x through it. " +
                    "Dual-licensed 'LGPL-2.1-or-later OR Apache-2.0'; RadioShed relies on the Apache-2.0 " +
                    "option.",
        ),
        ThirdPartyComponent(
            name = "JNA Platform (jna-platform-jpms)",
            coordinate = "net.java.dev.jna:jna-platform-jpms",
            version = "5.19.1",
            license = APACHE_2_0,
            copyright =
                "Copyright (c) Timothy Wall and the JNA Development Team (incl. Matthias " +
                    "Bläsing) and contributors",
            platforms = setOf(DESKTOP),
            homepage = "https://github.com/java-native-access/jna",
            notes =
                "DESKTOP only. Direct dependency: supplies com.sun.jna.platform.win32 " +
                    "(Advapi32Util) used to read the Windows VLC install dir from the registry during " +
                    "libvlc discovery. Dual-licensed 'LGPL-2.1-or-later OR Apache-2.0'; RadioShed relies " +
                    "on the Apache-2.0 option.",
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
            notes =
                "EXTERNAL, NOT BUNDLED. User/OS-installed native runtime discovered at startup and " +
                    "bound directly via JNA (NativeLibVlc.kt). LGPL obligations satisfied by the user's " +
                    "own system install.",
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
            version = "2.4.1",
            license = APACHE_2_0,
            copyright = "Copyright 2016 Square, Inc. (now Cash App / Block, Inc.)",
            platforms = setOf(ANDROID),
            homepage = "https://github.com/cashapp/sqldelight",
        ),
        ThirdPartyComponent(
            name = "requery sqlite-android",
            coordinate = "com.github.requery:sqlite-android",
            version = "3.49.0",
            license = APACHE_2_0,
            copyright =
                "Copyright (c) 2017-2025 requery.io (Nikhil Purushe); portions Copyright (C) " +
                    "2005-2012 The Android Open Source Project",
            platforms = setOf(ANDROID),
            homepage = "https://github.com/requery/sqlite-android",
            notes = "Wrapper is Apache-2.0; BUNDLES the native SQLite engine, which is PUBLIC DOMAIN.",
        ),
        ThirdPartyComponent(
            name = "Koin Android",
            coordinate = "io.insert-koin:koin-android",
            version = "4.2.2",
            license = APACHE_2_0,
            copyright = "Copyright 2017-present Arnaud Giuliani",
            platforms = setOf(ANDROID),
            homepage = "https://github.com/InsertKoinIO/koin",
        ),
        ThirdPartyComponent(
            name = "AndroidX Media3 ExoPlayer",
            coordinate = "androidx.media3:media3-exoplayer",
            version = "1.11.1",
            license = APACHE_2_0,
            copyright = "Copyright The Android Open Source Project",
            platforms = setOf(ANDROID),
            homepage = "https://github.com/androidx/media",
            notes =
                "The Android Open Source Project, Apache-2.0. The published AAR bundles no " +
                    "separate NOTICE file.",
        ),
        ThirdPartyComponent(
            name = "AndroidX Media3 Session",
            coordinate = "androidx.media3:media3-session",
            version = "1.11.1",
            license = APACHE_2_0,
            copyright = "Copyright The Android Open Source Project",
            platforms = setOf(ANDROID),
            homepage = "https://github.com/androidx/media",
        ),
        ThirdPartyComponent(
            name = "AndroidX Media3 Common",
            coordinate = "androidx.media3:media3-common",
            version = "1.11.1",
            license = APACHE_2_0,
            copyright = "Copyright The Android Open Source Project",
            platforms = setOf(ANDROID),
            homepage = "https://github.com/androidx/media",
        ),
        // ---- iOS only ----
        ThirdPartyComponent(
            name = "SQLDelight Native Driver",
            coordinate = "app.cash.sqldelight:native-driver",
            version = "2.4.1",
            license = APACHE_2_0,
            copyright = "Copyright 2016 Square, Inc. (now Cash App / Block, Inc.)",
            platforms = setOf(IOS),
            homepage = "https://github.com/cashapp/sqldelight",
            notes = "Kotlin/Native SqlDriver; pulls co.touchlab sqliter-driver + stately (Apache-2.0).",
        ),
    )
