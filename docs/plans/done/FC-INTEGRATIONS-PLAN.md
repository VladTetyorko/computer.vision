# FC-INTEGRATIONS-PLAN — flight-controller-aware telemetry (ArduPilot / INAV / Betaflight)

Status: approved spec (2026-07-28). Delivers the data layer for FEATURE-MATRIX Tier-1's
"RTH/failsafe banners" and "telemetry-verified pre-flight checklist", working over the
existing MAVLink 2 UDP RX path for all three major open FC firmwares. No command TX anywhere.

## Research facts this plan relies on (verified against firmware sources)

- All three firmwares stream MAVLink unsolicited over an IP link (companion/mavlink-router,
  ESP32 bridges, ExpressLRS backpack → UDP 14550). MSP/LTM/CRSF are not practical for a UDP
  ground link. Our `udp://host:port`-listen model already matches.
- **Firmware detect** from `HEARTBEAT.autopilot`: `3` (ARDUPILOTMEGA) → ArduPilot mode tables
  (INAV defaults to masquerading as `3` and maps its modes onto ArduPilot mode numbers, so the
  same tables are correct for INAV); `0` (GENERIC) → Betaflight table (BF ≥4.6: 0 ACRO, 1 ANGLE,
  2 HORIZON, 3 ALT_HOLD, 4 POS_HOLD, 5 AUTOPILOT, 6 RTL, 7 FAILSAFE); `12` → PX4 (label modes
  as `Mode <n>` — table out of scope). Mode table for ArduPilot selected by `MAV_TYPE`:
  copter table (0 Stabilize … 6 **RTL**, 9 **Land**, 21 SmartRTL, 27 AutoRTL) vs plane table
  (0 Manual … 11 **RTL**, 20 QLand, 21 QRTL) vs rover (0 Manual, 4 Hold, 10 Auto, 11 RTL, 15 Guided).
- `base_mode & 1` gates custom_mode validity; `base_mode & 128` = **armed** (all three).
- **Failsafe**: `HEARTBEAT.system_status == MAV_STATE_CRITICAL` (enum value 5; 6 is EMERGENCY —
  compare by enum identity, not ordinal) (all three when in failsafe);
  Betaflight additionally custom_mode 7 = FAILSAFE. "mode is RTL/Land AND state CRITICAL" =
  failsafe-triggered RTH vs pilot-commanded RTH.
- **GPS**: `GPS_RAW_INT` — `fix_type` (0 NO_GPS, 1 NO_FIX, 2 2D, 3 3D, 4 DGPS, 5 RTK_FLOAT,
  6 RTK_FIXED), `satellites_visible` (255 = unknown), `eph` = HDOP×100 (65535 = invalid).
- **RSSI**: `RC_CHANNELS` / `RC_CHANNELS_RAW` `rssi` 0–254 (255 = invalid) → percent = round(rssi/254*100).
- **Battery voltage**: `SYS_STATUS.voltage_battery` mV (65535 = unknown).
- **Arming blockers** (ArduPilot only): `STATUSTEXT` matching `^(PreArm|Arm): (.*)` — re-broadcast
  ~every 30 s while disarmed; cleared on arm. Machine-readable armability also in
  `SYS_STATUS.onboard_control_sensors_health` bit `0x10000000` (PREARM_CHECK) — out of scope for now.
- All messages above are **common dialect** and emitted unsolicited by all three firmwares
  (verified in each `telemetry/mavlink.c`; ArduPilot GCS_Common). EKF_STATUS_REPORT / VIBRATION /
  WIND etc. are ArduPilot extras → "Future" section.
- **SITL for manual verification**: `sim_vehicle.py -v ArduCopter --out=udp:127.0.0.1:14550`
  against a `mavlink` device with `uri = udp://0.0.0.0:14550`. INAV/BF SITLs exist but are
  TCP-UART + bridge fiddly — golden-bytes unit tests reproduce their exact emitter subsets instead.

## Design decision

Extend `Telemetry` with **one nullable component**: `FlightState` (a new domain record). Rejected:
encoding booleans as doubles in `extra` (dishonest typing — this codebase refuses fake reads, cf.
`AssetAttention.sourceState`), and a sibling telemetry channel (ceremony across every port for the
same data). `FlightState` rides the existing pipe: `TelemetrySourcePort` → `UsageTracker.applySample`
→ persistence + SSE, one choke point each.

```java
// vision-domain, com.drones.vision.domain.model
record FlightState(String firmware,        // "ardupilot" | "generic" | "px4" | null unknown
                   String mode,            // human mode name ("RTL", "Loiter", "Angle"), null unknown
                   Boolean armed, Boolean failsafe,
                   Integer gpsFixType,     // GPS_FIX_TYPE ordinal 0..6, null unknown
                   Integer satellites, Double hdop, Integer rssiPercent,
                   List<String> armingBlockers)   // non-null, empty = none known; List.copyOf
```
All fields nullable except `armingBlockers`; validation: gpsFixType ∈ [0,8] if present, rssiPercent
∈ [0,100], hdop ≥ 0, satellites ≥ 0. `static FlightState empty()` = all null + empty list.
`Telemetry` gains 9th component `FlightState flightState` (nullable) + an 8-arg convenience ctor
defaulting it to `null` (existing N-1-arg idiom) so every current call site compiles unchanged.

