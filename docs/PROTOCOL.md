# Edifier D32 — BLE control protocol

Verified live against an **Edifier D32** (October 2026). The framing is the same
family as the Edifier S880DB MKII documented by
[MikeBirdTech/liberated-edifier-s880db-mkii](https://github.com/MikeBirdTech/liberated-edifier-s880db-mkii/blob/master/PROTOCOL.md);
the D32 differs in its service UUID, advertised UUID and volume range.

## Discovery

| | |
|---|---|
| Advertised 16-bit service | `0x8600` |
| Manufacturer data company ID | `0x07E0` |
| Advertised name | none (empty) |

The BLE control link stays active regardless of the selected input
(AUX, USB, Bluetooth, AirPlay).

## GATT

```
service 48098601-1a48-11e9-ab14-d663bd873d93
  char  48090001-1a48-11e9-ab14-d663bd873d93   read, notify                    speaker → host
  char  48090002-1a48-11e9-ab14-d663bd873d93   read, write, write-no-response  host → speaker
service 66666666-6666-6666-6666-666666666666
  char  77777777-7777-7777-7777-777777777777   notify, write  (unused here)
```

## Framing

```
host → speaker:  AA EC <CMD> <LEN_HI> <LEN_LO> <PAYLOAD…> <CHECKSUM>
speaker → host:  BB EC <CMD> <LEN_HI> <LEN_LO> <PAYLOAD…> <CHECKSUM>
CHECKSUM = sum(all preceding bytes) & 0xFF
```

## Connect sequence (required)

1. Enable notifications on `48090001` (CCCD `01 00`).
2. Write `AA EC D8 00 00 6E` (state query). Commands sent before this are ignored.

## Commands used

| Cmd | Request | Reply | Meaning |
|---|---|---|---|
| `D8` | `AA EC D8 00 00 6E` | 23-byte blob | init / device state |
| `C9` | `AA EC C9 00 00 5F` | ASCII `EDIFIER D32` | model name |
| `66` | `AA EC 66 00 00 FC` | `BB EC 66 00 02 <max> <vol> <ck>` | get volume |
| `67` | `AA EC 67 00 01 <vol> <ck>` | `BB EC 67 00 02 <max> <vol> <ck>` | set absolute volume |

* Volume range on the D32 is **0–16** (`max` byte = `0x10`).
* `0x66` is also pushed unsolicited when the physical +/− buttons are used.
* Works in **AUX** mode — this is what makes TV control possible.

Only send documented packets; unknown command IDs may include factory reset or
firmware operations.
