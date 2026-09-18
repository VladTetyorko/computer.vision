# adapter-carrier-serial

Spring adapter that polls jSerialComm's serial port list, opens a `SerialLink` per matched port and registers/unregisters it on a `LinkRegistry` as ports hotplug in and out.

**Depends on:** `mavlink-core` (`LinkRegistry`/`LinkDescriptor`/`CarrierKind`/`SerialRole`/`SerialLink`/`LinkId`) · `spring-context`/`spring-boot` (`@Configuration`, `@EnableConfigurationProperties`, `@Scheduled`) · `com.fazecast:jSerialComm` — **never** `drone-link/mavlink` or `station/vision-app` (ArchUnit-enforced by `vision-app`'s `ArchitectureTest#adaptersDoNotDependOnEachOther`) · **Used by:** `station/vision-app` (`CarrierWiring` component-scans this module's `@Configuration`, supplying the `LinkRegistry` bean it registers onto)
**Build/test:** `./mvnw -B -pl drone-link/carrier-serial test` — **11 tests**, all green (2026-09-18). Pure unit tests only — no real serial hardware is exercised; `SerialPortEnumerator`'s logic is tested entirely against hand-built `PortInfo` values.

## API surface

### com.drones.vision.adapter.carrierserial
- `@ConfigurationProperties(prefix = "vision.carrier.serial") record CarrierSerialProperties(boolean enabled, Duration pollInterval, int defaultBaudRate, List<String> allow, List<String> deny, Map<String, Integer> baudOverrides)` — **frozen field set, LINK-PAIRING-PLAN.md §3.2: do not add a field here.** `enabled` defaults `false` (`@DefaultValue`); `pollInterval` defaults `2s`, validated positive; `defaultBaudRate` defaults `57600` (ArduPilot/PX4 SiK-radio convention, see Gotchas), validated positive; `allow`/`deny`/`baudOverrides` default to empty and are always copied immutable. `static CarrierSerialProperties defaults()` — disabled/2s/57600/no filters, for tests without a Spring context.
- `@ConfigurationProperties(prefix = "vision.carrier.serial.bench") record CarrierSerialBenchProperties(List<String> patterns)` — separate class (not a field on `CarrierSerialProperties`, see Conventions) carrying `vision.carrier.serial.bench.patterns`; empty by default (nothing is a bench cable). `static CarrierSerialBenchProperties defaults()` — no patterns.
- `final class SerialPortEnumerator` — pure, hardware-free port-matching policy.
  - `SerialPortEnumerator(CarrierSerialProperties properties, CarrierSerialBenchProperties benchProperties)`
  - `record PortInfo(String systemPortName, String systemPortPath, String descriptivePortName)` — `systemPortPath`/`descriptivePortName` null-coerced to `""` in the compact constructor. `String portKey()` — `systemPortPath` when non-blank, else `systemPortName`; this is the id fed to `SerialLink.open`, the hotplug-diff registry key, and the `baudOverrides` lookup key.
  - `List<PortInfo> currentPorts()` — snapshots `SerialPort.getCommPorts()`; safe/empty on a host with no serial hardware, never throws.
  - `boolean matches(PortInfo port)` — deny-then-allow: denied ports are rejected outright; otherwise allowed if `allow` is empty or any `allow` pattern matches.
  - `SerialRole roleFor(PortInfo port)` — `BENCH` if any `benchProperties.patterns()` entry matches, else `GROUND_RADIO`.
  - `int baudRateFor(PortInfo port)` — `baudOverrides.get(port.portKey())`, falling back to `defaultBaudRate`.
