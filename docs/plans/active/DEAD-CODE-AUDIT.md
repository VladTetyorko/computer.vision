# DEAD-CODE-AUDIT — wave K4, findings and dispositions

**Status:** audit executed 2026-08-19 against `master` @ `554fc105`. Branch `chore/dead-code-audit`.
**Row:** `docs/main/MASTER-MATRIX.md` §9 **K4** — *"Dead-type / dead-port audit. K2 was found by
grepping one type's usages. Do it for every domain type and port — anything referenced only by its
own test is either a gap like K2 or removable."* S ~16 h. One of the two rows in the K1–K4 feature
freeze that was still open.

The census itself is mechanical and lives in the session scratchpad (`k4-census.md`). **This
document is the part that matters: what was found, and what each finding *is*.** The census was
run read-only and deliberately forbidden from judging, because the entire lesson of K2 is that a
type nothing constructs may be a 180-hour **gap** rather than garbage. Deleting a gap destroys the
only remaining record of a design intent.

---

## 1. The headline: K2 does not repeat

**338 public types across `core/` and the eight contexts. 337 LIVE. 0 TEST-ONLY. 1 ORPHAN.**
**50 ports: 49 have at least one implementation. 1 has none.**

`TrackedObject` — a fully-specified domain type that production code never constructed, hiding a
180-hour feature — **has no sibling.** That is the question K4 existed to answer, and the answer is
no. The remaining findings are all one level down: dead *enum constants*, dead *columns*, and one
dead *port*.

Method note, because a clean result deserves scepticism: the census disambiguated 23 constant names
shared across two or more enums by matching qualified references only. Spot-checked independently —
every `MANUAL` hit in the tree is `CameraPoseSource.MANUAL` or `MarkSource.MANUAL`, never
`RemedyKind.MANUAL`. A naive bare-word grep would have reported `RemedyKind.MANUAL` as live. It is
not.

## 2. The one genuine fossil — `RecordingPort`

`contexts/vision-perception/.../domain/port/RecordingPort.java` — a documented three-method driven
port (`streamStarted`, `publish(StreamId, VideoFrame)`, `streamEnded`), **zero references anywhere
in the repo**: not implemented, not injected, not named outside its own file. Verified independently.

**Disposition: REMOVE.** This is not a gap, and that distinction is the whole audit. Recording
*shipped* — `MASTER-MATRIX` §2 lists "Recording + clip export + replay with deep links" as HAVE
(OPS-CORE R). It shipped by a **different route than this port anticipated**: mediamtx records
natively and `StreamPublisherPort#playbackUrl` resolves the result, so the platform never handles a
frame for recording purposes at all. MEDIA-SOT settled that direction — *mediamtx is the video
source of truth*.

`RecordingPort` is therefore a fossil of the pre-MEDIA-SOT design, in which Java would have pushed
decoded frames into a recorder. Its removal records a decision rather than losing one. `ARCHITECTURE.md`
still lists `adapter-recording` as a planned item; that line goes with it.

**Related, same cause:** `PixelFormat.H264_PACKET` is consumed in `DefaultProbeService.codecFor`'s
switch but **never produced** by any capture adapter — the mirror image. Under MEDIA-SOT pull mode
cv-service opens the RTSP stream itself, so compressed packets never cross into Java. Kept: it is a
legitimate branch of a format enum and costs nothing.

## 3. Not garbage — gaps, and each one has a matrix row

`RemedyKind` (`contexts/vision-flight`) has four constants. The only production line that ever
constructs one — `DefaultReadinessService:164` — assigns `MESSAGE_INTERVAL` or `null`, never the
other three. `RemediationOrchestrator`'s own javadoc concedes it: *"Only MESSAGE_INTERVAL … is ever
actually dispatched here."*

**Disposition: KEEP ALL FOUR.** Each dead branch is a named, deliberate gate — deleting them would
erase the markers:

