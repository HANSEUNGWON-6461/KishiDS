# KishiDS for Android

The phone version of [KishiDS](../README.md): remap buttons, tune sticks and triggers, set the LED, calibrate, and flash firmware on a Razer Kishi V1,
straight from the phone it is clamped to. It mirrors the Windows app page for page (Overview, Buttons, Sticks, Triggers, D-pad, Lighting, Calibration,
Identity, Firmware) and speaks the same protocol, so profiles saved by either app load in the other.

Built for how the Kishi is actually held: **landscape** gets the desktop's side navigation and two-column pages (with the system bars hidden on short
screens); **portrait** gets a bottom bar. Dark and light themes use the desktop palettes. Plain Android framework views, no libraries.

## Build

Needs JDK 17 and the Android SDK (platform `android-34`, build-tools 34). No Gradle, no Android Studio. Use JDK 17 specifically: with JDK 21's
`javac`, build-tools 34's `d8` stops with an internal error (`NullPointerException`) on an anonymous class in `AppModel`.

```powershell
.\build.ps1            # -> dist\KishiDS.apk   (min Android 7.0)
.\build.ps1 -Install   # also installs it with adb
.\test\run.ps1         # JVM self-tests of the core and the V2 Pro diagnostics (no phone needed)
```

The first build creates a throw-away signing key in `keystore\` (git-ignored). Use `-Keystore`, `-KeyAlias`, `-KeyPass` for your own release key.
The firmware image `firmware-research\ds4-firmware\kishi_ds4.bin` is packaged into the app, so rebuild after every firmware build. The config schema is
generated for Java by `firmware-research\tools\gen_config.py` (same single source of truth as the firmware and the Windows app); the controller artwork
is generated from `SVG\` by `tools\make_art.py`; icons by `tools\make_icons.py`.

Developer launch extras (`adb shell am start -n com.electrotamp.kishids/.ui.MainActivity ...`): `--ez demo true` runs with synthetic controller data and
no USB, `--es page <Name>`, `--ei guide <1-3>`, `--es phase waiting|ready|flashing|done|failed`, `--ez light true`.

## How it talks to the controller

Android is the USB host and has no HID feature-report API, so the app claims the Kishi's interface (detaching Android's HID driver while the app is in
front) and sends the HID class requests itself: `GET_REPORT`/`SET_REPORT` on feature report `0xAB` (telemetry) and `0xAC` (live editing), exactly what the
Windows app does through `HidD_GetFeature`/`HidD_SetFeature`, plus the interrupt-IN input report for the live stick/trigger previews. Settings apply
instantly and are saved to the controller's flash after a quiet moment, so they work in every game. A few seconds after the app leaves the screen it hands
the controller back to Android's HID driver.

Android asks for USB access per device each time the controller (re-)enumerates: once on connect, and again after a flash.

## How Razer's own app updates the firmware (and what we do the same)

Decompiling Razer's Android app (`com.razer.mobilegamepad.en`, library `com.razer.usbdfu`) shows a plain USB DFU update, with nothing proprietary:

1. The user must put the controller in *Firmware Update mode* by hand (detach from the phone, hold the button combination, reattach). The app only
   detects the bootloader (`27F8:0BC0`, product string contains "bootloader") and offers the update; it cannot reboot the controller into it.
2. It claims interface 0 (forcing the kernel driver off) and sends standard DFU control requests: `GET_STATUS`, `CLEAR_STATUS`, then `DNLOAD` in 1,024-byte
   blocks (block number in `wValue`, alternate setting 0), then a zero-length `DNLOAD` to start the manifest. No signing, container or encryption.
3. It picks the image by the controller's bcdDevice firmware version (2.70 for this unit's family).

KishiDS does the same transfer (`core/DfuFlasher.java`), additionally polling `GET_STATUS` until the bootloader is idle after each block (as `dfu-util`
does), checking every status, and treating the controller's reset after manifest as success. The same user steps apply, so the Firmware page walks
through them. Razer's original image is not bundled: with Razer's Kishi app installed, **Find it for me** reads it straight from the installed package
(verified by SHA-256); otherwise pick the `.apk` / `.bin` file.

## Kishi V2 Pro: recognition and read-only diagnostics

The app recognises a Razer Kishi V2 Pro (`1532:0717`) as its own model. It does **not** configure it and does **not** flash it: KishiDS firmware and
Razer's V1 firmware are for the Kishi V1 only, and the V2 Pro's bootloader, update mode and firmware format are unknown. See
[`V2PRO_ANALYSIS.md`](../V2PRO_ANALYSIS.md).

- **Recognition only.** Plugging in a V2 Pro shows "Kishi V2 Pro detected". The app claims nothing and asks for no permission, so the controller keeps
  working in games.
- **Kept out of every V1 flash path.** Apply, Restore and the setup steps are refused while a V2 Pro is attached. This is checked in the UI, in
  `AppModel` and again in `UsbLink.flash()` just before the bootloader is opened.
- **USB diagnostics page** (More → USB diagnostics). It lists every attached USB device with its interfaces and endpoints, which needs no permission.
  For a V2 Pro, the listing is compared with the recorded table (5 interfaces, IN `0x81 0x82 0x83`, OUT `0x02 0x03`). **Read report descriptors** asks
  for USB access, then reads the HID report descriptor of each HID interface and decodes it. Android's HID driver is detached while this runs, so input
  stops for a moment and the controller is reset afterwards. The result can be copied or saved as a text file.
- **Input mapping** (same page). A guided capture asks for one control at a time (A, B, X, Y, D-pad, bumpers, triggers, sticks, small
  buttons, optional M1/M2) and records which input report fields react. It claims the HID interfaces that have an IN endpoint (0, 1, 2) and
  only reads them: no control request and no OUT transfer. Games get no input until it ends, and it stops by itself a few seconds after the
  app leaves the screen. The result, including every distinct raw report, goes into Copy/Save.
- **Read-only by construction.** Every diagnostic control request goes through `v2pro/ReadOnlyPipe`, which passes only `GET_DESCRIPTOR` (HID report
  descriptor) and refuses everything else, including `SET_REPORT` and any DFU request.
- `test\run.ps1` now also compiles `v2pro\` and runs `test\V2ProSelfTest.java` (simulated V2 Pro, V1 regression checks) before `SelfTest`.

**Not verified on hardware.** The V2 Pro code paths have been run only against simulated devices in the JVM tests. The APK builds, but nothing about
the V2 Pro has been tried on a phone with a real controller: the identification, the interface layout, the permission prompt, the claim, the descriptor
request and the hand-back after the reset.

## Not yet verified on a phone

The core (config block, live protocol, telemetry, profiles, stock import, calibration, DFU against a simulated bootloader) is covered by `test\run.ps1`,
and the UI was exercised in an emulator in demo mode. Not yet run against a real controller: USB permission and interface claim on a real phone, the
control transfers, input-report streaming, and handing the controller back to Android afterwards (it uses a USB reset, which the platform may refuse; if
games stop seeing the controller after the app was open, unplug and replug it). The app keeps a technical log (Firmware page) for diagnosing exactly this.
