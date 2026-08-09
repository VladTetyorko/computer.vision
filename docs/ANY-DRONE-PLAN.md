# ANY-DRONE-PLAN — the adaptation layer: bring your own drone, it just works

Status: **thinking document for review (2026-08-09)** — not yet an authoritative spec. Grounded in
docs/FC-INTEGRATIONS-PLAN.md (decode), docs/DRONE-INFRA-PLAN.md (gateway I-a, discovery I-b, guided
onboarding I-g, command TX I-e), docs/RC-CONTROL-PLAN.md. Companion: docs/MOAT.md (why this matters
more than another feature).

## 0. The problem, stated honestly

A person owns a drone — ArduPilot, INAV or Betaflight, built or bought from anyone. They want to
use our platform. Today the path is:

1. Get MAVLink onto our UDP port (ELRS backpack / ESP32 bridge / companion computer).
2. Get video to us (RTSP push / MJPEG / capture card).
3. Configure the flight controller so it actually *emits* what we need.
4. Create the asset (solved — I-b heartbeat discovery + I-g wizard: four clicks, nothing typed).

Steps 1–2 are hardware, and I-d/I-g already cover them with costed recipes and copy-paste snippets
pre-filled with our real address. **Step 3 is where every real onboarding dies, and we do nothing
about it.** Wrong `SERIALx_PROTOCOL`, stream rates left at zero, MAVLink 1 vs 2, four aircraft all
shipping `SYSID_THISMAV=1`, no `RC_CHANNELS` so RSSI never appears, no gimbal attitude so visual
geolocation silently degrades.

### What we have vs what's missing

| | Today | Missing |
|---|---|---|
| Instructions | I-g wizard: firmware × link picker, snippets carrying our real IP/port | — |
| Verification | none — the user pastes, and hopes | **did it work? which line failed?** |
| Diagnosis | none — telemetry either shows up or doesn't | **why is ground speed empty? why no RSSI?** |
| Repair | none | **fix it for me** |
| Memory | none | **what changed on this aircraft since last flight?** |

I-g is **open-loop**. Everything below is closing that loop. That is the whole idea: the platform
stops *telling* the operator what to configure and starts *observing, diagnosing, and repairing* it.

## 1. Design: PROBE → DIAGNOSE → REMEDIATE → VERIFY

One loop, four stages, each independently valuable and shippable in that order.

```
        ┌──────── VERIFY (re-probe, prove the gap closed) ────────┐
        ▼                                                          │
   ┌─────────┐      ┌────────────┐      ┌──────────────┐          │
   │  PROBE  │─────►│  DIAGNOSE  │─────►│  REMEDIATE   │──────────┘
   │ listen  │      │ feature ×  │      │ 3 mechanisms │
   │ + ask   │      │ requirement│      │ by firmware  │
   └─────────┘      └────────────┘      └──────────────┘
   VehicleProfile   ReadinessReport      runtime request │ param write │ CLI script
```

### 1.1 PROBE — never ask the user what drone they have

Everything is observed. Three sources, all read-only, all safe:

- **Passive inventory.** Watch the gateway socket for N seconds: which message IDs arrive, at what
  rate. We already decode 15+ message types; the *inventory itself* (msgid → observed Hz) is new
  and is the single most diagnostic artifact in the system. "You are getting `GLOBAL_POSITION_INT`
  at 0.9 Hz and no `VFR_HUD` at all" explains a dozen UI symptoms at once.
- **`AUTOPILOT_VERSION`** (request via `MAV_CMD_REQUEST_MESSAGE`): firmware version, board, and the
  `capabilities` bitmask — the autopilot literally tells us which protocols it supports
  (`PARAM_FLOAT`, `MISSION_INT`, `SET_POSITION_TARGET_*`, `MAVLINK2`, `FTP`…). Free capability
  matrix, no guessing per firmware.
- **Targeted parameter read** (`PARAM_REQUEST_READ` per name, not a full `PARAM_REQUEST_LIST` —
  a full list is ~1000 params and minutes over a 2.4 kB/s telemetry link). We read a small named
  set, ~20 params, defined by the requirement matrix below. MAVFTP param download is the
  optimization for later, ArduPilot-only.

Output: a `VehicleProfile` value — firmware + version, vehicle type, capability bitmask, observed
message inventory with rates, the read parameter values, link quality. Persisted per asset,
timestamped. **This whole stage is RX-only plus two read requests — zero risk, no doctrine break.**

