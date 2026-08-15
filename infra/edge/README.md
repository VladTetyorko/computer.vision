# infra/edge — companion reference kit

Three costed, tested recipes for getting a real aircraft's MAVLink telemetry (and, for one
recipe, video) onto this platform. Every recipe converges on the same wire contract the
platform already listens on: **MAVLink 2 over UDP, pushed to `udp://<platform-host>:14550`**
(the platform binds/listens, the link hardware always pushes — see
`drone-link/mavlink/MODULE.md`, "udp://host:port means listen, not connect"). Register the
resulting feed the same way regardless of which recipe produced it: `POST /api/devices` with
`protocol: "mavlink"`, `uri: "udp://0.0.0.0:14550"`, `capabilities: ["TELEMETRY"]` (or use the
onboarding wizard's discovery flow once `docs/plans/active/DRONE-INFRA-PLAN.md` I-b lands).

**Today's limit**: one device listening on a given `udp://host:port` locks onto the first
MAVLink system id it hears and silently ignores any other sysid arriving on that same port
(`MavlinkTelemetryDecoder`, see its MODULE.md Gotchas). Two aircraft on the same link today
means registering two devices on two different ports (`--serial0 udpclient:host:14550` /
`...:14551`, etc.) — the multi-vehicle single-port gateway (`docs/plans/active/DRONE-INFRA-PLAN.md` I-a) is
the fix in flight; this kit doesn't need to wait for it, one aircraft per port works today.

## Decision table

| | ELRS backpack | ESP32 bridge | Companion computer (RPi) |
|---|---|---|---|
| Extra hardware | none (TX module firmware feature) | ~$5 ESP32/ESP8266 board | RPi Zero 2 W / CM4-class board (~$15-70) |
| Wiring | none — WiFi from the existing TX module | one spare UART, 3.3V logic | one UART + (optionally) a camera |
| Bandwidth | ~2.2 kB/s (1470 B/s down + 735 B/s up at 333Hz Full) | ~2-4 kB/s (baud-limited, typ. 115200 UART) | telemetry ~2-4 kB/s + video (LTE/WiFi-class, Mbps) |
| Carries video | no | no | **yes** — the only recipe that carries both over one link |
| Firmware support | ArduPilot ≥4.5 full; INAV ≥8 monitor-only; Betaflight ≥2025.12.0-beta | ArduPilot (any MAVLink-capable version); INAV/Betaflight via the same serial `MAVLink` telemetry function | ArduPilot (any MAVLink-capable version) — the only recipe built around a full companion link, not a passthrough bridge |
| Setup effort | lowest — no wiring, firmware config only | low — one UART wire, firmware config | highest — OS image, mavlink-router, camera pipeline |
| Best for | quick telemetry-only field kit, no spare hardware | cheap dedicated telemetry bridge, any FC | full recording/video/companion-grade rig |

See `elrs-backpack.md`, `esp32-bridge.md`, `companion-rpi.md` for the full setup of each.

## Firmware support matrix

| Firmware | Minimum version | Notes |
|---|---|---|
| ArduPilot | ≥ 4.5 | Full MAVLink telemetry + (ELRS recipe) command passthrough |
| INAV | ≥ 8 | **Monitor-only** — INAV's MAVLink implementation is transmit-only; don't expect two-way config/mission over these links |
| Betaflight | ≥ 2025.12.0-beta | MAVLink telemetry mode landed in this release; earlier Betaflight has no MAVLink output at all |

## Failure modes, platform-wide

All three recipes share the same on-link-loss behavior on the platform side: telemetry simply
stops arriving (`MavlinkTelemetrySource` has no notion of "disconnected," only "no samples
recently" — the asset's last-known telemetry goes stale, shown wherever `AssetAttention`/fleet
summary renders staleness). The companion-computer recipe additionally carries video, whose
loss is a separate, visible signal (stream ends, viewer shows the usual "no signal" state) —
see `companion-rpi.md`'s own failure-modes section for that recipe's specifics.
