package com.visorcraft.radioshed.source

import com.visorcraft.radioshed.data.IODispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

/**
 * Shared JVM file enumerator for Android and desktop. Both targets walk
 * local paths via [java.io.File] with identical glob + format filtering.
 */
class JvmFileEnumerator : FileEnumerator {
    override fun enumerate(
        rootUri: String,
        recursive: Boolean,
        filter: SourceFilter,
    ): Flow<DiscoveredTrack> =
        flow {
            val root = File(rootUri)
            if (!root.exists() || !root.isDirectory) return@flow
            val rootAbs = root.canonicalFile

            // Compile each glob once per scan, not once per file. A large
            // library walk visits tens of thousands of files; recompiling the
            // include/exclude patterns for every one of them was pure waste.
            val excludeRegexes = filter.excludeGlobs.map(::globToRegex)
            val includeRegexes = filter.includeGlobs.map(::globToRegex)

            val sequence =
                if (recursive) {
                    rootAbs.walkTopDown()
                } else {
                    rootAbs.listFiles()?.asSequence() ?: emptySequence()
                }
            for (file in sequence) {
                if (!file.isFile) continue
                val ext = file.name.audioExtensionOrNull() ?: continue
                if (filter.formats.isNotEmpty() && ext !in filter.formats) continue

                val relativePath = relativizeUnderRoot(rootAbs, file)
                if (excludeRegexes.any { it.matches(relativePath) }) continue
                if (includeRegexes.isNotEmpty() &&
                    includeRegexes.none { it.matches(relativePath) }
                ) {
                    continue
                }

                val ctime = readCtimeMillis(file)
                emit(
                    DiscoveredTrack(
                        uri = file.absolutePath,
                        fileName = file.name,
                        fileSize = file.length(),
                        fileCtime = ctime ?: file.lastModified(),
                        fileMtime = file.lastModified(),
                        format = ext,
                    ),
                )
            }
        }.flowOn(IODispatcher)

    private fun relativizeUnderRoot(
        root: File,
        file: File,
    ): String {
        val rootPath = root.absolutePath
        val filePath = file.absolutePath
        val rel =
            if (filePath.startsWith(rootPath)) {
                filePath.substring(rootPath.length).trimStart(File.separatorChar)
            } else {
                file.name
            }
        return rel.replace(File.separatorChar, '/')
    }

    private fun readCtimeMillis(file: File): Long? =
        runCatching {
            val attrs: BasicFileAttributes =
                Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            attrs.creationTime().toMillis().takeIf { it > 0 }
        }.getOrNull()
}
