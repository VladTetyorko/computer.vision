# MAVLINK-CORE-PLAN — a layered, reusable MAVLink component

**Status:** **W0–W4 done** (2026-08-15, branch `feat/mavlink-core-plan`). `libs/mavlink-core` is built and
green at 102 tests; `adapter-mavlink` is rewired onto it at **135/135, 0 skipped** — including the
docker-gated SITL suite running for real against ArduPilot firmware, per §6.1 rule 4. The adapter shrank
19 files/3 513 lines → 16 files/3 109 lines and contains **zero** `MavlinkConnection` construction sites.
W5 (operator-facing message-rate + link-health slice) and W6 (Parameter/Mission) are open.
**Context:** [MAVLINK-CORE-CONTEXT.md](MAVLINK-CORE-CONTEXT.md) — where the knowledge came from, starting state.
**Reads with:** [adapter-mavlink/MODULE.md](../../../adapters/adapter-mavlink/MODULE.md) (as-is surface),
[DOMAIN-SEPARATION-PLAN.md](DOMAIN-SEPARATION-PLAN.md) (§3 urgency classes, D7 asset lease),
[LAYERING-REFACTOR-PLAN.md](LAYERING-REFACTOR-PLAN.md) (§1.3 constructor rules, §5.1 package ceremony).
**Supersedes:** nothing. It *extends* the E2/F2 restructuring that already split the big classes; those waves
made the parts smaller, this one gives them levels.

---

## 1. Verdict on the proposed pattern

The requested shape was:

> `interface → service → byte operations service → sending service → result back to interface`

**Two thirds of this is exactly right, and it is exactly what is missing today.** Separating *what to do*
(service) from *how to encode it* (byte ops) from *how to put it on a wire* (sending) is the precise cut
`MavlinkFlightCommander` fails to make — that one class resolves a mode, builds a `COMMAND_LONG`, constructs
a `MavlinkConnection`, writes to a borrowed socket and awaits an ack, all in one method body. Four of the
five port classes have the same problem. Adopting the proposed levels fixes the real defect.

**One third needs correcting before it is built,** and the correction is what makes the difference between a
library that works and one that fights the protocol:

| Proposed | Reality | Consequence for the design |
|---|---|---|
| A single downward call chain | MAVLink is ~90 % *unsolicited inbound* streaming. Telemetry arrives at 1–10 Hz with nobody asking. | The stack needs a **mirrored inbound path**, not just an outbound one. |
| "result back to interface" | The result does **not** return up the call stack. `COMMAND_ACK` arrives later, on the RX read thread, matched by `(sysid, command id)`. | The join between the two paths is a **correlator** — a pending-request registry that completes a future. This is the one component the proposed chain has no slot for, and it is the load-bearing one. |
| "byte operations service" as our code | CRC_EXTRA seeds, descending-size field reordering, v2 trailing-zero truncation and extension-field rules are ~500 message definitions of generated detail (§2.1). | The byte layer is a **thin swappable seam over the library**, not a re-implementation. We own the *seam*, not the serializer. Re-implementing it is a multi-month tarpit with zero product value. |

So the corrected shape is **two paths joined by a correlator**, five levels deep:

```
                     ┌──────────────────────────────────────────────┐
   L5  PORTS         │  TelemetrySourcePort   FlightCommandPort ... │   vision domain types
                     └───────▲──────────────────────────┬───────────┘
        ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─│─ translation boundary ─ ─ │─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─
                     ┌───────┴──────────────────────────▼───────────┐
   L4  SERVICES      │ Heartbeat  Command  ManualControl  Interval  │   MAVLink microservices
                     └───────▲──────────────────────────┬───────────┘
                             │  onMessage()             │  request()
                     ┌───────┴──────────────────────────▼───────────┐
   L3  SESSION       │ Dispatcher  PeerDirectory  ┌──────────────┐  │   who is out there,
                     │ LinkHealth  TxScheduler    │  Correlator  │  │   what am I waiting for
                     └───────▲────────────────────└──────▲───────┘──┘
                             │  MavlinkFrame             │ completes
                     ┌───────┴──────────────────────────▼───────────┐
   L2  CODEC         │  FrameReader (resync)   FrameWriter (encode) │   bytes ⇄ frames
                     └───────▲──────────────────────────┬───────────┘
                     ┌───────┴──────────────────────────▼───────────┐
   L1  TRANSPORT     │  UdpListenLink  UdpTargetLink  TcpLink  ...  │   bytes ⇄ wire
                     └──────────────────────────────────────────────┘
                              ▲  inbound                 ▼  outbound
```

