# D32 Remote

**Control an Edifier D32 speaker's volume with your Google TV remote** — over Bluetooth LE, while the speaker plays the TV's sound through optical → DAC → AUX.

The D32 has no HDMI ARC or optical input, so it's usually wired to the TV through a cheap optical-to-analog DAC. That works, but the optical output is fixed-level: the TV remote can't change the volume and TCL just shows *"Audio output through optical fibers does not support volume adjustment."*

D32 Remote is a small Android TV app that fixes that:

```
TV remote ──► Google TV ──► D32 Remote ──BLE──► Edifier D32
iPhone / browser ──HTTP:8080──┘
```

- **TV remote Vol+ / Vol− / Mute** move the D32 volume (0–16)
- A clean on-screen volume bar that **covers TCL's "optical fibers" message**
- **HTTP API** on port 8080 for iPhone Shortcuts, widgets, Siri, Home automations
- Starts on boot, reconnects automatically, no extra hardware

Tested on a **TCL 65P8K** (Google TV, Android 14).

## How it works

TCL Google TVs don't pass volume keys to apps (not even to accessibility services). But even on optical output, the TV still changes its internal `STREAM_MUSIC` index (0–100) and broadcasts `android.media.VOLUME_CHANGED_ACTION`, while the actual S/PDIF gain stays at 0 dB.

So the app:

1. listens for that broadcast and turns each change into a ±1 step on the D32,
2. re-centers the TV's internal index to 50 near the edges so it never gets stuck at 0/100,
3. draws its own volume bar as a `TYPE_ACCESSIBILITY_OVERLAY` window — the only layer above TCL's system toast,
4. talks to the speaker with the reverse-engineered BLE protocol in [docs/PROTOCOL.md](docs/PROTOCOL.md).

## Requirements

- Google TV / Android TV 8+ with Bluetooth LE (developed on TCL; other brands may need tweaks to the overlay position)
- Edifier D32 connected to the TV (e.g. optical → DAC → AUX)
- A Mac/PC with **Android Studio** (for `adb` and the build toolchain)

## Install

1. **On the TV:** Settings → System → About → click *Android TV OS build* 7×. Then Developer options → **Wireless debugging** → ON.
2. **Pair** (once):
   ```bash
   adb pair <tv-ip>:<pairing-port>     # code shown under "Pair device with pairing code"
   ```
3. **Build, install and configure:**
   ```bash
   TV_IP=<tv-ip> ./scripts/install.command
   ```
   This builds a release APK, installs it, grants Bluetooth permissions and enables the accessibility service. If the TV refuses to bind it automatically, the script asks you to toggle *Settings → System → Accessibility → D32 Remote* off and on.
4. Close the **EDIFIER ConneX** app on your phone — the D32 accepts only one BLE client at a time.
5. Optional: turn Developer options off again. The app keeps working.

To remove: `./scripts/uninstall.command`, or *Settings → Apps → D32 Remote → Uninstall* on the TV.

## HTTP API

| Endpoint | Action |
|---|---|
| `GET /` | simple web remote (− / mute / +) |
| `GET /vol/up`, `/vol/down` | ±1 step |
| `GET /vol/set?v=8` | absolute volume 0–16 |
| `GET /mute` | toggle mute |
| `GET /status` | JSON status |
| `GET /enable`, `/disable` | turn TV-remote control on/off |

Example response:
```json
{"ok":true,"connected":true,"volume":6,"max":16,"enabled":true,"status":"Συνδεδεμένο"}
```

**iPhone:** Shortcuts → *Get Contents of URL* → `http://<tv-ip>:8080/vol/up`. Add it to a widget, Control Center or the Action Button. Give the TV a fixed IP (DHCP reservation) on your router.

> The API has no authentication — keep it on your home LAN.

## Pause / disable

Open **D32 Remote** on the TV → **⏸ Pause** (or call `/disable`). The TV remote then behaves as before.

## Repository

```
app/                 Android TV app (Kotlin, no external dependencies)
scripts/             install / uninstall helpers (macOS, zsh)
tools/d32_probe.py   BLE probe for macOS/Linux (bleak) — scan, read and set volume
esp32/               alternative: ESP32 bridge exposing the D32 to Apple HomeKit (HomeSpan)
docs/PROTOCOL.md     Edifier D32 BLE protocol
```

### Probe the speaker from a computer

```bash
python3 -m venv .venv && source .venv/bin/activate && pip install bleak
python3 tools/d32_probe.py probe      # GATT dump, model name, current volume
python3 tools/d32_probe.py set 8      # set volume 0–16
```

## Credits

The packet framing builds on the S880DB MKII research by
[MikeBirdTech/liberated-edifier-s880db-mkii](https://github.com/MikeBirdTech/liberated-edifier-s880db-mkii).

Not affiliated with Edifier or TCL. Use at your own risk.

## License

[MIT](LICENSE)
