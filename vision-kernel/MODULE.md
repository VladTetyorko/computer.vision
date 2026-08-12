# vision-kernel

Shared kernel: ids and pure value objects that every bounded context may depend on. Split out of
`vision-domain` in **W1.7a** (docs/plans/active/DOMAIN-SEPARATION-W1.md §16) as the first of the
wave's two universal modules — a pure `git mv` of `com.drones.vision.kernel`, no package rename, no
import changes anywhere in the repo.

**Depends on:** nothing but `java.base` (no third-party/framework imports anywhere in this module)
**Used by:** `vision-platform`, `vision-domain`, `vision-application`, every adapter, `vision-app`, `vision-api` — transitively, everything
**Build/test:** `./mvnw -B -pl vision-kernel test`

## The rule

The kernel is deliberately tiny and stays that way: **pure values, no ports, no aggregates.** It is
the one package every one of the eight bounded contexts (warehouse, identity, perception, flight,
map, learning, events, simulation) may import, so the dependency only stays safe because it flows one
way — a kernel type that reached back into a context (an id-lookup port, a reference to another
context's aggregate) would smuggle that context's coupling into all eight others at once, since
everyone already depends on the kernel. Enforced by `vision-app`'s `ContextArchitectureTest`
(`kernelDependsOnNothingButItselfAndTheJdk`) at the bytecode level; this module's own POM makes the
same rule structural — it has no internal dependency to declare.

## API surface

- `record AssetId(UUID value)` — `static random()`, `static of(String)`
- `record BearingDistance(double bearingDegrees, double distanceMeters)` — the output of `GeoProjection.bearingDistance`: initial bearing degrees [0,360) clockwise from true north, plus great-circle distance in meters (never negative); validated in its own compact ctor
- `record BoundingBox(double x, double y, double width, double height)` — each component in [0,1]
- `enum Capability` — VIDEO, TELEMETRY, PTZ, AUDIO
- `record CategoryId(String slug)` — must match `[a-z0-9]+(-[a-z0-9]+)*`; no random-id factories (reference-data key, not a generated id)
- `record DeviceId(UUID value)` — `static random()`, `static of(String)`
- `record FlightState(String firmware, String mode, Boolean armed, Boolean failsafe, Integer gpsFixType, Integer satellites, Double hdop, Integer rssiPercent, List<String> armingBlockers)` — flight-controller-reported state decoded from ArduPilot/INAV/Betaflight/PX4 telemetry; every field except `armingBlockers` individually nullable (a decoder merges this incrementally as different MAVLink messages arrive); `firmware` is `"ardupilot"`/`"generic"`/`"px4"`/`null`; `gpsFixType` (MAVLink `GPS_FIX_TYPE` ordinal) ∈[0,8] if present; `rssiPercent` ∈[0,100] if present; `satellites`/`hdop` ≥0 if present; `armingBlockers` non-null, defensively copied via `List.copyOf`; `static empty()` = all null + empty list
- `record GeoPosition(double latitude, double longitude, Double altitudeMeters)` — lat [-90,90], lon [-180,180], altitude nullable
- `final class GeoProjection` — pure, stateless geo-math; private ctor, static methods only (no interface — one implementation, no substitution point). `EARTH_RADIUS_METERS = 6_371_000.0` (IUGG mean radius); `DEFAULT_DEPRESSION_DEGREES = 45.0` (documented guess — no gimbal telemetry exists to read a real value from). `static GeoPosition project(GeoPosition drone, double headingDegrees, double altitudeMeters, double depressionDegrees)` — estimates the ground point a drone's camera is looking at; `depressionDegrees==90` (nadir) or `altitudeMeters==0` short-circuits to the drone's own ground position; `headingDegrees` normalized mod 360; throws `IllegalArgumentException` for `drone==null`, negative/non-finite `altitudeMeters`, `depressionDegrees` outside `(0,90]`, or non-finite `headingDegrees`; returned `altitudeMeters` always `null` (no terrain model). `static BearingDistance bearingDistance(GeoPosition from, GeoPosition to)` — haversine distance + initial great-circle bearing; throws `IllegalArgumentException` on either argument `null`. Both methods clamp/wrap internal trig results so no valid input can produce `NaN` or an out-of-range result
- `record GroupId(UUID value)` — `static random()`, `static of(String)`
- `enum LifecycleState` — ACTIVE, DEACTIVATED, **DELETED (soft)**. Nothing is ever destroyed: a deleted asset/device is hidden from listings and refuses to stream, but its record, usages and telemetry survive and the removal is reversible. Transitions: ACTIVE⇄DEACTIVATED; ACTIVE|DEACTIVATED→DELETED; DELETED→DEACTIVATED (restore — never straight back to ACTIVE)
- `record Ownership(UserId ownerId, GroupId groupId)`
- `record StreamDescriptor(String protocol, URI uri, Map<String,String> options)` — **protocol must be lower-case** (ctor throws otherwise); a protocol+URI+options value object produced by warehouse and consumed by perception, with no behavior of its own — the textbook shared-kernel shape
- `record StreamId(UUID value)` — `static random()`, `static of(String)`
- `record Telemetry(DeviceId deviceId, Instant at, Double latitude, Double longitude, Double altitudeMeters, Double headingDegrees, Double batteryPercent, Map<String,Double> extra, FlightState flightState)` — a telemetry sample from a device: position, attitude, battery state, flight-controller-reported state; all `Double` fields and `flightState` nullable; an 8-arg convenience ctor defaults `flightState=null`. Read by five contexts: flight, perception's OSD, warehouse's stats, events' replay, map
- `record UsageId(UUID value)` — `static random()`, `static of(String)`
- `record UserId(UUID value)` — `static random()`, `static of(String)`

## Conventions

- **Validation:** every record validates in its compact constructor with manual `if (…) throw new IllegalArgumentException(…)` per field (no Bean Validation, no `Objects.requireNonNull` — that idiom is application-layer only).
- **Defensive copies:** every `List`/`Set`/`Map` component is reassigned in the compact ctor via `List.copyOf`/`Map.copyOf` (`FlightState.armingBlockers`, `StreamDescriptor.options`, `Telemetry.extra`).
- **UUID id pattern:** `AssetId`/`DeviceId`/`GroupId`/`StreamId`/`UsageId`/`UserId` all wrap `UUID` with the same pair of factories — `random()` (`UUID.randomUUID()`) and `of(String)` (`UUID.fromString`, rethrows as `IllegalArgumentException`). `CategoryId` is the exception: a validated kebab-case `String` slug, not a UUID — categories are reference data with human-authored keys, not generated ids.

## Gotchas

- `StreamDescriptor.protocol` must already be lower-case; the compact ctor throws `IllegalArgumentException` if it isn't — callers cannot rely on normalization happening for them.
- `CategoryId.slug` must match `[a-z0-9]+(-[a-z0-9]+)*` (lower-case-kebab), enforced here rather than left to callers, because slugs double as stable, human-readable reference-data keys.
- `GeofenceZone.contains` (flight context, not this module) is a planar approximation over `GeoPosition` — see `contexts/vision-flight/MODULE.md`'s Gotchas for why that is safe at geofence scale but not near poles/antimeridian.

## Status

Stable since W1.6c (docs/plans/active/DOMAIN-SEPARATION-W1.md §15), which last changed the type set
by moving `Telemetry`/`FlightState` in from `flight`. W1.7a (this module's creation) moved the
package's *jar*, not its contents — same 17 types, same behavior, zero import changes anywhere in the
repo (verified: `./mvnw -B -DskipWeb test` green across the reactor after the split).
