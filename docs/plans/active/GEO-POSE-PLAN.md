# GEO-POSE-PLAN — give the projection the measurements it never had

**Status:** authoritative spec, not yet implemented. Branch `feat/geo-pose` off master.
**Reads with:** [MAVLINK-CORE-PLAN.md](MAVLINK-CORE-PLAN.md) (§2.2 message set, merged W0–W4),
`vision-kernel/src/main/java/com/drones/vision/kernel/GeoProjection.java` (the math being fixed).
**Scope:** the **shipped** `GeoProjection` "Mark target" feature. **Not** the parked `feat/visual-geo`
VPR branch — see §6.

---

## 1. Why

`GeoProjection.project()` computes `groundRange = altitudeMeters / tan(depressionDegrees)` and projects
that distance along a bearing. Three of its four inputs are wrong or guessed:

| Input | What it gets today | Consequence |
|---|---|---|
| `altitudeMeters` | **AMSL** — `GLOBAL_POSITION_INT.alt` (`PositionAndPowerState.java:40`), while the parameter's own javadoc says *"altitude above the ground"* | at the hardcoded 45° depression `tan = 1`, so **ground range = altitude**. Flying 100 m AGL at a site 180 m above sea level puts the mark 280 m downrange instead of 100 m. Systematic, always the same direction, magnitude = site elevation |
| `depressionDegrees` | the constant `45.0` — no caller ever overrides it (`GeolocateMarkRequest.java:49`; `marks-store.ts` sends no value) | the camera's real pointing angle is never consulted |
| `headingDegrees` | **airframe** heading | a gimbal panned 90° right still projects straight ahead |
| roll / pitch | **nothing** — `Telemetry` has no such field and `MavlinkTelemetryDecoder` deliberately ignores `ATTITUDE` (its own test `ignoresUnrecognizedMessageTypes` asserts this) | absent from the model entirely |

The altitude unit error is almost certainly the dominant symptom, and it is the cheapest to fix: the AGL
figure is already in the message we decode, in a field we discard.

## 2. Scope

