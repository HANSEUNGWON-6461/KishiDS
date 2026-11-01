# Razer Nexus (com.razer.bianca) firmware-update analysis — relevant to the Kishi V2 Pro

Decompiled the user's installed Razer Nexus APK (jadx) on 2026-11-01 to learn how the Kishi V2 Pro is updated, purely to judge whether a
custom DS4 firmware could be flashed. **No Razer binary (APK or firmware) is committed here** — this file records only findings. The firmware
image was downloaded from Razer's public CDN for local inspection, not redistributed.

## Device identity (app codename "Bianca")

| | value |
|---|---|
| Model name | "Razer Kishi V2 Pro" (`KISHI_V2_TIER1_PRODUCT_NAME`) |
| Group | `KISHI_V2_TIER1` |
| VID | `0x1532` (5426) |
| PID, HID mode | `0x0717` (1815) ← the unit the user has |
| PID, XInput mode | `0x0037` (55, "XINPUT_SPOOF_PID") |
| PID, XInput+ mode | `0x0718` (1816) |
| Bootloader / recovery | VID `0x1532`, PID `0x110E` (4366), product string "Recovery" |

So the V2 Pro presents a different PID per controller mode, and a separate "Recovery" identity `1532:110E` in bootloader mode.

## How Nexus updates firmware

1. **Manifest** per PID from `S3Env` (base `https://mobileapp-assets.razerzone.com/`):
   - Kishi V2 Pro (T1): `BiancaFw/T1/firmware_update_v3.json`
   - Fetched content (2026-11-01):
     ```json
     {"relative_url":"BiancaFw/T1/02.02.00.00/firmwareHex.hex","firmware_version":"02.02.00.00",
      "firmware_release_label":"2.2","min_supported_nexus_version_code":3600012,"latest_nexus_version_code":3600012}
     ```
   - So the latest V2 Pro firmware is **2.2 (02.02.00.00)**, delivered as a plain **Intel HEX** at
     `https://mobileapp-assets.razerzone.com/BiancaFw/T1/02.02.00.00/firmwareHex.hex`.
2. **Integrity = CRC only.** `FirmwareIntegrityVerifierImpl` checks **CRC16** (poly `0x1021`) or **CRC32**, read from a `FileInfo.json`
   inside an optional ZIP (`IntegritySpec.ZipWithManifest`), or `IntegritySpec.None`. A grep of the whole `updater/`, `integrity/` and
   `bootloader/` packages found **no RSA, ECDSA, Ed25519, X.509, SHA-256 signature or public key**. The client does not cryptographically
   authenticate firmware; it only guards against a corrupted download.
3. **Transfer = custom Razer DFU over USB**, driven by the native `libRazer_IoT_SDK.so` (commands `DfuFirmwareUpdater`,
   `FlashFirmwareUpdater`, `SetDFUChipSelection`, `GetDFUDeviceInformation`). The command headers (`CommandHeader.Dfu`) are:
   `GetDeviceInformation → SetErase → SetProgram → GetVerify → SetAbort → SetExit`. Entry to the bootloader is a command
   ("SetDfuNeedBootLeader"); like Razer's V1 app, Nexus only detects the already-entered bootloader, it does not document a key combo here.
   This is **not** standard USB DFU 1.1 (unlike the V1 Kishi); it is a vendor command set, and the wire framing lives in the native SDK.
4. **Recovery** exists (`RecoveryViewModel`, the "Recovery" `1532:110E` identity). The 2.2 image is publicly downloadable, so a stock
   restore is possible **as long as the bootloader region is never overwritten and the DFU path stays reachable**.

## The MCU (from the real 2.2 image)

Parsed `BiancaFw/T1/02.02.00.00/firmwareHex.hex` (262 KB Intel HEX, 93,184 bytes of data):

| | value |
|---|---|
| Application flash | `0x00008000`–`0x0001EC00` |
| Implied bootloader region | `0x00000000`–`0x00007FFF` (~32 KB, not in the app image) |
| Initial SP (vector[0]) | `0x04008000` |
| Reset handler (vector[1]) | `0x00008215` (thumb) → ARM Cortex-M |
| RAM | based at `0x04000000`, ~32 KB |
| Entry | `0x00008200` |

