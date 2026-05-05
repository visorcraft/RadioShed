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
internal fun joinPath(parent: String, child: String): String {
    if (parent.isEmpty()) return child
    if (child.isEmpty()) return parent
    val separator = if (parent.contains('\\') && !parent.contains('/')) "\\" else "/"
    val trimmedParent = parent.trimEnd('/', '\\')
    val trimmedChild = child.trimStart('/', '\\')
    return "$trimmedParent$separator$trimmedChild"
}