### 1.2 DIAGNOSE — a feature × requirement matrix, expressed as data

Each platform feature declares what it needs. Not code, not `if (firmware == …)` chains — a table:

| Feature | Requires | Symptom when missing |
|---|---|---|
| Map position, breadcrumb | `GLOBAL_POSITION_INT` ≥ 2 Hz | drone drifts/jumps on the map |
| Preflight checklist | `GPS_RAW_INT`, `STATUSTEXT` | GPS row unknown, no arming blockers |
| OSD ground speed | `VFR_HUD` | speed chip empty (already a documented gap) |
| Link quality / RSSI | `RC_CHANNELS` | no RSSI chip |
| Failsafe + RTH banners | `HEARTBEAT` `system_status`, mode table | no safety banner |
| Battery | `SYS_STATUS` / `BATTERY_STATUS` | no battery, no low-battery attention |
| **Visual geolocation** | `ATTITUDE` ≥ 5 Hz + gimbal attitude + known camera FOV | silent accuracy collapse — the worst failure mode we have |
| Fleet gateway (multi-aircraft) | unique `SYSID_THISMAV` | **two aircraft appear as one** |
| Command TX (RTL, mode) | MAVLink 2, ArduPilot/INAV, ack path | button hidden |
| RC relay | RC-override accepted, failsafe configured for link-loss | see RC-CONTROL-PLAN's gotcha |

Rendered as the **Readiness report** — the one screen this whole plan exists to produce:

```
Hexa-7 · ArduCopter 4.5.7 · ELRS backpack · 2.3 kB/s

  ✓ ready (6)     Position · Battery · Failsafe banners · Preflight · Command · Fleet ID
  ⚠ degraded (2)  Ground speed — VFR_HUD not arriving (SR2_EXTRA2 = 0)          [Fix]
                  Link quality — RC_CHANNELS not arriving (SR2_RC_CHAN = 0)     [Fix]
  ✗ missing (1)   Visual geolocation — no gimbal attitude message; camera FOV
                  unknown for this asset                             [How to enable]
```

Poka-yoke, consistent with the codebase's existing doctrine: **never fake a read.** "Unknown"
is a first-class state and says *why* it is unknown.

### 1.3 REMEDIATE — three mechanisms, chosen by firmware and by risk

