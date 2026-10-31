# Razer Kishi V2 Pro: USB and HID report descriptors

Measured on 2026-11-01 with KishiDS-Android (branch `v2pro-diagnostics`, USB diagnostics page) on a Lenovo Legion Y700 Gen 2.
Normal (game) mode. Read-only: only `GET_DESCRIPTOR` (HID report) was sent. All five claims succeeded, and the USB reset at the end returned true.

## Device

| Field | Value |
|---|---|
| VID:PID | `1532:0717` |
| Manufacturer / Product | `Razer` / `Razer Kishi V2 Pro` |
| Serial | not reported, even with USB access granted |
| bcdUSB / bcdDevice | 2.00 / 1.01 |
| DFU interface in this mode | none |

| # | Class:Sub:Proto | Endpoints | Report descriptor |
|---|---|---|---|
| 0 | 03:01:00 | `0x81` IN interrupt, 16 B, 1 ms | 146 bytes, gamepad |
| 1 | 03:00:01 | `0x82` IN interrupt, 32 B, 1 ms | 462 bytes, touch screen digitizer |
| 2 | 03:01:01 | `0x83` IN interrupt, 16 B, 1 ms | 27 bytes, report 7, 8-byte array |
| 3 | 03:00:01 | `0x02` OUT interrupt, 16 B, 1 ms | 37 bytes, 64-byte output + 90-byte feature |
| 4 | 03:00:01 | `0x03` OUT interrupt, 64 B, 1 ms | 22 bytes, 64-byte output |

The layout comes from the raw configuration descriptor (`getRawDescriptors`), so it agrees with what Android's `UsbInterface` reports.
Interfaces 3 and 4 have no IN endpoint.

## Interface 0: gamepad, input report 1 (13 bytes including the ID)

Decoded independently from the raw bytes below with a separate script, and it agrees with the app's parser.

| Byte | Bits | Field | Range |
|---|---|---|---|
| 0 | | Report ID `0x01` | |
| 1 | 0-7 | Generic Desktop X (`0x30`) | 0-255 |
| 2 | 0-7 | Y (`0x31`) | 0-255 |
| 3 | 0-7 | Z (`0x32`) | 0-255 |
| 4 | 0-7 | Rz (`0x35`) | 0-255 |
| 5 | 0-3 | Hat switch (`0x39`) | 0-7 = 0°-315° in 45° steps, other values = centred (Null State) |
| 5 | 4-7 | Buttons 1-4 | 0/1 |
| 6 | 0-7 | Buttons 5-12 | 0/1 |
| 7 | 0-5 | Buttons 13-18 | 0/1 |
| 7-8 | 10 bits | constant padding | |
| 9 | 0-7 | Simulation Controls Brake (`0xC5`) | 0-255 |
| 10 | 0-7 | Simulation Controls Accelerator (`0xC4`) | 0-255 |
| 11-12 | | Vendor page `0xFF00`, usage `0x21`, 2 × 8 bits | 0-255 |

This is the **declared** layout. Which physical stick, trigger and button drives which field has not been measured yet; that needs input reports
captured while pressing each control.

Report 3 (in a separate Consumer Control collection, also repeated on interface 1), 9 bytes: an 8-bit bitmap of AC Home (`0x223`), AC Back
(`0x224`), Menu (`0x40`), Volume Up (`0xE9`), Volume Down (`0xEA`), Power (`0x30`), Sleep (`0x32`), AL Select Task/Application (`0x1A2`), then 7
constant bytes.

## Interface 1: touch screen digitizer

Digitizer page, Touch Screen (`0x0D:0x04`). Input report 1, 40 bytes: five contacts, each Tip Switch (1 bit + 7 padding), Contact ID (8),
Tip Pressure (8), X (16, logical 0-2048), Y (16, logical 0-4096); then Scan Time (16), Contact Count (8), Contact Count Maximum (8).
Feature report `0x22`: Contact Count Maximum. Plus the same Consumer report 3 as interface 0. Its purpose has not been checked.

## Interface 2

Generic Desktop page, usage `0x00` (undefined) collection. Input report 7: 8 bytes, array, Generic Desktop usage `0x03` (reserved).
The interface descriptor says boot keyboard (01:01), but the report descriptor is not a keyboard. Purpose unknown.

## Interfaces 3 and 4: output and feature channels

Usage page `0x0081`, usage `0x0082`. That page is Monitor Enumerated in the HID usage tables, which this device is evidently not using in that
sense. There is no report ID.
- Interface 3: Output 64 bytes (usages `0x83`, `0x84`). Feature 90 bytes, vendor page `0xFF00` usage `0x04`, flagged Constant.
- Interface 4: Output 64 bytes.

