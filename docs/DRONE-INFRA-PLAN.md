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

### I-e Stage 1 — DONE (2026-07-29, all three waves landed + SITL-verified)

Implemented exactly per the frozen contract below. Verified end-to-end at the adapter
level against a genuine flying ArduPilot SITL aircraft
(`MavlinkSitlReturnHomeIntegrationTest`: DO_SET_MODE → COMMAND_ACK ACCEPTED → telemetry
reports mode=RTL, first attempt, 16s). Also landed alongside: MAVLINK sim telemetry
transport (SimulationSpec.telemetryTransport, REST-exposed), telemetry into
StreamPipeline (burned-in OSD gate finally reachable), and the I-c SITL smoke test.
Stages 2+ (arm/disarm, mode select, missions) remain parked pending their own explicit go.

#### Frozen contract (as approved)

Wave 1 (in flight): `FlightCommandPort` (vision-domain, `supports(Device)` +
`returnToHome(Device) -> CommandResult {ACCEPTED, NO_ACK}`, throws on unsupported
firmware/unheard vehicle/explicit MAV_RESULT denial) + `MavlinkFlightCommander`
(adapter-mavlink: COMMAND_LONG DO_SET_MODE via the hub's shared socket to the vehicle's
last-heard source address, RTL mode from FlightModes by firmware+mavType, COMMAND_ACK
await ~2s; Betaflight not commandable) + SITL smoke test (docker+image-gated).

Wave 2 wire contract (frozen — waves build against this):
- `POST /api/assets/{assetId}/return-home` (no body) →
  - `202 {"result": "ACCEPTED" | "NO_ACK"}` — command sent (NO_ACK = UDP sent, no
    COMMAND_ACK within timeout; honest, may still have landed);
  - `404` unknown asset;
  - `409 {"message": …}` — not commandable: no active mavlink telemetry device, firmware
    without RTL capability (Betaflight/unknown), or vehicle never heard on the socket;
  - `503`-shaped failures stay 409 with message (Stage 1 keeps one refusal code).
- Application: `FlightCommandService` resolving asset → active mavlink device →
  port; audit-logged via `AuditTrailPort` (action per existing audit conventions,
  attributes {assetId, command:"RTL", result}); an `Event(COMMAND_SENT)` on the events
  topic only if EventType growth proves trivial — else audit-only for Stage 1.
- UI (wave 3): "Bring home" button in Fly cockpit + Command asset panel; visible only
  when latest telemetry `flightState.firmware == "ardupilot"` (INAV masquerades as
  ArduPilot — correct by design) and telemetry is fresh; ALWAYS a confirm dialog
  ("Command <asset> to return home?"); result toast (ACCEPTED green / NO_ACK amber
  "sent, no acknowledgement"); 409 message surfaced verbatim. Red-adjacent styling,
  never auto-triggered, no keyboard shortcut (poka-yoke: commanding an aircraft is a
  deliberate two-step act).

### I-g — guided drone onboarding — DONE (2026-07-29, both waves green)

