package com.visorcraft.radioshed.audio

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.io.File

/**
 * Thin Kotlin + JNA binding to the libvlc 3.x C API, replacing the
 * VLCJ (GPL) layer with a direct binding to the LGPL libvlc runtime.
 *
 * Only the surface RadioShed needs is bound — instance/media/player
 * lifecycle plus the handful of transport + state calls
 * [AudioEngineDesktop] drives. State and position are read by polling
 * (see the engine), so no event-callback bridge is bound: that keeps
 * native crashes off libvlc's own threads.
 *
 * libvlc is NOT bundled. It's the user/OS-installed native runtime,
 * discovered at startup by [available]; LGPL obligations are satisfied
 * by the user's own system install.
 *
 * ### libvlc 3.x vs 4.x
 * The signatures below target the stable **3.x** line (the line
 * AGENTS.md and the desktop runtime require). 3.x `stop` and `set_time`
 * are `void` with the simple shapes here; 4.x renamed them
 * (`stop_async`, `set_time(.., fast)`). VLC 4.x is not yet a stable
 * release, so 3.x is the correct target. A 4.x-only host would hit a
 * missing symbol on `stop` / `set_time` — acceptable and documented.
 */
internal interface LibVlc : Library {
    fun libvlc_new(argc: Int, argv: Array<String>?): Pointer?
    fun libvlc_release(instance: Pointer)
    fun libvlc_media_new_path(instance: Pointer, path: String): Pointer?
    fun libvlc_media_new_location(instance: Pointer, mrl: String): Pointer?
    fun libvlc_media_release(media: Pointer)
    fun libvlc_media_player_new(instance: Pointer): Pointer?
    fun libvlc_media_player_release(player: Pointer)
    fun libvlc_media_player_set_media(player: Pointer, media: Pointer)
    fun libvlc_media_player_play(player: Pointer): Int
    fun libvlc_media_player_set_pause(player: Pointer, doPause: Int)
    fun libvlc_media_player_stop(player: Pointer)
    fun libvlc_media_player_set_time(player: Pointer, ms: Long)
    fun libvlc_media_player_get_time(player: Pointer): Long
    fun libvlc_media_player_get_length(player: Pointer): Long
    fun libvlc_media_player_get_state(player: Pointer): Int
    fun libvlc_audio_set_volume(player: Pointer, volume: Int): Int
    fun libvlc_audio_get_volume(player: Pointer): Int
    fun libvlc_media_player_set_rate(player: Pointer, rate: Float): Int
}

/**
 * The libvlc C ABI for setting an environment variable in the running
 * process. The JVM can't mutate its own environment via
 * `System.setProperty`, but libvlc reads `VLC_PLUGIN_PATH` from the C
 * environment at instance creation, so on Windows / macOS we set it via
 * the platform C runtime (`setenv` on POSIX, `_putenv` on Windows) —
 * mirroring what VLCJ does.
 */
private interface PosixEnv : Library {
    fun setenv(name: String, value: String, overwrite: Int): Int
}

private interface MsvcrtEnv : Library {
    fun _putenv(assignment: String): Int
}

/**
 * libvlc media-player states (`libvlc_state_t`). Plain ints across the
 * ABI; [AudioEngineDesktop]'s polling loop maps these onto [PlayerState].
 */
internal object LibVlcState {
    const val NOTHING_SPECIAL = 0
    const val OPENING = 1
    const val BUFFERING = 2
    const val PLAYING = 3
    const val PAUSED = 4
    const val STOPPED = 5
    const val ENDED = 6
    const val ERROR = 7
}

/**
 * Discovers and loads the system libvlc once per JVM. The result is
 * shared by [AudioEngineDesktop] (for playback) and
 * `isMediaPlaybackAvailable` (for the "VLC required" modal), so the
 * relatively expensive load + probe runs exactly once via [available].
 */
internal object NativeLibVlc {

    /**
     * The loaded libvlc binding, or null when discovery failed. Reading
     * this triggers discovery the first time (see [available]).
     */
    val lib: LibVlc? by lazy { discover() }

    /**
     * True when libvlc was found and a probe instance was created
     * successfully. Memoized: the underlying `by lazy` runs discovery
     * once, so both the engine and the modal observe the same result.
     */
    val available: Boolean
        get() = lib != null

