# LEDWallGUI — Android 14 + nRF52840 controller upgrade (v2 source preview)

**Source of truth:** This revision starts from the newly uploaded `LEDWallGUI-master(1).zip` Android Studio project and the separately uploaded `DormLED_RTC_Android.ino`. The separately uploaded sketch had improved, labeled `Type:Name:{Value}` request responses compared with the version embedded in the ZIP. Those response semantics are retained. The Arduino implementation builds upon the immediately preceding clock/room configuration revision and preserves the original 1,165-LED effects and hardcoded clock layout as fallback.

**Status: SOURCE UPDATE, NOT HARDWARE-VERIFIED.** The host has no Android SDK, Android emulator, Adafruit nRF52 board package, or ItsyBitsy/M8 hardware. No APK build or Arduino upload was possible in this environment. The isolated Kotlin protocol tests and Python static assertions pass. Use Android Studio and Arduino IDE to compile before using this as your permanent controller setup. Back up existing firmware and APK first.

## Updated features

| Area | Change | Source |
|---|---|---|
| Bluetooth discovery | BLE scan replaces classic Bluetooth discovery; named devices are displayed, and user can show unnamed devices | `ConnectionActivity.kt` |
| BLE connection | One shared native Android `BluetoothGatt` session used across Activities and reconnect service; one write in flight; explicit CCCD notification subscription; read mode list after subscription; no Activity-owned background BLE scopes; MTU request and 15s connect timeout | `GlobalBluetoothManager.kt` |
| Reconnection | Persist last address, avoid simultaneous connects, retry with 5–30 second backoff, stop when device is forgotten | `BluetoothReconnectionService.kt` |
| RTC | Read raw DS3231 standard time, read display time, manual set, sync from tablet | `ControllerSetupActivity.kt`, firmware |
| DST | Manual ON/OFF and tablet-derived DST at sync time; store offset in flash | `ControllerSetupActivity.kt`, firmware |
| Room mapping | Four clock-wall rows (first LED, LED count, reverse). Validate ranges/no overlaps, stage and apply, read back, and restore original hardcoded clock map | `ControllerSetupActivity.kt`, `ControllerProtocol.kt`, firmware |
| Microphone | Foreground microphone service uses `AudioRecord` RMS samples every ~150ms, sends an envelope not audio; firmware adds `Audio reactive` mode | `AudioReactiveService.kt`, firmware |
| Motion | Foreground Activity-only CameraX grayscale **changed-pixel detection** on 16×12 samples at ~2.5 frames/s. After 20 seconds idle, MainActivity dims to ~1.5% brightness; repeated motion or touch restores tablet brightness | `TabletMotionWake.kt`, `MainActivity.kt` |
| Cleanup | Original unregistered camera service and full-frame luminosity analyzer removed from active app source; preserved in `legacy_code/` | `legacy_code/` |
| Build | Consistent Kotlin Android Gradle plugin version, versionCode 2 | `build.gradle.kts`, `app/build.gradle.kts` |

## How to build

1. Open the **Android Studio** project directory (where `settings.gradle.kts` lives). Install Android SDK platform **35**, build-tools as requested by Studio, and use JDK 17 or a supported later JDK. Sync the Gradle 8.10.2 project.
2. On Windows: `gradlew.bat :app:assembleDebug --console=plain` then `adb install -r app\build\outputs\apk\debug\app-debug.apk`. The M8 tablet requires Android 14/API 34 or newer; this is intentionally not compatible with older Android devices because the existing project's minSdk was 34.
3. In Arduino IDE, install **Adafruit nRF52 Boards**, select **Adafruit ItsyBitsy nRF52840 Express**, then open `DormLED_RTC_Android.ino` in its own matching-named sketch folder. Install the libraries used in the sketch (`Adafruit_NeoPixel`, `RTClib`, `EasyColor`, `Adafruit TinyUSB`, Adafruit nRF52 Bluefruit and LittleFS). Verify before uploading.
4. The Arduino expects **DS3231 RTC**, strip data on **pin 5**, square-wave on **pin 10**, and `LEDCOUNT = 1165`. These are unchanged from the upload. WS2815 needs its own suitable 12V supply and common ground; do not power the strip through the ItsyBitsy.
5. Keep your **old APK and old sketch** somewhere safe. New Android clock controls require flashing the matched new Arduino sketch; old firmware will not understand the RTC/Wall/Audio commands.