Read the diagram as the requested chain with the return arrow made honest: a command goes down the right
edge (service → codec → transport), and its acknowledgement comes back up the *left* edge (transport →
codec → session), where the **Correlator** matches it to the waiting caller and completes their future.

---

## 2. Pinned protocol facts

Extracted from mavlink.io by the four research agents. These are the numbers the implementation must not
invent. Where mavlink.io is itself ambiguous it is marked — those are the seams the library must expose as
configuration rather than hardcode.

### 2.1 Wire layer — fixed by spec, one place in the code

| Fact | Value |
|---|---|
| Magic byte | v1 `0xFE`, v2 `0xFD` — the only version discriminator; there is no handshake packet |
| Header size | v1 6 bytes; v2 10 bytes |
| Frame size | v1 8–263 B; v2 12–280 B (280 = 255 B payload + 13 B signature) |
| Payload ceiling | **255 bytes, always** — `len` is one byte in both versions |
| CRC | CRC-16/MCRF4XX over everything except `magic` and the signature block, **with the message's CRC_EXTRA byte fed in last** |
| CRC_EXTRA | one byte per message id, derived at codegen time from name + wire-ordered field types/names; extension fields excluded |
| Field wire order | descending native size (8→4→2→1 B), ties keep XML declaration order; **extension fields exempt** — appended in declaration order |
| v2 truncation | trailing zero payload bytes stripped on send, first byte never stripped; receiver must zero-pad back before parsing |
| `incompat_flags` | unknown bit ⇒ **MUST discard**. Only documented bit: `MAVLINK_IFLAG_SIGNED = 0x01` |
| `compat_flags` | unknown bit ⇒ **MAY ignore** |
| Signature block | 13 B = 1 B `link_id` + 6 B timestamp (10 µs units, epoch 2015-01-01) + 6 B truncated HMAC-SHA-256; 32-byte key; replay state per `(sysid, compid, link_id)`; `RADIO_STATUS` exempt |
| Version mixing | **A single link cannot mix v1 / unsigned-v2 / signed-v2 peers.** Version is per-link state, not per-packet — do not scatter auto-detection through the codec |
| `seq` | 1 byte, wraps at 255. Spec says "used to detect packet loss" and gives **no formula** — drop-rate accounting is ours to define |

> **Contradiction in the source, flagged not guessed.** mavlink.io gives two irreconcilable per-dialect
> message-id range tables (`xml_schema.html`: common 0–149/230–255, vendor 180–229 — vs
> `define_xml_element.html`: common 300–10000, ArduPilotMega 11000–11999). Do not hardcode either. Ranges
> come from the XML being targeted. There is also **no protocol-level tie-break for two dialects claiming
> one id** — that is a build-time error, not runtime logic.

### 2.2 Services — the whole catalogue collapses to two families

The single most useful research finding. ~20 named microservices, but structurally there are **two**:

**Family A — request/response with client-driven timeout + retry.**
Command, Mission, Parameter, Parameter-Ext, FTP, and everything built on `MAV_CMD_REQUEST_MESSAGE`
(Camera discovery, Gimbal discovery, Component Metadata). One configurable state machine
— *send X, expect Y matching key K within T, retry N times, duplicate response is idempotent* — covers
all of them. Per-service configuration only:

| Service | Timeout | Retries | Source |
|---|---|---|---|
| Mission | **1500 ms** (250 ms per plan item) | **5** | spec, the only numerically pinned service |
| FTP | **50 ms** | **6** | spec (recommended GCS values) |
| Command | *implementation-defined* — "a flight-specific number of times" | *impl-defined* | spec explicitly declines |
| Parameter | *implementation-defined* — "GCS dependent" | *impl-defined* | spec explicitly declines |
| Gimbal v2 | *"not specified in protocol document"* | — | spec explicitly declines |

**Family B — unacknowledged streaming broadcast.** Heartbeat, Battery, Manual Control / RC override,
Landing Target, Traffic Management, High Latency. One periodic-emit / latest-value-wins abstraction covers
all of them. No retry, no correlation.

Only **Mission** (server-driven lock-step "re-request the expected seq, drop out-of-order") and **FTP**
(session + opcode multiplexing, dedupe by `seq_number`) need real per-service logic on top of Family A.

