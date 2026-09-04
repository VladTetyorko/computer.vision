# TRACK-FOLLOW — the operator picks a target, and the system says what it is doing with it

Status: **ACTIVE, nothing built.** Branch `feat/track-follow`, sub-branch per wave.
Owner ask (2026-09-04): *"an operator clicks a detected object and the system follows it"* — thought
through as a whole flow from the user's seat before any implementation.

Same design language as [`FLY-FLOW-PLAN.md`](FLY-FLOW-PLAN.md) and
[`COMMAND-MAP-FLOW-PLAN.md`](COMMAND-MAP-FLOW-PLAN.md): stages and layers, controls that appear at
the moment of intent, honest states, no fabricated calm. This plan **does not supersede** any frozen
decision of [`TRACKING-PLAN`](../done/TRACKING-PLAN.md) §4 (the lock wire),
[`CV-CLEAN-FEED-PLAN`](../done/CV-CLEAN-FEED-PLAN.md) (tiers, declutter) or
[`TRACK-IDENTITY-PLAN`](../done/TRACK-IDENTITY-PLAN.md) (label election). It **reverses exactly one**
shipped decision — CV-CLEAN-FEED D-3's "the tracks poll is bounded to the Vision drawer's open
lifetime" — and §2.2 D1 says why that decision, correct for a *statistics* panel, silently breaks a
*lock*.

**Grounding:** a read-only sweep on 2026-09-04 over `contexts/vision-perception`, `station/vision-api`
DTOs, `cv/cv-service`'s `session.py`/`lock.py`/`levels.py`, `drone-link/mavlink`, the `vision-web`
player and Fly feature tree, plus `git` on every branch this feature was thought to depend on. Every
claim below carries a file or commit reference.

---

## 0. The flow

The whole thing, from the seat, before any code. Six beats.

```
  watching  ──click a box (or a list row)──▶  following  ──target leaves──▶  lost
     ▲                                          │  │                          │
     └──────────── release ◀────────────────────┘  └── reacquired ◀───────────┘
```

**1 · Watching.** A live feed with boxes over it. Declutter defaults to *Priority*, so the glass is
already an index of what is there, not a confetti of every leaf. This ships today
(`shared/player/detection-overlay-logic.ts`, `DEFAULT_DECLUTTER_LEVEL = 'priority'`).

**2 · Choosing.** Two doors, because clicking a 14-px box on a moving feed is not always possible:
- **the glass** — click a tracked box. The gesture exists (`player.ts:2486` `onOverlayClick` →
  `trackFollowed` output). Untracked boxes and empty canvas stay a deliberate no-op.
- **a list** — a *target list* of the stream's live tracks (label, id, state, age), one row per
  track, click to follow. Does not exist today; §3.4 specifies it. This is also the accessible path
  and the only path that works on a touch screen in a moving vehicle.

**3 · Following.** An explicit, persistent, on-the-glass state: **`Following · person #7`** — the
elected label, not a bare id. The followed target is visually distinct (T0 tier: 3-px stroke, full
opacity, trail, label with confidence) and everything else dims to 40 %. Both of those already ship
(`detection-overlay-logic.ts:1058` `tierAlphaPercent`, `:991` `detectionTiers`) — what does *not*
ship is any statement of the fact anywhere the operator can see it with the Vision drawer closed
(§2.2 D1). The state is a **mode readout with an exit**, never a call-to-action; §3.2 places it.

**4 · What "follow" physically does — the capability ladder.** The single most important honesty
question in this plan, because "follow" means four very different things and only two of them are
buildable now:

| Tier | Name | What actually moves | Status |
|---|---|---|---|
| **F0** | **Hold** | Nothing. cv-service pins its per-frame tracker to one object and duty-cycles the detector down; the UI marks it. | **Ships today**, unnamed and half-visible |
| **F1** | **Name and state** | Nothing. The operator learns *who* is followed and *what the system is doing with it* — holding / coasting / re-acquiring / lost. | **This plan, W1–W4** |
| **F2** | **Digital crop-follow** | The *view* re-frames on the target. Same pixels, cropped and scaled client-side. No camera moves. | **This plan, W6**, off by default |
| **F3** | **Gimbal follow** | A real mount points at the target. | **Deferred, gated.** §3.6 — the TX messages do not exist in this repo. Needs owner go |
| **F4** | **Fly toward the target** | The aircraft moves. | **NEVER.** §5.3 — permanent non-goal |

F4 is not "later". Flying toward a thing a classifier picked is a human act, and this plan states
that as a standing refusal rather than a sequencing note. It is the same doctrine
[`TRACKING-PLAN`](../done/TRACKING-PLAN.md) §10 already set for the guidance controller and
[`RC-CONTROL-PLAN`](RC-CONTROL-PLAN.md) set for actuation: *actuation needs an explicit user go and
its own plan.*

**5 · Loss, honestly.** When the target goes, the box must not simply stop being drawn. The rule:

> **Lost — last seen 4 s ago.** The last real box stays frozen where it was last observed, drawn in
> a distinct lost style (dashed, muted, no confidence), with a **Re-acquire** affordance while the
> server can still honour one, and **Release** always.

The server already tells us this cleanly (§3.1): the frame where the lock drops carries
`Detection{track_id: N, track_state: LOST}` **and** `lockedTrackId: 0` together, and every frame
after it carries `detectorReason: NO_LOCK`. Today nothing in the web reads that pair, so the box
simply vanishes and the drawer chip silently empties — the exact "silently vanishing box" this plan
exists to ban.

**6 · Release.** One gesture from the same place the follow state lives. Everything un-dims, the
frozen lost box clears, declutter returns to whatever it was. `buildReleaseLockPatch()` already
exists (`features/fly/cv-control-panel-logic.ts:653`); it just has no reachable home on the glass.

**Where this lives in the cockpit's one-CTA law.** [`FLY-FLOW-PLAN`](FLY-FLOW-PLAN.md) §2: *"at any
stage the glass carries at most one call-to-action, and it is the next step of the flow."* Follow
does not break it, and the reason is structural rather than a plea for an exception:

- The **click gesture** is on L0, the glass itself, through the overlay canvas. It is not a control
  and consumes no budget.
- The **follow readout** is a *mode state with an exit*, which FLY-FLOW's own layer list already
  admits beside the dock: L2 is *"ONE bottom-center zone … **plus the Arm zone** (bottom-right,
  S3+)"*. The Arm zone is precisely a mode readout with an exit that coexists with the dock's CTA.
  The follow zone is its mirror — **bottom-left, above the OSD shelf** — and appears only while a
  lock exists, i.e. only after the operator has expressed the intent.
- The dock's own CTA (`Take control` at `live`) is untouched at every stage.

**And a second seat.** [`CREW-CONTROL-PLAN`](CREW-CONTROL-PLAN.md) will make an OBSERVER a real
server posture — someone with the full read surface and no arm/disarm. Following a target is the
archetypal crew act: it is *watching*, not flying. So every interaction surface this plan builds is
a **presentational component with pure inputs and outputs**, mounted by a host, never a Fly-only
page fragment (§3.3). When the crew seat exists it mounts the same components with
`canRelease = false` or `true` from its own authority; nothing in this plan needs re-authoring.

---

## 1. The users and their jobs