Wave A: `GET /api/system/network` + shared `vision.discovery.mavlink-port` (scanner and
endpoint read one property — can't disagree); vision-api 277/277, vision-app 107/107.
Wave B: onboarding wizard firmware×link picker + pre-filled copy-paste configs
(parameterized by the server's real address+port) + listen-and-create; vision-web
1032 tests, tsc clean, production build green. Original spec below.

### I-g spec — add real hardware in minutes

Goal: an operator with a Betaflight / INAV / ArduPilot aircraft adds it to the app with
near-zero configuration knowledge. Two halves: the app *guides and pre-fills*, the drone
side becomes *copy-paste* because every generated snippet already carries this app's own
reachable IP and MAVLink port — the single biggest source of setup failure (typed-wrong
addresses) is designed out.

**Flow (extends the existing U-d wizard — no new UX concepts):** the Connect step gains a
`drone` entry point beside the existing methods:
1. **Firmware & link picker**: firmware (ArduPilot | INAV | Betaflight) × link (ELRS
   backpack | ESP32/WiFi bridge | Companion computer). Each combination maps to one
   recipe; impossible/degraded combos are labeled honestly (e.g. INAV = monitor-only,
   Betaflight below 2025.12 = hard no-go — wording from `infra/edge/`).
2. **Configure-your-drone step**: the tailored recipe rendered in-wizard with
   copy-paste blocks parameterized by the server's real LAN address + MAVLink port
   (from the new network endpoint below): FC serial/protocol settings, backpack/bridge
   target fields, and for the companion path a **downloadable, pre-filled
   `mavlink-router main.conf`** (client-side blob). Content adapted from
   `infra/edge/*.md` — the wizard embeds, never contradicts, those docs.
   Multi-NIC hosts: all site-local addresses listed, first one pre-selected, operator
   can switch (poka-yoke: never silently guess the wrong network).
3. **Listen step**: the existing mavlink heartbeat discovery scan, auto-repeating,
   rendering found vehicles live (name "ArduPilot quadcopter (sysid 7)", firmware badge,
   mode, armed, claimed-elsewhere label). Pick → existing probe (test-before-save) →
   create-step prefills (name, category `drone`, mavlink device
   `udp://0.0.0.0:<port>` + `sysid` option pinned to the heard vehicle).
4. Create → done, deep-link to Fly. Companion-path users may add the paired RTSP video
   device in the same asset (existing multi-device create).

**Frozen wire contract (wave B builds against this):**
- `GET /api/system/network` → `200 {"addresses": [{"address": "192.168.0.104",
  "interfaceName": "wlp2s0"}], "mavlinkPort": 14550}` — site-local IPv4 of up,
  non-loopback interfaces, sorted by interface name; `mavlinkPort` = the same port the
  heartbeat scanner listens on. Never errors for "no addresses" — empty list, UI copes
  (shows a "couldn't detect my address" manual field).

**Waves:** A (backend: endpoint + a shared `vision.discovery.mavlink-port` property so
scanner wiring and the endpoint can never disagree; vision-api + vision-app) ·
B (frontend: wizard flow per above; vision-web only) — parallel, disjoint.

### I-h — low-latency drone video ingest (SRT + UDP/MPEG-TS) + connect-flow usability (approved 2026-07-29)

Goal: accept the two video transports real drone/FPV kit actually uses over lossy cellular/
long-range links, and make picking any ingest protocol in the app obvious. Pure RX — no
command surface. `FfmpegVideoSource` (adapter-rtsp) is already JavaCV/FFmpeg-backed, which
decodes SRT and UDP/MPEG-TS today; only its `supports()` gate + per-protocol demuxer options
are missing, so this is a contained extension, not a new adapter.

Why these two: **SRT** is the de-facto standard for low-latency drone video over 4G/5G and
long-range links (DJI transmission, Herelink, cheap SRT encoders, OBS) — packet-loss-resilient
with a tunable latency budget, exactly the lossy-link case RTSP handles badly. **UDP/MPEG-TS**
is the classic ground-station/encoder output (`ffmpeg … -f mpegts udp://…`, analog-to-digital
boxes). Both compose with the existing publish path (ingest → republish to mediamtx for HLS/
WHEP viewing) unchanged, and with CV detection unchanged.

**Frozen contract (UI ↔ adapter):**
- Protocol strings: **`srt`** and **`udp`** (lower-case, as `StreamDescriptor.protocol()`).
- `srt` URI: `srt://host:port` (caller — app dials the encoder) or `srt://0.0.0.0:port` +
  option `mode=listener` (app binds, encoder dials in). Options (all optional, sane defaults):
  `latency` (ms, SRT's core knob, default ~120), `mode` (caller|listener), `streamid`,
  `passphrase`. Adapter owns exact FFmpeg AVOption names.
- `udp` URI: `udp://0.0.0.0:port` (listen) or `udp://host:port`. Options: `fifo_size`,
  `overrun_nonfatal`, `buffer_size`; MPEG-TS assumed. Low-latency defaults applied.
- The probe (test-before-save, `ProbeService`) and `VideoSourceRegistry` auto-dispatch by
  `supports()` — **zero application/api change**; probe works for both the moment the adapter
  claims the protocol.

**Waves (parallel, disjoint):**
- **A — adapter** (`adapters/adapter-rtsp/**`): `FfmpegVideoSource#supports` accepts `srt`/`udp`;
  per-protocol option application (rtsp options stay rtsp-only); loopback integration tests
  (UDP/MPEG-TS via a JavaCV recorder pushing to a local port; SRT gated on libsrt presence in
  the ffmpeg build, skip cleanly if absent, same posture as the docker-gated tests elsewhere);
  MODULE.md updated (its class javadoc already enumerates supported protocols — extend it).
- **B — UI usability** (`vision-web/**`): add `srt`/`udp` to `REGISTERABLE_PROTOCOLS`
  (`features/onboarding/protocols.ts`) with plain-words hints + realistic placeholders; verify
  the register-manually and drone-onboarding connect flows present them well (grouped/ordered so
  the common choices lead); a short "what's this?" affordance per protocol so an operator picks
  right without docs. Connect-flow polish: sensible default option hints (SRT latency, listener
  vs caller) surfaced inline. vitest for the protocol-list logic.

**Future scalability note (not this wave):** mediamtx natively ingests SRT/RTMP/WHIP on its own
ports — a drone could push straight to mediamtx and the app consume that path, moving ingest
decode off the backend entirely (the same push-vs-pull lever as CV-SCALE-PLAN §S5). Direct
in-app ingest (this wave) is the incremental step; mediamtx-native ingest is the scale step,
sequenced when backend ingest decode becomes the measured ceiling.

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
