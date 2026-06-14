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
 * args[3] = desktop side-car file (…/desktopAppResources/THIRD_PARTY_LICENSES),
 *           copied beside the binary by jpackage via appResourcesRootDir
 */
fun main(args: Array<String>) {
    val licensesDir = File(args[0])
    val rendered = render(licensesDir)
    args.drop(1).forEach { File(it).apply { parentFile.mkdirs() }.writeText(rendered) }
}

internal fun render(licensesDir: File): String =
    buildString {
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
            // Render the primary license body, then any secondary bodies layered
            // components must reproduce (e.g. sqlite-jdbc -> BSD-2-Clause, Skiko ->
            // BSD-3-Clause), so the in-app screen and this file disclose them inline.
            (listOf(c.license) + c.additionalLicenses).forEach { license ->
                val body =
                    File(licensesDir, license.textResource.substringAfterLast('/'))
                        .takeIf { it.exists() }?.readText().orEmpty()
                appendLine(body.trimEnd())
                appendLine()
            }
            appendLine("-".repeat(78))
            appendLine()
        }
    }