Other pinned service facts that change the design:

- **Command protocol**: `COMMAND_INT` is *preferred* for anything positional (integer lat/lon, explicit
  frame); `COMMAND_LONG` only when float precision is needed in params 5–6 or the target supports nothing
  else. We send LONG-only today. `confirmation` increments per resend so the vehicle can distinguish a
  retry from a fresh command — required for at-most-once side effects. Long-running commands reply
  `IN_PROGRESS` with `progress` 0–100, and **each progress update extends the timeout**.
- **Message rates**: `MAV_CMD_SET_MESSAGE_INTERVAL` (param1 = message id, param2 = interval **in
  microseconds**) and `MAV_CMD_REQUEST_MESSAGE`. `REQUEST_DATA_STREAM` deprecated since 2015-08.
- **Mission** has a correctness trap: `MISSION_ITEM_INT` **must** use `MAV_FRAME_GLOBAL_*_INT` variants —
  non-INT global frames get silently rounded into the int32 lat/lon fields and corrupt the position.
  ArduPilot deviates from spec on atomicity (partial upload can leave mixed state) and uses seq 0 = home.
- **Parameter** has a real interop hazard: values are packed into a 4-byte float two incompatible ways
  (byte-wise reinterpretation vs C-style cast). ArduPilot uses the cast, does not set the capability bit,
  and ignores `param_type` on write, inferring type from the name. Any parameter feature must encode
  per-firmware policy, not a single "correct" path.
- **Manual control**: mavlink.io documents **no rate and no failsafe-on-silence** for RC override — those
  live on the flight-stack side. Our 33 Hz default is a local choice and must stay configurable.

### 2.3 Routing and links — what a naive design gets wrong

| Rule | Why it matters here |
|---|---|
| Identity is `(sysid, compid)`, **never** the transport peer address | One link multiplexes several compids (companion computer relaying for itself *and* the autopilot); NAT can change the observed address between packets from one system. Demux-by-socket breaks under both. |
| Routing tables are **learned from observed traffic** | The spec's forwarding rule is "only forward a targeted message to a link a message from that target previously arrived on". Consequence: **you cannot send a targeted message to an address you have not yet heard from** — intermediate routers drop it silently. Our existing "you cannot command what you cannot hear" rule is not a local safety choice; it is the protocol. |
| Framing differs by transport | UDP: one read ≈ one datagram. TCP/serial: arbitrary byte stream needing a resync state machine that carries buffered state **across** reads. A decoder built and tested only on UDP breaks the first time it sees TCP. |
| Broadcast (`target_system = 0`) is a distinct dispatch path | unconditional forward, no learned-route check |
| Link health | convention ~1 Hz heartbeat, disconnect after 4–5 missed — **not a spec number**, must be configurable |
| Backpressure | `RADIO_STATUS.txbuf` is how a serial telemetry radio reports congestion; throttling is application-level and entirely ours to implement |
| Never mutate a relayed frame | re-encoding breaks signed packets — relay raw bytes |
| Reboot detection | ArduPilot convention: `SYSTEM_TIME` going backwards ⇒ reset learned state for that sysid |
| Component type | always read `HEARTBEAT.type` / `autopilot`; **never** infer from the numeric compid bucket |

### 2.4 Library verdict

**Keep `io.dronefleet.mavlink:mavlink:1.1.11`.** It is unmaintained (last release 2023-02-27, maintainer
has stated they lack time; 13 open issues) — a real and growing risk — but nothing we do today is blocked
by its gaps (signing unused, custom dialects would need a fork). `mavlink-kotlin` (Apache 2.0, actively
maintained, genuine bring-your-own-XML Gradle codegen, clean Java interop) is the credible successor if the
risk materialises. MAVSDK-Java (wrong abstraction level — it is a gRPC client to a C++ backend) and
jMAVlib (dormant) are both non-viable.

**The layering work is worth doing regardless of which library sits underneath, and it is what makes a
future swap a one-place change instead of a five-class one.** That is the strongest argument for doing it
now rather than after the library goes stale.

---

## 3. Target shape

### 3.1 Modules

```
libs/mavlink-core/          NEW. Zero project dependencies. Package root com.drones.mavlink.
   └── depends on: io.dronefleet.mavlink only

adapters/adapter-mavlink/   SHRINKS to translation. Package root com.drones.vision.adapter.mavlink.
   └── depends on: libs/mavlink-core + vision-kernel + the contexts' ports
```

