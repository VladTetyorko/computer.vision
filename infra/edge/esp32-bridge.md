# ESP32/ESP8266 bridge (DroneBridge for ESP32 / mavesp8266)

~$5 in hardware: a spare UART on the flight controller wired to an ESP32 (or older ESP8266)
board running either **DroneBridge for ESP32** or the original **mavesp8266** firmware — both
are WiFi↔UART MAVLink bridges, telemetry only (no video).

## Requirements

- An ESP32 or ESP8266 dev board (e.g. Adafruit HUZZAH ESP8266, any ESP32-WROOM board, or
  official ArduPilot-branded hardware using the JST-GH 6-pin connector).
- A flight controller UART not already in use.
- Bridge firmware: [DroneBridge for ESP32](https://github.com/DroneBridge/ESP32) (ESP32,
  actively maintained, MAVLink/MSP/LTM/Bluetooth LE) or
  [mavesp8266](https://github.com/ArduPilot/mavesp8266) (ESP8266, ArduPilot-maintained,
  MAVLink only).

## Wiring

Cross-connect UART TX/RX (flight controller TX → ESP RX, flight controller RX → ESP TX) at
**3.3V logic level** — most flight controller UARTs are already 3.3V, but check the FC's
datasheet before connecting; some boards' telemetry ports run at 5V and need a level shifter.
Official ArduPilot hardware uses a JST-GH 6-pin cable straight into `TELEM1`/`TELEM2`; a
DIY ESP8266 board typically needs a 6-pin header soldered to its UART pins (TX, RX, VCC, GND).

## Firmware-side settings (flight controller)

| Firmware | Setting |
|---|---|
| ArduPilot | `SERIALx_PROTOCOL = 2` (MAVLink2) on the UART wired to the ESP board, `SERIALx_BAUD = 115` (115200 — both DroneBridge and mavesp8266 default to this baud; leave it there unless you've explicitly reconfigured the bridge firmware) |
| INAV / Betaflight | `feature TELEMETRY` enabled; assign the **MAVLink** telemetry function to that UART (Configurator/Betaflight Configurator Ports tab → Telemetry Output → `MAVLink`; CLI sets the port's function bitmask to include `FUNCTION_TELEMETRY_MAVLINK`) |

## WiFi modes

Both bridge firmwares default to **Access Point (AP) mode** on first boot:

- DroneBridge for ESP32: SSID `DroneBridge ESP32`, password `dronebridge`.
- mavesp8266: SSID `ArduPilot` (or `PixRacer` on some pre-flashed hardware), password
  `ardupilot`/`pixracer`.

Both can be reconfigured to **Station (STA) mode** to join an existing WiFi network instead —
useful when this platform's host is already on the same LAN and you'd rather not hop networks
to reach the bridge. Configuration is via the bridge's own web UI (reachable at the AP's
gateway IP, typically `192.168.4.1`) or DroneBridge's companion app.

## UDP push configuration

Both bridges auto-forward MAVLink to **UDP port 14550** to whichever client(s) connect/are
configured — this is the same well-known GCS port this platform listens on, no bridge-side
reconfiguration needed for the port itself. Point the bridge's target (STA mode) or connect
from the platform host's network (AP mode) at `udp://<platform-host>:14550`; register the
device the same way as every other recipe:

```
POST /api/devices
{
  "name": "Drone (ESP32 bridge)",
  "protocol": "mavlink",
  "uri": "udp://0.0.0.0:14550",
  "capabilities": ["TELEMETRY"]
}
```

## Bandwidth

UART-baud-limited (115200 baud ≈ 11.5 kB/s raw serial), but MAVLink itself at ArduPilot's
default telemetry stream rates sits well under that — typically 2-4 kB/s of actual MAVLink
traffic. The bottleneck in practice is the WiFi link's own latency/jitter under load, not the
UART.

## Limitations

- Telemetry only — no video, same as the ELRS backpack recipe.
- AP-mode range is short (WiFi class, tens of meters at best) — for anything beyond
  line-of-sight bench/field range, STA mode onto existing infrastructure (or the
  companion-computer recipe's LTE option) is the better fit.

## Sources

- [DroneBridge for ESP32 — GitHub](https://github.com/DroneBridge/ESP32) — wiring, WiFi AP
  defaults, UDP 14550 auto-forward.
- [ArduPilot Copter docs — DroneBridge for ESP32](https://ardupilot.org/copter/docs/common-esp32-telemetry.html),
  [ArduPilot — ESP8266 WiFi telemetry](https://ardupilot.org/copter/docs/common-esp8266-telemetry.html) —
  serial protocol/baud settings, mavesp8266 AP defaults.
- [mavesp8266 — GitHub](https://github.com/ArduPilot/mavesp8266) — ArduPilot-maintained
  ESP8266 bridge firmware.
- `docs/DRONE-INFRA-PLAN.md` I-d (recipe scope/costing).
