# AGENTS.md: guide for AI agents working on Second Display

Read this before touching anything. It encodes hard-won facts about the AirPlay
protocol, this Android port, and the target devices. Skipping it will cost you
hours.

## What this is

An AirPlay-compatible **mirroring and extended display receiver** for Android,
for Android 9+ phones, tablets, Android TV and Google TV (minSdk 28, targetSdk 36, arm64 and
armv7), tested on a Xiaomi Redmi Pad (Android 14, HyperOS) and a Galaxy S26 Ultra. Second Display is a fork of
Wormhole Display (pgodlews/wormhole-display), which targeted Meta Portals (Android 9-10); Portals
are not supported by this build, but older Android TV sets on Android 9-10 are. A Mac, iPhone or
iPad sees the device in its native Screen Mirroring picker and streams video and
audio to it. The AirPlay heavy lifting (RTSP, FairPlay, decryption, mDNS) is a
vendored copy of UxPlay's C core at `app/src/main/cpp/uxplay/`; the app layer is
Kotlin + MediaCodec/AudioTrack. AirPlay is the only protocol: Windows and Linux
are deliberately unsupported (see "Windows and Linux" below).

## Repo layout

```
app/src/main/cpp/uxplay/lib/              vendored UxPlay core (DO NOT casually edit - see below)
app/src/main/cpp/uxplay/lib/llhttp/       bundled HTTP parser (MIT)
app/src/main/cpp/uxplay/lib/playfair/     FairPlay handshake crypto
app/src/main/cpp/uxplay/lib/mdnsd/        embedded mDNS responder (the Bonjour advertiser)
app/src/main/cpp/receiver_jni.c           JNI shim: server lifecycle + all raop callbacks
app/src/main/cpp/dmap.c                   DMAP parser for now-playing metadata (title/artist/album/year)
app/src/main/cpp/deps/                    committed prebuilt static libs (openssl, plist)
app/src/main/java/io/github/swiderekartmax/seconddisplay/
    MainActivity.kt                       Compose dashboard + video surface
    Theme.kt, Palette.kt                  Material You theme (wallpaper colours) and the orange fallback scheme
    AppIcons.kt                           the few Material icons missing from material-icons-core, as path data
    ReceiverService.kt                    foreground service: keeps the receiver discoverable, boot autostart
    ReceiverServer.kt                     process-wide owner of the native server, renderers and UI state
    NativeBridge.kt                       JNI declarations
    ScreenOrientation.kt                  portrait/landscape/auto orientation modes and model helpers
    VideoFrameQueue.kt, VideoRenderer.kt  compressed-frame queue → MediaCodec → Surface (H.264/HEVC)
    AudioRenderer.kt                      AAC-ELD / PCM decode → AudioTrack (44.1 kHz)
    NowPlaying.kt                         track metadata + extrapolated position for audio-only sessions
    ReceiverIdentity.kt                   persisted deviceid, service name, Wi-Fi multicast lock
scripts/build.sh                          build APK → dist/second-display.apk
scripts/build-deps.sh                     rebuild the native deps (rarely needed)
scripts/test-native.sh                    host regression tests for the vendored mirror loop and the DMAP parser
docs/architecture.md                      design overview
docs/uxplay-patches.md                    inventory of vendored-code changes
```