`mavlink-core` deliberately does **not** depend on `vision-kernel`. Not even for ids. The moment it knows
what a `DeviceId` is, it stops being reusable in another project — and reuse is the whole point of the
task. It carries its own tiny value types (`SysId`, `CompId`, `PeerId`, `LinkId`, `MavFrame`). The package
root is `com.drones.mavlink`, **not** `com.drones.vision.*`, so the boundary is visible in every import.

New `libs/` aggregator in the root `pom.xml`, placed before `adapters`. **ArchUnit amendment required**
(wave W1): the dependency rule becomes `libs ← kernel ← platform ← contexts ← adapters ← app`, where
`libs` depends on nothing and everything above may depend on it. `libs` must be Spring-free, exactly like
the contexts.

### 3.2 The five levels

| L | Package | Owns | One reason to change |
|---|---|---|---|
| L1 | `com.drones.mavlink.transport` | `MavlinkLink` + `UdpListenLink`, `UdpTargetLink`, `TcpClientLink`, (`SerialLink` deferred) | a new physical link type appears |
| L2 | `com.drones.mavlink.codec` | `FrameReader` (per-source resync), `FrameWriter`, `MavFrame`, dialect selection | the wire format or dialect set changes |
| L3 | `com.drones.mavlink.session` | `MavlinkNode`, `PeerDirectory`, `Dispatcher`, `Correlator`, `LinkHealth`, `TxScheduler` | addressing / routing / link-health policy changes |
| L4 | `com.drones.mavlink.service` | `HeartbeatService`, `CommandService`, `ManualControlService`, `MessageIntervalService` (later: `ParameterService`, `MissionService`, `FtpService`) | a MAVLink microservice is added or its state machine changes |
| L5 | `com.drones.vision.adapter.mavlink` | the five port classes + `VehicleClaimPolicy` + `TelemetryTranslator` | **our domain** changes |

Every level depends only on the one below it, through an interface. No level reaches two levels down.

### 3.3 The seams

Small role interfaces, not one fat one — a service that only sends must not be handed the whole session.

| Seam | Level | Shape | Implemented by |
|---|---|---|---|
| `MavlinkLink` | L1 | `receive(timeout) → ByteChunk(bytes, source)` · `send(byte[], LinkPeer)` · `preservesMessageBoundaries()` | Udp/Tcp/Serial links |
| `FrameSource` | L2 | `poll() → MavFrame` — resync and CRC already handled | `FrameReader` |
| `FrameSink` | L2 | `send(MavMessage, PeerId)` — seq, sysid/compid, framing handled | `FrameWriter` |
| `PeerDirectory` | L3 | `peers()` · `peer(SysId, CompId)` · `lastHeard` · `address` — read-only facts | `DefaultPeerDirectory` |
| `Dispatcher` | L3 | `subscribe(MessageFilter, Consumer<MavFrame>) → Subscription` | `DefaultDispatcher` |
| `Correlator` | L3 | `await(MatchKey, Duration) → CompletableFuture<MavFrame>` · `cancel(MatchKey)` | `DefaultCorrelator` |
| `TxScheduler` | L3 | `repeat(Duration, Supplier<MavMessage>) → Handle` — one thread pool, not one thread per feature | `DefaultTxScheduler` |

`MavlinkLink.preservesMessageBoundaries()` exists so the contract is honest for both datagram and stream
links: the contract is *"a byte stream with buffered resync"*, which a UDP link satisfies trivially and a
TCP link satisfies correctly. Had the interface promised *"one read = one message"*, `TcpClientLink` would
be an LSP violation waiting to happen. `FrameReader` keeps **one resync buffer per source address**, which
also fixes a latent flaw in today's `MavlinkUdpInputStream` (it concatenates datagrams from *all* sources
into one stream — harmless so far, and exactly why source-address demux had to be descoped before).

### 3.4 Where policy lives — the sharpest cut in this plan

Today `VehicleClaimRegistry` decides both *who is on the air* and *which `Device` owns them*. Those are
different jobs with different owners:

| Concern | Question it answers | Home |
|---|---|---|
| `PeerDirectory` (L3, core) | "which `(sysid, compid)` are transmitting, on which link, last heard when, running what firmware?" | **protocol fact** — mavlink-core |
| `VehicleClaimPolicy` (L5, adapter) | "our `Device` X is pinned to sysid 7"; "an unpinned device claims the first free sysid and may re-elect after 30 s of silence" | **project policy** — adapter-mavlink |