| Seat | Job while following | What they must never be given |
|---|---|---|
| **Pilot** (`/fly`) | Flying the aircraft. Follow is peripheral: they need to know a lock is held and get out of it in one gesture, while their attention is on attitude, battery and the dock's next step. | A second thing competing with `Take control`/`Arm` for the same glass |
| **Crew / spotter** (CREW-CONTROL, unbuilt) | *This is their whole job.* Pick the target, keep it named, say when it was lost, hand the observation on. | Any control that commands the aircraft |
| **Wall watcher** (`/monitor/wall`) | Peripheral awareness across many feeds: "tile 5 is following something". | A per-tile control surface — the Wall is a watch surface ([`WALL-FLOW-PLAN`](WALL-FLOW-PLAN.md) §5) |
| **Reviewer** (`/replay`, `/assets/:id`) | After the fact: what was followed, and when was it lost. | Out of scope this plan — §5.3 |

The three jobs a follow state must answer, in this order, for every one of them:

1. **Am I following something, and what is it?** — a name, not `#7`.
2. **Is the system actually holding it right now?** — holding / coasting / re-acquiring / lost, from
   the wire, never from a click's optimistic guess.
3. **How do I stop?** — one gesture, always reachable, never behind a drawer.

---

## 2. Diagnosis

### 2.1 Branch reconciliation — the verdict is: **there is nothing to reconcile**

This task was scoped on the belief that three prerequisite branches were built and unmerged, and
that reconciling them was half the job. **They are all already ancestors of `master`.** Checked with
`git merge-base --is-ancestor` on 2026-09-04, `master` @ `fe931e7e`:

| Believed state | Actual state | Evidence |
|---|---|---|
| `feat/track-identity` — BUILT, unmerged | **Merged 2026-08-22**, branch deleted | `2484d6ab` *"Merge feat/track-identity: tracks keep their name, and the lock survives occlusion"* — ancestor of `master`. Its four waves are on master individually: L3 `34f528ed`, L1 `899fb838`, L2 `e25c9b03`, **L4 `6176a44e`** |
| `feat/cv-clean-feed` — BUILT, unmerged | **Merged**, carried in by the above (track-identity was branched off it), branch deleted | `a4735f24` (W1), `adc66bc1` (W3), `8eaa4253` (W4), `2b3237d8` (W5), `65b0d56f` (panel split), `d775c4de` (W7) — all ancestors of `master` |
| `feat/cv-rate-control` — BUILT, unmerged | **Merged 2026-08-12** via `feat/media-sot`, branch deleted | `610b1145` ancestor of `master`; `1bece62d` is the media-sot merge |

`git branch -a` lists no branch matching `*track-identity*`, `*clean-feed*` or `*rate-control*`.
[`docs/plans/README.md`](../README.md) — which is the status authority, not any plan header — already
records rows 41/45/50 as merged at those commits, from its 2026-08-22 reconciliation pass. The stale
belief lives in the agent memory notes, not in the repo.

**Verdict: no merge wave, no cherry-pick, no rebase. Waves start against `master` directly.**
The only residue is a bookkeeping one (W0).

**What L4 "follow memory" already gives this feature — and it is a lot.** `6176a44e`, in
`cv/cv-service/session.py`:

- `_settle_followed` (`session.py:1737-1776`) remembers a lost follow target into `ObjectMemory`
  explicitly, because the automatic remember-on-expiry path is unreachable for an orphaned FOLLOW key.
- `_attempt_follow_recovery` (`session.py:1175-1225`) re-acquires it via
  `ObjectMemory.match_identity` (`memory.py:220-243`) — which scores **one specific** dormant track
  id rather than searching the gallery, deliberately, so a recovery can never silently redirect the
  operator's lock to an object nobody asked for.
- **The re-acquisition keeps the same `lock_seq` and the same track id** — the operator issues no
  second lock. Test: `test_follow_memory_recovery_rebinds_the_same_id_within_ttl_without_a_new_lock_seq`.
- The recovered track keeps its **elected label** (`track.py:923-937`, `session.py:2301`), so the
  target the operator gets back is called what it was called when they lost it.
- Retention: `MemoryParams.ttl_millis` default **30 s** (`memory.py:83`), wire-overridable via
  `TrackingConfig.memory_ttl_millis`.

That is the entire mechanism behind beat 5's *"Re-acquire"* affordance and the `reacquirable` flag in
§3.1's contract. It is built, tested and merged; **this plan spends no wave on it.**

### 2.2 What is actually broken — the follow flow, feature by feature

Everything in beat 1, the click of beat 2, and the tier drawing of beat 3 already ships. The
defects are concentrated in *state, honesty and reach*.

