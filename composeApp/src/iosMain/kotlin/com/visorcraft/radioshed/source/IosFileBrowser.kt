package com.visorcraft.radioshed.source

/**
 * iOS stub. iOS filesystem browsing for music apps goes through
 * `UIDocumentPickerViewController` rather than free-form path navigation
 * (the OS doesn't let apps see the global filesystem the way Android does).
 * When the iOS work lands, replace this with a SwiftUI bridge that
 * presents the document picker and translates the picked URL into a
 * security-scoped bookmark we can persist.
 */
class IosFileBrowser : FileBrowser {
    override suspend fun listSubdirectories(path: String): List<DiscoveredFolder> = emptyList()

    override fun parentOf(path: String): String? = null
}
