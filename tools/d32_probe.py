#!/usr/bin/env python3
"""
Edifier D32 BLE probe (macOS, bleak).

Βασίζεται στο δημοσιευμένο πρωτόκολλο του S880DB MKII:
https://github.com/MikeBirdTech/liberated-edifier-s880db-mkii/blob/master/PROTOCOL.md

Στέλνει ΜΟΝΟ τεκμηριωμένες εντολές:
  D8 (init/state), C9 (όνομα συσκευής), 66 (τρέχουσα ένταση), 67 (set volume).

Χρήση:
  python3 d32_probe.py scan            # βρες το ηχείο
  python3 d32_probe.py probe           # σύνδεση, GATT dump, όνομα + ένταση (read-only)
  python3 d32_probe.py set 12          # όρισε ένταση (0-16)
  python3 d32_probe.py probe --addr <UUID>   # αν βρέθηκαν πολλές συσκευές
"""
import asyncio
import sys

from bleak import BleakClient, BleakScanner

EDIFIER_SVC16 = "00008600-0000-1000-8000-00805f9b34fb"   # advertised 16-bit service (D32)
UUID_PREFIX = "4809"                                     # Edifier vendor UUID family
MAX_SAFE_VOL = 16


def packet(cmd: int, payload: bytes = b"") -> bytes:
    n = len(payload)
    body = bytes([0xAA, 0xEC, cmd, (n >> 8) & 0xFF, n & 0xFF]) + payload
    return body + bytes([sum(body) & 0xFF])


def describe(data: bytes) -> str:
    h = data.hex(" ").upper()
    if len(data) >= 6 and data[0] == 0xBB and data[1] == 0xEC:
        cmd = data[2]
        n = (data[3] << 8) | data[4]
        payload = data[5:5 + n]
        ok = (sum(data[:-1]) & 0xFF) == data[-1]
        extra = ""
        if cmd == 0xC9:
            extra = f'  name="{payload.decode(errors="replace")}"'
        elif cmd in (0x66, 0x67) and len(payload) >= 2:
            extra = f"  volume={payload[1]} / max={payload[0]}"
        elif cmd == 0x61 and len(payload) >= 2:
            extra = f"  source=0x{payload[1]:02X}"
        return f"[{h}]  cmd=0x{cmd:02X} len={n} checksum={'ok' if ok else 'BAD'}{extra}"
    return f"[{h}]  (unknown framing)"


async def scan():
    print("Scanning 8s...")
    found = await BleakScanner.discover(timeout=8.0, return_adv=True)
    hits = []
    for addr, (dev, adv) in found.items():
        name = adv.local_name or dev.name or ""
        uuids = [u.lower() for u in (adv.service_uuids or [])]
        mfr = list(adv.manufacturer_data.keys())
        is_edf = ("edifier" in name.lower()) or (EDIFIER_SVC16 in uuids) or (0x07E0 in mfr)
        if is_edf:
            hits.append(addr)
            print(f"  ✅ {addr}  name='{name}'  rssi={adv.rssi}  svc={uuids}  mfr={[hex(m) for m in mfr]}")
    if not hits:
        print("  Δεν βρέθηκε Edifier. Δες τα 'Αν δεν βρίσκει τίποτα' στις οδηγίες.")
    return hits


async def pick_addr(args):
    if "--addr" in args:
        return args[args.index("--addr") + 1]
    hits = await scan()
    if len(hits) != 1:
        sys.exit("Βρέθηκαν 0 ή >1 συσκευές. Ξανατρέξε με --addr <UUID>.")
    return hits[0]


def find_chars(client):
    """Βρίσκει το ζεύγος notify (...0001) / write (...0002) της Edifier."""
    for svc in client.services:
        if not svc.uuid.lower().startswith(UUID_PREFIX):
            continue
        notify = write = None
        for ch in svc.characteristics:
            u = ch.uuid.lower()
            if u.startswith(UUID_PREFIX) and "notify" in ch.properties:
                notify = ch
            if u.startswith(UUID_PREFIX) and ("write" in ch.properties or "write-without-response" in ch.properties):
                write = ch
        if notify and write:
            return svc, notify, write
    return None, None, None


async def session(addr, action, vol=None):
    async with BleakClient(addr, timeout=15.0) as client:
        print(f"\nConnected: {addr}\n\nGATT:")
        for svc in client.services:
            print(f"  service {svc.uuid}")
            for ch in svc.characteristics:
                print(f"    char {ch.uuid}  {ch.properties}")

        svc, notify, write = find_chars(client)
        if not write:
            print("\n❌ Δεν βρέθηκε Edifier service (4809xxxx). Στείλε μου το GATT dump από πάνω.")
            return
        print(f"\nUsing service {svc.uuid}\n  notify {notify.uuid}\n  write  {write.uuid}\n")

        async def on_notify(_, data: bytearray):
            print("  <-", describe(bytes(data)))

        await client.start_notify(notify, on_notify)

        async def send(cmd, payload=b""):
            p = packet(cmd, payload)
            print("  ->", p.hex(" ").upper())
            await client.write_gatt_char(write, p, response=False)
            await asyncio.sleep(0.4)

        await send(0xD8)          # connect ritual (απαραίτητο)
        await send(0xC9)          # όνομα
        await send(0x66)          # τρέχουσα ένταση

        if action == "set":
            v = max(0, min(int(vol), MAX_SAFE_VOL))
            print(f"\nSetting volume -> {v}")
            await send(0x67, bytes([v]))
            await send(0x66)

        await asyncio.sleep(1.0)


async def main():
    args = sys.argv[1:]
    if not args or args[0] not in ("scan", "probe", "set"):
        print(__doc__)
        return
    if args[0] == "scan":
        await scan()
        return
    addr = await pick_addr(args)
    if args[0] == "probe":
        await session(addr, "probe")
    else:
        await session(addr, "set", args[1])


if __name__ == "__main__":
    asyncio.run(main())