## Wire contract (frozen — frontend builds against this)

`TelemetrySampleResponse` gains two fields (both `@JsonInclude(NON_NULL)` / omitted when absent):

```json
{ "deviceId": "…", "at": "…", "latitude": 1, "longitude": 2, "altitudeMeters": 120,
  "headingDegrees": 90, "batteryPercent": 76,
  "flightState": { "firmware": "ardupilot", "mode": "RTL", "armed": true, "failsafe": true,
                   "gpsFixType": 3, "satellites": 12, "hdop": 0.9, "rssiPercent": 87,
                   "armingBlockers": [] },
  "extra": { "groundspeedMps": 12.3, "batteryVoltage": 22.8, "vxMps": 1.0 } }
```

- `flightState.armingBlockers` omitted when empty; `flightState` omitted when null.
- `extra` (the existing `Telemetry.extra` map, previously dropped at this DTO) omitted when empty.
- Fleet snapshot: `FleetAssetResponse` gains `flightMode?: string`, `armed?: boolean`,
  `failsafe?: boolean` (from `AssetAttention`, same latest-telemetry derivation as `batteryPercent`).
- Same DTO serves REST `/api/usages/{id}/telemetry`, the replay timeline, and SSE `telemetry:<assetId>`
  — no new topics, no envelope change. Payload growth is bounded (≤ ~200 bytes/sample) — acceptable
  within the FIFO-50 × 150 ms SSE buffer.

## Phases & agent scopes (disjoint files; each ends green + MODULE.md updated)

### F-a — domain + MAVLink decoder (backend core; blocks F-b/F-c)
Scope: `vision-domain/**`, `adapters/adapter-mavlink/**`.
- `FlightState` record + `Telemetry` 9th component (+ tests, existing-ctor compatibility).
- adapter-mavlink: new package-private `FlightModes` (ArduPilot copter/plane/rover + Betaflight
  tables, `String name(int autopilot, int mavType, long customMode)`); `MavlinkTelemetryDecoder`
  statefully merges HEARTBEAT (firmware/mode/armed/failsafe), GPS_RAW_INT (fix/sats/hdop with
  invalid-sentinel handling: 255 sats, 65535 eph), RC_CHANNELS + RC_CHANNELS_RAW (rssi, 255 invalid),
  SYS_STATUS (+`extra["batteryVoltage"]` V from mV, 65535 invalid), STATUSTEXT (`^(PreArm|Arm): `
  → rolling blocker set, cleared when armed flag turns true; cap 10, insertion-ordered).
  Every emitted sample carries the merged `FlightState` (null until first heartbeat).
- TX simulator (`MavlinkFeedTransmitter`): heartbeat now sends `base_mode` armed + custom_mode
  Loiter(5), `system_status` ACTIVE; plus 1 Hz GPS_RAW_INT (3D fix, 12 sats, eph 90); when the
  drained battery falls below new option `failsafeBatteryPercent` (default 15, lenient parse) →
  custom_mode RTL(6) + system_status CRITICAL. Round-trip integration test asserts the RX side
  reports armed → failsafe transition.
- Build: `./mvnw -B -pl vision-domain,adapters/adapter-mavlink test`.

### F-b — application + API + persistence (after F-a)
Scope: `vision-application/**`, `vision-api/**`, `adapters/adapter-persistence/**`, `vision-app/**`.
- `AssetAttention` + `DefaultFleetSummaryService`: derive `flightMode`/`armed`/`failsafe` from
  `latestTelemetry` (honest nulls when absent).
- vision-api: `FlightStateResponse` + `TelemetrySampleResponse.flightState`/`extra` per the frozen
  contract; `FleetAssetResponse` 3 new fields; SSE path is automatic (same DTO).
- adapter-persistence: `TelemetrySampleEntity` gains nullable jsonb `flight_state` column
  (`V6__telemetry_flight_state.sql`), round-trips `FlightState` (persistence-local map/POJO shape,
  same `@JdbcTypeCode(SqlTypes.JSON)` idiom as `extra`); older rows read back as null.
- vision-app: no wiring change expected; ArchUnit + devsupport compile pass; smoke-check
  in-memory repos (they store the domain record as-is — likely zero change).
- Build: `./mvnw -B -pl vision-application,vision-api,adapters/adapter-persistence,vision-app -am test`
  (with `-DskipWeb`).