Vendored UxPlay changes are listed in `docs/uxplay-patches.md` and marked
"Android port" in the source (the `raop_get_pk_str` getter is marked "Android
JNI addition"). Keep that inventory current and avoid unrelated vendor changes
so future upgrades remain diffable.

## Environment

- JDK 17 and the Android SDK. `scripts/build.sh` sets `JAVA_HOME` and
  `ANDROID_HOME` defaults, so plain `java` does not need to be on PATH.
- Native deps are **committed prebuilt** (`app/src/main/cpp/deps/`). You only
  need the NDK if you edit C code: AGP will use `.tools/ndk-r27d` if present
  (see `app/build.gradle.kts`) or any SDK-installed NDK. Pinned version:
  27.3.13750724. If you change the NDK version, update `ndkVersion` there.

## Build, deploy, run

```bash
./scripts/build.sh
adb install -r dist/second-display.apk
adb shell am start -n io.github.swidegit.seconddisplay/io.github.swiderekartmax.seconddisplay.MainActivity
```

The device is reachable over ADB via USB or wireless debugging. Device and sender
must share a local network: the same Wi-Fi, or the sender joined to the device's own
hotspot. `mdnsd.c` advertises on every local IPv4 link (cellular, VPN and CLAT
skipped), rescanning every 3 s. USB tethering to a Mac only works where the device
offers NCM (`tetherableNcmRegexs` in `dumpsys tethering`); RNDIS, the default on many
devices (Xiaomi Redmi Pad among them), has no macOS driver.

Installing on a real device:

- **Never install while a session is running**: the install force-stops the app
  and drops the sender. Check first that the newest of `mirror video started` /
  `mirror video stopped` in `adb logcat -d -s SecondDisplay` is `stopped`.
- **Play Protect** rejects debug APKs over USB (`INSTALL_FAILED_VERIFICATION_FAILURE`).
  Switch `settings put global verifier_verify_adb_installs 0` only for the install
  and restore it to `1` right after.
- **versionCode**: local builds default to 1 and cannot replace a higher installed
  version (`INSTALL_FAILED_VERSION_DOWNGRADE`). Set `SECOND_DISPLAY_VERSION_CODE` above
  the installed value.
- A new signature (or an uninstall) drops the *Display over other apps* grant;
  re-grant with `adb shell appops set io.github.swidegit.seconddisplay SYSTEM_ALERT_WINDOW allow`.
- Vendor ROMs (Xiaomi, Samsung, Huawei, OnePlus) kill background apps; logcat
  `Killing … OneKeyClean` means the user cleared Recents, not a crash.

## How a connection works (protocol flow)

1. Native `mdnsd` advertises `_airplay._tcp` and `_raop._tcp` (both → port 7000)
   with TXT records including `pk=<hex ed25519 pubkey>`, `model=AppleTV3,2`,
   `srcvers=220.68`, `features=0x5A7FFEE6,0x0`. This is what populates the
   sender's picker.
2. Client connects to TCP 7000, does `GET /info` (RTSP-style, **with CSeq**),
   then ed25519 `pair-verify` (transient - no PIN with our feature bits).
3. `POST /fp-setup` × 2 - FairPlay SAP handshake (in `playfair/`).
4. `SETUP` negotiates streams: video over TCP 7100 ("mirror data"), timing/control
   UDP 7011/7001/7101. Then `RECORD` starts the stream.
5. Video arrives AES-128-CTR encrypted; the core decrypts and calls
   `video_process` with **Annex-B H.264/HEVC** (parameter sets prepended after each
   parameter packet). The JNI shim copies it into a jbyteArray → `VideoFrameQueue`
   → `VideoRenderer` → MediaCodec.
6. Audio (AAC-ELD, 44.1 kHz stereo) is decrypted by the core and delivered to
   `audio_process`; the shim copies each frame to `AudioRenderer`.

## Windows and Linux

Not supported, on purpose. A Miracast over Infrastructure (MS-MICE) receiver was
built (`_display._tcp` on TCP 7250, Wi-Fi Display RTSP, RTP/MPEG-2 TS) and removed
after a Windows PC could not see the device: Windows discovers wireless displays
over Wi-Fi Direct first, and every beacon must carry the MS-MICE vendor IE, which an
ordinary Android app cannot emit (`WifiP2pManager.setWfdInfo` is a system API).
mDNS and TCP 7250 are only used after the user picks a receiver found that way.
Linux (GNOME Network Displays) would not need Wi-Fi Direct but was never tested,
and keeping an untested protocol was not worth the code. Do not re-add either
without a device that proves it works; the removed code is in git history before
the "AirPlay only" commit.

## Verification recipes

Device seen by the sender (run on the Mac):

```bash
dns-sd -B _airplay._tcp local.        # must list the service name, e.g. "Second Display"
dns-sd -B _raop._tcp local.           # must list "<DEVICEID>@<service name>"
```

Server alive (run on the Mac; substitute the device's LAN IP):

```bash
printf 'GET /info RTSP/1.0\r\nCSeq: 0\r\nUser-Agent: AirPlay/320.20\r\n\r\n' \
  | nc -w 5 <device-ip> 7000 | head -c 200
# expect: "RTSP/1.0 200 OK" + application/x-apple-binary-plist body
```

Decode the plist body with `plistlib` to check advertised `displays[0]` width/
height/maxFPS. A 409 means another client is already connected.

Logs on the device:

```bash
adb logcat | grep -E "uxplay|SecondDisplay|AndroidRuntime|libc"
```

## Troubleshooting: every one of these was hit for real

| Symptom | Cause | Fix / notes |
|---|---|---|
| Plain `curl http://…:7000/info` hangs, empty reply | The core drops requests **without a CSeq header** unless HLS support is on | Probe with the RTSP+CSeq recipe above, not curl |
| Crash: `assertion "raop->dnssd" failed` on first /info | `/info` reads TXT records from the C dnssd object | `dnssd_init` + `dnssd_register_*` must complete before HTTP starts (done in `nativeStart`) |
| Picker shows the device, connect fails instantly | Advertised `pk` ≠ the server's actual ed25519 key | Always advertise `raop_get_pk_str()` output; never a random/persisted key |
| Mac mirrors at 1920×1080, looks wrong on the panel | UxPlay defaults advertise 1920×1080@30; **macOS mirrors at whatever you advertise** | `raop_set_plist(raop,"width"/"height"/"refreshRate"/"maxFPS", …)` - done from real display metrics in `nativeStart` |
| App not in the launcher | Missing launcher icon: some launchers hide icon-less apps | Keep `android:icon="@mipmap/ic_launcher"` in the manifest |
| Re-pairing a running app via `am start … --es` silently does nothing | Standard-launchMode activities don't get `onNewIntent` when already on top | `android:launchMode="singleTop"` (already set) |
| Stream is live (decoders started) but the app stays behind the launcher | Android 10 blocks background activity starts; logcat shows `Background activity start … isBgStartWhitelisted: false` | Needs "Display over other apps" (`SYSTEM_ALERT_WINDOW` appop). The dashboard's *Open when a stream starts* row links to it. A new `applicationId` starts without the grant |
| mDNS advertising works on desktop Java but not on device | Android drops multicast without a `WifiManager.MulticastLock`; also needs `CHANGE_WIFI_MULTICAST_STATE` | `ReceiverIdentity` holds the multicast lock - it must be held while mdnsd runs |
| Why not `NsdManager` for Bonjour? | It cannot publish TXT records before API 31; this build supports API 28 | Native `mdnsd` is used instead (and is required for /info anyway) |
| Native link errors: `dnssd_private_init`, `dnssd_get_*_txt` undefined | Those symbols live in a dnssd *backend*; you must compile exactly one (`mdnsd/`) | `${UX}/mdnsd/*.c` is in the CMake glob |
| Stale native builds after adding/removing .c files | CMake `file(GLOB)` result is cached | The glob uses `CONFIGURE_DEPENDS`; if it still looks stale, `rm -rf app/build/intermediates/cxx app/.cxx` then rebuild |
| Multiple receivers on one LAN collide / connections misrouted | `gethostname()` returns "localhost" on Android | mDNS hostname is derived from `hwaddr` (vendor patch in `mdnsd/dnssd_mdnsd.c`) |
| Frames must not be kept past the callback | The core `free()`s `video_decode_struct.data` on return | Always copy in the JNI layer (already done) |
| JNI "thread has no JNIEnv" crashes | Callbacks fire on the core's RTP threads | `env_for_thread()` attaches on demand (already done) |
| `UnsatisfiedLinkError` / `NoClassDefFoundError` at startup after a rename | JNI symbol names and `FindClass` strings encode the Kotlin package | See "Package or class renames" below |

## When modifying the code

- **Advertised device parameters** (model, srcvers, features, ports): TXT records
  are built by the vendored `dnssd` from `lib/dnssdint.h` constants; geometry comes
  from `raop_set_plist` in `receiver_jni.c`. If a sender update breaks
  compatibility, these strings/bitmasks are the knobs - compare against current
  UxPlay upstream. They are protocol values: never rebrand them.
- **Ports**: RTSP 7000, mirror data TCP 7100, UDP 7011/7001/7101 - set in
  `nativeStart`. The mDNS registration must match.
- **Version indicator**: `MainActivity` shows `versionName` in the top app bar
  (it becomes *Update to vX.Y.Z* when a newer release exists) and in *This receiver*, and fetches `releases/latest` from `api.github.com` once
  per dashboard composition. This is the app's only request outside the LAN; keep
  README "Privacy and security" in sync if that changes.
- **Audio**: `cb_audio_process` in `receiver_jni.c` delivers decrypted audio to
  `AudioRenderer`. Mirroring and iOS audio-only are AAC-ELD (`ct=8`, spf 480);
  legacy RAOP audio (iTunes / older macOS / some Android senders, via `ANNOUNCE`)
  is ALAC (`ct=2`, spf 352), decoded in-process by `app/src/main/cpp/alac/` to
  PCM (`ct=0`) before the JNI hop. The negotiated codec/lifetime reach Kotlin via
  the `audio_running(bool, ct)` callback. Dropping audio frames is protocol-safe
  (the core handles resends).
- **Now playing**: DMAP metadata, cover art and progress reach Kotlin through
  `onNowPlaying` / `onCoverArt` / `onProgress` (see `docs/architecture.md`). Never pass
  sender text to `NewStringUTF`: build strings with `utf8_jstring` (CheckJNI aborts
  debug builds on invalid modified UTF-8). An audio-only session brings the app forward
  like mirroring and shows `NowPlayingScreen`; Back/Home disconnects the sender.
- **Legacy RAOP audio (RSA)**: `rsakey.c` holds the AirPort Express key; `raop.c`
  answers `Apple-Challenge` and dispatches `ANNOUNCE`; `raop_handlers.h` parses
  the SDP (RSA-OAEP AES key, ALAC fmtp) and the Transport-header SETUP. The RAOP
  record advertises `et=0,1 ek=1 am=AirPort4,107`. **Modern macOS Music does not
  use this path** - it sends FairPlay SAP v2.5 (`fp-setup` version `0x02`), which
  playfair cannot answer and no open code implements; see `docs/uxplay-patches.md`.
  Do not spend time trying to make macOS Music audio work from the receiver.
- **H.265**: optional experimental dashboard setting (off by default). Bit 42 is
  advertised only when `VideoDecoderSupport` finds a hardware HEVC decoder for
  the advertised size/rate. JNI carries the codec on each frame; `VideoFrameQueue`
  handles HEVC VPS/SPS/PPS and random access, and `VideoRenderer` selects that
  decoder. H.264 stays available. A renderer failure in an HEVC session suppresses
  HEVC advertisement until the setting is toggled or the process restarts.
  The sender still chooses the codec: upstream saw a Mac on Wi-Fi select AVC at
  2160×1440 despite bit 42 (also seen on a 2000×1200 tablet), while an iPad and an iPhone negotiated HEVC. For a real
  codec A/B comparison keep resolution, content, frame-rate limit and network
  identical, and confirm `negotiated video codec` in logcat on both runs.
- **Screen orientation & rotation**:
  `ScreenOrientation` exposes `LANDSCAPE`, `PORTRAIT`, and `AUTO`. `AUTO`
  (accelerometer rotation) is offered when `ReceiverIdentity.supportsAutoOrientation()`
  finds an accelerometer and the device is not a TV (UI mode or leanback/television
  feature). Do not detect devices by model-name substrings: upstream's Portal
  heuristics ("Go", "+", "TV" in `Build.MODEL`) misfired on ordinary phones and
  tablets and were removed. Manual
  `PORTRAIT` / `LANDSCAPE` stay available everywhere. Because macOS/iOS negotiate
  resolution at session setup and do not renegotiate geometry mid-session,
  switching orientation disconnects any active mirroring client
  (`disconnectClient("orientation_changed")`) and restarts the native receiver to
  re-advertise transposed dimensions in RTSP `/info`.
- **Preferred resolution**: `ResolutionPreset` (native, fixed 16:10 / 16:9 sizes,
  80/64/50 %) feeds `scaledDisplayInfo()` in `ReceiverServer`, which every
  `computeDisplayInfo` call goes through. A change reuses the orientation path
  (`applyOrientation(..., resolutionChange = true)`), so the notice reads
  "Resolution Changed". `NATIVE` must stay exactly the panel size (no rounding);
  fixed presets larger than the panel are filtered by `available()` and ignored by
  `scaledDisplayInfo()` if saved on another device. Covered by `ResolutionPresetTest`.
- **Decoder buffering**: `VideoRenderer` builds its queue with `STEADY_MAX_FRAMES`
  (90) and `STARTUP_MAX_FRAMES` (120). Do not lower them back to 12: an overflow
  drops the prediction chain, the receiver cannot request an IDR, and the session
  ends 2 s later. Catch-up rendering (`CATCH_UP_DEPTH`, `CATCH_UP_MAX_FREEZE_NS`)
  lives in `drainOutput`. Details in `docs/architecture.md`.
- **Wi-Fi lock**: `ReceiverIdentity.holdWifiLock()` / `releaseWifiLock()` sit next to
  the multicast lock in the server lease. `adb shell cmd wifi force-low-latency-mode`
  is not available to the shell user on Android 14, so the app lock is the only way
  to stop the radio dozing. Verify with `adb shell dumpsys wifi | grep -i seconddisplay`.
- **In-session settings**: a transparent `pointerInput` box over the `SurfaceView`
  opens `MirrorSettingsDialog` on long press. Stats overlay and speaker apply live;
  resolution and HEVC restart the receiver. Back inside the dialog only closes it.
- **Pinch zoom**: the same overlay box runs `detectTransformGestures` (pinch 1-5×,
  pan) and a double tap reset; the math lives in `PinchZoom` (covered by
  `PinchZoomTest`). The zoom is applied by laying the `SurfaceView` out at the zoomed
  size inside a `clipToBounds` box, **not** with `graphicsLayer`: a SurfaceView's
  surface is a separate layer and does not follow Compose layer transforms. The
  codec keeps its own buffer size, so a large view does not allocate large buffers.
  Zoom resets when the picture aspect changes and is never sent to the sender.
- **Picture-in-picture**: Home while mirroring shrinks the stream into a floating
  window (`setAutoEnterEnabled` on Android 12+, `enterPictureInPictureMode` in
  `onUserLeaveHint` on Android 11). Params follow the video aspect ratio and the
  surface bounds (`setSourceRectHint`). In the window: no zoom, stats or dialogs, and
  `onConfigurationChanged` is not forwarded, so the advertised display never follows
  the window shape. Closing the window (`onPictureInPictureModeChanged` with the
  lifecycle at CREATED) disconnects the sender; so does leaving the app any other way
  with the screen on (`onStop`). The user can turn PiP off per app in system settings
  (checked through AppOps) and in the app's *Floating window* setting
  (`floating_window_enabled`, default on); Home then disconnects as before. The manifest needs
  `supportsPictureInPicture` and `smallestScreenSize|screenLayout` in `configChanges`,
  or entering the window recreates the activity.
- **Receiver lifetime**: `MainActivity.onStart` starts `ReceiverService`; `onStop`
  (not `onDestroy`, which may come hours later) stops the service and the server when
  "Run in background" is off and no other `MainActivity` is visible. The service is
  `START_STICKY` only with background on, and a sticky restart with background off
  stops itself. The close button in the top bar always stops both and calls
  `finishAndRemoveTask()`.
- **ABIs**: `arm64-v8a` and `armeabi-v7a`. Many Google TV sets (CHiQ U43QM9G, TCL with
  2 GB RAM) run a 32-bit Android on a 64-bit SoC, and Play marks an arm64-only app as
  incompatible there. `scripts/build-deps.sh <abi>` builds OpenSSL and libplist per ABI;
  `openssl/configuration.h` differs between them (`THIRTY_TWO_BIT` vs
  `SIXTY_FOUR_BIT_LONG`), so it lives in `deps/openssl/include-abi/<abi>/` ahead of the
  shared headers. Using the arm64 header on armv7 would silently break the bignum code
  in pairing. The 16 KB rule and its CI check apply to `arm64-v8a` only.
- **16 KB pages**: `-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON` (cmake arguments in
  `app/build.gradle.kts`) aligns the 64-bit `libseconddisplay.so` LOAD segments to 16 KB; NDK r27
  defaults to 4 KB. Without it Google Play withholds the app from devices with 16 KB
  memory pages, which report "can't download" (a Galaxy S26 Ultra did). CI checks
  `llvm-readelf -lW` and `zipalign -c -P 16` on every build.
- **Build toolchain**: AGP 9.4.1 with built-in Kotlin (no `org.jetbrains.kotlin.android`
  in the app module; the root only pins the Kotlin Gradle plugin, 2.4.21), Gradle 9.8.2,
  compileSdk 37 (`platforms;android-37.0`), targetSdk 36, Compose BOM 2026.09.00.
  `kotlinOptions` is gone in AGP 9: JVM target is set in `kotlin { compilerOptions }`.
  SDK 37 annotates more platform types as nullable, e.g. `videoCapabilities`.
- **Platform rules (targetSdk 36)**: `startForeground` must pass
  `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE` (manifest type, Android 14); the
  service start is wrapped because Android 12+ can refuse background starts;
  `POST_NOTIFICATIONS` is requested once (Android 13); system bars are hidden with
  `WindowInsetsController` in `onWindowFocusChanged`, the window extends into the
  cutout and the dashboard pads `WindowInsets.displayCutout` (Android 15 edge to
  edge). Inside `MainActivity`, refer to `android.view.WindowInsets` by its full
  name: the Compose `WindowInsets` from `foundation.layout.*` has the same name.
  Android 16 ignores `requestedOrientation` on screens of 600dp and wider; the
  manifest sets `PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY` so the orientation
  setting still works on tablets. That opt-out ends with targetSdk 37, which will need
  another answer (e.g. rotating the dashboard ourselves). Back is handled only
  through `BackHandler` (predictive back: `onBackPressed` and `KEYCODE_BACK` are
  not delivered).
- **Application ID and package**: `applicationId` is `io.github.swidegit.seconddisplay`
  (the GitHub account is `swide-git`; the old ID is taken on Google Play by a managed
  Play private build, and Play never frees package names). `namespace` and the Kotlin
  package stay `io.github.swiderekartmax.seconddisplay`: the JNI function names in
  `receiver_jni.c` (`Java_io_github_swiderekartmax_seconddisplay_NativeBridge_*`) and the
  `FindClass` string encode the Kotlin package, not the application ID; see "Package or
  class renames". Because the two differ, `am start` needs the full class name
  (`<applicationId>/io.github.swiderekartmax.seconddisplay.MainActivity`). The
  user-facing name *Second Display* lives in `app_name`, the dashboard title,
  `ReceiverIdentity.DEFAULT_SERVICE_NAME` and the service notification texts.
- **UI (Material You)**: `SecondDisplayTheme` uses the wallpaper colour scheme on
  Android 12+ and `Palette.LightScheme` / `DarkScheme` (orange) on Android 11, light
  or dark with the system. Composables take colours from `MaterialTheme.colorScheme`
  only; the exceptions are the black mirror background and `TelemetryOverlay`, which
  sits on video and uses the fixed `Palette.Overlay*` colours. The dashboard is a
  `Scaffold` with a status card, resolution and orientation tiles, *Settings*,
  *Recent connections* and *This receiver*; two columns when the window is at least
  600 dp wide in landscape (or 840 dp in any orientation). Clickable rows and tiles
  carry `focusRing` so D-pad focus stays visible. Icons come from
  `material-icons-core` plus `AppIcons`; `material-icons-extended` is not needed
  (debug builds are not shrunk and it adds megabytes there).
- **Standby, battery, TV**: *Standby screen* (`standby_enabled`, off) swaps the
  dashboard for `StandbyScreen` after `STANDBY_AFTER_MS` without touch, key, stream or
  pairing code, at window brightness 0.02; the clock drifts every minute against burn-in.
  `dispatchTouchEvent` and the root `onPreviewKeyEvent` record activity; the first touch
  or key only wakes. `ComponentActivity.dispatchKeyEvent` is library-restricted (lint
  error), hence the Compose key hook, which also opens the in-session settings on OK,
  Enter or Menu (remotes have no long press). `LowBatteryBadge` appears over the
  picture at 15 % or less when not charging (sticky `ACTION_BATTERY_CHANGED`, read once
  a minute while mirroring). On TV the dashboard keeps a 48 x 27 dp title-safe margin
  and *Floating window* is hidden where picture-in-picture is unsupported.
- **Picture delay (A/V sync)**: mirroring plays audio and video as they arrive, so a
  Bluetooth output (100-300 ms) leaves sound behind the picture. The fix delays the
  picture, not the sound: `DecoderPump` submits a frame only `pictureDelayNanos` after
  it was queued, and the catch-up rule counts `VideoFrameQueue.dueDepth` (frames past the
  delay) instead of `depth`, so held frames are not mistaken for a backlog. 0-500 ms,
  `picture_delay_ms`; the 90-frame queue covers 500 ms at 60 fps. The dashboard suggests
  it when an A2DP or LE audio output is present. RAOP's `Audio-Latency` header only
  matters for buffered audio-only streams.
- **Require a code (AirPlay PIN)**: off by default (`require_pin`). On: `dnssd_init`
  gets `pin_pw=1` (TXT `pw=true`, RAOP `sf=0x8c`) and `raop_set_plist(raop, "pin", 0)`
  draws a random code per pairing. `cb_display_pin` → `onDisplayPin` shows it
  (`PairingCodeDialog`, status text, notification; brings the app forward like a
  stream). After pairing `cb_register_client` → `onClientRegistered` stores the sender's
  base64 ed25519 key (`paired_senders`, "key<TAB>name"); `nativeSetTrustedSenders` loads
  them before `nativeStart`, and `cb_check_register` lets them skip the code. Toggling
  restarts the server because `pw=` is in the mDNS record.
- **Notification, tile, hotspot hint**: the service notification has **Disconnect**
  (`ACTION_DISCONNECT` → `disconnectClient`, only during a session) and **Stop**
  (`ACTION_STOP`). `ReceiverTileService` (Quick Settings) shows `ReceiverService.isRunning`;
  a tap stops the receiver or opens the app, because starting a foreground service from a
  tile is not reliably allowed. The service calls `ReceiverTileService.refresh` on create
  and destroy. `NetworkInfoHelper.getHotspotAddress` finds the soft AP by interface name
  (`ap*`, `swlan*`, `softap*`); the status card then says a Mac can join the hotspot.
  The dashboard refreshes network details every 5 s.
- **Problem reports**: `SecondDisplayApp` installs `ProblemReport`'s uncaught-exception
  recorder (`files/last-crash.txt`, then the default handler). *Report a problem* builds
  the report off the main thread: summary (version, device, Android, page size), the last
  crash and `logcat -d` (an app reads only its own entries), copies it and opens a
  prefilled GitHub issue, or shares it. Nothing is sent automatically; keep it that way,
  the privacy policy says no data is collected.
- **R8**: release builds are minified and resource-shrunk (about 6.7 MB instead of
  28 MB); the App Bundle carries the mapping file for Play. `app/proguard-rules.pro`
  keeps `NativeBridge` (JNI function names) and `NativeBridge$Listener` with every
  implementation (`receiver_jni.c` calls its methods by name). A new JNI callback or
  `external fun` needs no new rule as long as it lives in those two types; anything
  else the native side looks up by name does. `scripts/check-jni-names.sh <apk>` (also a CI step) checks every `GetMethodID` name and
  `Java_..._NativeBridge_*` function against the APK's dex.
- **Launcher icon**: adaptive icon in `mipmap-anydpi-v26/ic_launcher.xml` (vector
  monitor and tablet in the Material You tones of `Palette.kt`: `#FFDCC2` background,
  `#FF9F43` monitor, `#904D00` tablet; scaled to 92 % so the corners stay in the 72 dp
  visible area; monochrome layer for themed icons). The TV banner and the 512 px Play
  icon (`docs/images/second_display_icon.png`, square, 32-bit) use the same design, as
  does the 1024x500 Play feature graphic (`docs/images/play_feature_graphic.png`). The
  PNG mipmaps are only legacy fallbacks; the source design is
  `docs/images/second_display_icon.svg`.
  The notification uses `ic_notification.xml`, the same glyph cropped to 24 dp and
  white (status bar icons use only alpha), tinted `#904D00` with `setColor`.
- **Package or class renames**: the JNI functions in `receiver_jni.c`
  (`Java_io_github_swiderekartmax_seconddisplay_NativeBridge_*`) and the
  `FindClass("io/github/swiderekartmax/seconddisplay/NativeBridge$Listener")` string encode
  the Kotlin package and class names. The build does not catch a mismatch; it
  fails at runtime. The native library name `seconddisplay` must match
  `System.loadLibrary` in `NativeBridge.kt` and the CMake target in
  `app/build.gradle.kts`.
- **UxPlay upgrades**: follow `docs/uxplay-patches.md` - re-vendor `lib/`,
  re-apply each listed change, and keep the `dns_sd/` backend excluded.

## Rebuilding the native deps (only to upgrade OpenSSL/libplist)

```bash
# NDK r27d: $ANDROID_NDK_ROOT, .tools/ndk-r27d or $ANDROID_HOME/ndk/27.3.13750724
./scripts/build-deps.sh arm64-v8a
./scripts/build-deps.sh armeabi-v7a
```

Gotchas already encoded in the script: OpenSSL 3.0 wants `ANDROID_NDK_ROOT` (not
`HOME`) and rejects `no-apps`; libplist 2.6.0's release tarball is autotools-only;
its `make install` fails on ranlib PATH lookup *after* installing the archive -
the script runs `llvm-ranlib` manually. It also strips debug info from
`libplist-2.0.a` so local build paths (`/Users/...`) never end up in the committed
archive; check with `strings -a <archive> | grep /Users/` before committing
rebuilt deps. Update `THIRD_PARTY_NOTICES.md` if versions change.

## Releases (GitHub Actions)

`.github/workflows/release.yml` runs on the public GitHub repo (Gitea keeps using
`.gitea/workflows/`, which takes precedence there):

- **Pull requests:** unit tests + debug APK, uploaded as a workflow artifact.
- **Push to `main`:** automatic release: next version = newest `vX.Y.Z` tag with the patch
  bumped (`#minor` / `#major` in the merge message bump those, `[skip release]` publishes
  nothing). The same run creates the tag and the GitHub Release, because tags pushed with
  `GITHUB_TOKEN` do not start another workflow run. The old rolling `latest` pre-release
  is deleted by that step.
- **Tag `vX.Y.Z`:** signed release APK published as a GitHub Release with
  `second-display.apk` and its `.sha256`.
- Every published release (automatic or tagged) also carries `second-display.aab`,
  the signed App Bundle for Google Play (Play takes bundles, not APKs, for new
  apps), plus its `.sha256`. Play App Signing uses the release key as the upload key.

Versions come from `scripts/version.sh` (newest reachable `vX.Y.Z` tag): a tagged
commit builds as `X.Y.Z` with versionCode `X*1000000 + Y*10000 + Z*100`; a `main`
build N commits later is `X.Y.Z-N-g<sha>` with `+N` (capped at 99), so the next tag
always upgrades it. Minor and patch must stay ≤ 99. Local builds default to
`0.1.0-dev` / versionCode 1.

`targetSdk` is 36 (Google Play minimum for new apps and updates), so lint's `ExpiredTargetSdkVersion` no longer needs an override.
Local builds pass `SECOND_DISPLAY_VERSION_CODE` / `SECOND_DISPLAY_VERSION_NAME` to install over
an existing build (see "Build, deploy, run").

Release signing reads `SECOND_DISPLAY_KEYSTORE_PATH`, `SECOND_DISPLAY_KEYSTORE_PASSWORD`,
`SECOND_DISPLAY_KEY_ALIAS` and `SECOND_DISPLAY_KEY_PASSWORD`. CI fills them from repository
secrets (`SECOND_DISPLAY_KEYSTORE_BASE64` plus the other three). Without a keystore,
`assembleRelease` produces an unsigned APK, and pushes to `main`/tags fail rather
than publish unsigned builds. Never commit keystores (`*.jks`/`*.keystore` are
gitignored). Release-signed and debug-signed APKs can't replace each other on a
device: uninstall first when switching (this resets the receiver identity and the
"Display over other apps" grant).

## Hard limitations (don't promise otherwise)

- Wi-Fi only, same LAN, no AWDL/p2p. AirPlay senders only; no Windows or Linux
  (see "Windows and Linux").
- The sender decides mirroring vs extended display, resolution within the
  advertised modes, and codec; the receiver advertises capabilities and renders
  what it is sent.
- No PIN or password: any device on the LAN can connect. This is intentional.
- macOS/iOS updates can and do break third-party receivers; treat
  `model`/`srcvers`/`features` as moving targets.
- **GPLv3**: everything here links UxPlay. Never copy code from this repo into
  non-GPL-compatible projects, and keep `LICENSE` and `THIRD_PARTY_NOTICES.md`
  intact.
