package com.visorcraft.radioshed.source

import kotlin.test.Test
import kotlin.test.assertEquals

class PathJoinTest {
    @Test
    fun joinPath_windowsDriveRoot() {
        assertEquals("D:\\Music", joinPath("D:\\", "Music"))
        assertEquals("C:\\Users\\me\\Music", joinPath("C:\\Users\\me", "Music"))
    }

    @Test
    fun joinPath_posix() {
        assertEquals("/Users", joinPath("/", "Users"))
        assertEquals(
            "/storage/emulated/0/Music",
            joinPath("/storage/emulated/0", "Music"),
        )
    }

    @Test
    fun joinPath_emptyArgs() {
        assertEquals("Music", joinPath("", "Music"))
        assertEquals("D:\\", joinPath("D:\\", ""))
    }

    @Test
    fun joinPath_uncRoot_preservesDoubleBackslash() {
        // Regression: pre-fix, joinPath("\\", "server") trimmed both
        // backslashes and produced "\\server" (single-prefix), which
        // Windows resolves as a local device path rather than a UNC
        // host. The UNC-root guard preserves the leading "\\".
        assertEquals("\\\\server", joinPath("\\\\", "server"))
        assertEquals("\\\\server\\share\\Music", joinPath("\\\\server\\share", "Music"))
        assertEquals("\\\\server\\share\\Music", joinPath("\\\\server\\share\\", "Music"))
    }

    @Test
    fun joinPath_stripsLeadingChildSeparator() {
        // Caller may have already prefixed the child with a separator;
        // the join shouldn't double-up.
        assertEquals("D:\\Music", joinPath("D:\\", "\\Music"))
        assertEquals("/foo/bar", joinPath("/foo", "/bar"))
    }

    @Test
    fun parentPath_windowsDrive() {
        assertEquals("C:\\Music", parentPath("C:\\Music\\Song.mp3"))
        assertEquals("C:\\", parentPath("C:\\foo.mp3"))
    }

    @Test
    fun parentPath_posix() {
        assertEquals("/storage/emulated/0", parentPath("/storage/emulated/0/song.mp3"))
        assertEquals("/", parentPath("/song.mp3"))
    }

    @Test
    fun parentPath_unc() {
        assertEquals("\\\\server\\share", parentPath("\\\\server\\share\\song.mp3"))
    }

    @Test
    fun parentPath_noSeparator() {
        assertEquals("", parentPath("song.mp3"))
    }
}