The bundled fallback image in the APK (`res/raw/fw_1_00_01.hex`, v1.00.01) is based at `0x20000000` with the same SP `0x04008000` — a
RAM-run/alternate variant sharing the same RAM map.

**This is not the V1 Kishi's STM32F072** (STM32 has flash at `0x08000000`, RAM at `0x20000000`). RAM at `0x04000000` is unusual for a
Cortex-M and points to a specific unidentified vendor part. The firmware contains almost no ASCII strings (only "Razer Bianca Android",
"XUSB10") — not enough to name the chip. **Identifying the MCU needs a physical teardown** (reading the chip markings).

## What this means for a DS4-on-iPhone firmware

- **The flashing door looks open on the app side:** firmware is unsigned (CRC only), the DFU is a reverse-engineerable vendor command set,
  and a public stock image + recovery path exist. The one unknown is whether the **on-device bootloader** enforces its own signature check;
  that lives in the MCU and cannot be seen from the app. There is real brick risk until that is proven.
- **The actual work is enormous and cannot start yet.** A DS4 emulator would have to be written from scratch for an *unidentified* Cortex-M
  MCU: its USB device stack, the exact wired-DualShock 4 descriptors and feature-report handshake, and whatever iOS checks after
  enumeration. The V1 project succeeded because someone had already done the STM32 bring-up; for the V2 Pro none of that exists. Required
  before any firmware could be attempted: (1) identify the MCU by teardown, (2) reverse-engineer the native `libRazer_IoT_SDK.so` DFU
  transport, (3) reverse-engineer the stock 2.2 image's board bring-up (clocks, USB, pins, haptics). This is a multi-month embedded
  reverse-engineering effort with brick risk, not a small patch.

## Facts vs. unknowns

Established (from the app + the public firmware image): identity/PIDs, bootloader identity, manifest/CDN, firmware format, CRC-only
integrity, the DFU command set, the memory map, and that the MCU differs from V1.

Unknown: the MCU part number; whether the device bootloader checks a signature; the native DFU wire framing; the stock firmware's internals.

## Appendix: DFU command set and transport (decompiled 2026-11-01)

Extracted from the Nexus app's Java (`CommandHeader`, `BaseCommandHeader`) and the native `libRazer_IoT_SDK.so` (string/symbol analysis; `.text` local symbols are stripped, so exact native byte-packing was not recovered).

### Command header serialization (`BaseCommandHeader.getHeader()`)
8-byte header, then the command payload:
```
[0] 0x00  [1] 0x00  [2] 0x00  [3] 0x00  [4] 0x00  [5] size  [6] commandClass  [7] commandId
```

### DFU commands (commandClass = 0x10)
| Command | size | id | payload | direction |
|---|---|---|---|---|
| GetDeviceInformation | 0x09 | 0x80 | — | get |
| SetErase | 0x08 | 0x01 | StartAddress(LE32) + EndAddress(LE32) | set |
| SetProgram | 0x08 | 0x02 | StartAddress(LE32) + binary data | set |
| GetVerify | 0x05 | 0x83 | StartAddress(+length) → device returns CRC16 | get |
| SetAbort | 0x00 | 0x04 | — | set |
| SetExit | 0x00 | 0x05 | — | set |

(ids with the high bit set, 0x80/0x83, are the read/"get" commands; low ids are "set". From the `CommandHeader.Dfu` subclasses.)

