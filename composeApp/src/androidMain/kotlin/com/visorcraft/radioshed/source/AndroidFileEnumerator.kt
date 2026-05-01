package com.visorcraft.radioshed.source

import com.visorcraft.radioshed.data.IODispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

/**
 * Walks a local filesystem path on Android using [java.io.File]. Requires
 * `MANAGE_EXTERNAL_STORAGE` to be granted, otherwise reads return empty.
 *
 * Future work: SAF tree-URI fallback (`DocumentFile`) when MEMS is denied.
 * For v1 we rely on the all-files permission since RadioShed is sideloaded
 * via direct download — Play Store flavor would need the SAF path.
 */
class AndroidFileEnumerator : FileEnumerator {

    override fun enumerate(
        rootUri: String,
        recursive: Boolean,
        filter: SourceFilter,
    ): Flow<DiscoveredTrack> = flow {
        val root = File(rootUri)
        if (!root.exists() || !root.isDirectory) return@flow
        // Resolve once so the prefix-strip below is reliable even if the
        // input path was relative or contained `..`.
        val rootAbs = root.canonicalFile

        val sequence = if (recursive) rootAbs.walkTopDown() else rootAbs.listFiles()?.asSequence() ?: emptySequence()
        for (file in sequence) {
            if (!file.isFile) continue
            val ext = file.name.audioExtensionOrNull() ?: continue
            if (filter.formats.isNotEmpty() && ext !in filter.formats) continue

            // Globs are matched against the path *relative to the folder root*,
            // not the absolute path. So a folder rooted at /storage/emulated/0/Music
            // with exclude glob "Spoken Word/**" will exclude
            // /storage/emulated/0/Music/Spoken Word/anything as expected.
            val relativePath = relativizeUnderRoot(rootAbs, file)
            if (filter.excludeGlobs.any { matchesGlob(relativePath, it) }) continue
            if (filter.includeGlobs.isNotEmpty() &&
                filter.includeGlobs.none { matchesGlob(relativePath, it) }
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

    /**
     * Returns [file]'s path relative to [root], using forward slashes
     * regardless of host platform so user-supplied globs are portable.
     * Returns the bare file name if [file] is somehow not under [root].
     */
    private fun relativizeUnderRoot(root: File, file: File): String {
        val rootPath = root.absolutePath
        val filePath = file.absolutePath
        val rel = if (filePath.startsWith(rootPath)) {
            filePath.substring(rootPath.length).trimStart(File.separatorChar)
        } else {
            file.name
        }
        return rel.replace(File.separatorChar, '/')
    }

    /**
     * BasicFileAttributes.creationTime() — falls back to lastModified for
     * filesystems that don't track ctime separately. Wrapped in runCatching
     * because some Android filesystems throw on stat() of unusual paths.
     */
    private fun readCtimeMillis(file: File): Long? = runCatching {
        val attrs: BasicFileAttributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        attrs.creationTime().toMillis().takeIf { it > 0 }
    }.getOrNull()
}

// Minimal glob matcher: supports a single asterisk (any chars except slash),
// double-asterisk (any chars including slash), and ? (one char). Sufficient
// for the include/exclude patterns we accept from users (e.g. "Music/**",
// "Spoken Word/**").
internal fun matchesGlob(path: String, glob: String): Boolean {
    val regex = StringBuilder("^")
    var i = 0
    while (i < glob.length) {
        val c = glob[i]
        when {
            c == '*' && i + 1 < glob.length && glob[i + 1] == '*' -> {
                regex.append(".*"); i += 2
            }
            c == '*' -> { regex.append("[^/]*"); i += 1 }
            c == '?' -> { regex.append("[^/]"); i += 1 }
            c in ".()+|^$@%{}[]\\" -> { regex.append('\\').append(c); i += 1 }
            else -> { regex.append(c); i += 1 }
        }
    }
    regex.append("$")
    return Regex(regex.toString()).matches(path)
}