| # | Defect | Evidence | Consequence from the seat |
|---|---|---|---|
| **D1** | **Closing the Vision drawer silently un-follows the target — visually.** `CvControlPanel` owns the tracks-poll lifecycle: `trackTracks(streamId)` from its constructor, `untrackTracks()` from its `DestroyRef` (`cv-control-panel.ts:190-207`). `untrackTracks()` sets `tracksResponseSignal` to **`null`** (`detections-store.ts:341-345`). `lockedTrackId` then computes to `0` (`cv-control-panel.ts:163`), the panel emits `lockedTrackIdChange(0)`, `CockpitFacade#lockedTrackId` goes 0, and `player.ts:2204` `lockActive` goes false. | **The server is still following. The screen stops saying so.** The T0 tier drops, the trail vanishes, every other box un-dims. An operator who closed a drawer now watches an unmarked feed while a lock is held. This is the plan's headline defect and the reason CV-CLEAN-FEED D-3's drawer-bound poll — right for *statistics* — is wrong for a *lock* |
| **D2** | **The lock has no name.** The chip is literally `Following #{{ locked }}` (`cv-control-panel.html:89-99`) — a track id. `grep -rn "Following"` over `src/` finds this chip and two unrelated map auto-follow buttons, and nothing else. | The operator is told they are following **#7**. Not a person, not a car — a number. The elected label is right there on the wire (`TrackResponse.label`, and cv-service elects it stably per `899fb838`), unread |
| **D3** | **Loss is invisible.** `TrackBook` drops a `LOST` track **at once** — *"`TrackState.LOST` is terminal (leaves at once)"* (`vision-perception/MODULE.md`, `TrackBook`), and anything unobserved for `vision.tracking.track-retention-seconds` (**5 s**) is dropped anyway. Nothing anywhere freezes the last box. | The followed box **disappears mid-frame** and the chip empties. Indistinguishable from "I released it" or "the drawer glitched". Exactly the state the owner's mandate bans |
| **D4** | **`lockedTrackId` on `GET .../tracks` is derived from a decaying statistics window, not from the held lock.** `StreamController.java:358` reads `stats == null ? 0L : stats.lockedTrackId()`; `TrackingStats` comes from `TrackingStatsWindow.snapshot()`, which returns `TrackingStats.empty(mode, window)` — **`lockedTrackId = 0`** — the moment its sample deque is empty, i.e. after `vision.tracking.stats-window-seconds` (**30 s**) of no traffic. | A stalled but still-locked stream reports "not following". The endpoint's own javadoc (`StreamController.java:317-320`) says the field was hoisted to the top level *"so the chip that reads it must stay honest even when the window has no statistics to show"* — **the implementation does the opposite of what its contract promises** |
| **D5** | **The wire cannot tell "never locked" from "was held and lost".** Both are `lockedTrackId == 0`. The only distinguishing signal is the single frame carrying `track_state = LOST` for the bound id — and `_reportable` (`cv-service/servicers.py:295-314`) keeps a below-threshold box only for `CONFIRMED`/`COASTING`, so a weak target's loss frame can be dropped entirely. | Any client-side derivation of "lost" is a race against a one-frame event that is allowed to go missing. The distinction has to be made server-side (§3.1 decision 2) |
| **D6** | **Follow is reachable from exactly one page.** `(trackFollowed)` is bound only at `features/fly/cockpit.html:46`. `/live/:deviceId`, the Wall and `/assets/:id` all mount `<vision-player>` and never bind it. | Clicking a box anywhere but the cockpit does nothing, with no explanation. The crew seat CREW-CONTROL will add has, today, no follow surface to inherit |
| **D7** | **Discoverability is one muted sentence inside a drawer.** `cv-control-panel.html`: *"Click a tracked box in the video to lock onto it."* | The feature's only instruction lives behind the door the feature's own state also hides behind |
| **D8** | **No target list anywhere.** The only per-object UI is the class strip (`shared/player/detections-strip.ts`), which aggregates by **label** with counts — it cannot address an individual track. | The only way to follow is to hit a moving box, sometimes 12 px across (`SUB_SCALE_PX = 12`, drawn as a 3-px dot at T3). On a touch screen this is not a feature |
| **D9** | **FOLLOW can silently degrade to ASSOCIATE and the follow flow never says so.** `registry.py:149-155` — `_FOLLOWER_MIN_LEVEL = {"lk": L2, "ncc": L2}`; an **L1** host offers no follower at all, so `registry.follower()` returns `None` and `session.py`'s `FOLLOW → ASSOCIATE → OFF` ladder takes over. The wire reports it honestly (`capabilityLevelServed`/`Reason`, fields 25/26). | The capability notice exists — in the **Vision drawer** (`cv-control-panel` item 6). An operator clicking a box on an L1 relay gets no lock and no explanation at the point of the act |
| **D10** | **No digital zoom, anywhere.** Exhaustive search of `shared/player/**` and `features/fly/**`: `player.css:8-14` is `object-fit: contain` and nothing else; the only `ctx.setTransform` is the flat devicePixelRatio scale (`player.ts:2136`); every `zoom` hit in the tree is Leaflet. | F2 crop-follow is genuinely new work, and it must thread one transform through four sites that all currently assume the canvas CSS box maps 1:1 onto the letterboxed video rect: `letterboxRect` (`player.ts:2281`), `drawnBoxes`, `onOverlayMouseMove` (`:2454`), `onOverlayClick` (`:2486`) |
| **D11** | **The two wire fields that prove a re-acquisition are decoded by nobody.** `cv.proto` `Detection.identity_confidence` (field 10 — *">0 ⟹ this id was RECOVERED from memory, at this score"*) and `dormant_millis` (field 11 — *"how long this identity was dormant before it was recovered"*) are populated by cv-service's L4 path and **dropped on the floor in Java**: `DetectionFrameCodec#toTrackRef` (`cv/grpc/.../DetectionFrameCodec.java:404`) reads only wire fields 4-9 plus 14, and a repo-wide grep for `getIdentityConfidence`/`getDormantMillis`/`identityConfidence`/`dormantMillis` over every `.java` and `.ts` returns **zero hits**. | The server already says, per detection, *"I got this one back from memory after 8.2 s, at confidence 0.71"* — and no layer above the codec can hear it. This is the same "shipped green but inert" shape that made `CameraPose` dead for a release and `DeviceOrigin` unconsumed. Without it, "Re-acquired" is inferred from `lockedTrackId` bouncing 0 → N; with it, it is the server's own statement |

### 2.3 What is already right, and must not be rebuilt

Named explicitly so no wave re-invents them:

- **The lock wire is complete in both directions.** `PATCH /api/streams/{id}/config` with
  `{tracking:{mode:'FOLLOW', lock:{trackId}}}` — there is deliberately **no separate lock endpoint**
  (`StreamController.java:238-239` says so). Release is the same call with `lock:{release:true}`.
  Both builders exist in `cv-control-panel-logic.ts:647/653`.
- **`lockSeq` never crosses the HTTP boundary in either direction.** The client never sends one
  (`TargetLockRequest` has no such field, by design), and no response exposes it. It is an
  `AtomicLong` per `RunningStream` inside `DefaultStreamService` (`:764-767`, created `:279`),
  stamped during the fold (`TrackingConfigPatch#foldOnto`, `:102-118`) and burned **only** when a
  patch actually carries a lock. Per-stream, in-memory, resets to 0 on stream restart — safe,
  because a restart also opens a fresh cv-service session.
- **The lock is restated declaratively on every frame** and is idempotent by `lockSeq`
  (`lock.py:100-120`) — a dropped frame or a reconnect cannot lose it. Push mode restates via
  `StreamPipeline`'s `volatile config` on every sampled frame; pull mode restates via
  `PulledDetectionSession#controlBuilder` on every control message. One encoder serves both
  (`DetectionFrameCodec#toWireTrackingConfig`).
- **Three rules the UI must not fight.** A `null` lock in a patch means *unchanged*, never *release*
  (`vision-perception/MODULE.md:277`). A lock on a **start** request is a deliberate **400**
  (`TrackingConfigRequest#toStartPatch` — *"it names a track that does not exist yet"*), so following
  is always a PATCH on a running stream. And `GET /api/streams/{id}/config` carries **no lock field
  at all** — `GET .../tracks` is the one and only place a held lock is confirmed, which is exactly
  why §3.1 puts the follow object there.
- **The per-frame lock fact already reaches the browser continuously.**
  `DetectionResultResponse.tracking` → `FrameTrackingResponse.lockedTrackId` → the web's
  `DetectionResult.tracking?.lockedTrackId` (`core/api/models.ts:1649`, `:689`). This rides the
  **detections feed** — SSE `detections:<assetId>` or the 2 s poll — which runs for the whole life
  of the stream, drawer or no drawer. **D1 is fixable with no new endpoint and no new poll.**
- **Identity is stable and named.** L1 label election (`899fb838`) with a 10-vote decayed window,
  a 1.5× switch margin and a 3-call streak; it lands on the existing `Detection.label` field, and it
  survives a memory re-acquisition.
- **Tiering, dimming, collision-yielded labels, staleness fade, coast dashing and client-side
  projection** all ship (`detection-overlay-logic.ts`, `player.ts:2118-2276`).
- **`tools/trackeval --mode FOLLOW` exercises the whole lock path with no camera** — it injects a
  **point lock at the centre of the primary target's first box, exactly like an operator click**
  (`tools/trackeval/replay.py:544-655`). This is the verification instrument for §5.

---

## 3. The target model

### 3.1 Frozen wire contract — one additive object, `follow`

**Decision 1 — the follow state is a server fact, not a client derivation.** D4 and D5 make
client-side inference a race against a one-frame event that is allowed to be dropped. The pipeline
already sees every frame; it is the only place that can answer "was held, now lost" without guessing.

