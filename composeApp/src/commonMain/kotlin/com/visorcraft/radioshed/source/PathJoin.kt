package com.visorcraft.radioshed.source

/**
 * Cross-platform path concatenation. Handles the awkward case where
 * the parent already carries a trailing separator (Windows drive
 * roots like `D:\` and POSIX root `/`) AND auto-detects which
 * separator to use based on what the parent already contains —
 * Windows paths can use either `\` or `/`, POSIX uses `/`.
 *
 * Examples:
 *  - `joinPath("D:\\", "Music")`              → `"D:\\Music"`
 *  - `joinPath("C:\\Users\\me", "Music")`     → `"C:\\Users\\me\\Music"`
 *  - `joinPath("/storage/emulated/0", "Music")` → `"/storage/emulated/0/Music"`
 *  - `joinPath("/", "Users")`                 → `"/Users"`
 *
 * Avoids `java.io.File` so the helper stays in commonMain — iOS Native
 * has no `File` class, and we want one path-joining contract that
 * works everywhere RadioShed renders a path string for the user.
 */
internal fun joinPath(
    parent: String,
    child: String,
): String {
    if (parent.isEmpty()) return child
    if (child.isEmpty()) return parent
    val separator = if (parent.contains('\\') && !parent.contains('/')) "\\" else "/"
    // UNC root guard. `parent == "\\"` is the network-share root the
    // file browser reaches when the user navigates above
    // `\\server\share` to the synthetic "network" pane. Plain
    // trimEnd would strip both backslashes, leaving a single
    // backslash followed by the child — `\server` — which Windows
    // resolves as a local device path rather than a UNC host.
    // Preserve the leading double-backslash explicitly.
    if (parent == "\\\\" || parent == "//") {
        val trimmedChild = child.trimStart('/', '\\')
        return "$parent$trimmedChild"
    }
    val trimmedParent = parent.trimEnd('/', '\\')
    val trimmedChild = child.trimStart('/', '\\')
    return "$trimmedParent$separator$trimmedChild"
}

/**
 * Cross-platform "parent of file" — strips the last path segment and
 * returns the directory that contains it. Handles both separators.
 *
 * Examples:
 *  - `parentPath("C:\\Music\\Song.mp3")`         → `"C:\\Music"`
 *  - `parentPath("/storage/emulated/0/song.mp3")` → `"/storage/emulated/0"`
 *  - `parentPath("\\\\server\\share\\song.mp3")` → `"\\\\server\\share"`
 *  - `parentPath("song.mp3")`                    → `""` (no parent)
 *  - `parentPath("/")` / `parentPath("C:\\")`    → `""`
 *
 * The Track-Info "Open folder" affordance was hard-coded to look for
 * `/` only and fell back to `"/"` on Windows, opening the synthetic
 * filesystem root instead of the song's actual directory.
 */
internal fun parentPath(path: String): String {
    if (path.isEmpty()) return ""
    val lastSep = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
    if (lastSep < 0) return ""
    val parent = path.substring(0, lastSep)
    // `C:\foo.mp3` → lastSep at index 2 yields `C:` — restore the
    // trailing separator so it remains a usable Windows drive root.
    if (parent.length == 2 && parent[1] == ':') return parent + "\\"
    // `/foo.mp3` → lastSep at index 0 yields `""` — convert that to
    // the POSIX root so callers don't have to special-case.
    if (parent.isEmpty() && path.startsWith('/')) return "/"
    return parent
}