| Constant | What it actually is |
|---|---|
| `MESSAGE_INTERVAL` | live — I3, shipped as onboarding wave O8 |
| `PARAM_WRITE` | **I4, operator-gated (O9).** Tier-A param write is unbuilt *by decision*, not by neglect |
| `CLI_SCRIPT` | **I6** — BF/INAV CLI diff generation, an unbuilt matrix row |
| `MANUAL` | a real modelling distinction nothing yet produces: `remedy: null` means *no remedy applies*, `MANUAL` means *a remedy is needed and we cannot automate it*. Readiness should be able to say the second. It never does |

**But it does reach the wire, and that is worth recording.** `ReadinessReportResponse` maps
`readiness.remedy().name()` onto the API, and `station/vision-web/src/app/core/api/models.ts:1934`
declares `type RemedyKind = 'MESSAGE_INTERVAL' | 'PARAM_WRITE' | 'CLI_SCRIPT' | 'MANUAL'`. The
contract advertises four remediation mechanisms where the system can only ever produce one. Nothing
lies today — the backend simply never emits the other three, so no screen renders them — but this is
the same shape as the CV panel's false slider label: a contract wider than the truth, waiting for a
client to trust it. Flagged, not fixed; fixing it means either narrowing the type or building I4/I6.

`Capability.PTZ` and `Capability.AUDIO` are the same story one module over: persistable via
`@Enumerated(STRING)` on `DeviceEntity`, referenced by tests, but **never assigned to a real device
by any discovery adapter** (ONVIF, mDNS, v4l2, MAVLink). Only `VIDEO` and `TELEMETRY` are ever
produced. PTZ is C2's gimbal/PTZ steering row. Kept.

`PixelFormat.UNKNOWN` is a true orphan — zero references including tests. Kept anyway: it is a
format enum's safe default, and removing it forces every unrecognised input to throw instead of
degrade.

## 4. Dead data — three columns written and never readable

Pass 3 compared every Flyway migration against the JPA entities. Three columns are faithfully
written on every insert and cannot be read back through any port:

| Column | Since | Entity |
|---|---|---|
| `asset_usages.first_armed_at` | V19 | no field on the usage entity |
| `asset_usages.last_disarmed_at` | V19 | no field |
| `pilot_assignments.assigned_at` | V9 | no field — `AssignmentEntity`'s own javadoc calls it *"bookkeeping only, never surfaced through the port"* |

The first two were already known. The third was not, and it is the more interesting one: the entity
**documents its own dead column** and nobody noticed the documentation was an admission. A
`TIMESTAMPTZ NOT NULL DEFAULT now()` recording when a pilot was assigned to an asset is exactly the
kind of thing a fleet manager would want and cannot have.

**Disposition: RECORD, do not act here.** Surfacing any of the three means a domain field, a port
change and a DTO in another context's scope — beyond this wave, and beyond the "cheapest rows"
brief this cycle was picked under. Each is a small, well-defined follow-up.

## 5. What K4 changes

1. Delete `RecordingPort` and its `ARCHITECTURE.md` planned-adapter line. Update `contexts/vision-perception/MODULE.md` to say why it went, so the decision survives the deletion.
2. Everything else in §3 stays, documented above and cross-referenced from the module docs where it is not already obvious.
3. §4 becomes three follow-up items.

**K4 is then done, and the K1–K4 freeze has only K3 (`StreamPipeline` decomposition) left.**

## 6. The finding behind the finding

This audit was commissioned as one of the two *cheapest* remaining rows. Before it ran, checking two
other "cheap" rows — P11 (16 h) and A2 (12 h) — showed both were blocked on `PositionFix`/`FixOrigin`,
which exist only on the parked `feat/visual-geo` branch at module paths the reorg deleted. The matrix
gates P6–P9 on merging that branch but never marked P11/A2 as dependent.

That is a K4-class defect in the planning documents rather than the code, found by the same method:
**ask what actually references a thing.** `docs/main/MASTER-MATRIX.md` §6 should gate P11 and A2 on
A3 alongside the rest.