    private fun discover(): LibVlc? {
        configureNativePaths()
        // Try the loader's default name resolution first ("vlc" ->
        // libvlc.so / libvlc.dylib / libvlc.dll), then the versioned
        // Linux SONAME. On Linux the unversioned libvlc.so usually only
        // ships in the -dev package, so libvlc.so.5 is the common case.
        val candidateNames = buildList {
            add("vlc")
            if (isLinux()) {
                // The unversioned libvlc.so usually only ships in the -dev
                // package, so the versioned SONAME is the common runtime case.
                add("libvlc.so.5")
            }
        }
        for (name in candidateNames) {
            val loaded = runCatching { Native.load(name, LibVlc::class.java) }.getOrNull() ?: continue
            // Probe: create then release a throwaway instance. A
            // successfully loaded-but-broken library (wrong arch, missing
            // plugins entry point) fails here rather than at first play.
            val probe = runCatching { loaded.libvlc_new(0, null) }.getOrNull()
            if (probe != null) {
                runCatching { loaded.libvlc_release(probe) }
                return loaded
            }
        }
        return null
    }

    /**
     * Append per-OS install dirs to `jna.library.path` and, on
     * Windows / macOS, point `VLC_PLUGIN_PATH` at the install's plugins
     * dir so libvlc can locate its demuxers/decoders. Honors an explicit
     * `RADIOSHED_LIBVLC_PATH` override first.
     *
     * On Linux the loader's default search path and libvlc's compiled-in
     * plugin path cover the standard `apt install vlc` case, so we only
     * add the common lib dirs and skip the plugin-env dance.
     *
     * > **Highest-risk area.** Per-OS discovery (esp. the Windows
     * > install-dir registry lookup and the Linux `.so.5` SONAME) is the
     * > whole reason VLCJ's NativeDiscovery exists. This covers standard
     * > installs; `RADIOSHED_LIBVLC_PATH` is the escape hatch and it
     * > needs real-machine testing on each OS.
     */
    private fun configureNativePaths() {
        val dirs = LinkedHashSet<String>()

        // 1. Explicit override wins.
        System.getenv("RADIOSHED_LIBVLC_PATH")
            ?.takeIf { it.isNotBlank() }
            ?.let { dirs.add(it) }

        // 2. Per-OS standard install locations.
        when {
            isWindows() -> {
                windowsRegistryInstallDir()?.let { dirs.add(it) }
                dirs.add("""C:\Program Files\VideoLAN\VLC""")
                dirs.add("""C:\Program Files (x86)\VideoLAN\VLC""")
            }
            isMac() -> {
                dirs.add("/Applications/VLC.app/Contents/MacOS/lib")
                System.getProperty("user.home")?.let {
                    dirs.add("$it/Applications/VLC.app/Contents/MacOS/lib")
                }
            }
            else -> {
                // Linux: rely on the loader's default path; add the common
                // multi-arch lib dirs as belt-and-suspenders.
                dirs.add("/usr/lib")
                dirs.add("/usr/lib64")
                dirs.add("/usr/lib/x86_64-linux-gnu")
                dirs.add("/usr/local/lib")
            }
        }

        val existing = dirs.filter { File(it).isDirectory }
        if (existing.isNotEmpty()) {
            val current = System.getProperty("jna.library.path").orEmpty()
            val merged = (existing + current.split(File.pathSeparator))
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(File.pathSeparator)
            System.setProperty("jna.library.path", merged)
        }

        // 3. Plugin path on Windows / macOS, where the install layout puts
        //    plugins in a sibling dir libvlc can't self-locate from the
        //    loaded library. The JVM can't mutate its own C environment,
        //    so set it through the platform C runtime — but only when not
        //    already set, to respect a user-provided value.
        if (System.getenv("VLC_PLUGIN_PATH").isNullOrBlank()) {
            val pluginsDir = existing.asSequence()
                .map { File(it, "plugins") }
                .firstOrNull { it.isDirectory }
            if (pluginsDir != null && (isWindows() || isMac())) {
                setNativeEnv("VLC_PLUGIN_PATH", pluginsDir.absolutePath)
            }
        }
    }

    /**
     * Read `HKLM\Software\VideoLAN\VLC\InstallDir` via jna-platform's
     * [Advapi32Util]. Guarded to Windows only — the win32 registry types
     * are present on the classpath cross-platform but the calls only make
     * sense (and only succeed) on Windows.
     */
    private fun windowsRegistryInstallDir(): String? = runCatching {
        Advapi32Util.registryGetStringValue(
            WinReg.HKEY_LOCAL_MACHINE,
            """Software\VideoLAN\VLC""",
            "InstallDir",
        )
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun setNativeEnv(name: String, value: String) {
        runCatching {
            if (isWindows()) {
                Native.load("msvcrt", MsvcrtEnv::class.java)._putenv("$name=$value")
            } else {
                Native.load("c", PosixEnv::class.java).setenv(name, value, 1)
            }
        }
    }

    private fun osName(): String = System.getProperty("os.name").orEmpty().lowercase()
    private fun isWindows(): Boolean = osName().contains("win")
    private fun isMac(): Boolean = osName().contains("mac") || osName().contains("darwin")
    private fun isLinux(): Boolean = osName().contains("linux")
}
