package com.visorcraft.radioshed.source

/**
 * Path glob matcher for the include / exclude filters on a folder
 * scan. Lives in commonMain (rather than the androidMain
 * `AndroidFileEnumerator` it used to live next to) so that:
 *
 *  1. The iOS enumerator (when it lands) can share the same matcher
 *     and produce identical inclusion semantics.
 *  2. The "Test rule" affordance in `EditFolderDialog` can call the
 *     same code the scanner uses without hopping through an
 *     expect/actual indirection. A divergent test-only matcher would
 *     give users false confidence that a glob does what they think.
 *
 * Supported syntax (intentionally minimal):
 *  - `*`  matches any run of characters except `/`
 *  - `**` matches any run of characters including `/`
 *  - `?`  matches exactly one character (not `/`)
 *  - All regex metacharacters are escaped so user paths containing
 *    `(`, `+`, `.` etc. don't accidentally match.
 *
 * Operates on path strings as-is — caller is responsible for choosing
 * absolute vs relative path. The scanner uses relative-to-folder-root
 * so a glob like `(double-star)/Spoken Word/(double-star)` matches
 * everywhere inside the user's chosen library root regardless of where
 * on disk that root sits. (We can't write the literal `**` followed
 * by `/` here because that closes the KDoc comment.)
 */
fun matchesGlob(
    path: String,
    glob: String,
): Boolean {
    val regex = StringBuilder("^")
    var i = 0
    while (i < glob.length) {
        val c = glob[i]
        when {
            c == '*' && i + 1 < glob.length && glob[i + 1] == '*' -> {
                regex.append(".*")
                i += 2
            }
            c == '*' -> {
                regex.append("[^/]*")
                i += 1
            }
            c == '?' -> {
                regex.append("[^/]")
                i += 1
            }
            c in ".()+|^$@%{}[]\\" -> {
                regex.append('\\').append(c)
                i += 1
            }
            else -> {
                regex.append(c)
                i += 1
            }
        }
    }
    regex.append("$")
    return Regex(regex.toString()).matches(path)
}