## Tablet interaction

- Tap **Settings > Connection**, grant **Nearby Devices**, select `Room LED Controller`. This is BLE scanning, not classic device pairing.
- In **Settings**, tap **RTC / Wall Map** (replaces previously blank `Sync LED Config` action) to access clock and layout controls. **Refresh** reads actual controller settings.
- **Sync RTC from tablet now**: reads the tablet's local date/time/timezone, applies the tablet's DST flag if `Use tablet's DST when syncing` is on, then writes the **standard local RTC time** (the displayed LED hour applies +1 for DST). Time synchronization is **manual**, not automatically scheduled. The tablet clock and time zone must already be correct.
- Alternatively uncheck `Use tablet's DST when syncing`, change the **DST active** toggle, or manually write the RTC's **standard-time** timestamp. An RTC readback displays both raw DS3231 time and LED display time.
- **Four clock walls**: 0=12→3, 1=3→6, 2=6→9, 3=9→12. For each, enter the physically measured first LED index, LED count, and reverse direction. They can have physical gaps but cannot overlap. Each wall must have at least 15 LEDs; all indices must be within 0..1164. The form does not invent your bedroom's actual lengths. Confirm, then **Apply and save**. `Reset` restores your original hand-tuned 60 minute and 12 hour mappings.
- Mic **On** prompts for microphone permission (if needed), starts a microphone foreground service, selects `Audio reactive`, and sends a smoothed *level* over BLE. It never transmits raw audio. Mic **Off** stops the service and returns to the previous effect when available. On the M8, check both the Android global microphone privacy control and whether the tablet actually has a working microphone.
- Motion Detection **On** prompts for camera permission. When the main LED panel is visible, the camera looks for spatial pixel changes (not average brightness); after 20 seconds with no movement/touch the **main panel dims**, and detected motion/touch brightens it again. Long-press Motion Detection to switch front/back camera. The camera is stopped upon leaving the main panel; no photos are stored or uploaded.

## BLE command protocol

Service: `12345678-1234-5678-1234-56789abcdef0`

- Mode list **read**: `...def1` (up to 512 bytes; ~463 bytes for 23 modes).
- Requests **write with response**: `...def2`.
- Responses **notify/read**: `...def3` (subscribe to CCCD before writing requests).
- Controls **write with response**: `...def4`.

The Android GATT client serializes writes, requests MTU 247, and waits up to 6 seconds for explicit responses. New firmware accepts the old lighting protocol unchanged:

```text
Mode:None:{Rainbow}
Parameter:Speed:{20}
Power:None:{1}
Brightness:None:{50}
```

New protocol:

```text
# Controls sent to ...def4                 # Response on ...def3
RTC:DST:{1}                              RTC:DST:{OK}
RTC:Time:{2026-11-08T22:30:00}           RTC:Time:{OK}
Wall:0:{0,290,0}                        Wall:0:{OK}
Wall:1:{290,290,0}                      Wall:1:{OK}
Wall:2:{580,290,0}                      Wall:2:{OK}
Wall:3:{870,295,0}                      Wall:3:{OK}
Wall:Apply:{1}                          Wall:Apply:{OK}
Wall:Reset:{1}                          Wall:Reset:{OK}
Audio:Level:{30}                        (no response, ~6.7 messages/sec)

# Requests sent to ...def2
RTC:Time:{?}                            RTC:Time:{2026-11-08T22:30:00}
RTC:Now:{?}                             RTC:Now:{2026-11-08T23:30:00}
RTC:DST:{?}                             RTC:DST:{1}
Wall:State:{?}                          Wall:State:{1}
Wall:0:{?}                              Wall:0:{0,290,0}
```

