# mavlink-core — frozen seam contract (W0)

**Plan:** [docs/plans/active/MAVLINK-CORE-PLAN.md](../../docs/plans/active/MAVLINK-CORE-PLAN.md).
**Status:** contract frozen for waves W1–W3. Changing anything here needs a plan amendment.

A reusable, framework-free MAVLink component. **Zero project dependencies** — it does not know what a
`Device`, a `DeviceId` or a `Telemetry` is, and must never learn. One third-party dependency:
`io.dronefleet.mavlink:mavlink`. Package root `com.drones.mavlink`, deliberately *not* `com.drones.vision.*`,
so the boundary is visible in every import.

## Levels

| Level | Package | Built in | Depends on |
|---|---|---|---|
| L0 kernel | `com.drones.mavlink` (root) | W1 | nothing — `SysId`, `CompId`, `PeerId` only |
| L1 transport | `com.drones.mavlink.transport` | W1 | L0 |
| L2 codec | `com.drones.mavlink.codec` | W1 | L0, L1 |
| L3 session | `com.drones.mavlink.session` | W2 | L0, L1, L2 |
| L4 service | `com.drones.mavlink.service` | W3 | L0, L3 |
| L4½ api | `com.drones.mavlink.api` | W3 | L0 only — importable by a broker adapter alone |
| — config | `com.drones.mavlink.config` | W1 | nothing |

Above L0, no level may reach two levels down. L0 and `config` are universal and may be depended on by
anything — they are this module's own kernel, mirroring how `vision-kernel` sits under every context.
Enforced by ArchUnit in W1.

**L0 — identity value types.** `SysId(int value)` and `CompId(int value)`, both validated 1..255 in their
compact constructors, and `PeerId(SysId system, CompId component)`. They live in the root package rather
than under `session` because L2's `MavHeader` and `FrameSink` need them: a message's origin is a wire-level
fact, not a session-level one.

---

## L1 — transport

Bytes in, bytes out. Knows nothing about frames.

| Type | Kind | Signature / fields |
|---|---|---|
| `LinkId` | record | `String value` — e.g. `"udp-listen:0.0.0.0:14550"`. Stable across the link's life. |
| `LinkPeer` | record | `String host, int port`. `LinkPeer.NONE` for links with no addressable peer (serial). |
| `ByteChunk` | record | `byte[] data, int length, LinkPeer source, Instant receivedAt` |
| `MavlinkLink` | interface | `LinkId id()` · `boolean preservesMessageBoundaries()` · `ByteChunk poll(Duration timeout)` · `void send(byte[] frame, int off, int len, LinkPeer target)` · `LinkPeer defaultTarget()` · `void close()` |

**`poll` returns `null` on timeout** — named for `BlockingQueue.poll`'s established contract, so the return
value carries its own documentation. It throws `IOException` only on genuine socket failure; a closed link
returns `null` rather than throwing.

**`preservesMessageBoundaries()` is the LSP hinge.** The interface contract is *"a byte stream with
buffered resync"*, which a datagram link satisfies trivially and a stream link satisfies correctly. It must
**never** be read as "one poll = one message" — that promise would make `TcpClientLink` an unimplementable
subtype. The flag exists only so `FrameReader` can decide how many resync buffers to keep, never so a
caller can skip resync.

W1 implementations: `UdpListenLink` (bind, learn peers from inbound), `UdpTargetLink` (fixed destination,
for the TX simulator), `TcpClientLink`. `SerialLink` deferred (§9 open question 1).

**Send is thread-safe on every implementation.** Multiple services share one link concurrently
(`DatagramSocket#send` already is; TCP needs a write lock).

---

## L2 — codec

Frames in, frames out. Knows nothing about who is out there.