**Decision 2 — it is a peer of `TrackBook`, not a field on it.** `TrackingStatsWindow`'s class doc
already establishes the pattern and its reason: *"Folding these counters into `TrackBook` would give
that class two responsibilities."* A follow state machine is a third responsibility; it gets a third
peer, `FollowTracker`, fed the same `DetectionResult` the other two are fed.

**Decision 3 — additive and absent-by-default, so every existing test stays green.**
`StreamTracksResponse` is `@JsonInclude(NON_NULL)` and already carries three convenience
constructors for exactly this kind of growth. `follow` is **omitted entirely** when no lock has ever
been issued on the stream — which is every stream in every existing test. No feature flag is needed
for the read model; the empty case *is* today's behaviour, byte-identical.

#### `GET /api/streams/{streamId}/tracks` — the one changed response

```jsonc
{
  "streamId": "…",
  "lockedTrackId": 7,          // UNCHANGED field, FIXED source — see decision 4
  "tracks":  [ … ],            // unchanged
  "stats":   { … },            // unchanged
  "latency": { … },            // unchanged
  "rate":    { … },            // unchanged
  "detectionState": "…",       // unchanged

  "follow": {                  // NEW — object omitted entirely when no lock was ever issued
    "state":        "HOLDING", // REQUESTING | HOLDING | COASTING | LOST | RELEASED
    "trackId":      7,         // the track actually bound; 0 while REQUESTING and after RELEASED
    "label":        "person",  // the elected label, last known; "" if never observed
    "since":        "2026-09-04T10:15:02.500Z", // when the CURRENT state began
    "lastSeenAt":   "2026-09-04T10:15:06.700Z", // last frame the bound track was observed; null if never
    "lastSeenAgeMillis": 4200, // server-computed — the UI never re-derives from a clock it does not share
    "lastBox":      { "x": 0.41, "y": 0.32, "width": 0.08, "height": 0.19 }, // frozen last real box; null if never
    "reacquirable": true,      // memory TTL not expired AND the lock form is trackId — see below
    "recoveredAfterMillis": 8200,  // >0 ⟺ the CURRENT bind came back from memory; 0 = a fresh acquisition
    "recoveryConfidence":   0.71   // cv-service's own identity_confidence for that recovery; 0 when not a recovery
  }
}
```

The last two come from D11's two undecoded wire fields, `Detection.dormant_millis` and
`identity_confidence`, and they are what lets the HUD say **"Re-acquired after 8 s"** as a server
fact rather than an inference from `lockedTrackId` bouncing. Note cv-service never resolves an
appearance extractor in FOLLOW, so a recovery is scored on label and motion alone and
`recoveryConfidence` is **always < 1.0** (`session.py:1197-1201`) — the UI must not render it as a
percentage bar that never fills; it is a tie-breaker fact, shown only in the drawer, never a headline.

**`FollowState` — the five values and exactly what each means.** These mirror cv-service's own five
lock states (`lock.py`, `session.py`) one-for-one; no new semantics are invented:

| Value | Wire condition | Shown as |
|---|---|---|
| `REQUESTING` | a lock was issued; `lockedTrackId` is still `0` | *"Acquiring…"* |
| `HOLDING` | `lockedTrackId == trackId`; the bound track's newest `TrackRef.source == DETECTOR` | *"Following · person #7"* |
| `COASTING` | `lockedTrackId == trackId`; newest `TrackRef.state == COASTING` (`source == TRACKER`) | *"Following · person #7 — coasting"* |
| `LOST` | `lockedTrackId` fell to `0` **after** having been non-zero for this lock generation | *"Lost — last seen 4 s ago"* |
| `RELEASED` | the operator's own release patch was applied | the object is dropped on the next poll |

`reacquirable` is `true` iff `state == LOST`, the lock was issued in **`trackId` form** (cv-service's
memory path is gated to track-id locks — `session.py:1207-1210`; box/point locks keep a purely
geometric reference and never reach it), and `lastSeenAgeMillis < memoryTtlMillis`. Where the
deployment has not overridden it, `memoryTtlMillis` is cv-service's own default **30 000**
(`memory.py:83`); the resolved value is read from the pipeline's `TrackingConfig`, never hard-coded
in the web.

**Decision 4 — `lockedTrackId` is repointed at the same source, and the change is a bug fix.**
`StreamController.java:358` stops reading `stats.lockedTrackId()` and reads the new
`FollowTracker`'s bound id instead. The field's meaning, type and zero-sentinel are **unchanged**;
only its honesty improves (D4), and the endpoint's own javadoc already promised this behaviour.

**Decision 5 — nothing is added to the SSE envelope.** `FrameTrackingResponse.lockedTrackId` already
rides every detections frame and is enough to drive the continuous *visual* lock (§2.3). Putting the
richer `follow` object on a per-frame envelope would churn nine fields at frame rate to say something
that changes a handful of times per flight. The web reads the cheap per-frame fact continuously and
the rich object from the tracks poll — §3.5 sets when each poll runs.

**Decision 6 — `PATCH /api/streams/{id}/config` is unchanged.** `TargetLockRequest(trackId, pointX,
pointY, release)` and its one-of-three rule are already frozen by TRACKING-PLAN §4.D and already
validated server-side (400 on two forms). Re-acquire sends the **same `trackId` again** — a fresh
`lockSeq` on the same id, which cv-service treats as a new bind and which its memory path may
satisfy from the dormant gallery. No new verb, no new field.

**Decision 7 — no new REST endpoint anywhere in this plan.** Two DTO records and one repointed field
is the entire backend surface.

#### Java-side records (frozen names)

```
contexts/vision-perception/…/domain/model/TrackRef.java         GROWS BY TWO components (W1) —
                                                                  double identityConfidence, long dormantMillis
contexts/vision-perception/…/domain/model/FollowState.java      enum  REQUESTING|HOLDING|COASTING|LOST|RELEASED
contexts/vision-perception/…/domain/model/FollowStatus.java     record(FollowState state, long trackId, String label,
                                                                       Instant since, Instant lastSeenAt,
                                                                       BoundingBox lastBox, boolean reacquirable,
                                                                       long recoveredAfterMillis,
                                                                       double recoveryConfidence)
contexts/vision-perception/…/application/pipeline/FollowTracker.java   package-private, final — the peer
station/vision-api/…/dto/FollowResponse.java                    record + static from(FollowStatus, Instant now)
```

`TrackRef` today is `(long trackId, TrackState state, DetectionSource source, double velocityX,
double velocityY, int ageFrames, boolean reupdated)` with 3-/6-arg convenience constructors. **Both
new components are appended, and the existing convenience constructors default them to `0`** — so
every current call site keeps compiling and every current test keeps passing. Per CLAUDE.md rule 10
no *new* convenience overload is added; the canonical constructor grows and its call sites are
updated.

`FollowStatus` validates in a compact constructor (`state` non-null; `trackId >= 0`; `label` non-null,
`""` allowed; `lastSeenAt`/`lastBox` both null or both non-null) per the repo's domain idiom.
`lastSeenAgeMillis` is **not** a domain field — it is computed in `FollowResponse.from` from the
request instant, so the domain record stays a fact and the DTO carries the derived convenience.
`StreamService` gains one forgiving read, `Optional<FollowStatus> followStatus(StreamId)`, matching
the existing `trackingStats`/`pipelineLatency`/`detectionRate` shape (unknown or stopped stream reads
empty, never an error).

