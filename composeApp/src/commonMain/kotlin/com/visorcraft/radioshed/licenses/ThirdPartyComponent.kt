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
    /**
     * Secondary license texts that must be reproduced alongside the primary
     * [license] for layered components (e.g. sqlite-jdbc carries BSD-2-Clause
     * for the Zentus-derived code; Skiko bundles Skia under BSD-3-Clause).
     * BSD reproduces its copyright notice and conditions in binary
     * distributions, so these bodies are rendered after the primary one in
     * both the in-app detail view and the generated THIRD_PARTY_LICENSES.
     */
    val additionalLicenses: List<License> = emptyList(),
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

    /**
     * RadioShed's own proprietary license. Not a third-party OSS license; modeled
     * here so the proprietary text reuses the same list -> detail rendering path.
     * Its [textResource] is the bundled LICENSE body (see the licenses resources).
     */
    PROPRIETARY(
        "LicenseRef-RadioShed-Proprietary",
        "RadioShed Proprietary License",
        "files/licenses/RadioShed-Proprietary.txt",
    ),
}