Pin/re-election is not in any MAVLink spec. It is a vision-specific answer to a vision-specific question,
and shipping it inside a reusable library would make the library un-reusable. This split is what turns the
eight package-private pass-throughs on `MavlinkTelemetrySource` from a smell into nothing at all — every
port class becomes a peer that depends on `PeerDirectory`/`FrameSink` directly, instead of reaching through
the RX port to a hub it should never have known about.

---

## 4. SOLID / DRY / KISS, applied to the actual offenders

Not as principles-in-general — as the specific defects each one removes.

### SOLID

| | Applied |
|---|---|
| **S** | Five levels, five reasons to change (§3.2). Concretely: `MavlinkFlightCommander` today changes if the domain changes, *or* the command protocol changes, *or* the wire library changes, *or* the socket model changes. After: only if the domain changes. |
| **O** | Adding `ParameterService` touches L4 only — zero edits to session, codec or transport. Adding `SerialLink` touches L1 only. This is the test of whether the layering is real. |
| **L** | `MavlinkLink`'s contract is *byte stream with resync*, so a datagram link and a stream link are genuinely substitutable (§3.3). |
| **I** | `CommandService` gets `FrameSink` + `Correlator` + `PeerDirectory` — three small interfaces — not a `MavlinkSession` god object. `ManualControlService` gets `FrameSink` + `TxScheduler` and cannot even *express* an ack wait. |
| **D** | L4 depends on `FrameSink`, never on `io.dronefleet.mavlink.MavlinkConnection`. Today five classes construct that type by hand; after, exactly one class in L2 does. |

### DRY — the three offenders the audit counted

| Duplication | Copies today | Becomes |
|---|---|---|
| "fresh `MavlinkConnection` per send over the shared socket" | 2 near line-for-line (`MavlinkFlightCommander.send`, `MavlinkManualControlSender.sendOneFrame`) | one `FrameWriter` |
| thread lifecycle: CAS guard → close socket → interrupt → bounded 5 s join, plus a verbatim-duplicated `closeQuietly` | 3 (`MavlinkSocketHub`, `FeedRuntime`, `RcLinkRuntime`) | one `TxScheduler` / `RunLoop` |
| lenient numeric option parsing, incl. independently redeclared `MIN_SYSID`/`MAX_SYSID` = 1/255 | 3–4 across two classes | one `Options` value type |

Plus two the audit found in passing: `MAV_TYPE_*` constants exist in **two** hand-synced copies
(`FlightModes` and `MavlinkHeartbeatScanner`), and `VehicleClaimRegistry` parses `Heartbeat` fields
directly, duplicating `MavlinkTelemetryDecoder`'s job.

### KISS — the explicit non-goals

The layering above is only cheap if it is bounded. **We deliberately do not build:**

1. **Our own serializer.** No CRC_EXTRA tables, no field reordering, no truncation logic. The library does
   this correctly for ~500 messages. We own the seam, not the bytes.
2. **Message signing.** Nothing in the fleet uses it; the design leaves room (`incompat_flags` is already
   surfaced on `MavFrame`) and nothing more, until there is a requirement.
3. **A general MAVLink router.** mavlink-router exists and is better at it. We build a `PeerDirectory` —
   the 20 % that our five port classes actually need.
4. **Mission / FTP / Parameter services in the first pass.** They are gated on the seam proving itself (W6).
5. **A network microservice.** See §5.
6. **A DI framework inside `mavlink-core`.** Plain constructors, exactly like every adapter today.
7. **Own message DTOs.** Deferred, deliberately — see §7 (D3).

---

## 5. "Microservice" — the word is doing two jobs

MAVLink's own spec calls its protocols *microservices* (mission, parameter, command, FTP…). Those are §2.2
and they are L4 classes. The other sense — a separately deployed network service — needs a decision, and
the decision is **no, not by default**:

- `DOMAIN-SEPARATION-PLAN.md` **D7** already pins a whole asset — video pipeline, MAVLink socket, RC relay,
  OSD telemetry supplier — to one worker, precisely so a network hop never lands inside a control loop.
- **U0** budgets the command/ack and RC frame paths at ≤ 50 ms, *never queued, never persisted in flight*.
  A broker or an extra RPC hop in front of RC override directly violates it.
