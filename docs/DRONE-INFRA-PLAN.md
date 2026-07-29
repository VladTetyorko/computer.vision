# DRONE-INFRA-PLAN — fleet infrastructure for ArduPilot / INAV / Betaflight

Status: draft for review (2026-07-28). Companion to docs/FC-INTEGRATIONS-PLAN.md (the decode
layer, in flight). This plan is the layer above: how many real aircraft physically and logically
plug into one platform — link hardware, multi-vehicle ingest, provisioning, dev/demo fleets,
edge kits, and (deliberately last) command TX.

## The infrastructure picture

```
aircraft (ArduPilot / INAV / Betaflight)
  │  MAVLink over: ELRS backpack WiFi · ESP32 bridge (DroneBridge/mavesp8266)
  │                companion computer (RPi + mavlink-router, LTE/WiFi) · SiK + serial-UDP
  ▼
UDP :14550  ──►  MAVLink fleet gateway (I-a: one socket, N vehicles, demux by src+sysid)
  │                    │
  │                    ├─► auto-provisioning (I-b: heartbeat discovery → asset wizard)
  │                    └─► FlightState telemetry per asset (FC-INTEGRATIONS, running)
  ▼
video path (already multi-protocol): camera → companion FFmpeg/OpenIPC → RTSP push → mediamtx
  ▼
vision platform: Command · Fly · Wall · Replay · CV · SSE live channel
```

Field reality this must serve: every firmware ships **sysid=1 by default**; radio bridges push
to **one well-known GCS port (14550)**; operators will not hand-edit sysids or register UUIDs in
the field. Infrastructure = the platform absorbs that mess, not the operator.

## Current state (honest)

| Layer | Today | Gap |
|---|---|---|
| MAVLink ingest | one UDP listen port **per device**, decoder locks onto first sysid heard, silently drops others (`MavlinkTelemetryDecoder`) | one aircraft per port; a second drone on 14550 is invisible; no re-adoption after silence |
| Aircraft identity | manual: create device + asset, type a `udp://` URI | nothing maps (source addr, sysid) → asset; no auto-registration |
| Discovery | mdns / onvif / v4l2 scanners | no MAVLink heartbeat scanner ("what drones can I hear right now?") |
| Firmware awareness | FC-INTEGRATIONS wave adds firmware/mode/armed/failsafe/GPS/blockers | landing now |
| Dev/demo fleet | our own TX simulators (sim, mjpeg, mavlink feed) | no real-SITL fleet in a box; CI never sees a genuine ArduPilot stream |
| Edge/companion | none documented | no reference kit: which bridge, which config, how video + telemetry both reach us |
| Command TX | none (RX-only doctrine) | no "bring it home" even as the aircraft flies away |

## Phases

### I-a — MAVLink fleet gateway (multi-vehicle single-port ingest) — M
The core infrastructure change. In `adapter-mavlink`:
- A shared **socket manager**: one `DatagramSocket` per distinct bind (host:port), reference-counted
  across `open()` calls; a single read thread demultiplexes datagrams by **(source address, sysid)**
  into per-vehicle decoder pipelines.
- `StreamDescriptor.options["sysid"]`: a device may pin a sysid; unpinned devices get "first
  unclaimed vehicle" (today's behavior, but per-socket and re-electable after a silence window
  — fixes the no-re-adoption gotcha).
- Vehicles heard on the socket that **no open device claims** are surfaced to I-b (unclaimed-
  vehicle callback), not dropped silently.
- No domain/port change: `TelemetrySourcePort` contract untouched; N devices with the same
  `udp://0.0.0.0:14550` URI and different `sysid` options now genuinely coexist.
- Tests: loopback multi-vehicle (two TX feeds, distinct sysids, one port → two clean streams),
  re-adoption after silence, claim precedence.

### I-b — plug-and-fly provisioning (heartbeat discovery → asset) — S-M
- `MavlinkHeartbeatScanner implements DeviceDiscoveryPort` (`method() = "mavlink"`) — lives in
  **`adapter-mavlink`**, not adapter-discovery (adapters never depend on each other, and the
  scanner must share `MavlinkSocketHub`'s socket when the gateway already owns the port instead
  of failing to bind): when the hub has an active socket on the scan port, the scan reads its
  unclaimed-vehicle registry (plus claimed vehicles, labeled); otherwise it binds the port itself
  for the scan timeout. Reports every distinct sysid heard as a `DiscoveredDevice` — name
  "ArduPilot quadcopter (sysid 7)", suggestedCategory `drone`, suggestedStream
  `mavlink | udp://0.0.0.0:14550 | {sysid: 7}`, details {firmware, mavType, mode, armed}.