### Protocol semantics (from native log/error strings)
- Flow: GetDeviceInformation → SetErase(region) → SetProgram(chunks) → GetVerify(CRC16) → SetExit. SetAbort on error.
- Each command echoes its arguments back; the app checks them ("DFUErase: startAddressRet != StartAddress || endAddressRet != EndAddress", "DFUProgram: ... strBinDataRet != strBinData", "DFUVerify: strVerifyBinData != strBinData").
- Verify = the device computes **CRC16** over the flashed region ("ask firmware to calculate the CRC16", `FlashRegionCRC16`, `FwCheckSum != SwCheckSum`) and the app compares. **No cryptographic signature on device or client** — integrity only.
- The native updater for the Kishi is `BiancaFlashFirmwareUpdater` / `FlashFirmwareHexUpdater` (parses Intel HEX), alongside generic `DfuFirmwareUpdater`. (`vendor/ry72xx/Ry72xxFirmwareUpdater` is for Razer **earbuds**, not the Kishi.)

### Transport
The app does **not** issue raw `controlTransfer` for DFU. It hands the firmware to the native SDK (`RazerUsbDeviceManager.updateDFUFirmwareVersion` → native `Razer_CMD_SDK` / `sendCmdPayload`), after configuring the USB path through `Razer_USB_SDK.setNativeUsbInterfaceID / setNativeUsbEndpointType / setNativeUsbMode / setNativeUsbValue`. The native SDK wraps the header+payload into HID reports (`SetSendHIDControl` / `SetSendHIDDeviceControl`) and performs the USB I/O. The V2 Pro's vendor interfaces 3/4 (64-byte interrupt OUT, from the HID descriptor dump) are the likely carrier.

### What is still needed to build a working flasher
- The **exact USB wrapping**: which interface/endpoint, SET_REPORT vs interrupt-OUT, report ID, and chunk/packet size. These live in the stripped native SDK. The reliable + safe way to capture them is a **USB bus capture of a real Nexus firmware operation** (even just GetDeviceInformation/GetVerify), not further static disassembly.
- The MCU part number (for writing any custom image) — still unidentified; needs a physical teardown. `libRazer_IoT_SDK.so` did not name the Kishi MCU.

## Decisive finding (2026-11-01): iOS support is MFi, and the V2 Pro firmware has none

Compared the stock firmware images of the iOS-capable siblings against the V2 Pro, all from Razer's public CDN:

| firmware | `apple` strings | `mfi` strings | gamepad axes |
|---|---|---|---|
| Kishi V2 Pro (BiancaFw/T1, 2.2) | **0** | **0** | 8-bit (0-255) |
| Kishi V2 (BiancaFw/T2_PID_1819, 2.0) | 1 | 1 (`com.apple.mfi.launch_notify_previous`) | 16-bit signed |
| Kishi Ultra (DeniseV2/T1, 2.0) | 1 | 1 (`com.apple.mfi.launch_notify_previous`) | 16-bit signed + digitizer |

- The iOS-capable models carry an **MFi / iAP2 (Made for iPhone) accessory element**. The V2 Pro firmware contains **no Apple/MFi/iAP code at all**.
- Wired USB-C controllers on iPhone are effectively MFi-gated. This matches the observed behavior: the V2 Pro is rejected by iPhone in both its HID and XInput modes.
- So the gap between "works on iPhone" and "doesn't" is **not a single patchable descriptor** - it is a whole MFi/iAP2 implementation the V2 Pro lacks, plus a different (standard 16-bit) HID descriptor.

### Why this very likely blocks the firmware route
MFi/iAP2 authentication normally needs an **Apple MFi authentication coprocessor** (a physical IC). If Razer left that IC off the Android-only V2 Pro - plausible, and consistent with the firmware having zero MFi code - then no firmware change can make the V2 Pro an iOS wired controller, because the hardware to pass Apple's auth is absent. Razer's "a future firmware update will add iPhone support" implies the hardware is capable, but user reports say the shipped update did nothing.

### The one remaining check
Whether the V2 Pro PCB physically carries the Apple MFi auth coprocessor can only be answered by a **teardown**.
- If absent -> the iPhone firmware path is **not feasible**; keep the V2 Pro for Android and use a natively iOS-supported controller (Kishi V2 / Ultra / V3 Pro) on iPhone.
- If present -> a firmware port of the MFi + standard-HID path from a sibling is theoretically possible, but a large port (not a patch), with brick risk, still gated on identifying the MCU.