### 3.2 Where follow lives on the glass

FLY-FLOW's layers, with the follow zone placed:

```
L0  the glass      the video · the overlay canvas · the CLICK GESTURE (no CTA budget)
L1  the frame      header · OSD shelf · tool rail
L2  the dock       ONE bottom-center CTA zone   ·  Arm zone (bottom-right, S3+)
                                                ·  FOLLOW ZONE (bottom-left, only while a lock exists)   ← new
L3  on demand      rail drawers · CV setup modal · stop-confirm scrim
```

The follow zone is a `--hud-*` frosted pill group pinned bottom-left, above the OSD shelf, on the
8-px grid, self-applying `surface-dark` (frontend-style §2/§7 — it floats directly over video). It
renders **only when `follow` is present**, which means only after the operator has acted; at rest the
glass is exactly as FLY-FLOW W4 left it. Contents, one row:

```
[● person #7]  [state text]  [Re-acquire]?  [Release]
```

- **`● person #7`** — a `.chip`, the dot carrying state colour (live / warn / danger per §3.3).
- **state text** — one short phrase, never a spinner without words.
- **Re-acquire** — `.btn secondary small`, rendered **only** when `state == 'LOST' && reacquirable`.
- **Release** — `.btn secondary small`, always.

Neither button advances the flow; both exit or repair a mode the operator already entered. This is
the Arm zone's shape, and §0 argues the law is satisfied rather than excepted.

### 3.3 The one component, mounted by four hosts — `<vision-follow-hud>`

**Frozen, in `shared/player/`, not `features/fly/`** — because CREW-CONTROL will mount it from a
different seat and `/live` and the Wall should show it read-only.

```
shared/player/follow-hud/follow-hud.{ts,html,css}      the pill group
shared/player/follow-hud/follow-logic.ts               pure: state → label, tone, affordances
shared/player/follow-hud/follow-logic.spec.ts
```

```ts
@Input  follow      : FollowStatus | null   // required; null = render nothing
@Input  canRelease  : boolean               // false = read-only (Wall, Live, an OBSERVER without authority)
@Output release     : void
@Output reacquire   : void
```

It injects **nothing**. No `VisionApi`, no store, no facade — the host owns every write, the same
"dumb component, host owns the write" rule `player.ts`'s `trackFollowed`/`latencyChanged` already
follow. That is what makes it seat-agnostic: a crew page mounts it with its own authority answer and
its own patch call, and this plan needs no revision when CREW-CONTROL lands.

Pure logic, frozen (`follow-logic.ts`):

```ts
export type FollowTone = 'live' | 'warn' | 'danger';
export interface FollowPresentation {
  readonly title: string;      // "person #7" — label falls back to "#7" when label is ""
  readonly detail: string;     // "Following" | "Acquiring…" | "Coasting" | "Lost — last seen 4 s ago"
  readonly tone: FollowTone;   // HOLDING→live · REQUESTING/COASTING→warn · LOST→danger
  readonly showReacquire: boolean;
}
export function followPresentation(follow: FollowStatus, nowMs: number): FollowPresentation;
```

Age is rendered through the existing `humanAge` vocabulary the rest of the app uses
(OPERATOR-UX-4/5), so "4 s ago" reads identically here and in the OSD.

### 3.4 The target list — the second door

Added to the **Vision drawer**, below the class strip and above the CV control panel body, as a
non-routed presentational child `features/fly/target-list.{ts,html,css}`:

- One row per entry of `tracks[]`, ordered **followed first, then most-recently-seen**.
- Columns per frontend-style §5: `label` (text, left) · `#id` (`.mono`, muted) · state (a dot +
  plain text, **not** a chip — the followed row already spends the row's one chip) · age (`.mono`,
  right).
- The followed row uses the app's **one selection language** (§4): 2-px left inset bar in
  `--color-info` + `--color-info-soft` tint. No second selection idiom.
- Click a row → the host's `followTrack(trackId)` — the identical write path the glass click uses,
  so the two doors can never drift.
- Empty state via `vision-empty`: *"No tracked objects right now."* — never a blank box.
- It renders the **same** `tracks[]` the panel already polls; it adds no request.

### 3.5 Two feeds, two jobs — when each poll runs

The rule that fixes D1 without paying for a permanent second poll:

| Fact | Source | Runs when |
|---|---|---|
| *Is a lock held, and on which id?* | `DetectionResult.tracking.lockedTrackId`, on every detections frame (SSE or the 2 s poll) | **Always**, for the life of the stream — already true today |
| *What is it called, what is its state, when was it last seen, can it be re-acquired?* | `follow` on `GET .../tracks`, 2 s | The Vision drawer is open **OR** the cheap fact above is non-zero **OR** the last `follow` read was `LOST` |

So: the T0 highlight, the dimming and the trail are driven by the continuous per-frame fact and can
never be switched off by closing a drawer (D1). The richer poll starts the moment a lock appears and
keeps running through `LOST` until the operator releases or the stream stops — which is precisely the
window in which the answer is interesting. A cockpit with no lock and a closed drawer polls exactly
what it polls today.

`DetectionsStore` grows one method, `followTracks(streamId, wanted: boolean)`, and `CvControlPanel`
**stops owning** `trackTracks`/`untrackTracks` — that ownership moves to `CockpitFacade`, which is
the only object that knows both the drawer state and the lock state. This is the CV-CLEAN-FEED D-3
reversal, scoped exactly.

### 3.6 F3 — gimbal follow, deferred with its shape named

Investigated rather than assumed. **Gimbal RX is built and good; gimbal TX does not exist anywhere in
this repository.**

