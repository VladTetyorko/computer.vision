# adapter-carrier-udp

Spring adapter that binds the well-known MAVLink "lobby" UDP socket at boot and registers it on a `LinkRegistry` — the only place in the whole application that opens that socket.

**Depends on:** `mavlink-core` (`LinkRegistry`/`LinkDescriptor`/`CarrierKind`/`SerialRole`/`LinkId`/`UdpListenLink`) · `spring-context` (`@Configuration`/`@Bean`/`@Value` only) — **never** `drone-link/mavlink` or `station/vision-app` (ArchUnit-enforced by `vision-app`'s `ArchitectureTest#adaptersDoNotDependOnEachOther`) · **Used by:** `station/vision-app` (`CarrierWiring` component-scans this module's `@Configuration` and supplies the `LinkRegistry` bean it binds onto, backed by `MavlinkTelemetrySource.linkRegistry(port)`)
**Build/test:** `./mvnw -B -pl drone-link/carrier-udp test` — **2 tests**, all green (2026-09-18).

## API surface

### com.drones.vision.adapter.carrierudp
- `@Configuration class UdpCarrierConfiguration` — no explicit constructor (implicit no-arg; the bean method takes everything it needs as parameters).
  - `@Bean LinkId lobbyUdpLink(LinkRegistry linkRegistry, @Value("${vision.mavlink.bind-host:0.0.0.0}") String bindHost, @Value("${vision.discovery.mavlink-port:14550}") int port)` — eagerly binds `bindHost:port` as a `UdpListenLink` at bean-creation time (application boot) and registers it via `linkRegistry.register(link, new LinkDescriptor(CarrierKind.UDP, SerialRole.NONE, "lobby", 50))`. Returns the assigned `LinkId` (always `link.id()`). Throws `UncheckedIOException` synchronously on a bind conflict — surfaces as a bean-creation failure, same as the pre-L1 eager lobby bind always has.
  - `static final String LOBBY_LABEL = "lobby"`, `static final int LOBBY_PRIORITY = 50` — package-private constants mirroring the frozen `LinkDescriptor` literal above (test-visible, not part of the public contract).

## Conventions

- **Dependency inversion via injected bean, not a Maven dependency.** This module never depends on whatever implements `LinkRegistry` (`drone-link/mavlink`'s `MavlinkGateway` in production) — it only depends on the interface, declared in `mavlink-core`. `station/vision-app`'s `CarrierWiring` is the sole place that wires the two together.
- **No parallel config surface.** The bind host/port are read via `@Value` off the exact property keys `vision-app` already owns for this address (`vision.mavlink.bind-host`, `vision.discovery.mavlink-port`) — `@Value` needs only the key string, not a compile dependency on `vision-app`'s `@ConfigurationProperties` type. There is no `vision.carrier.udp.*` namespace.
- **Frozen descriptor, LINK-PAIRING-PLAN.md §3.2.** `LinkDescriptor(CarrierKind.UDP, SerialRole.NONE, "lobby", 50)` is a literal constant, not derived from config — a serial ground radio (`drone-link/carrier-serial`) registers above priority 50, a bench cable always at 0.

## Gotchas

- **Eager bind, same as always.** The lobby UDP socket has been bound at application boot since the zero-config-onboarding wave shipped, independent of this module — this class only relocated *where* that bind happens (out of `MavlinkGateway`, into its own adapter), not *when*.
- **`register` never mints an id.** `LinkRegistry#register` always returns `link.id()` — this bean's return value is mainly so a caller/test can confirm registration happened, not a fresh identity assigned by the registry.
- **Priority 50 must stay below `GROUND_RADIO_PRIORITY` (100) in `drone-link/carrier-serial`'s `SerialCarrierConfiguration`.** The two constants are not derived from each other (each module must not depend on the other, ArchUnit-enforced) — they are independently frozen by LINK-PAIRING-PLAN.md §3.2 and must be changed in lockstep by hand if that section is ever revisited.
- **Test never needs a Spring context.** `UdpCarrierConfigurationTest` calls `lobbyUdpLink(...)` as a plain method against a real loopback-bound `UdpListenLink` and a hand-written recording `LinkRegistry` — no mocks, no `@SpringBootTest`, matching this codebase's adapter-test convention of preferring real loopback IO.

## Status

Real, in production use behind `station/vision-app`'s `CarrierWiring`. Ships the entire LINK-PAIRING-PLAN.md §3.2 UDP carrier surface — no placeholder methods, no follow-up work scoped against this module.
