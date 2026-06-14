package com.visorcraft.radioshed.licenses

import kotlinx.coroutines.test.runTest
import radioshed.composeapp.generated.resources.Res
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the hand-curated license catalog ([THIRD_PARTY_COMPONENTS]) — the
 * single source of truth for the in-app Licenses screen and the generated
 * `THIRD_PARTY_LICENSES` file. Fails the build if a shipped coordinate is
 * undocumented, a test/tooling-only dep leaks in, or a license text resource
 * is missing.
 */
class LicenseCatalogTest {
    @Test
    fun everyLicenseTextResourceResolvesAndIsNonEmpty() =
        runTest {
            License.entries.forEach { license ->
                val body = Res.readBytes(license.textResource).decodeToString()
                assertTrue(
                    body.isNotBlank(),
                    "License text for ${license.spdx} (${license.textResource}) is empty/missing.",
                )
            }
        }

    @Test
    fun everyComponentBodyResolves() =
        runTest {
            (listOf(RADIOSHED_PROPRIETARY) + THIRD_PARTY_COMPONENTS).forEach { component ->
                val body = Res.readBytes(component.license.textResource).decodeToString()
                assertTrue(
                    body.isNotBlank(),
                    "License body for ${component.coordinate} is empty/missing.",
                )
            }
        }

    @Test
    fun everyShippedCoordinateIsDocumented() {
        val documented = THIRD_PARTY_COMPONENTS.map { it.coordinate }.toSet()
        val missing = EXPECTED_SHIPPED_COORDINATES - documented
        assertTrue(missing.isEmpty(), "Shipped but undocumented: $missing")
    }

    @Test
    fun noExcludedCoordinateLeaksIntoCatalog() {
        val documented = THIRD_PARTY_COMPONENTS.map { it.coordinate }.toSet()
        val leaked = EXCLUDED_NOT_SHIPPED.intersect(documented)
        assertTrue(leaked.isEmpty(), "Test/tooling/unwired deps must not be documented: $leaked")
    }

    @Test
    fun coordinatesAreUnique() {
        val coordinates = THIRD_PARTY_COMPONENTS.map { it.coordinate }
        assertEquals(coordinates.size, coordinates.toSet().size, "Duplicate coordinate in catalog.")
    }
}

/**
 * Declared / standalone shipped coordinates that MUST be documented. Deep
 * transitive Apache-2.0 leaves folded into a parent component's `notes` (per
 * the catalog) are intentionally NOT listed here — only coordinates a reader
 * would expect to find as their own entry.
 */
private val EXPECTED_SHIPPED_COORDINATES: Set<String> =
    setOf(
        // Common
        "org.jetbrains.kotlin:kotlin-stdlib",
        "org.jetbrains.kotlinx:kotlinx-coroutines-core",
        "org.jetbrains.kotlinx:kotlinx-serialization-json",
        "org.jetbrains.compose.runtime:runtime",
        "org.jetbrains.compose.foundation:foundation",
        "org.jetbrains.compose.ui:ui",
        "org.jetbrains.compose.components:components-resources",
        "org.jetbrains.compose.ui:ui-tooling-preview",
        "org.jetbrains.compose.material3:material3",
        "org.jetbrains.compose.material:material-icons-extended",
        "org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose",
        "org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose",
        "app.cash.sqldelight:runtime",
        "app.cash.sqldelight:coroutines-extensions",
        "app.cash.sqldelight:primitive-adapters",
        "io.insert-koin:koin-core",
        "io.insert-koin:koin-compose",
        "com.materialkolor:material-kolor",
        "com.russhwolf:multiplatform-settings-no-arg",
        "com.russhwolf:multiplatform-settings-coroutines",
        "io.coil-kt.coil3:coil-compose",
        // Desktop
        "org.jetbrains.kotlinx:kotlinx-coroutines-swing",
        "org.jetbrains.skiko:skiko",
        "app.cash.sqldelight:sqlite-driver",
        "org.xerial:sqlite-jdbc",
        "net.jthink:jaudiotagger",
        "net.java.dev.jna:jna-jpms",
        "net.java.dev.jna:jna-platform-jpms",
        // Android
        "androidx.activity:activity-compose",
        "app.cash.sqldelight:android-driver",
        "com.github.requery:sqlite-android",
        "io.insert-koin:koin-android",
        "androidx.media3:media3-exoplayer",
        "androidx.media3:media3-session",
        "androidx.media3:media3-common",
        // iOS
        "app.cash.sqldelight:native-driver",
    )

/** Test/tooling-only and declared-but-unwired deps that must NOT be documented. */
private val EXCLUDED_NOT_SHIPPED: Set<String> =
    setOf(
        "org.jetbrains.kotlin:kotlin-test",
        "org.jetbrains.kotlin:kotlin-test-junit",
        "junit:junit",
        "androidx.test.ext:junit",
        "androidx.test.espresso:espresso-core",
        "org.jetbrains.kotlinx:kotlinx-coroutines-test",
        "org.jetbrains.compose.ui:ui-tooling",
        "app.cash.sqldelight:sqlite-3-38-dialect",
        "androidx.core:core-ktx",
        "androidx.appcompat:appcompat",
        // Replaced by the direct JNA libvlc binding (feature/jna-libvlc); the
        // GPL VLCJ layer no longer ships and must not return to the catalog.
        "uk.co.caprica:vlcj",
        "uk.co.caprica:vlcj-natives",
    )