| Type | Kind | Signature / fields |
|---|---|---|
| `MavHeader` | record | `int version, int sequence, SysId system, CompId component, int messageId, int incompatFlags, int compatFlags, boolean signed` |
| `MavFrame` | record | `MavHeader header, Object payload, LinkId link, LinkPeer source, Instant receivedAt` + `boolean is(Class<?>)` · `<T> T as(Class<T>)` |
| `FrameReader` | class | `void offer(ByteChunk chunk, Consumer<MavFrame> out)` |
| `FrameSink` | interface | `void send(Object payload, PeerId target)` · `void broadcast(Object payload, LinkId link)` |
| `FrameWriter` | class | the one `FrameSink` implementation; the **only** class in the module allowed to construct a `MavlinkConnection` |

**`Object payload` is a known wart**, accepted under plan D3 (library message types flow through L2–L4).
**Settled in W1 — do not re-investigate.** Every generated message class in `io.dronefleet.mavlink` is a
plain `public final class` with no common interface or superclass; the only shared marker is the
class-level `@MavlinkMessageInfo` annotation, which cannot occupy a type position. `Object` stays.
`FrameWriter` uses that annotation defensively instead: a payload lacking it is rejected with a clear
`IllegalArgumentException` rather than an obscure NPE deep in `MavlinkConnection.send2`.

`MavHeader.version` is the **semantic protocol number (1 or 2)**, not the raw magic byte — decided in W1.

**Routing lives in L3, not here (resolved W1→W2).** `FrameWriter` is **link-scoped**: it owns one
persistent `MavlinkConnection` per link, which is what makes per-link sequence numbering work at all (a
fresh connection per send resets `seq` to 0 every time). It cannot answer "which link is this `PeerId`
reachable on" — that needs `PeerDirectory`. W1 shipped the honest subset (single registered link routes to
its `defaultTarget()`; more than one throws `IllegalStateException` naming the ambiguity rather than
silently fanning a targeted command out to every link). **W2 replaces it with `RoutingFrameSink`** (L3),
which resolves the peer's link through `PeerDirectory` and delegates to that link's `FrameWriter`.
`FrameWriter` keeps its narrow link-scoped contract and stops being a `FrameSink` in its own right.

**`FrameReader` keeps one resync buffer per source.** For a boundary-preserving link that means one buffer
per `LinkPeer`; for a stream link, exactly one. This is not an optimisation — concatenating datagrams from
different vehicles into one byte stream is the latent flaw in today's `MavlinkUdpInputStream`, and it is
what forced source-address demux to be descoped before. Buffers are bounded and evicted LRU
(`maxResyncBuffers`, default 64) so a spoofing or scanning source cannot grow memory without bound.

`FrameWriter` owns sequence-number allocation, **per `(link, our sysid, our compid)`**, wrapping at 255.

---

## L3 — session

Who is out there, what am I waiting for, what runs periodically.

(`SysId`, `CompId`, `PeerId` are L0 — see the level table above.)

