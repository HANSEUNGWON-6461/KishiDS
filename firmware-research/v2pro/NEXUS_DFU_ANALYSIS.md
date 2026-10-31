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