- **Built (RX):** `MavlinkTelemetryDecoder.java:257-261` decodes `ATTITUDE` (#30),
  `GIMBAL_DEVICE_ATTITUDE_STATUS` (#285) and `MOUNT_ORIENTATION` (#265); `AttitudeState.java`
  latches #285 over #265 and records gimbal yaw **only ever earth-frame**, leaving it `null` rather
  than guessing when the frame cannot be resolved. `Attitude` (kernel) carries all six angles.
- **Absent (TX):** a repo-wide search for `DO_MOUNT_CONTROL | GIMBAL_MANAGER | SET_PITCHYAW |
  DO_SET_ROI` finds **three documentation files and zero source files**. The entire TX command
  inventory is six commands (`DO_SET_MODE`, `COMPONENT_ARM_DISARM`, `DO_AUX_FUNCTION`,
  `REQUEST_MESSAGE`, `SET_MESSAGE_INTERVAL`, `NAV_RETURN_TO_LAUNCH`).
- **Absent (capability):** `GIMBAL_MANAGER_INFORMATION` (#280) / `STATUS` (#281) are not decoded, so
  there is no honest way to know a mount exists, its axis limits, or its component id. This repo
  refuses to offer controls it cannot verify — `ControlAction.java:8-10` says so about gimbal
  commands by name, and `CONTROLLER-SETUP-CONTEXT.md:99` records *"no gimbal in this platform — not
  faked"*.
- **Wrong shape:** `GIMBAL_MANAGER_SET_PITCHYAW` (#287) is **not** a COMMAND_LONG and has no
  `COMMAND_ACK`, so `CommandService.sendLong/sendInt` cannot send it at all; a pointing loop wants
  5–20 Hz streamed setpoints, which the one-shot-with-ack machinery is the wrong shape for. The only
  ack-less streaming TX pattern that exists is `ManualControlService`'s RC override.
- **Nothing to test against:** no gimbal on the ESP32 rover, none configured in `infra/sitl`.
- **No FOV from the wire:** the error term needs a real HFOV; today it is the hand-set
  `vision.tracking.camera-hfov-degrees`, default `0` = unknown.

**Verdict: F3 is a separate plan of ~3 waves** — decode #280/#281 and populate a gimbal capability →
a new streaming-TX service in `drone-link/mavlink-core` beside `ManualControlService` → a
`GimbalCommandPort` behind `vision.mavlink.gimbal.enabled` (default `false`, the
`vision.onboarding.probe.enabled` idiom verbatim: a `@ConditionalOnProperty(havingValue="false",
matchIfMissing=true)` no-op bean that refuses honestly). It needs an owner go, a mount to test
against, and a `FeatureRequirement` key — note `FEATURE_KEYS` is a **frozen v1 set** and does not
contain one today. **This plan does not start it**, and §3.3's component takes no gimbal input, so
adding one later is additive.

---

## 4. Waves

Seven waves. Backend first (W1→W2→W3, sequential — they hand one contract along), then web. Every
wave is one subagent with a disjoint file scope and ends **independently green**.

- **Backend:** `./mvnw -B -pl <module> test` — scoped, never reactor-wide (CLAUDE.md), and
  `-am` where a sibling contract changed in the same chain.
- **Web:** `npx tsc --noEmit` **and** `npm run test:ci` (= `ng test --watch=false`). **Never bare
  `npx vitest run`** — it fabricates ~536 failures (`station/vision-web/MODULE.md:227`). A bare `ng`
  on `PATH` can resolve to a nethack launcher; use `npm run` / `npx ng` (`MODULE.md:228`).
- Every wave updates the MODULE.md of each module it touches, in the same task.

| Wave | Scope | Agent | Depends on |
|---|---|---|---|
| W0 | docs only | — | none (parallel with everything) |
| W1 | `cv/grpc` + one perception domain record | **`adapter-builder`** | none |
| W2 | `contexts/vision-perception` | **`application-service`** | W1 |
| W3 | `station/vision-api` | **`spring-integrator`** | W2 |
| W4 | `station/vision-web` — glass + store | **`web-ui`** | W3 |
| W5 | `station/vision-web` — list + reach | **`web-ui`** | W4 |
| W6 | `station/vision-web` — crop-follow | **`web-ui`** | W4 (∥ W5) |
| W7 | verification | — | W5, W6 |

---

**W0 — correct the record** (`docs/plans/README.md`, and the status headers of
`TRACK-IDENTITY-PLAN.md` / `CV-CLEAN-FEED-PLAN.md` / `CV-RATE-CONTROL-PLAN.md` if any still claims
"not merged"). No code. §2.1's table is the content. This exists because the belief that three
branches were unmerged is what this feature was scoped on, and the next reader deserves not to
re-derive it. Acceptance: no plan document in `docs/plans/` claims an unmerged state for a commit
that is an ancestor of `master`.

---

**W1 — decode the recovery evidence** (`adapter-builder`)

Files: `cv/grpc/src/main/java/…/DetectionFrameCodec.java` (+ its tests),
`contexts/vision-perception/src/main/java/…/domain/model/TrackRef.java` (+ its test),
**`station/vision-api/src/test/java/…/StreamControllerTest.java:1164` (one line — see the blast
radius below)**, `cv/grpc/MODULE.md`, `contexts/vision-perception/MODULE.md`.

**Blast radius, surveyed so the wave does not discover it mid-flight.** `new TrackRef(` appears in
four modules — 7 sites in `vision-perception`, 1 in `cv/grpc`, 1 in `station/vision-api`, 1 in
`storage/persistence`. Only **two** use the 7-arg canonical constructor and therefore break:
`DetectionFrameCodec.java:416` (this wave's own file) and `StreamControllerTest.java:1164`. The
other two cross-module sites (`StreamControllerTest.java:1331`,
`PostgresDockerIntegrationTest.java:1447`) use the **6-arg convenience** constructor and keep
compiling untouched. So this wave reaches one line outside its home modules, and that line is named
here rather than left to be found by a red build. Adding a 7-arg convenience overload to dodge it is
**not** the answer — CLAUDE.md rule 10 withdrew that convention explicitly.

D11's fix, and the smallest wave in the plan. `TrackRef` grows `identityConfidence` and
`dormantMillis`; `toTrackRef` reads wire fields 10 and 11 into them. Nothing else changes — no
enum gains a value (so `DetectionFrameCodec`'s exhaustive switches stay exhaustive), no proto edit,
no new port.

Guard rails, all already true of the surrounding code and to be preserved verbatim: `track_id == 0`
short-circuits to untracked **before** any other track field is read; an `UNSPECIFIED`/`UNRECOGNIZED`
state or source also decodes untracked, never a guessed value. A pre-L4 server sends proto zeros, so
both new components read `0` — which is exactly "not a recovery", the honest answer.

Acceptance: `./mvnw -B -pl contexts/vision-perception test`, `./mvnw -B -pl cv/grpc -am test` **and**
`./mvnw -B -pl station/vision-api -am test` all green — the third is not optional, because of the
blast radius above. **Install the changed modules before any downstream `-pl` run** — a stale
`~/.m2` jar can make a suite green while the cross-module contract is broken, a lesson this repo has
already paid for twice. A codec test asserts a recovered detection round-trips both values and a
non-recovered one yields `0`/`0.0`.

---

**W2 — the follow state machine** (`application-service`)

Files: `contexts/vision-perception/src/main/java/…/domain/model/FollowState.java` *(new)*,
`…/domain/model/FollowStatus.java` *(new)*, `…/application/pipeline/FollowTracker.java` *(new)*,
`…/application/pipeline/StreamPipeline.java`, `…/application/stream/StreamService.java` +
`DefaultStreamService.java`, plus their tests and `contexts/vision-perception/MODULE.md`.

`FollowTracker` is a package-private final peer of `TrackBook`/`TrackingStatsWindow`, constructed
per pipeline, fed the same `DetectionResult` via a `synchronized accept(...)`. It observes
`TrackingTelemetry.lockedTrackId` and the frame's detections and maintains `FollowStatus`:

- a lock patch that carries a lock arms it into `REQUESTING` (the pipeline already knows — the fold
  happens in `TrackingConfigPatch.foldOnto`, and `UpdateOutcome.trackingChanged` is already computed);
- `lockedTrackId` going non-zero → `HOLDING`, `trackId` bound, `since` stamped;
- while bound, the bound track's newest `TrackRef` decides `HOLDING` vs `COASTING`, and every
  observation refreshes `label` (the elected label from `Detection.label`), `lastSeenAt` and
  `lastBox`;
- `lockedTrackId` falling to `0` **while a bind was held** → `LOST`, freezing `lastSeenAt`/`lastBox`
  and *not* clearing `label` — this is D3/D5's fix and the whole point of the wave;
- a bind whose `TrackRef.identityConfidence > 0` (W1) stamps `recoveredAfterMillis` from
  `dormantMillis` and `recoveryConfidence` from `identityConfidence`; a fresh acquisition leaves both
  at zero. **A recovery is not a loss** — the state goes straight back to `HOLDING` with the same
  `trackId` and the same `label`, because that is what cv-service's L4 path actually did;
- an applied release → `RELEASED`, after which `followStatus()` reads empty.

**Timebase:** every instant comes from `DetectionResult#capturedAt()`, never wall-clock — the rule
`DetectionEventEngine` and `DetectionExtrapolator` already follow, and what lets the tests drive the
whole lifecycle from hand-picked `Instant`s with no clock injection.

`StreamService` gains `Optional<FollowStatus> followStatus(StreamId)`, forgiving on an unknown or
stopped stream. `reacquirable` reads the pipeline's resolved `TrackingConfig` for the memory TTL —
**never a constant in this module**.

Acceptance: `./mvnw -B -pl contexts/vision-perception test` green; new unit tests cover all five
states, the LOST freeze, the release drop, and the "coast then recover keeps the same trackId and
label" path (which is L4's behaviour and must not be mistaken for a loss).

---

**W3 — expose it** (`spring-integrator`)

Files: `station/vision-api/src/main/java/…/dto/FollowResponse.java` *(new)*,
`…/dto/StreamTracksResponse.java`, `…/controller/StreamController.java`, their tests, and
`station/vision-api/MODULE.md` (the API-surface table row for `GET /api/streams/{id}/tracks`).

Adds `follow` per §3.1 as a trailing component with a convenience constructor preserving the current
arity, and **repoints `lockedTrackId` off `stats`** (D4) onto `followStatus()`. Updates the
endpoint's javadoc, which currently describes behaviour the code does not have.

Acceptance: `./mvnw -B -pl station/vision-api -am test` green. **Every existing
`StreamControllerTest` assertion holds with no change to its expected JSON** — no lock was ever
issued in any of them, so `follow` is absent and the bodies are byte-identical. (W1 already touched
one *construction* line in that file; this wave changes no expectation.) New cases assert the five
states, the recovery fields, and the omitted-object default.

---

**W4 — the glass: the follow HUD, and a lock that survives a closed drawer** (`web-ui`)

Files: `shared/player/follow-hud/follow-hud.{ts,html,css}` *(new)*,
`shared/player/follow-hud/follow-logic.ts` + `.spec.ts` *(new)*, `core/api/models.ts`
(`FollowStatus`/`FollowState` interfaces + `StreamTracksResponse.follow`),
`core/detections/detections-store.ts`, `features/fly/cockpit-facade.ts`,
`features/fly/cockpit.{html,css}`, `features/fly/cv-control-panel.ts` (poll ownership removed only),
`station/vision-web/MODULE.md`.

Three things, in this order:

1. **Break the drawer coupling (D1).** `CockpitFacade#lockedTrackId` stops being fed by
   `CvControlPanel`'s `lockedTrackIdChange` and becomes `computed(() =>
   detections.results()[0]?.tracking?.lockedTrackId ?? 0)` — the continuous per-frame fact of §2.3.
   `CvControlPanel` keeps rendering its chip but no longer owns the tracks poll; `CockpitFacade`
   drives `followTracks(streamId, wanted)` per §3.5's rule. `lockedTrackIdChange` is **deleted**, not
   left dangling.
2. **Mount the follow zone.** `<vision-follow-hud>` at `.main-left`'s bottom in `cockpit.html`,
   `[follow]="facade.follow()"`, `[canRelease]="!facade.watchMode()"`, `(release)`/`(reacquire)` →
   facade writes using the existing `buildReleaseLockPatch()` / `buildFollowLockPatch(trackId)`.
   Watch mode gets the readout and no actions, per the existing gates.
3. **Draw the loss (D3).** When `follow().state === 'LOST'`, the overlay draws `lastBox` in a lost
   style — dashed, muted, no confidence label — pinned where it was last seen, and no other box is
   dimmed. This is a **new tier-independent draw pass** in `player.ts`, fed by one new input
   `lostBox: BoundingBox | null`; it must not touch `detectionTiers` (W6 also edits that file — see
   the sequencing note).

Naming trap to carry into the wave: the two nested wire shapes **disagree on purpose** — the
per-frame `DetectionResult.detections[].track` names its id field **`id`**, while
`StreamTracksResponse.tracks[]` names it **`trackId`**. Both are already mirrored correctly in
`core/api/models.ts`; do not "fix" either.

Constraints: three files per component; `follow-hud` injects nothing; `cockpit.ts` gains no store
injection and no `*Open`/`*Menu`/`*Confirm`/`*Editing` bare signal (`architecture.spec.ts`
invariants 1 and 2 — `fly/cockpit` is in `ROUTED_PAGES`); tokens only, both themes, `--hud-*` with
`surface-dark` self-applied (frontend-style §2/§7).

Acceptance: `npx tsc --noEmit` + `npm run test:ci` green; `follow-logic.spec.ts` covers all five
states, the `""`-label fallback to `#7`, tone mapping and the `showReacquire` gate; a spec asserts
that `lockedTrackId` is non-zero with the tracks poll stopped (the D1 regression).

---

**W5 — the second door, and the other seats** (`web-ui`) — parallel with W6

Files: `features/fly/target-list.{ts,html,css}` *(new)* + `target-list-logic.ts` *(new)* + spec,
`features/fly/cv-control-panel.{html,css}`, `features/live/live.{html,ts}`,
`features/live/live-facade.ts`, `features/wall/wall-tile.{html,ts}`,
`station/vision-web/MODULE.md`.

- The target list per §3.4, mounted in the Vision drawer. Ordering, empty state and the one selection
  language are the acceptance surface.
- **Reach (D6/D7):** `/live/:deviceId` binds `(trackFollowed)` and mounts `<vision-follow-hud>` with
  `canRelease` from its own facade; the Wall tile mounts it **read-only** (`canRelease=false`) so a
  watch surface stays a watch surface (WALL-FLOW §5).
- Delete D7's muted sentence from `cv-control-panel.html` — the list makes the gesture discoverable
  by existing, and the HUD states it.

Does **not** touch `player.ts` or `detection-overlay-logic.ts`, which is what makes it parallel-safe
with W6.

Acceptance: `npx tsc --noEmit` + `npm run test:ci` green.

---

**W6 — F2, digital crop-follow** (`web-ui`) — parallel with W5

Files: `shared/player/player.{ts,html,css}`, `shared/player/detection-overlay-logic.ts`,
`shared/player/crop-follow-logic.ts` *(new)* + spec, `core/settings/settings-store.ts` (one persisted
stop), `station/vision-web/MODULE.md`.

A client-side transform on the video frame, driven by the followed target's box:

- **Off by default**, a persisted per-viewer setting beside `declutterLevel`, offered as a small
  `Zoom ×2` toggle **inside the follow HUD** — it is meaningless without a lock, so it appears only
  with one.
- Pure logic in `crop-follow-logic.ts`: box → a `{scale, offsetX, offsetY}` transform, with a
  **deadband** (no re-frame until the target leaves a centre box) and a **rate limit / critically
  damped ease**, so the picture does not jitter with every detection. Constants live in that file
  with their reasons, not scattered in the component.
- **Honesty:** the toggle is labelled with what it is — *"Digital zoom ×2 — crop, no extra detail"*.
  It must never read as optical zoom, and it must degrade to nothing (no transform, control hidden)
  when `state === 'LOST'`, because re-framing on a frozen box is a lie about where the camera is
  looking.
- **The four sites (D10):** the same transform must be threaded through `letterboxRect`,
  `drawnBoxes`, `onOverlayMouseMove` and `onOverlayClick`, all of which currently assume the canvas
  CSS box maps 1:1 onto the letterboxed video rect. **The acceptance test for this wave is that
  click-to-follow still selects the correct box at ×2** — if the hit test drifts, the wave is not
  done.
- `prefers-reduced-motion` disables the ease and snaps (frontend-style §9).

Sequencing note: W4 and W6 both edit `player.ts`. W6 starts after W4 lands; W6 owns
`detectionTiers`/geometry, W4 owned the lost-box draw pass — a deliberate split so the two never
need the same function.

Acceptance: `npx tsc --noEmit` + `npm run test:ci` green; `crop-follow-logic.spec.ts` covers the
deadband, the clamp at frame edges, and the identity transform at scale 1.

---

**W7 — verification and close-out**

Runs §5's acceptance list live, records the result in a Close-out section of this document, and
updates [`docs/plans/README.md`](../README.md).

---

## 5. Verification

### 5.1 What can be proven without hardware

**`tools/trackeval` is the instrument, and it already drives the real path.** Its FOLLOW replay runs
a real `StreamTrackingSession` frame by frame and **injects a point lock at the centre of the primary
target's first visible box — exactly what an operator click produces** (`tools/trackeval/replay.py:544-655`).
Fifteen scenarios are registered; the follow-relevant ones are `occlusion`, `long_occlusion`,
`pan_occlusion`, `clutter`, `crossing_similar`, `nonlinear`, `tiny_fast`.

```
PYTHONPATH="$PWD" .venv/bin/python -m tools.trackeval --scenario long_occlusion --mode FOLLOW
```

This proves the *server* half — that a lock is held, coasts, is lost and is recovered — with no
camera and no aircraft. Unit coverage for the same paths already exists at
`cv/cv-service/tests/tracking/test_session.py:1263-1400` (L4 memory), `:1032-1130` (re-acquire
cadence), `:1459-1560` (top-k), and `tests/grpc/test_detect_stream_tracking.py` for the wire round
trip. **No wave in this plan changes cv-service**, so these are regression guards, not new work.

### 5.2 What needs a live CV service

The web half cannot be proven by `tsc` and `test:ci` alone. The acceptance list, run against a
running station with `vision.cv.enabled=true` and a real cv-service:

1. Click a box → within one poll the HUD reads **`Following · person #7`**, with a name, not a number.
2. **Close the Vision drawer.** The target stays T0, the rest stay dimmed, the HUD stays. *(D1 — the
   headline regression test.)*
3. Occlude the target. The HUD reads **coasting** while the box dashes; it does not read "lost".
4. Remove the target. Within one poll: **`Lost — last seen N s ago`**, the last box **frozen where it
   was**, nothing else dimmed, **Re-acquire** offered while inside the memory TTL and gone after it.
5. Bring the target back inside the TTL and press **Re-acquire** → the *same* `#7` and the *same*
   name come back (L4's guarantee, now visible).
6. Press **Release** → everything un-dims, the frozen box clears, the HUD leaves the glass.
7. Follow from the **target list** and confirm it is indistinguishable from following by click.
8. On `/live` the HUD appears and can release; on a **Wall tile** it appears and **cannot**.
9. At ×2 crop-follow, click a *different* box and confirm the correct one is selected (W6's gate).
10. Both themes, and the HUD checked over both a bright and a dark video frame.

**Where to run it.** Locally is preferred: `docker-compose.yml` starts `cv-service` on `:50051` and
the laptop CPU is ~2× faster per frame than the alternative. The **GB4005 box is remote and
Intel-only — no CUDA**: `vlad@192.168.0.106`, sources by `rsync` (no git on the box), CPU torch
wheel, speedup via **OpenVINO** (`yolo26n_openvino_model`), measured **~135–150 ms/frame** against
~230 ms for PyTorch CPU. That is a ~7 fps ceiling, so **keep per-stream `inferenceFps` ≤ 5** when
verifying against it, and expect coasting to be the *normal* state between passes rather than a
defect. Because `openvino` imports there, `levels.probe()` returns **L4** on that box.

**D9's case is only reachable on a genuinely L1 host** (no `cv2`/`numpy`, or `MemAvailable` < 512 MiB).
Rather than manufacture one, the wave asserts it the way the ladder is meant to be asserted: set
`vision.tracking.capability-level: 2` or lower and confirm the follow HUD surfaces the served level's
reason instead of silently showing nothing. If that cannot be arranged, it is recorded as a residual,
not claimed.

### 5.3 Non-goals, named

- **F4 — flying toward a target. Permanently refused, not deferred.** No wave, now or later, in this
  plan turns a lock into a velocity, a waypoint, a mode change or an RC override. Same standing rule
  as TRACKING-PLAN §10 and RC-CONTROL Phase 2.
- **F3 — gimbal follow.** Shape named in §3.6, needs an owner go, a decoded `GIMBAL_MANAGER_*`
  capability, a new streaming-TX service, a default-off flag and a mount to test against. Not started
  here.
- **Cross-stream identity.** Following "the same person" across two cameras. TRACKING-V3 §7 already
  refuses it; nothing here changes that.
- **Following in replay.** `/replay` has no live lock to hold; showing *what was followed* in a
  recorded flight needs a durable follow record, which nothing persists. Out.
- **Multi-target follow.** cv-service's `follow_top_k` ships at **2** and already tracks one extra
  object for situational awareness — but the extras are deliberately not lock candidates
  (`session.py:1591-1596`) and have no wire identity as such. One lock, one operator, one plan.
- **Auto-follow / "follow the most interesting thing".** A machine choosing the target defeats the
  entire point of the click.
- **Recording or exporting a follow session.** Belongs with events/replay.
- **Server-side crop.** F2 is client-side by construction; cropping server-side would fork the video
  path and break the MEDIA-SOT "one path, many subscribers" rule.

### 5.4 Residuals accepted up front

1. **The follow poll is one more 2 s request while a lock is held.** Accepted: it is bounded to
   exactly the window in which the answer changes, and it *replaces* the drawer-bound poll rather
   than adding to it in the common case.
2. **`REQUESTING` can be brief to the point of being unseen** on a healthy stream — the lock often
   binds within one frame. It is specified anyway, because on a loaded or L2 host it is the state
   that explains a two-second wait, and a state that only appears when things are slow is exactly
   the state worth having.
3. **A weak target's `LOST` frame can be dropped by cv-service's `_reportable` filter**
   (`servicers.py:295-314`). `FollowTracker` therefore derives `LOST` from `lockedTrackId` falling to
   zero after a bind — *not* from seeing the LOST detection — so the state is correct even when the
   frame is filtered. What is lost in that case is the final `lastBox` update, so the frozen box may
   be one verify-cadence stale. Recorded, not hidden.
4. **No live smoke exists until W7.** The same standing residual FLY-FLOW and WALL-FLOW both carry;
   §5.2's list is the gate, not `tsc` green.
5. **F2 is a crop of already-downscaled pixels.** At ×2 on a 640-px detect width the result is soft.
   The label says so; there is no honest way to make it not true.
