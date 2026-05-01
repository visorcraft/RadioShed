# AutoLinkR Elite — Magisk Root Guide

A complete, end-to-end procedure for rooting an **AutoLinkR Elite** (Qualcomm Snapdragon "Bengal" / SM4250, Android 13) using **Magisk** and the official `boot_patch.sh` workflow — driven entirely from a single PC with no second Android device required.

> **Form factor note.** The AutoLinkR Elite is a USB-A wireless-CarPlay dongle with no built-in screen and no buttons. It uses the car's head-unit display while plugged into a vehicle. While plugged into a USB power brick at home, it joins WiFi but renders to nothing — this guide is structured around that constraint.

---

## Risks & disclaimers

- **Rooting can brick a device.** This procedure modifies the boot partition. If the patched image fails to load and the A/B fallback doesn't catch it, the device may be unusable until you can flash recovery via fastboot.
- The bootloader on the AutoLinkR Elite ships **already unlocked** (`androidboot.verifiedbootstate=orange`). This guide does **not** cover bootloader unlocking because none is needed.
- Operating on a device you don't own, or where you don't have authorization, may violate manufacturer terms of service and applicable law. This guide is for owners of devices they purchased.
- Manufacturer firmware updates may re-lock or re-flash slots. Re-rooting after an OTA may require repeating this procedure against the new boot image.

---

## What you'll need

| Item | Notes |
|---|---|
| A PC (Windows / macOS / Linux) | Commands below are POSIX shell; PowerShell variants where needed are called out |
| **Android SDK Platform Tools** (`adb` + `fastboot`) | Recent enough to know mDNS / pair flows is a plus |
| The AutoLinkR Elite | Powered (any USB power source works) |
| A WiFi network the AutoLinkR can join | The PC must be on the same network |
| A USB-A port on the PC | Required only for the **flash** step at the end |
| **One-time:** access to a display the AutoLinkR can drive | Either the car's CarPlay screen or any HDMI display via the relevant adapter, in order to tap "Allow debugging" once. After that, headless operation works |

---

## Verified result

The procedure below was performed end-to-end against:

```
ro.build.fingerprint = qti/bengal/bengal:13/TKQ1.230627.001/eng.zhangz.20260207.200118:user/release-keys
ro.product.cpu.abi   = arm64-v8a
ro.build.version.sdk = 33  (Android 13)
verifiedbootstate    = orange  (bootloader unlocked)
```

If your `getprop ro.product.cpu.abi` reports something other than `arm64-v8a`, swap the Magisk ABI in the toolkit-extraction step accordingly.

---

## Phase 1 — One-time wireless ADB authorization

The AutoLinkR has no display when plugged into power only, so the very first ADB connection needs to happen while the device is plugged into a screen (your car, or any HDMI display via the appropriate adapter). Once you've authorized the host once, the device caches your RSA key and future wireless connections proceed silently.