| Type | Kind | Signature / fields |
|---|---|---|
| `HeartbeatInfo` | record | `int autopilot, int mavType, int baseMode, long customMode, int systemStatus` — `null` until the first `HEARTBEAT` |
| `Peer` | record | `PeerId id, LinkId link, LinkPeer address, Instant firstHeard, Instant lastHeard, HeartbeatInfo heartbeat` |
| `PeerDirectory` | interface | `Collection<Peer> peers()` · `Peer peer(PeerId)` · `List<Peer> peersOnLink(LinkId)` — read-only facts |
| `MessageFilter` | interface | `boolean test(MavFrame)`; statics `any()`, `messageId(int)`, `type(Class<?>)`, `fromSystem(SysId)`, `and(...)` |
| `Dispatcher` | interface | `Subscription subscribe(MessageFilter, Consumer<MavFrame>)` |
| `Subscription` | interface | `void close()` — idempotent |
| `MatchKey` | record | `SysId system, int messageId, long discriminator` |
| `Correlator` | interface | `CompletableFuture<MavFrame> await(MatchKey, Duration)` · `void cancel(MatchKey)` |
| `TxScheduler` | interface | `Handle repeat(String name, Duration period, Runnable)` · `Handle` has `void close()` |
| `LinkHealth` | interface | `Health of(PeerId)`; `record Health(boolean connected, Instant lastHeard, long received, long lost, double dropRate)` |
| `RoutingFrameSink` | class | the `FrameSink` implementation that resolves a `PeerId`'s link via `PeerDirectory` and delegates to that link's `FrameWriter` (see L2's routing note) |
| `MavlinkNode` | record | `SysId system, CompId component`; `static groundStation()` → **255 / 190** |
| `MavlinkSession` | class | `MavlinkSession(MavlinkNode, MavlinkCoreSettings)` · `void addLink(MavlinkLink)` · `void removeLink(LinkId)` · `PeerDirectory peers()` · `Dispatcher dispatcher()` · `Correlator correlator()` · `FrameSink sink()` · `LinkHealth health()` · `void close()` |

`MavlinkSession` is the composition root: it owns one reader thread per link, drives `FrameReader`, updates
`PeerDirectory` and `LinkHealth` from every frame, offers each frame to `Correlator` and then to
`Dispatcher`, in that order.

### Contracts that must be honoured exactly

- **`Dispatcher` handlers run on the RX thread and MUST NOT block.** The dispatcher does not queue and does
  not retry. A consumer that needs to buffer wraps itself in `BoundedSubscriber` (provided, drop-oldest,
  bounded capacity, counts drops). This is the whole of plan §5.1 B5 — a Kafka producer subscribes exactly
  like the SSE plane does, and a broker stall degrades the broker path only, never the RX loop.
- **`Correlator.await` must be registered before the request is sent**, and always released in a `finally`
  via `cancel`. A waiter registered and never removed both leaks and permanently shadows future replies for
  the same key — this is a documented sharp edge of today's `CommandAckRegistry` and must not survive.
- **`MatchKey.discriminator`** is the command id for `COMMAND_ACK`, the item seq for mission transfers, the
  session/seq for FTP. `COMMAND_ACK` is matched on `(origin sysid, command id)` only — `targetSystem`/
  `targetComponent` are wire extension fields and are **not** reliably populated; never match on them.
- **`PeerDirectory` records protocol facts only.** Pin/claim/re-election is project policy and lives in
  `adapter-mavlink` (`VehicleClaimPolicy`), not here.
- **Peer identity is `(sysid, compid)`, never the transport address.** One link multiplexes several compids
  and NAT can move an address between packets from one system.
- **`LinkHealth` drop rate** is expected-vs-received `seq` accounting per `(PeerId, LinkId)`, handling the
  8-bit wrap. The MAVLink spec defines no formula (plan §2.1); ours is documented, not standard.

---

## L4 — services

One class per MAVLink microservice. Adding one must touch nothing below.

| Service | Responsibility | Family (plan §2.2) |
|---|---|---|
| `HeartbeatService` | emit our `HEARTBEAT` at a fixed rate; mark peers connected/disconnected | B — streaming |
| `CommandService` | `COMMAND_LONG` **and** `COMMAND_INT`, `COMMAND_ACK` await, retry with `confirmation` increment, `IN_PROGRESS` extends the deadline, `correlationId` dedupe | A — request/response |
| `ManualControlService` | fixed-rate `RC_CHANNELS_OVERRIDE` relay, latest-wins mailbox, release burst | B — streaming |
| `MessageIntervalService` | `MAV_CMD_SET_MESSAGE_INTERVAL` (µs) / `MAV_CMD_REQUEST_MESSAGE`, over `CommandService` | A, via Command |

Services depend on **small role interfaces only** — `FrameSink`, `Correlator`, `PeerDirectory`,
`TxScheduler` — never on `MavlinkSession` as a whole, and never on `MavlinkConnection`. `ManualControlService`
must not even be able to *express* an ack wait: it gets `FrameSink` + `TxScheduler` and nothing else.

Family A's shared machine — *send X, expect Y matching key K within T, retry N times, duplicate response is
idempotent* — is one class, `RequestResponse`, parameterised per service. It is what W6's `MissionService`
(1500 ms / 250 ms / 5) and `FtpService` (50 ms / 6) will reuse.

---

## L4½ — api (the broker seam)

The only package a Kafka/NATS driving adapter imports. Records and two interfaces. **No broker dependency
ever enters this module.**

| Type | Kind | Signature / fields |
|---|---|---|
| `CommandRequest` | record | `String vehicleKey, String kind, List<Double> params, String correlationId, Duration deadline, boolean force` |
| `CommandOutcome` | record | `String correlationId, Status status, int resultCode, String detail, Instant at` |
| `CommandOutcome.Status` | enum | `ACCEPTED, IN_PROGRESS, DENIED, NO_ACK, UNSUPPORTED, DUPLICATE, UNREACHABLE` |
| `VehicleKeyResolver` | interface | `PeerId resolve(String vehicleKey)` — returns `null` if unknown |
| `CommandGateway` | interface | `CompletionStage<CommandOutcome> submit(CommandRequest)` |

- `kind` names a high-level command (`"ARM"`, `"DISARM"`, `"SET_MODE"`, `"RTL"`, `"SET_MESSAGE_INTERVAL"`)
  so an ordinary caller never touches raw params; `params` is the positional param1..param7 escape hatch,
  size ≤ 7, for anything not yet named.
- `vehicleKey` is an **opaque string**. Core cannot know what it means — the host application supplies a
  `VehicleKeyResolver` mapping it to a `PeerId`. This is what keeps the module domain-free *and* routable.
- `correlationId` is **caller-supplied**. The returned `CompletionStage` is one delivery mode; publishing
  the same `CommandOutcome` record to a reply topic is another. Never key correlation on object identity.
- `submit` is **idempotent within the dedupe window** (`commandDedupeWindow`, default 60 s): a repeat of a
  live `correlationId` returns the original outcome, and a repeat of a completed one returns `DUPLICATE`.
  Kafka is at-least-once; redelivery must not arm an aircraft twice.
- `submit` never throws for an unreachable or unknown vehicle — it completes with `UNREACHABLE`. Only a
  malformed request throws.

---

## Configuration

`MavlinkCoreSettings` — one framework-free record, compact-constructor validated, `static defaults()`,
`with*` copy methods. Every value here is configuration because the **MAVLink spec itself declines to
specify it** (plan §2.2), not because we were being cautious.

| Field | Default | Source of the default |
|---|---|---|
| `heartbeatPeriod` | 1 s | convention; spec mandates no rate |
| `peerTimeout` | 5 s | convention "4–5 missed heartbeats" |
| `commandTimeout` | 2 s | today's `MavlinkFlightCommander.ACK_TIMEOUT_MILLIS`, preserved byte-identically |
| `commandRetries` | 2 | spec: "a flight-specific number of times" — implementation-defined |
| `commandDedupeWindow` | 60 s | ours; §5.1 B3 |
| `rc.overrideHz` / `min` / `max` | 33 / 10 / 50 | today's `MavlinkSettings.Rc`, preserved |
| `rc.releaseFrames` | 3 | today's `MavlinkSettings.Rc`, preserved |
| `closeJoinTimeout` | 5 s | today's shared literal across three classes |
| `maxResyncBuffers` | 64 | ours; L2 eviction bound |
| `dispatchQueueCapacity` | 256 | ours; `BoundedSubscriber` default |
| `mission.timeout` / `itemTimeout` / `retries` | 1500 ms / 250 ms / 5 | **spec numbers** (W6) |
| `ftp.timeout` / `retries` | 50 ms / 6 | **spec numbers** (W6) |

No `System.getenv`, no `System.getProperty`, no Spring anywhere in this module. Callers construct the
record; `vision-app` binds it from `application.yaml` exactly as it already does for `MavlinkSettings`.

---

## Non-goals — do not build these

Our own serializer (no CRC_EXTRA tables, no field reordering, no truncation logic — the library does it for
~500 messages). Message signing. A general MAVLink router. A DI framework. Own message DTOs (deferred,
plan D3). Any broker client. Mission/Parameter/FTP services before W6.
