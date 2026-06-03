package com.visorcraft.radioshed.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-logic guards for the libvlc binding's two decision points that
 * have no runtime test here: MRL scheme detection (new_location vs
 * new_path) and the `libvlc_state_t` int → [PlayerState] mapping.
 */
class LibVlcMappingTest {

    @Test
    fun schemeBearingMrlsUseLocation() {
        assertTrue(hasUriScheme("http://stream.example/radio.mp3"))
        assertTrue(hasUriScheme("https://stream.example/radio.mp3"))
        assertTrue(hasUriScheme("file:///home/user/song.flac"))
        assertTrue(hasUriScheme("smb://server/share/song.mp3"))
        assertTrue(hasUriScheme("rtsp://host/live"))
    }

    @Test
    fun localAndUncPathsAreNotSchemes() {
        // Bare local paths and Windows drive paths route through new_path.
        assertFalse(hasUriScheme("/home/user/Music/song.mp3"))
        assertFalse(hasUriScheme("""C:\Music\song.mp3"""))
        // A UNC path is not a URI scheme — the `://` requirement excludes it.
        assertFalse(hasUriScheme("""\\server\share\song.mp3"""))
        assertFalse(hasUriScheme("song.mp3"))
    }

    @Test
    fun resolveMrlPassesPathsThroughUntouched() {
        val unc = """\\server\share\song.mp3"""
        assertEquals(unc, resolveMrl(unc))
        assertEquals("http://host/x.mp3", resolveMrl("http://host/x.mp3"))
    }

    @Test
    fun stateIntsMapToPlayerState() {
        assertEquals(PlayerState.Playing, mapState(LibVlcState.PLAYING))
        assertEquals(PlayerState.Paused, mapState(LibVlcState.PAUSED))
        assertEquals(PlayerState.Buffering, mapState(LibVlcState.OPENING))
        assertEquals(PlayerState.Buffering, mapState(LibVlcState.BUFFERING))
        assertEquals(PlayerState.Idle, mapState(LibVlcState.STOPPED))
        assertEquals(PlayerState.Ended, mapState(LibVlcState.ENDED))
        assertTrue(mapState(LibVlcState.ERROR) is PlayerState.Error)
    }

    @Test
    fun nothingSpecialEmitsNoChange() {
        assertNull(mapState(LibVlcState.NOTHING_SPECIAL))
    }
}