- `@Configuration @EnableConfigurationProperties({CarrierSerialProperties.class, CarrierSerialBenchProperties.class}) class SerialCarrierConfiguration` — the scheduled hotplug poll.
  - `SerialCarrierConfiguration(CarrierSerialProperties properties, CarrierSerialBenchProperties benchProperties, LinkRegistry linkRegistry)`
  - `@Scheduled(fixedDelayString = "${vision.carrier.serial.poll-interval:2s}") void pollPorts()` — package-private, one poll tick; no-op while `properties.enabled()` is `false`. Diffs the current matched-port set against `byPortKey`: a port no longer seen is unregistered and closed exactly once; a newly-seen port is opened and registered via `computeIfAbsent` (a failed open returns `null` from the helper, so nothing is cached and the next tick retries).
  - `static final int GROUND_RADIO_PRIORITY = 100` (must exceed carrier-udp's frozen lobby priority 50), `static final int BENCH_PRIORITY = 0` — a bench link registers at 0 and is never auto-elected regardless of the number, enforced by L3 election policy, not this class.

## Conventions

- **Dependency inversion via injected bean, not a Maven dependency.** Same seam as `adapter-carrier-udp`: `LinkRegistry` arrives as a plain injected bean; this module depends on nothing that implements it. `station/vision-app`'s `CarrierWiring` supplies the real one.
- **Role is declared, never guessed.** `SerialRole` (`GROUND_RADIO` vs `BENCH`) is decided purely by which glob pattern list (`allow`/`deny` vs `bench.patterns`) a port matches — never inferred from USB vendor/product id (LINK-PAIRING-PLAN.md §3.2 frozen decision; a cheap USB-serial bridge chip is shared by ground radios and bench dongles alike, so vendor/product id carries no signal).
- **`CarrierSerialProperties`'s field set is frozen.** A new role-decision config surface (bench detection) was added as a *separate* `@ConfigurationProperties` class (`CarrierSerialBenchProperties`) rather than a new field on the frozen record — see Gotchas for why this was necessary rather than optional.
- **Glob matching, not regex, in config.** Only `*` is special (zero-or-more); every other character (`?` included) is literal. Matched case-insensitively against `systemPortPath`, `descriptivePortName` **and** `systemPortName` — any one match counts, so an operator can key off whichever is stable for their hardware.
- **Threading:** `pollPorts()` runs on Spring's shared scheduling thread pool, serialized with itself (never two ticks concurrently) but with nothing else — relies on `LinkRegistry#register`/`#unregister` being documented thread-safe.

## Gotchas

- **`CarrierSerialProperties` cannot carry the bench-pattern field the plan's own prose calls for** — LINK-PAIRING-PLAN.md §3.2 freezes that record's exact field set/order/prefix verbatim while the same section separately requires bench-cable role detection. Resolved by adding `CarrierSerialBenchProperties` as an independent `@ConfigurationProperties(prefix = "vision.carrier.serial.bench")` class rather than touching the frozen record — a plan-internal tension, not an implementation shortcut.
- **`GROUND_RADIO_PRIORITY` (100) and carrier-udp's `LOBBY_PRIORITY` (50) are independently frozen, not derived from each other.** The two modules must never depend on each other (ArchUnit-enforced), so there is no shared constant — if LINK-PAIRING-PLAN.md §3.2's priority numbers are ever revisited, both modules' literals must be changed by hand, in lockstep.
- **A failed `SerialLink.open` is not sticky.** `openAndRegister` returns `null` on `IOException` (logged at WARNING), and `computeIfAbsent` then caches nothing for that key — the very next poll tick retries the open from scratch. A port that is present but perpetually unopenable (permissions, already claimed by another process) logs one WARNING per poll interval forever; there is no backoff.
- **`ConfigurationProperties` classes exempted from `vision-app`'s `ArchitectureTest`.** `configurationPropertiesClassesLiveOnlyInAppConfigPropertiesPackage` normally requires every `@ConfigurationProperties` class to live in `vision-app`'s own `config.properties` package; `com.drones.vision.adapter.carrierserial` (this package) is one of exactly two named exemptions (`adapter-carrier-udp` is the other, though that module currently declares none), since this module must never depend on `vision-app` to bind against a type declared there.
- **57600 default baud is not arbitrary** — it is ArduPilot/PX4's own common SiK-radio telemetry default; a ground radio configured without an explicit `baudOverrides` entry will usually already match its counterpart.
- **`SerialLink.open`/hotplug close are real IO** — `mavlink-core`'s `SerialLink` has its own gotchas (a negative read is a genuine `IOException`, unlike `TcpClientLink`'s silent close); see `drone-link/mavlink-core/MODULE.md`.

## Status

Real, in production use behind `station/vision-app`'s `CarrierWiring` and the `docker-compose.yml` `serial` profile's `/dev/serial/by-id` passthrough (`vision-app-serial` service). `vision.carrier.serial.enabled` defaults to `false` even with the device present — the compose passthrough only makes hardware reachable, it does not turn the poller on. No placeholder methods; ships the entire LINK-PAIRING-PLAN.md §3.2 serial carrier surface except the bench-pattern config shape noted above as a plan-internal tension, resolved rather than deferred.