**In:** decode the missing measurements, carry them on `Telemetry`, and let the projection use them.
**Out:** DEM/terrain intersection (`MASTER-MATRIX:306`'s "missing half"), camera intrinsics, per-detection
pixel→ray projection, and the VPR branch. This makes the estimate *honest*, not survey-grade — §6.

## 3. Decisions (pinned)

| # | Decision | Rationale |
|---|---|---|
| G1 | **`altitudeMeters` keeps its AMSL meaning; AGL is a new, separate field** | `GeoPosition` and the rest of the codebase already read `altitudeMeters` as absolute. Redefining it would silently change every other consumer. The bug is that the *projection* reads the wrong one, not that the field is wrong |
| G2 | **AGL source is `GLOBAL_POSITION_INT.relative_alt`** (height above home), not `RANGEFINDER` | the `RANGEFINDER` message carries **no validity or max-range field**. Promoting it would clamp altitude at the sensor's ceiling (~40 m typical lidar) and be *worse* than AMSL above that. Doing it safely needs `DISTANCE_SENSOR` (#132) with its `min_distance`/`max_distance` — deferred, deliberately. `relative_alt` is exact for a flat site and wrong only by terrain change since takeoff, which is the error DEM would fix anyway |
| G3 | **New `Telemetry` components are appended, with an old-arity convenience constructor** | exactly the `flightState` precedent this record already documents. Keeps all 46 existing `new Telemetry(...)` call sites compiling untouched |
| G4 | **Gimbal orientation is preferred over airframe attitude; both are decoded** | `GIMBAL_DEVICE_ATTITUDE_STATUS` (#285) is the current spec message, `MOUNT_ORIENTATION` (#265) is deprecated but still what much ArduPilot firmware actually emits. Support both, prefer #285 |
| G5 | **The projection reports whether it measured or guessed** | an operator must be able to tell a fix from an assumption. `CameraAim.measured` drives that; marks stay editable either way |
| G6 | **No behaviour changes when the new fields are absent** | a device that reports none of this projects exactly as it does today, including the AMSL fallback. Every existing test must stay green on that path |

## 4. Design

### 4.1 `vision-kernel`

```
record Attitude(Double rollDegrees,       Double pitchDegrees,       Double yawDegrees,
                Double gimbalRollDegrees, Double gimbalPitchDegrees, Double gimbalYawDegrees)
```
All nullable — a fixed camera has no gimbal, a device without an IMU has no attitude. Degrees
throughout (the wire is radians; conversion belongs in the decoder, not here).

`Telemetry` gains three appended components: `Double aglMeters`, `Attitude attitude`,
`Long deviceBootMillis`. The existing 9-arg constructor becomes a convenience overload delegating
`(null, null, null)`.

`GeoProjection` gains the aim resolver — pure, kernel-local, and the single place the precedence rules
live:

```
record CameraAim(double bearingDegrees, double depressionDegrees, double aglMeters, boolean measured)
static CameraAim aimFrom(Telemetry telemetry, double fallbackDepressionDegrees)
```

| Output | Precedence |
|---|---|
| `bearingDegrees` | `attitude.gimbalYawDegrees` (earth-frame) → else `headingDegrees` |
| `depressionDegrees` | `-attitude.gimbalPitchDegrees` if it lands in `(0, 90]` → else `fallbackDepressionDegrees` |
| `aglMeters` | `aglMeters` → else `altitudeMeters` (**today's behaviour, carrying today's AMSL error — kept as the honest fallback, not silently fixed**) |
| `measured` | `true` only when depression came from a real gimbal reading **and** `aglMeters` was present |

**What `measured` means, settled after V1 raised it.** It means *"no assumed constant was used"* — not
*"every input came from a gimbal"*. A bearing taken from compass heading still counts as measured,
because for a fixed forward-facing camera the airframe heading **is** the camera's bearing, and it is a
real reading either way. The two things it excludes are the `45°` depression constant and the AMSL
altitude standing in for AGL — the two actual assumptions. This is deliberately not resolvable further
from telemetry alone: a missing `gimbalYawDegrees` is indistinguishable between "there is no gimbal"
(heading is correct) and "the gimbal is not reporting" (heading is wrong). V3 must therefore label the
flag as *measured aim* vs *assumed 45°*, never as an accuracy guarantee.

`project(...)`'s existing 4-arg signature is **unchanged** (G6); the new overload takes a `CameraAim`.

### 4.2 `adapter-mavlink`

| Message | Field | Lands on |
|---|---|---|
| `GLOBAL_POSITION_INT` | `relative_alt` (mm) | `Telemetry.aglMeters` (÷1000) |
| `GLOBAL_POSITION_INT` | `time_boot_ms` | `Telemetry.deviceBootMillis` |
| `ATTITUDE` (#30) | `roll`/`pitch`/`yaw` (rad) | `Attitude.roll/pitch/yawDegrees` (`toDegrees`) |
| `GIMBAL_DEVICE_ATTITUDE_STATUS` (#285) | `q` (quaternion, w x y z) | `Attitude.gimbal*Degrees` — **preferred** |
| `MOUNT_ORIENTATION` (#265) | `roll`/`pitch`/`yaw` (deg) | `Attitude.gimbal*Degrees` — fallback, only when #285 has never arrived |

Quaternion→Euler is the one piece of real new math and the one most likely to be subtly wrong (the
parked VPR branch flagged its own quaternion path as *"not hardware/SITL-validated"*). It gets its own
unit tests against hand-computed values, and #265 exists precisely so there is a
non-quaternion path to cross-check against.

### 4.3 `vision-map` / api / web

`DefaultMarkService.geolocate` resolves a `CameraAim` instead of passing raw telemetry fields, and
records on the mark whether the fix was measured or assumed. The API keeps accepting an explicit
`depressionDegrees` override (it now means "override the measurement"). The cockpit shows measured vs
estimated; marks stay editable in both cases, as they are today.

## 5. Waves

| Wave | Agent | Scope (disjoint) | Exit criteria |
|---|---|---|---|
| **V1** | domain-modeler | `vision-kernel/**` | `Attitude`, the three `Telemetry` components, `CameraAim`/`aimFrom`, all precedence rules unit-tested incl. every fallback. **All 46 existing `new Telemetry(...)` call sites still compile** — verified by building every dependent module |
| **V2** | adapter-builder | `adapters/adapter-mavlink/**` | the five mappings above; quaternion→Euler unit-tested against hand-computed values; #285-preferred-over-#265 tested; `ignoresUnrecognizedMessageTypes` updated **only** to drop `ATTITUDE` (which is now recognised) with its other message types kept. 135+ green, SITL un-skipped |
| **V3** | application-service → spring-integrator → web-ui | `contexts/vision-map/**`, `vision-api/**`, `vision-web/**` | geolocate uses `aimFrom`; measured-vs-estimated surfaced; existing `DefaultMarkServiceTest` assertions unchanged on the no-pose path |

V1 → V2 → V3 strictly sequential (each needs the type below it).

## 6. What this does **not** fix — the ceiling

- **Flat earth stays flat earth.** No DEM, so a target on a hillside is still wrong by the terrain
  offset. `MASTER-MATRIX:306` already calls this "the missing half".
- **No camera intrinsics**, so this is still a projection of the *aircraft's aim*, not of a pixel. A
  detection's position within the frame does not move the mark.
- **`Telemetry.at` stays wall-clock-at-decode.** `deviceBootMillis` is carried so a future wave can
  align a frame to a pose, but nothing consumes it yet and `UsageTracker.latestTelemetry` still returns
  "the newest sample ever received".
- **The parked `feat/visual-geo` VPR branch is untouched.** Its failure is image-retrieval ranking
  (correct tile at median rank 14 of 40, `0/12` top-1); no telemetry work affects it.

Expected outcome: marks land where the aircraft is actually looking, within the error of a flat-ground
assumption — instead of being offset by the site's height above sea level on every single fix.