- A MAVLink link is stateful per vehicle (seq counters, learned routes, correlator waiters, claim state).
  Splitting that across a network boundary means replicating the state or pinning the session anyway —
  which is what D7 already does, in-process, for free.

**Therefore:** ship it as an **embeddable library** (`libs/mavlink-core`) plus the existing thin adapter.
The broker sits *in front of* that library as a driving adapter (§5.1), never *inside* the control path.
Deployability is a runtime decision, per D1 — the scaling answer is `vision.roles` gaining a
`mavlink-gateway` role that activates the telemetry/command modules on a given node, **not** a ninth
container. `docker-compose.yml` gains a role-flagged service, not a new image. Another project consumes
`mavlink-core` as a jar with one third-party dependency, which is the reuse the task asked for.

### 5.1 The broker seam — commands in, telemetry out, over Kafka

Commands and telemetry will later travel over a message broker. That is a **driving adapter in front of
L4**, and it works only if the boundary is made of *values* rather than live objects. Getting this shape
right costs nothing now and is expensive to retrofit, so it is pinned here even though no broker code is in
scope.

> **Resolved 2026-08-15: the broker is NATS JetStream** — operator decision, consistent with
> `DOMAIN-SEPARATION-PLAN.md` D3 and `FLEET-MIGRATION-PLAN.md` MD1. An earlier draft of this section
> specified Kafka; that is withdrawn. Nothing below changed as a result, which was the point of making the
> seam broker-agnostic: both brokers impose the same five constraints. `mavlink-core` still depends on
> neither, and the infrastructure + `adapters/adapter-nats/**` are owned by FLEET-MIGRATION **T2.a**, not
> by this plan.

A new package `com.drones.mavlink.api` (L4½) holds the boundary records. It is the **only** package a
broker adapter imports.

| # | Rule | Why |
|---|---|---|
| B1 | **Requests are self-describing records** — `CommandRequest(vehicleKey, kind, params, correlationId, deadline)`. No `Device`, no live references, no vision types; serializable to JSON/Avro as-is. | A Kafka consumer, a REST controller and an in-process caller submit the *identical* record. One code path, three drivers. |
| B2 | **Correlation is caller-supplied, and the future is a delivery mode — not the contract.** `CommandGateway.submit(CommandRequest) → CompletionStage<CommandOutcome>` in-process; the same `CommandOutcome` record published to a reply topic when broker-driven. | With a broker the reply may return on another topic, another partition, even another node. A `CompletableFuture` keyed by an internal object identity cannot survive that; a `correlationId` the caller chose can. |
| B3 | **Idempotency is mandatory, at two levels.** A dedupe window keyed by `correlationId` in `CommandService`, **and** MAVLink's own `confirmation` field (§2.2). | Kafka is at-least-once. Redelivery must not arm an aircraft twice. `confirmation` is how the *vehicle* tells a retry from a fresh command — the protocol already solved half of this. |
| B4 | **Ordering is per vehicle.** The broker partition key is the vehicle/asset key. | Commands to one aircraft must stay ordered; commands to different aircraft are independent and must not share a queue head. |
| B5 | **Telemetry egress must never backpressure the RX loop.** Egress is an ordinary `Dispatcher` subscription whose contract is *handlers run on the RX thread and must not block*; consumers that need to buffer wrap themselves in the provided bounded drop-oldest subscriber. | U1 semantics, and project rule 9. A slow producer that blocks would stall the MAVLink read thread and stale *every* consumer — a broker hiccup must degrade the broker path only. No separate sink type is needed: a Kafka producer subscribes exactly like the SSE plane does. |

Consequence for the layering: `CommandService` (L4) is driven through `CommandGateway`, not called
directly by port classes. `MavlinkFlightCommander` becomes one caller of that gateway; a future
`adapter-kafka-mavlink` becomes another, and is roughly *consume → deserialize → `submit()` → publish
outcome* — no MAVLink knowledge at all. That is the test of whether this seam is real.

Explicitly **not** in scope now: any broker dependency, any topic naming, any serializer. `mavlink-core`
must not gain a Kafka dependency — records only.

---

## 6. Waves

Strangler pattern. `mavlink-core` grows underneath with its own tests; `adapter-mavlink` is not touched
until W4, and when it is, **the 135 existing tests are the acceptance criterion** — they must pass
unmodified. Public constructors of the five port classes are frozen throughout (§8).