On the AutoLinkR (using the car's screen):

1. Open **Settings → System → Developer options → Wireless debugging** and toggle it **on**.
2. Note the **IP address & port** displayed at the top.
3. From the PC:
   ```bash
   adb connect <DEVICE_IP>:<DEVICE_PORT>
   adb devices
   ```
4. The device's screen shows an **"Allow debugging from this computer?"** dialog with an RSA fingerprint. Check **Always allow from this computer**, then tap **Allow**.
5. Re-run `adb devices` — the entry should now read `device` (not `unauthorized`).

### **CRITICAL: Make Wireless Debugging persist across reboots**

By default on Android 11+, Wireless Debugging is a **per-session** state — it turns itself off on every reboot, every USB-host change, and sometimes when the dongle gets confused about its USB role. Since the AutoLinkR has **no battery and no display**, every time the dongle loses power and reboots you'd otherwise be stuck without ADB and without a way to re-enable it.

**The fix is one command, run NOW while you have ADB alive:**

```bash
adb shell settings put global adb_wifi_enabled 1
adb shell settings get global adb_wifi_enabled    # should print: 1
```

This sets the AOSP global setting that auto-starts wireless ADB on every boot. It's persisted in the system database and survives reboots, USB-host changes, and even most factory-OTA updates (until a full data wipe).

**Skipping this step is the single biggest "how do I root my screenless dongle" trap.** Don't skip it. Do it the first time you have wireless ADB alive — preferably immediately after the previous step's `adb devices` confirmation — and you never have to think about display access again for the rest of this guide (or any future re-rooting session).

You can now unplug and move the dongle. The host key is persisted across reboots, and now so is wireless ADB itself.

---

## Phase 2 — Connect over WiFi (every subsequent session)

Modern AOSP wireless debugging picks a **random ephemeral port** in the Linux range `32768–60999` for each session. There's no fixed port to memorize.

### Find the AutoLinkR's IP

You can read it from the **Wireless debugging** screen on-device, OR sweep your LAN for the device. The MAC OUI tends to be `9c:04:b6:*` (Hon Hai / Foxconn). On Windows:

```powershell
arp -a | Select-String '9c-04-b6'
```

### Scan for the listening port

The wireless-ADB connect port shows up as a single open TCP port (sometimes two if the pairing screen is open simultaneously).

**Linux / macOS:**
```bash
IP=192.168.X.X
for p in $(seq 32768 60999); do
  (echo > /dev/tcp/$IP/$p) 2>/dev/null && echo $p
done
```

**Windows PowerShell 7+ (parallel — completes in seconds):**
```powershell
$ip = '192.168.X.X'
32768..60999 | ForEach-Object -Parallel {
  $tcp = New-Object System.Net.Sockets.TcpClient
  try {
    if ($tcp.ConnectAsync($using:ip, $_).Wait(250) -and $tcp.Connected) {
      Write-Output $_
    }
  } catch {} finally { $tcp.Close() }
} -ThrottleLimit 256
```

### Connect and verify

```bash
adb connect <DEVICE_IP>:<CONNECT_PORT>
adb devices -l
```

Expected output:

```
List of devices attached
192.168.X.X:NNNNN    device product:bengal model:Bengal_for_arm64 device:bengal
```

If you see two open ports and `adb connect` to one fails with `failed to connect`, that one is the *pairing* port (only open while the pairing screen is visible) — the other is the persistent *connect* port.

> **Tip.** Set a shell alias for the rest of the guide:
> ```bash
> # Linux / macOS / WSL
> alias D='adb -s 192.168.X.X:NNNNN'
> ```
> ```powershell
> # PowerShell
> function D { adb -s 192.168.X.X:NNNNN @args }
> ```
> Wherever you see `D` below, that's `adb -s <your-device>`.

---

## Phase 3 — Confirm the device is rootable

Before touching anything, sanity-check the bootloader and partition layout. Every check below should be **non-destructive**.

```bash
D shell 'getprop ro.boot.verifiedbootstate; \
         getprop ro.boot.flash.locked; \
         getprop ro.boot.slot_suffix; \
         getprop ro.boot.dynamic_partitions; \
         getprop ro.product.cpu.abi'
```

Expected on a stock AutoLinkR Elite:

```
orange       # bootloader unlocked — no challenge needed
0            # flash partition unlocked
_b           # currently running from the B slot (yours may be _a)
true         # dynamic partitions (super)
arm64-v8a    # 64-bit ARM
```

Also verify the boot partition is reachable as a raw block device:

```bash
D shell 'ls -la /dev/block/by-name/boot_a /dev/block/by-name/boot_b'
```

You should see two symlinks pointing at `/dev/block/mmcblk0p7X` partitions. **Note your active slot suffix** (the value of `ro.boot.slot_suffix`); the rest of this guide uses `boot_b` as the example. Substitute `boot_a` if your device is on the A slot.

---

## Phase 4 — Back up the stock boot image

This is the **single most important step** in the procedure. The image you pull here is your only path back if anything goes wrong later.

### Dump from the active slot to /sdcard

```bash
D shell 'dd if=/dev/block/by-name/boot_b of=/sdcard/boot_b.img bs=4M'
D shell 'sha256sum /sdcard/boot_b.img'
```

Note the SHA-256 — you'll match it against the local copy after pulling.

### Pull to PC

```bash
mkdir -p autolinkr-backup
D pull /sdcard/boot_b.img autolinkr-backup/boot_b-original.img
```

> **Windows / Git Bash (MSYS) gotcha:** MSYS rewrites paths starting with `/` into Windows-style paths before passing them to `adb.exe`, which mangles the *remote* path. Either:
> - Set `MSYS_NO_PATHCONV=1` in your shell, or
> - Use a leading double slash for the remote path: `D pull //sdcard/boot_b.img ./boot_b-original.img`

### Verify integrity

```bash
sha256sum autolinkr-backup/boot_b-original.img
```

The hash **must** match what the device reported. If it doesn't, redo the pull — never proceed with a corrupt backup.

### Sanity-check the magic header

```bash
head -c 8 autolinkr-backup/boot_b-original.img | xxd
```

Expected:

```
00000000: 414e 4452 4f49 4421                      ANDROID!
```

Clean up the on-device copy:

```bash
D shell 'rm /sdcard/boot_b.img'
```

---

## Phase 5 — Prepare the Magisk toolkit on the PC

Magisk's GUI ("Patch a file" inside Magisk Manager) is a thin frontend over a shell script called `boot_patch.sh`, bundled inside the Magisk APK along with all the binaries it needs. Since the AutoLinkR Elite IS an Android device, we can run that script on the AutoLinkR itself — no separate Android phone required.

### Download the Magisk APK

Use the latest **stable** release from the upstream GitHub repo:

```bash
mkdir -p magisk-toolkit && cd magisk-toolkit
curl -sLO "https://github.com/topjohnwu/Magisk/releases/download/v30.7/Magisk-v30.7.apk"
```

(Replace `v30.7` with the current latest stable from <https://github.com/topjohnwu/Magisk/releases/latest>.)

### Extract the toolkit files

The APK is a ZIP. We need:

| Source path inside APK | Why we need it |
|---|---|
| `assets/boot_patch.sh` | The patch script |
| `assets/util_functions.sh` | Helpers sourced by the script |
| `assets/stub.apk` | Stub APK injected into the image |
| `assets/addon.d.sh` | OTA-survival shim |
| `lib/arm64-v8a/libmagisk.so` | The main `magisk` binary |
| `lib/arm64-v8a/libmagiskboot.so` | The `magiskboot` repacker |
| `lib/arm64-v8a/libmagiskinit.so` | The `magiskinit` first-stage init |
| `lib/arm64-v8a/libinit-ld.so` | LD_PRELOAD shim that's injected into `/init` |

Extract them all into a `staging/` directory and **rename the `lib*.so` files to executable names** (the `.so` extension is just packaging — they're statically-linked binaries, not shared libraries):

```bash
mkdir -p staging
unzip -j -o Magisk-v30.7.apk \
  "assets/boot_patch.sh" "assets/util_functions.sh" \
  "assets/stub.apk" "assets/addon.d.sh" \
  -d staging/

unzip -j -o Magisk-v30.7.apk "lib/arm64-v8a/libmagisk.so"     -d staging/
mv staging/libmagisk.so      staging/magisk

unzip -j -o Magisk-v30.7.apk "lib/arm64-v8a/libmagiskboot.so" -d staging/
mv staging/libmagiskboot.so  staging/magiskboot

unzip -j -o Magisk-v30.7.apk "lib/arm64-v8a/libmagiskinit.so" -d staging/
mv staging/libmagiskinit.so  staging/magiskinit

unzip -j -o Magisk-v30.7.apk "lib/arm64-v8a/libinit-ld.so"    -d staging/
mv staging/libinit-ld.so     staging/init-ld
```

> **Common mistake:** missing `init-ld` (the LD_PRELOAD shim). On Magisk v27+ `boot_patch.sh` references it explicitly; if it's not in `staging/`, the patch fails midway with `lstat 'init-ld.xz': No such file or directory`. Make sure the eight files above all end up in `staging/`.

Sanity check:

```
$ ls -la staging/
addon.d.sh
boot_patch.sh
init-ld
magisk
magiskboot
magiskinit
stub.apk
util_functions.sh
```

---

## Phase 6 — Patch the boot image on the AutoLinkR

### Push the toolkit to /data/local/tmp on the device

`/data/local/tmp` is writable without root, executable, and persists across reboots — the standard place for staging.

```bash
D shell 'rm -rf /data/local/tmp/magisk; mkdir -p /data/local/tmp/magisk'
D push staging/. /data/local/tmp/magisk/
D shell 'chmod +x /data/local/tmp/magisk/magisk \
                  /data/local/tmp/magisk/magiskboot \
                  /data/local/tmp/magisk/magiskinit \
                  /data/local/tmp/magisk/boot_patch.sh'
```

### Dump the boot image into the toolkit dir under a NON-conflicting name

> **Critical gotcha.** `boot_patch.sh` writes to a working file literally named `stock_boot.img`. Internally, line 139 reads:
> ```bash
> cat $BOOTIMAGE > stock_boot.img
> ```
> If you pass it an input file *also* named `stock_boot.img`, the shell opens `stock_boot.img` for write **first** (truncating it to 0 bytes), and then `cat` reads from the now-empty file. The patch fails downstream with `mmap 'stock_boot.img': Invalid argument`.
>
> **Always name the input file something other than `stock_boot.img`.** `boot.img` is the canonical choice.

```bash
D shell 'dd if=/dev/block/by-name/boot_b \
            of=/data/local/tmp/magisk/boot.img bs=4M'

D shell 'ls -la /data/local/tmp/magisk/boot.img'   # confirm full size, not 0
```

### Run boot_patch.sh

```bash
D shell 'cd /data/local/tmp/magisk && ./boot_patch.sh boot.img'
```

A successful run prints (abbreviated):

```
- Unpacking boot image
Parsing boot image: [boot.img]
HEADER_VER      [2]
KERNEL_FMT      [gzip]
RAMDISK_FMT     [gzip]
- Checking ramdisk status
- Stock boot image detected
- Patching ramdisk
- Pre-init storage partition: userdata
Patch with flag KEEPVERITY=[false] KEEPFORCEENCRYPT=[false]
Found fstab file [fstab.default]
Found fstab file [fstab.emmc]
- Patch fstab in boot image dtb
- Repacking boot image
Repack to boot image: [new-boot.img]
HEADER_VER      [2]
CHECKSUM        [<a-different-hash-than-stock>]
```

You'll also see lines like `Failed to patch` repeated three times during the dtb-fstab pass. **These are benign** — they're best-effort scrubs of `,avb` flags from fstab nodes that aren't present in this device's dtb. The repack succeeds regardless.

### Pull the patched image back to PC

```bash
D shell 'sha256sum /data/local/tmp/magisk/new-boot.img'

D pull /data/local/tmp/magisk/new-boot.img \
       autolinkr-backup/boot_b-magisk-patched.img

sha256sum autolinkr-backup/boot_b-magisk-patched.img
```

The two hashes must match. Also confirm the magic header:

```bash
head -c 8 autolinkr-backup/boot_b-magisk-patched.img | xxd
# 00000000: 414e 4452 4f49 4421                      ANDROID!
```

Clean up on-device:

```bash
D shell 'rm -rf /data/local/tmp/magisk'
```

You should now have two files side-by-side:

```
autolinkr-backup/boot_b-original.img        # untouched, your safety net
autolinkr-backup/boot_b-magisk-patched.img  # what we'll flash
```

---

## Phase 7 — Flash the patched boot image via fastboot

This is the **only** destructive step in the procedure.

### Connect the AutoLinkR to the PC over USB

Wireless-ADB is fine for shell access but **fastboot is USB-only** on the Qualcomm Bengal platform. Plug the AutoLinkR's USB-A male connector directly into a USB-A port on the PC. Confirm it shows up:

```bash
adb devices
```

You should now see the device with a *USB* serial (no `192.168.x.x:` prefix). It's fine if the WiFi entry is also there.

### Reboot to the bootloader

```bash
adb -s <USB-SERIAL> reboot bootloader
```

Wait ~10 seconds for the bootloader to come up.

### Confirm fastboot enumeration

```bash
fastboot devices
```

You should see the device's serial again. If `fastboot devices` is empty after 30 seconds, hit Ctrl+C and unplug → re-plug the dongle. On Windows you may need the [universal ADB driver](https://github.com/Microsoft/Universal-ADB-Drivers) to expose the bootloader's USB device.

### Flash the patched boot to the active slot

```bash
fastboot flash boot_b autolinkr-backup/boot_b-magisk-patched.img
```

> **Substitute the correct slot.** If your device's `ro.boot.slot_suffix` was `_a`, flash `boot_a` instead. Flashing the wrong slot won't take effect at boot.

Expected output:

```
target reported max download size of XXXXXXXX bytes
sending 'boot_b' (98304 KB)...
OKAY [  X.XXXs]
writing 'boot_b'...
OKAY [  X.XXXs]
finished. total time: X.XXXs
```

### Reboot

```bash
fastboot reboot
```

The dongle will boot back into Android. Give it 30–60 seconds to rejoin WiFi.

---

## Phase 8 — Verify root

Reconnect over wireless ADB (the IP and port may have changed if your DHCP lease rotated; rerun the port scan from Phase 2 if needed).

```bash
adb connect <DEVICE_IP>:<CONNECT_PORT>
adb devices
adb shell
```

Inside the shell, request a root shell:

```bash
$ su
```

If Magisk is active you'll either get a `#` prompt immediately, or the `su` will hang briefly while Magisk shows a grant prompt **on the device's display** — which on a headless dongle won't be visible. To pre-grant ADB shell access without a display:

```bash
# from a non-root adb shell:
adb shell magisk --setup-sbin /sbin
adb shell sh -c 'echo "shell" >> /data/adb/.magisk/config'
```

…or simply install the Magisk APK as a regular app and use `pm grant` to give it the permissions it needs. The simplest verification, however, is just:

```bash
adb shell 'su -c id'
```

Successful output:

```
uid=0(root) gid=0(root) groups=0(root) context=u:r:magisk:s0
```

You're rooted.

### Optional: install the Magisk Manager APK

The same APK we used for `boot_patch.sh` can also be installed as a regular app, giving you a GUI for module management:

```bash
adb install Magisk-v30.7.apk
```

---

## Recovery — rolling back to stock

If the device bootloops, fails to boot, or behaves unexpectedly:

1. Hold any way you can to interrupt boot — for the AutoLinkR, simply unplug power, wait 5 seconds, plug back in. The bootloader on Qualcomm Bengal will idle in fastboot mode if the boot image is unverifiable; the device's boot retry counter will switch slots after a few failed attempts but you can also force fastboot:
   ```bash
   adb reboot bootloader   # while still booted
   ```
2. Reflash the original:
   ```bash
   fastboot flash boot_b autolinkr-backup/boot_b-original.img
   fastboot reboot
   ```

This restores the device to the exact byte-for-byte state captured in Phase 4. Verify by re-running:

```bash
adb shell 'sha256sum /dev/block/by-name/boot_b'
```

The hash must match `boot_b-original.img`.

> **A/B fallback.** Because the AutoLinkR Elite is an A/B-slot device, even without manual recovery the bootloader may auto-switch to the other slot after several failed boot attempts. This is partial protection, not a substitute for keeping the original image around.

---

## Appendix A — Device facts (verified)

| Property | Value |
|---|---|
| Manufacturer / brand | QUALCOMM / qti (OEM-rebranded) |
| Codename | `bengal` |
| SoC | Snapdragon 480 (SM4250) |
| Android | 13 (SDK 33) |
| ABI | `arm64-v8a` |
| Display | 1920 × 894 @ 30Hz, 238 dpi (≈ 1290 × 600 dp) |
| Boot scheme | A/B slots, dynamic partitions (`super`) |
| Bootloader state | `orange` (unlocked from factory) |
| `flash.locked` | `0` |
| SELinux | Permissive (logging only) |
| System SQLite | 3.32 (predates `RETURNING` — apps that need it must bundle their own) |
| Boot HAL | `android.hardware.boot@1.1` |
| USB controller | DWC3 (`4e00000.dwc3`) |
| Default USB role in Android | Device (CarPlay-protocol target) |
| Default USB role in fastboot | Device (responds to host PC) |

---

## Appendix B — Known gotchas

| # | Gotcha | Symptom | Fix |
|---|---|---|---|
| 1 | Naming the input boot image `stock_boot.img` | Patch reaches "Repacking" step, then fails: `mmap 'stock_boot.img': Invalid argument` | Name the input `boot.img` (or anything other than `stock_boot.img`). The script self-clobbers via `cat $BOOTIMAGE > stock_boot.img`. |
| 2 | Forgetting to extract `lib/arm64-v8a/libinit-ld.so` | Patch fails: `lstat 'init-ld.xz': No such file or directory` | Make sure `init-ld` (renamed from `libinit-ld.so`) is in `staging/` before pushing. |
| 3 | MSYS / Git Bash on Windows mangling adb paths | `adb push file /sdcard/x.img` becomes `adb push file C:/Program Files/Git/sdcard/x.img` | `export MSYS_NO_PATHCONV=1` or prefix remote paths with `//`. |
| 4 | Trying to flash via WiFi-ADB | Bootloader enumeration silent / `fastboot devices` empty | fastboot is USB-only on Qualcomm. Physical USB connection required for the flash step (Phase 7). |
| 5 | `adb devices` shows `unauthorized` and there's no display | Can't tap "Allow debugging from this computer" | One-time only: plug into car or HDMI display to authorize, then go headless. |
| 6 | Flashing the wrong slot | Flash succeeds, reboot lands on stock (no Magisk) | Match `ro.boot.slot_suffix` — flash `boot_a` if running on A, `boot_b` if running on B. |
| 7 | Magisk version skew | `boot_patch.sh` errors mention files (`init-ld`, `stub.apk`) that aren't in the toolkit | Always extract from the same APK release; don't mix files between versions. |
| 8 | Skipping the `adb_wifi_enabled` persistence step (Phase 1) | Wireless Debugging dies on reboot/USB-host-change; you can't re-enable it without display access; on a screenless dongle that means going back to the car | Run `adb shell settings put global adb_wifi_enabled 1` the **first** time you have wireless ADB alive. This is the single most important command in the guide for screenless devices. |
| 9 | Plugging dongle into PC USB while in normal Android (CarPlay-target mode) | Windows enumerates as Apple iPhone (VID 0x05AC PID 0x12A8); USB-ADB does not appear; popup says "device requires further installation" looking for iTunes drivers | Expected — the dongle's USB role is locked to CarPlay-protocol while Android is running. Use wireless ADB to issue `adb reboot bootloader`; the bootloader's own USB stack will then expose fastboot on the same USB-C port. |

---

## Appendix C — Cross-platform notes

**Where to put the workspace.** The procedure produces three artifacts to keep:
- `autolinkr-backup/boot_b-original.img` (your safety net — keep forever)
- `autolinkr-backup/boot_b-magisk-patched.img` (what got flashed)
- `magisk-toolkit/Magisk-vXX.X.apk` (the source of truth for the toolkit version)

Stash these somewhere that survives PC reformats — they're how you re-root after a stock OTA wipes Magisk.

**Windows-specific.** PowerShell 7+ ships parallel `ForEach-Object -Parallel` which makes the port-scan in Phase 2 fast. Older `powershell.exe` (5.1) lacks `-Parallel`; substitute with a `Start-ThreadJob` loop or run scans serially (slow).

**Linux-specific.** If your distro doesn't ship `fastboot` in the same package as `adb`, install `android-tools-fastboot` (Debian/Ubuntu) or `android-tools` (Arch/Fedora).

**macOS-specific.** Install platform-tools via `brew install --cask android-platform-tools`. Apple Silicon Macs run the universal binary cleanly.

**WSL.** ADB-over-WiFi works fine inside WSL2 (the Linux kernel sees the Windows host's network). USB-fastboot does NOT work natively from WSL2 — use Windows-side `adb.exe` / `fastboot.exe` for Phase 7, even if you used WSL bash for everything else.

---

## Credits

This procedure stands on the shoulders of:

- **topjohnwu** for [Magisk](https://github.com/topjohnwu/Magisk) and the `boot_patch.sh` workflow that makes headless rooting tractable
- **Google / AOSP** for the A/B-slot architecture that gives us a software safety net
- **Qualcomm** for shipping a bootloader that supports standard fastboot on Bengal hardware

Magisk is GPL-3.0; this guide reproduces no Magisk source.