### F-c — simulation parity (after F-a; parallel with F-b, disjoint files)
Scope: `adapters/adapter-simulation/**`.
- `SimulatedTelemetrySource` emits a full `FlightState`: firmware "ardupilot", armed, mode
  "Loiter", 3D fix, 12 sats, hdop 0.8, rssi ~90 with mild jitter; when simulated battery < 20 %
  → mode "RTL" + failsafe=true (dev-mode demo of the banner without hardware); < 8 % → mode "Land".
  While a sim asset is "on the ground" (before stream start there is no sample — unchanged), the
  first samples may carry armed=false + one synthetic arming blocker for a few seconds, then arm —
  gives the checklist something real to show in dev. Keep deterministic-ish (seeded per device).
- Build: `./mvnw -B -pl adapters/adapter-simulation test`.

### F-d — frontend flight state UI (parallel with F-a/b/c; vision-web only, builds against frozen contract)
Scope: `vision-web/**`.
- `core/api/models.ts`: `FlightState`, `TelemetrySample.flightState?`, `.extra?`,
  `FleetAsset` 3 new optional fields.
- `core/telemetry/flight-state-logic.ts` (pure, tested): `gpsFixLabel` (No GPS / No fix / 2D /
  3D / DGPS / RTK); `gpsSeverity` (null|0|1 → red, 2 → amber, ≥3 → ok); `flightBanner(sample)` →
  `null | {kind:'failsafe'|'rth'|'landing', text}` (failsafe=true → red FAILSAFE banner; mode
  RTL/SmartRTL/QRTL/Auto RTL without failsafe → amber "Return to home active"; Land → amber);
  `derivePreflight(sample, hasVideo, streaming)` → checklist items
  [video feed, telemetry fresh, GPS 3D fix, battery ≥ 45 %, FC reports armable (no blockers)]
  each `{label, state: 'ok'|'fail'|'unknown', detail?}` — unknown, never faked, when data absent.
- `features/fly/`: **failsafe banner** (full-width over video, `--live`-red, B612 Mono, highest
  z-inside-hud; auto-shows/hides from `flightBanner`); **fly-osd** chips: mode, GPS (icon+sats,
  severity-colored), armed/disarmed, ground speed from `extra.groundspeedMps` (closes the OSD's
  own documented gap), RSSI when present; **preflight-checklist** card shown when watch-mode off
  and (disarmed or no telemetry yet) — includes arming blockers verbatim under the armable row.
- `features/command/`: `command-logic.ts` new attention reasons `failsafe` (rank above
  battery-critical) and `gps-degraded` (below battery-low); asset-panel status tab: mode/armed/GPS
  rows; rail row shows a compact red FS chip when failsafe.
- `features/wall/wall-tile.ts`: small mode badge, red when failsafe.
- `shared/map/fleet-map`: popup gains mode line (severity-colored when failsafe).
- Poka-yoke: banners state what the *aircraft* is doing, not what the user should do; no fake
  data — absent fields render as `—`/unknown; severity colors follow existing tokens
  (`--live` red only for failsafe, amber for advisory).
- Build: `cd vision-web && npm test` (vitest) — new specs for `flight-state-logic`.

## F-e — ArduPilot integration depth (RX-only extras; approved follow-up)

All numeric → they ride the existing `Telemetry.extra` map (already on the wire since F-b), no
domain/DTO change. Frozen `extra` keys (frontend builds against these):

| Source message | Keys |
|---|---|
| `WIND` (ardupilotmega) | `windSpeedMps`, `windDirectionDegrees` |
| `VIBRATION` (common) | `vibeXMs2`, `vibeYMs2`, `vibeZMs2` (clipping counts deliberately skipped) |
| `EKF_STATUS_REPORT` (ardupilotmega) | `ekfVelocityVariance`, `ekfPosHorizVariance`, `ekfPosVertVariance`, `ekfCompassVariance` |
| `MISSION_CURRENT` (common) | `missionSeq` |
| `RANGEFINDER` (ardupilotmega) | `rangefinderDistanceM` |

Thresholds (frontend, QGC/ArduPilot conventions): EKF variance ok < 0.5, warn 0.5–1.0, bad > 1.0;
vibration ok < 30 m/s², warn 30–60, bad > 60. The dronefleet library ships every dialect;
`MavlinkConnection` auto-selects by HEARTBEAT autopilot — ardupilotmega classes decode without
config changes. Non-ArduPilot firmwares simply never emit these → keys absent → UI shows nothing
(poka-yoke: no fake reads).

## Future (explicitly out of scope now)
- ArduPilot FENCE_STATUS, NAMED_VALUE_FLOAT — once geofencing/rules land.
- SYS_STATUS sensor-health bitmask (RC_RECEIVER, PREARM_CHECK bits) → richer checklist.
- Command TX (mode set, arm/disarm, missions, param audit) — a separate, deliberate decision
  (FEATURE-MATRIX Tier-4 "missions" caveats apply).
- Betaflight `AVAILABLE_MODES` self-describing mode tables; PX4 mode table.
- HIGH_LATENCY2 satcom check-ins for long-range fleets.
