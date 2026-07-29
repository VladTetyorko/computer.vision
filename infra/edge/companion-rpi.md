# Companion computer (RPi-class)

The only recipe that carries **both** telemetry and video over one link — a Raspberry Pi Zero
2 W / CM4-class board (or equivalent) running `mavlink-router` (UART → UDP telemetry) and
`ffmpeg` (camera → RTSP video), both pushing to this platform over LTE or WiFi.

## Requirements

- RPi Zero 2 W, CM4, or similar Linux-capable SBC with a spare UART and a camera (USB/V4L2 or
  the RPi camera module via CSI).
- Flight controller with a free telemetry-capable UART.
- `mavlink-router` ([github.com/mavlink-router/mavlink-router](https://github.com/mavlink-router/mavlink-router))
  and `ffmpeg` installed on the companion OS image.
- Network uplink: LTE modem or WiFi reaching this platform's host.

## Wiring

Cross-connect UART TX/RX between the flight controller's telemetry port and the RPi's UART
(3.3V logic on both ends — RPi GPIO UARTs are 3.3V natively, no level shifting needed). On
Raspberry Pi OS, the primary UART (`/dev/ttyAMA0`, GPIO14/15) is shared with Bluetooth by
default — either disable Bluetooth (`dtoverlay=disable-bt` in `/boot/firmware/config.txt`) to
free it, or use a USB-serial adapter (`/dev/ttyUSB0`) instead and skip the config-file edit.

## Setup

1. Install `mavlink-router` and `ffmpeg` on the companion OS image.
2. Copy `mavlink-router/main.conf` (this directory) to `/etc/mavlink-router/main.conf`, editing
   the `Device` (UART path) and `Address` (this platform's reachable host) values.
3. Copy both `systemd/*.service` units (this directory) to `/etc/systemd/system/`, editing the
   `CHANGE-ME` placeholders in `vision-rtsp-push.service` (platform host, feed name), then:
   ```
   sudo systemctl daemon-reload
   sudo systemctl enable --now mavlink-router vision-rtsp-push
   ```
4. Register the telemetry device once MAVLink starts arriving:
   ```
   POST /api/devices
   { "name": "Drone (companion)", "protocol": "mavlink",
     "uri": "udp://0.0.0.0:14550", "capabilities": ["TELEMETRY"] }
   ```
   The video side needs no separate registration on this platform today — mediamtx accepts the
   RTSP publish and serves it out over HLS/WHEP the same as every other stream it hosts
   (`docker-compose.yml`'s `mediamtx` service, port `8554` RTSP in / `18888` HLS out /
   `18889` WHEP out); wire up an `rtsp`-protocol device pointed at
   `rtsp://<mediamtx-host>:8554/feed-<name>` the same way any RTSP source is registered
   (`adapters/adapter-rtsp/MODULE.md`) to bring it into the asset/stream model.

## Config files provided here

- `mavlink-router/main.conf` — `[UartEndpoint fc]` (serial ↔ flight controller) +
  `[UdpEndpoint platform]` (`Mode = Normal`, i.e. mavlink-router actively pushes to the
  platform — matches this platform's listen-not-connect ingest model, see
  `adapters/adapter-mavlink/MODULE.md`). Syntax verified against mavlink-router's own
  `examples/config.sample` and ArduPilot's published companion-computer reference config.
- `systemd/mavlink-router.service` — runs the above config as a service (skip if your OS
  image's `mavlink-router` package already ships an equivalent unit).
- `systemd/vision-rtsp-push.service` — `ffmpeg` pushing the companion's camera to mediamtx's
  RTSP ingest, encoding profile mirrored from this platform's own publisher
  (`adapters/adapter-publish-hls/MODULE.md`: veryfast preset, `tune=zerolatency`, 1s GOP to
  match mediamtx's pinned 1s HLS segment duration), bitrate capped for LTE-class uplinks.

## Bandwidth guidance (LTE/WiFi)

| Stream | Typical | Notes |
|---|---|---|
| Telemetry (mavlink-router) | 2-4 kB/s | Same MAVLink stream rates as any other recipe here — negligible next to video |
| Video (ffmpeg, as configured) | ~1.5 Mbps (190 kB/s) | The provided unit's `-b:v 1500k` — comfortable on a mid-tier LTE connection; raise for WiFi/good LTE, lower (e.g. 600-800 kbps at reduced resolution) for marginal signal |
| Combined | ~1.5-2 Mbps | Telemetry is small enough to not need separate budgeting once video is flowing |

On a marginal or metered LTE link, lower `-b:v`/`-maxrate`/`-bufsize` together (keep the 2:1
`bufsize:maxrate` ratio) and/or drop `-video_size`/`-framerate` before touching anything on the
telemetry side — telemetry is the safety-relevant signal and costs almost nothing to keep at
full rate.

## Failure modes

| What breaks | Platform-visible symptom |
|---|---|
| Link loss (LTE drop, WiFi out of range) | Both telemetry and video stop simultaneously: telemetry goes stale (last-known values persist wherever `AssetAttention`/fleet summary renders them, no live update), the video viewer shows its usual "no signal"/stream-ended state. Both recover automatically once the link returns — `mavlink-router` and `ffmpeg`'s `Restart=on-failure` reconnect without operator action. |
| UART/flight-controller-only failure (video keeps working) | Telemetry goes stale; video keeps streaming — a genuinely useful differential signal (aircraft is still transmitting video/power but the telemetry link specifically died). |
| Camera/video-only failure (telemetry keeps working) | Video viewer shows "no signal"; telemetry (position/battery/flight-state) keeps updating normally — the aircraft is still trackable even with video down. |
| RPi/companion computer itself crashes or loses power | Both stop identically to a link loss from the platform's point of view — no way to distinguish "link down" from "companion down" without a separate heartbeat/health channel (out of scope for this recipe). |

## Sources

- [mavlink-router — GitHub](https://github.com/mavlink-router/mavlink-router) —
  `examples/config.sample`, endpoint config syntax.
- [ArduPilot/companion — mavlink-router.conf reference](https://github.com/ArduPilot/companion/blob/master/Common/Ubuntu/mavlink-router/mavlink-router.conf) —
  real-world companion-computer config shape this file follows.
- `docker-compose.yml` (this repo) — mediamtx's `8554:8554` RTSP port mapping, `MTX_HLS*`
  pinned settings.
- `adapters/adapter-publish-hls/MODULE.md` — this platform's own RTSP→mediamtx encoding
  profile, mirrored here for the companion-side push.
- `docs/DRONE-INFRA-PLAN.md` I-d (recipe scope/costing).