**Unconfirmed observation.** 90 bytes is the size of the feature report used by Razer's PC peripheral control protocol, as implemented by the
open-source OpenRazer driver. That makes interface 3 a likely candidate for Razer's command channel. Nothing has been sent to it, and whether it
speaks that protocol, or offers any way into an update mode, is unknown. **Do not send anything to it before the risks have been assessed.**

## Raw report descriptors

Interface 0 (146 bytes)
```
05 01 09 05 A1 01 85 01 09 30 09 31 09 32 09 35
15 00 26 FF 00 75 08 95 04 81 02 09 39 15 00 25
07 35 00 46 3B 01 65 14 75 04 95 01 81 42 65 00
05 09 19 01 29 12 15 00 25 01 75 01 95 12 81 02
75 0A 95 01 81 03 05 02 09 C5 09 C4 15 00 26 FF
00 35 00 46 FF 00 75 08 95 02 81 02 06 00 FF 09
21 95 02 81 02 C0 05 0C 09 01 A1 01 85 03 0A 23
02 0A 24 02 09 40 09 E9 09 EA 09 30 09 32 0A A2
01 15 00 25 01 75 01 95 08 81 02 75 08 95 07 81
01 C0
```

Interface 1 (462 bytes)
```
05 0D 09 04 A1 01 85 01 09 22 A1 02 09 42 15 00
25 01 75 01 95 01 81 02 95 07 81 03 26 FF 00 75
08 95 01 09 51 81 02 09 30 81 02 75 10 95 01 05
01 55 0F 65 11 26 00 08 35 00 46 FF FF 09 30 81
02 46 FF FF 26 00 10 09 31 81 02 C0 05 0D 09 22
A1 02 09 42 15 00 25 01 75 01 95 01 81 02 95 07
81 03 26 FF 00 75 08 95 01 09 51 81 02 09 30 81
02 75 10 95 01 05 01 55 0F 65 11 26 00 08 35 00
46 FF FF 09 30 81 02 46 FF FF 26 00 10 09 31 81
02 C0 05 0D 09 22 A1 02 09 42 15 00 25 01 75 01
95 01 81 02 95 07 81 03 26 FF 00 75 08 95 01 09
51 81 02 09 30 81 02 75 10 95 01 05 01 55 0F 65
11 26 00 08 35 00 46 FF FF 09 30 81 02 46 FF FF
26 00 10 09 31 81 02 C0 05 0D 09 22 A1 02 09 42
15 00 25 01 75 01 95 01 81 02 95 07 81 03 26 FF
00 75 08 95 01 09 51 81 02 09 30 81 02 75 10 95
01 05 01 55 0F 65 11 26 00 08 35 00 46 FF FF 09
30 81 02 46 FF FF 26 00 10 09 31 81 02 C0 05 0D
09 22 A1 02 09 42 15 00 25 01 75 01 95 01 81 02
95 07 81 03 26 FF 00 75 08 95 01 09 51 81 02 09
30 81 02 75 10 95 01 05 01 55 0F 65 11 26 00 08
35 00 46 FF FF 09 30 81 02 46 FF FF 26 00 10 09
31 81 02 C0 05 0D 55 0C 66 01 10 47 FF FF 00 00
27 FF FF 00 00 75 10 95 01 09 56 81 02 09 54 25
7F 95 01 75 08 81 02 09 55 25 7F 95 01 75 08 81
02 05 0D 85 22 09 55 15 00 25 7F 95 01 75 08 B1
02 C0 05 0C 09 01 A1 01 85 03 0A 23 02 0A 24 02
09 40 09 E9 09 EA 09 30 09 32 0A A2 01 15 00 25
01 75 01 95 08 81 02 75 08 95 07 81 01 C0
```

Interface 2 (27 bytes)
```
05 01 09 00 A1 01 85 07 09 03 15 00 26 FF 00 35
00 46 FF 00 75 08 95 08 81 00 C0
```

Interface 3 (37 bytes)
```
05 81 09 82 A1 01 09 83 09 84 15 00 26 FF 00 75
08 95 40 91 02 06 00 FF 09 04 15 00 25 01 75 08
95 5A B1 01 C0
```

Interface 4 (22 bytes)
```
05 81 09 82 A1 01 09 83 09 84 15 00 26 FF 00 75
08 95 40 91 02 C0
```

## Technical log of the run

```
03:38:53.559  Asking for USB access
03:38:54.777  USB access granted
03:38:54.794  Reading V2 Pro report descriptors
03:38:54.803  claim interface 0: true
03:38:54.878  claim interface 1: true
03:38:54.953  claim interface 2: true
03:38:54.960  claim interface 3: true
03:38:54.960  claim interface 4: true
03:38:55.244  USB reset: true
```
