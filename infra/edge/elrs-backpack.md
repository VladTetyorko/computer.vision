# ELRS MAVLink backpack

Zero extra hardware: the TX module's own "backpack" WiFi radio forwards MAVLink straight to
this platform over UDP. No wiring, no companion computer — just firmware settings on both
ends of the link.

## Requirements

- ExpressLRS **≥ 3.5.0** on both the TX and RX (air unit) side, and TX Backpack firmware
  **≥ 1.5.0** (older backpack firmware predates MAVLink mode — it only ever forwarded
  Crossfire telemetry frames, not MAVLink).
- A flight controller that can emit MAVLink over the CRSF/ELRS telemetry link (see the
  firmware-side settings below).

## Firmware-side settings (flight controller)

| Firmware | Setting | Notes |
|---|---|---|
| ArduPilot ≥ 4.5 | `SERIALx_PROTOCOL = 2` (MAVLink2) on the UART wired to the ELRS receiver's telemetry pin, `SERIALx_BAUD = 460` (460800) | Full two-way MAVLink over the link |
| INAV ≥ 8 | Assign the **MAVLink** telemetry function to the CRSF UART (Configurator Ports tab, or CLI `serial <port> <mask>` with `FUNCTION_TELEMETRY_MAVLINK` set), `feature TELEMETRY` enabled | **Monitor-only** — INAV's MAVLink implementation is transmit-only; don't expect config/mission upload over this link |
| Betaflight ≥ 2025.12.0-beta | Ports tab: set the CRSF UART's **Telemetry Output** to `MAVLink`, `feature TELEMETRY` enabled | Earlier Betaflight has no MAVLink telemetry output at all — this recipe is a hard no-go below 2025.12 |

## TX-module backpack WiFi setup

The backpack's WiFi is reached via the transmitter's own Lua script: **Backpack → Telemetry →
WiFi** (not the general-purpose "WiFi" toggle — that's for firmware flashing, a different
mode).

**AP mode** (default, best for field use — no existing network needed):
1. Enable Backpack telemetry WiFi from the Lua script above.
2. Connect a phone/laptop/companion device to SSID `ExpressLRS TX Backpack XXXXXX`
   (`XXXXXX` = part of the module's UID), password `expresslrs`.
3. Point this platform's device registration at the backpack's AP-mode gateway address,
   UDP port **14550** (the backpack pushes; see below — no IP to discover on this platform's
   side beyond "listen on 0.0.0.0:14550").

**STA mode** (join an existing WiFi network — better for a fixed indoor/bench setup, or when
this platform's host is already on the same LAN):
1. Same Lua menu; configure the backpack with your network's SSID/password instead of AP mode.
2. The backpack joins that network and UDP-pushes MAVLink to whatever GCS/platform address is
   configured — point it at this platform's host IP, port 14550.

Either mode: the backpack is the **client**, pushing UDP datagrams — it never listens. This
matches this platform's ingest model exactly (`adapter-mavlink` binds and listens; it never
dials out). Register the device once reachable:

```
POST /api/devices
{
  "name": "Drone (ELRS backpack)",
  "protocol": "mavlink",
  "uri": "udp://0.0.0.0:14550",
  "capabilities": ["TELEMETRY"]
}
```

## Bandwidth

Measured at 333Hz Full packet rate (2.4 GHz, the ELRS-recommended rate for MAVLink use,
including the initial parameter download and any mission upload): **~1470 B/s downlink +
~735 B/s uplink**, ≈ 2.2 kB/s combined — the plan's "~2.4 kB/s" figure, telemetry-only. Lower
packet rates shrink this further and can starve MAVLink of bandwidth entirely; don't drop
below 333Hz Full for this use case.

## Limitations

- **~2.4 kB/s ceiling** — plenty for `HEARTBEAT`/`GPS_RAW_INT`/`SYS_STATUS`/`RC_CHANNELS`
  (this platform's `FlightState` decode set, see `docs/plans/done/FC-INTEGRATIONS-PLAN.md`) at a modest
  rate, not enough for high-rate attitude streams or bulk log/parameter downloads without
  first bumping the packet rate.
- **INAV: monitor-only** — no command/config/mission traffic will get through in the direction
  that matters; treat INAV over this link as read-only telemetry.
- **No video** — this recipe is telemetry-only by construction; pair it with a separate video
  path (or use the companion-computer recipe, `companion-rpi.md`, if one link needs to carry
  both).

## Sources

- [ExpressLRS — MAVLink](https://www.expresslrs.org/software/mavlink/) — firmware/backpack
  minimum versions, `SERIALx_PROTOCOL`/`SERIALx_BAUD` values, UDP port, per-firmware support,
  throughput numbers.
- `docs/plans/active/DRONE-INFRA-PLAN.md` I-d (recipe scope/costing) and `docs/plans/done/FC-INTEGRATIONS-PLAN.md`
  (this platform's MAVLink decode set, firmware detection).
- `drone-link/mavlink/MODULE.md` (this platform's listen-not-connect ingest model).