**Mechanism A — runtime request, no write at all (the default, and the best idea in this plan).**
Most "missing message" gaps are stream *rates*, and MAVLink has a runtime fix:
`MAV_CMD_SET_MESSAGE_INTERVAL` (#511, ArduPilot ≥ 4.0), legacy `REQUEST_DATA_STREAM` as fallback.
It changes nothing persistent, survives nothing, breaks nothing, and needs no confirm dialog —
**we simply ask the aircraft for the messages we need, every time we connect.** A large share of
degraded onboardings vanish without ever writing a parameter. This should arguably land before
anything else in this plan.

**Mechanism B — parameter write (`PARAM_SET`), tiered by risk.** For what must persist. The tiering
is the credibility line of the whole product:

| Tier | Examples | Policy |
|---|---|---|
| **A — reporting** | `SRx_*` stream rates, `SYSID_THISMAV`, `SERIALx_PROTOCOL`=MAVLink2 | one confirm, snapshot before, one-click restore |
| **B — link & failsafe behavior** | GCS/RC failsafe options, RC-override acceptance | per-item explicit consent, plain-language "what changes in flight", disarmed-only, audit-logged |
| **C — flight-critical** | PIDs, arming checks, frame class, battery calibration | **never written. Reported only.** |

An allowlist, in domain code, is the enforcement — not a UI convention. Every write: disarmed-only,
value read back and verified, previous value stored, `AuditTrailPort` entry. A platform that writes
someone's PIDs kills an aircraft once and is dead as a product; the C tier is not negotiable.

**Mechanism C — generated CLI script.** Betaflight and INAV do not usefully expose the MAVLink
parameter protocol. So we generate the exact `serial …` / `set …` / `save` lines **from the probed
state** — a diff, not a generic snippet — for paste into the configurator, then a **[Verify]**
button that re-probes and proves it. This is I-g's snippet, upgraded from generic to
vehicle-specific and closed-loop.

*(Exact BF/INAV CLI keys and serial-function bitmasks must be verified against firmware sources
before implementation — this repo's docs do not carry unverified wire facts.)*

### 1.4 VERIFY — the loop closes

Every remediation is followed by an automatic re-probe with the same code path that produced the
report. Green means proven, not claimed. This is the difference between "here's a config snippet"
and an integration layer.

## 2. The vehicle passport (falls out for free)

Every probe is a timestamped snapshot. Store them and two capabilities appear at almost no cost:

- **Config drift.** "`FS_THR_ENABLE` changed since the flight on 2026-08-02." No GCS does this well;
  for a fleet it's maintenance-grade information, and it pairs with FEATURE-MATRIX's maintenance-log
  row.
- **Incident forensics.** The parameter set and message inventory **at the time of a flight**,
  attached to the `AssetUsage`. For a referee, an investigator, or an insurer this is evidence.
  We already store usages + telemetry; the profile is one more foreign key.

## 3. "Works with" — the compatibility matrix as a community asset

Every probe teaches us one combination: *ArduCopter 4.5.7 + ELRS backpack → 9/9 features, 2.3 kB/s.*
Aggregated (opt-in, anonymized), that becomes a public compatibility matrix nobody else can build
— because nobody else sits on the wire of thousands of heterogeneous DIY aircraft. It is
simultaneously marketing, documentation, and a defaults database that makes onboarding #1000
easier than onboarding #1.

## 4. The video half — don't forget it's harder

Telemetry is the *tractable* half; video is where people actually give up. Two moves:

- **One-line companion install.** `curl http://<server>/setup.sh | sh` — served by us, generated
  per-asset, already carrying our address, the mediamtx path, the credentials: installs
  mavlink-router + an FFmpeg/OpenIPC push unit. This is I-d's recipe turned into an executable.
  Same philosophy as I-g's pre-filled snippets, taken to its conclusion.
- **Ingest self-diagnosis.** Codec, resolution, FPS, keyframe interval, arrival jitter, bitrate vs
  link budget → the same readiness language. "Your keyframe interval is 10 s; HLS viewers will wait
  up to 10 s to start" is a support ticket the platform answers itself.

## 5. Where this attaches (existing seams — no new architecture)

| Piece | Attaches to |
|---|---|
| Probe transport | `MavlinkSocketHub` shared socket + the ack seam `MavlinkFlightCommander` already uses |
| Command shape | `MavlinkFlightCommander`'s one-connection-per-send + `COMMAND_ACK` await — `PARAM_SET`/`PARAM_REQUEST_READ`/`SET_MESSAGE_INTERVAL` are the same shape |
| New port | `VehicleConfigPort` (vision-domain): `probe(Device)`, `requestMessageInterval(...)`, `readParams(...)`, `writeParam(...)` — read half is doctrine-safe, write half gated exactly like I-e |
| Capability model | generalize `FlightCommandPort.capabilities()` into the profile, so the whole UI is capability-driven rather than firmware-conditional |
| Discovery / wizard | `MavlinkHeartbeatScanner` + the I-g onboarding wizard — readiness becomes the wizard's final step |
| Audit | `AuditTrailPort` (every write), `AssetUsage` (profile snapshot per flight) |

## 6. Suggested wave order (value per unit of risk)

| Wave | Content | Risk | Why here |
|---|---|---|---|
| **1** | Message inventory + `AUTOPILOT_VERSION` + targeted param read → `VehicleProfile`; Readiness report in the asset page and as the wizard's last step | none (RX + 2 read requests) | ships the diagnostic value with zero doctrine break |
| **2** | `SET_MESSAGE_INTERVAL` auto-request on connect | none (non-persistent) | silently fixes a large share of degraded setups |
| **3** | Tier-A `PARAM_SET` with snapshot + restore + audit; `SYSID_THISMAV` assignment in the wizard | low, gated | makes the fleet gateway actually work for multi-aircraft owners |
| **4** | Generated BF/INAV CLI diff + [Verify] | none (we don't write) | closes the loop for the two firmwares we can't write to |
| **5** | Passport: drift detection + per-usage snapshot | none | maintenance + forensics, nearly free |
| **6** | Video-side readiness + one-line companion installer | none | the half that actually loses users |
| **later** | Tier-B writes, "works with" aggregation | needs explicit go | policy decisions, not engineering ones |

Waves 1–2 are, I'd argue, worth more than any single remaining Tier-2 row in FEATURE-MATRIX —
because they are the funnel through which every other feature is reached. See docs/MOAT.md §4.