The example wall boundaries above are **illustrative only**, not assumed measurements. Replies of `OK_VOLATILE` mean the change was applied but LittleFS persistence failed. `ERR_*` means rejected. The layout is staged in RAM until `Wall:Apply:{1}`. An applied layout persists to the internal LittleFS file `/ledclock.bin`; first boot uses the original clock points.

**Clock caveat:** The firmware stores local *standard* time in the DS3231 and a persisted manual +1 hour DST offset. The time zone itself is not stored on the controller, and it **does not automatically switch DST on the DST transition date** unless the tablet re-syncs it or the user manually changes DST. In New York, syncing with the tablet auto-DST toggle is straightforward; on other time zones, the one-hour convention may not match local rules.

## Test and diagnostics

From project root, the following have passed here:

```text
python tests/check_source.py
kotlinc app/src/main/java/com/example/ledwallgui/ui/settings/ControllerProtocol.kt tests/ProtocolMain.kt -include-runtime -d protocol-tests.jar
java -jar protocol-tests.jar
```

Hardware tests to run *in this order* after compiling both components:

1. **BLE:** Connect > read all 23 mode names (including `Audio reactive`) > Color > Brightness > Power; switch screens; connect/disconnect ten times and verify no duplicated connection notifications or growing memory.
2. **Reconnection:** Toggle Bluetooth off/on, power-cycle the Arduino, then leave the M8 wall panel running overnight. Watch foreground service and `LedGattSession` logs: `adb logcat -s LedGattSession BluetoothReconnectionService ConnectionActivity`.
3. **RTC:** Read original time/DST; set DST 0 then 1 and verify LED clock changes by exactly an hour; sync and verify both raw RTC and displayed time. Power-cycle to verify saved DST. Restore correct time before using clock mode for long periods.
4. **Clock map:** Record the current fallback config; stage a measured, non-overlapping four-wall layout; inspect the Time effect and its clockwise direction. Reboot and verify readback. Reset and verify the original hand-tuned clock position is restored.
5. **Mic:** Turn Mic on, observe microphone privacy indicator, clap/talk and watch the `Audio reactive` brightness/length change. Stop mic, verify the foreground notification disappears and LEDs return to last mode. `adb logcat -s AudioReactive LedGattSession` helps identify M8 mic initialization/read errors.
6. **Motion:** Turn Motion on and reopen main screen. Confirm screen dims in ~20 s with no motion, restores after 2 consecutive changed frames, also restores on touch, and camera access stops when leaving screen. `adb logcat -s TabletMotionWake` shows camera errors.
7. **Memory:** Android Studio Memory Profiler or LeakCanary in debug builds, measure heap after 30+ mins and repeated connect/settings cycles. Source fixes do *not* prove absence of leaks without device profiling.

### Known limitations / design decisions

- **Not a true screen-off wake:** Android 14 restricts background camera use and ordinary apps cannot reliably reopen the camera after the display fully sleeps. This implementation intentionally **keeps the display powered but very dim while the wall panel is visible**. A hardware PIR sensor plus additional device administration/vendor integration is the better solution if truly dark/fully off display is mandatory.
- No hardware access to this specific M8 BigdroidOS build; manufacturer firmware camera/audio/charging behavior remains unverified.
- No APK or `.hex` included because compilation and physical testing were unavailable here. Do not flash these sources without a verified build and backup.
- The existing BLE service permits unauthenticated writes from nearby clients; Bluetooth bonding/authentication has not yet been added. Keep the controller off untrusted networks/nearby environments until access control is designed.
- Room editor uses four numeric wall segments and a clock-order explanation, not a drag-and-drop floor plan.