Sub-branches off `feat/mavlink-core`, merged back per wave.

| Wave | Agent | Scope (disjoint) | Exit criteria |
|---|---|---|---|
| **W0** | Opus | This plan + `libs/mavlink-core/API.md` (the frozen seam contract, no code) | seams in §3.3 pinned; reviewed |
| **W1** | Sonnet | `libs/mavlink-core/**` L1+L2 only · root `pom.xml` (`libs` aggregator) · `vision-app` ArchUnit rule | `-pl libs/mavlink-core test` green. Golden-byte codec tests + a loopback UDP test + **a TCP-framing test that fails against a datagram-shaped decoder** (the LSP guard) |
| **W2** | Sonnet | `libs/mavlink-core/**` L3 only | green + a multi-peer loopback test: two sysids on one port, correct `PeerDirectory` entries, correlator matches an ack, drop-rate computed across a seq wrap |
| **W3** | Sonnet | `libs/mavlink-core/**` L4 + `com.drones.mavlink.api` | green + `HeartbeatService`/`CommandService`/`ManualControlService`/`MessageIntervalService` tested against a `FakeVehicle` double (port the one from `MavlinkFlightCommanderTest`, incl. its continuous-heartbeat fix). **`CommandGateway` + the §5.1 records land here**, incl. a duplicate-`correlationId` dedupe test |
| **W4** | **Opus** | `adapters/adapter-mavlink/**` only | see §6.1 — the criterion below, not "135 unmodified". `MavlinkSocketHub`, `CommandAckRegistry`, `MavlinkUdpInputStream`, `MavlinkUdpOutputStream` deleted; `VehicleClaimRegistry` reduced to `VehicleClaimPolicy`; the transport/correlation pass-throughs gone; MODULE.md rewritten |
| **W5** | Sonnet | new feature slice: `MessageIntervalService` + `LinkHealth` surfaced to operators (context service → api → web) | operator can set telemetry rates per asset and see link quality; needs its own sub-branch per §9 |
| **W6** | gated | `ParameterService`, `MissionService` (+ `SerialLink`, `TcpClientLink` hardening) | **only after W4 proves the seam.** Mission ⇒ flight plans from the tactical map straight to the aircraft |

**W4 is the risky wave and is deliberately Opus, not Sonnet.** It is the only one that can break working
flight hardware paths, and it is the only one where "the tests still pass" is the entire safety argument.

### 6.1 W4's acceptance criterion, corrected

The original wording — *"all 135 existing tests pass unmodified"* — turned out to be unachievable as
literally stated, and pretending otherwise would have quietly weakened the safety argument. Measured
against the real code: four test doubles construct `MavlinkUdpInputStream`/`MavlinkUdpOutputStream`
directly, and two reference `MavlinkSocketHub`'s nested `UnclaimedVehicle`/`CommandTarget` records. Those
tests cannot survive the deletion of the classes they instantiate, no matter how correct the refactor is.

The criterion that carries the same safety weight and is honest:

1. **No assertion is weakened, deleted, or retargeted.** Every existing test keeps asserting exactly what
   it asserts today — same expected values, same wire-level checks.
2. **Test count does not fall below 135.**
3. **Only test *scaffolding* may change**, and every changed file is enumerated in MODULE.md with the
   reason. Scaffolding means: how a fake vehicle opens a socket, how a test names a bind address. It does
   not mean what the test proves.
4. **The docker-gated SITL suite must run un-skipped and pass** (`MavlinkSitlSmokeIntegrationTest`,
   `MavlinkSitlReturnHomeIntegrationTest`). Confirmed available on the dev machine — 3/3 in ~32 s against
   real ArduPilot firmware. A skipped SITL run is not evidence of anything.
5. **The five port classes' public constructors are unchanged** (D8), so `vision-app` wiring is untouched.

Correspondingly, "the 8 pass-throughs gone" is refined: the three that leaked **transport and correlation
internals** — `socket`, `awaitAck`, `cancelAckWait` — are deleted outright, since core's `FrameSink` and
`Correlator` replace them and they were the actual architectural defect (they let TX classes reach through
the RX port into a socket). The remaining lookups (`bindKey`, `bindKeyFor`, `hasActiveHub`,
`unclaimedVehicles`, `claimedVehicles`, `commandTarget`) survive as thin delegations to a real
`MavlinkGateway` collaborator rather than to a socket hub — they are ordinary queries, not a leak.