- Onboarding wizard Connect step gains **"Listen for drones"** (the existing discovery UI flow —
  scan, pick, probe, create). Zero new UX concepts; a powered-on drone becomes an asset in
  four clicks with nothing typed.
- Guard: a discovered vehicle already claimed by an existing device is labeled as such
  (poka-yoke: no accidental duplicate assets).

### I-c — fleet-in-a-box: SITL farm for dev/CI/demo — S
- `infra/sitl/` : compose file + script launching **N ArduPilot SITL instances** (official
  ardupilot docker image, distinct sysids via `SYSID_THISMAV`, `--out=udp:host:14550`), optional
  paired test-pattern video feeds into mediamtx. `./infra/sitl/up.sh 5` → five real ArduPilot
  aircraft flying circuits into the gateway, full PreArm/EKF/failsafe realism our simulators
  can't fake.
- CI (optional, docker-gated like mediamtx tests): one SITL instance smoke-tests the gateway
  end-to-end.
- INAV/Betaflight SITLs: deliberately not farmed (TCP-UART + bridge fiddliness, weak CI value);
  their exact MAVLink emitter subsets are covered by golden-bytes fixtures in the adapter tests.

### I-d — edge companion reference kit — S (docs/configs only)
`infra/edge/` + a docs page: three costed, tested recipes an operator can copy:
1. **ELRS MAVLink backpack** (zero extra hardware): TX-module WiFi → UDP 14550. ArduPilot ≥4.5,
   INAV ≥8 (monitor-only), Betaflight ≥2025.12. ~2.4 kB/s — telemetry only.
2. **ESP32 bridge** (~$5): DroneBridge/mavesp8266 on a spare UART → WiFi UDP. Telemetry only.
3. **Companion computer** (RPi Zero 2 W/CM4 class): mavlink-router conf (serial ↔ UDP out) **plus**
   camera → FFmpeg/OpenIPC → RTSP push to our mediamtx — the only recipe that carries video and
   telemetry together over one LTE/WiFi link; includes a ready mediamtx path + systemd units.
Each recipe: wiring, firmware settings (`SERIALx_PROTOCOL=2`, `feature TELEMETRY`, …), bandwidth,
failure modes. This is where "whole infrastructure" meets the field.

### I-e — guarded command TX (deliberate, staged; needs explicit go) — L
The RX-only doctrine ends only here, on purpose, with stages:
1. **Stage 1 — "Bring home"**: a single command, `MAV_CMD_DO_SET_MODE → RTL`, big red-adjacent
   button in Fly/Command, confirm dialog, audit-logged. ArduPilot + INAV (Betaflight: hidden —
   its RX handles no such command; capability matrix per firmware drives button visibility).
2. Stage 2 — arm/disarm + mode select (capability-gated).
3. Stage 3 — mission upload (ArduPilot), fence upload (ties to FEATURE-MATRIX geofencing's
   "autopilot-side enforcement" future line).
Needs: `FlightCommandPort` (domain), TX path on the gateway socket (it's already bidirectional
UDP), per-firmware capability matrix, U-e roles before stage 2+ (who may command what).

### I-f — fleet ops infra (future, mostly needs TX or U-e)
Log/blackbox ingest (dataflash via MAVFTP, BF blackbox upload), parameter drift audit
(PARAM_REQUEST_LIST fleet-wide), firmware version dashboard (AUTOPILOT_VERSION), multi-site
edge gateways reporting to a central instance, HIGH_LATENCY2 satcom check-ins.

## Sequencing & dependencies

- I-a first — it unblocks everything and conflicts with nothing already merged; **must wait for
  the FC-INTEGRATIONS F-a agent to land** (same adapter-mavlink files).
- I-b right after I-a (uses its unclaimed-vehicle surface); UI half is independent of I-a.
- I-c and I-d are file-disjoint from everything (new `infra/` tree) — can run any time.
- I-e is a product decision, not a default next step: it changes the platform's risk class
  (commanding aircraft vs observing them). Recommended entry: Stage 1 only, after I-a/I-b prove
  stable multi-vehicle ingest.