Parallelism: W1→W2→W3 are strictly sequential (each builds on the level below). W4 needs all three. W5/W6
are independent of each other.

---

## 7. Decisions (pinned)

| # | Decision | Rationale |
|---|---|---|
| D1 | **Library, not network service** | §5 — D7 + U0 forbid a hop in the control loop |
| D2 | **`mavlink-core` has zero project dependencies**, package root `com.drones.mavlink` | knowing `DeviceId` would end its reusability, which is the task |
| D3 | **Keep the library's message types flowing through L2–L4 for now; own DTOs deferred to W6+** | full isolation means modelling ~20 messages by hand and re-proving 31 golden-byte tests. Cost now, value only on a library swap. The *transport and connection* machinery is isolated immediately — that is where 5 duplicated call sites live |
| D4 | **Keep `io.dronefleet.mavlink`** | §2.4 — unmaintained but not blocking; the layering is what makes the swap cheap later |
| D5 | **Pin/claim/re-election stays in the adapter as `VehicleClaimPolicy`** | §3.4 — it is project policy, not protocol |
| D6 | **Every spec-ambiguous number is configuration, never a literal** | §2.2: command/parameter timeouts are "implementation-defined" *by the spec itself*. Extends the existing `MavlinkSettings` record. Per CLAUDE.md rule 1, runtime-variable values (per-asset message intervals) go to the database + cache layer, not properties |
| D7 | **`COMMAND_INT` support added alongside `COMMAND_LONG`** | spec prefers INT for anything positional; we are LONG-only today, which blocks every positional command |
| D8 | **Public constructors of the five port classes are frozen** | they are the strangler's contract; `vision-app` wiring must not change during W1–W4 |
| D9 | **The L4 boundary is value-typed records + caller-supplied correlation ids** (`com.drones.mavlink.api`) | §5.1 — makes a Kafka/NATS driving adapter a ~200-line translation instead of a redesign. Costs nothing now; expensive to retrofit |

---

## 8. Frozen contract

Not to change without amending this plan:

- **Port class constructors** — `MavlinkTelemetrySource(MavlinkSettings)`, `MavlinkFeedTransmitter(MavlinkSettings)`,
  `MavlinkHeartbeatScanner(MavlinkTelemetrySource, int, MavlinkSettings.Scan)`,
  `MavlinkFlightCommander(MavlinkTelemetrySource, Duration)`,
  `MavlinkManualControlSender(MavlinkTelemetrySource, MavlinkSettings.Rc)`, and their pre-existing overloads.
- **`Telemetry` / `FlightState` / `FlightCapability` field mapping** — the table in adapter-mavlink/MODULE.md
  is byte-for-byte unchanged by W1–W4. Any new key is a W5+ addition, additive only.
- **The seven seams in §3.3** — signatures pinned in W0's `API.md`.
- **The 135 existing tests** — none deleted, weakened, or rewritten to assert something new before W4 lands.

---

## 9. Risks and open questions

| Risk | Mitigation |
|---|---|
| W4 breaks a working flight path in a way tests do not catch | the SITL suite (`MavlinkSitlSmokeIntegrationTest`, `MavlinkSitlReturnHomeIntegrationTest`) must be run **un-skipped** as a W4 exit gate — build the `vision-sitl:4.7.0` image first. Tests that skip are not a safety net |
| Refactor consumes a cycle with no user-visible output | W5 is scheduled deliberately as the first user-visible payoff (telemetry rate control + link quality) and should not be deferred |
| `libs/` aggregator trips ArchUnit in an unforeseen way | W1 lands the rule amendment **with** the module, not after |
| Timing-sensitive tests flake under `-Dtest=` filters | already a documented module gotcha — every wave verifies via a full `-pl` module run, three consecutive times, never a filtered one |
| `mavlink-kotlin` migration becomes urgent mid-plan | D4 accepts the risk; the seam is exactly what makes it a one-module change. Revisit only if the library blocks a wave |

**Open questions for the operator (not blocking W0–W3):**

1. **Serial transport** — is a directly USB-attached flight controller in scope? It is the one transport
   that changes L1's threading model (no socket to close to unblock a read). Currently deferred.
2. **Mission upload priority** — W6 or sooner? It is the largest product win in this plan (tactical map →
   aircraft) and the reason to care about the seam at all.
3. **Signing** — any customer or regulatory requirement on the horizon? If yes it moves from non-goal to W6.
