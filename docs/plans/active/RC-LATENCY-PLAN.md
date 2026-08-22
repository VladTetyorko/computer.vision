# RC-LATENCY — the stick reaches the wire when it moves, and the rate it reports is the rate it sends

**Branch:** `feat/rc-latency` (off `master`) · **Status:** active · **Supersedes one rough edge in**
[RC-CONTROL-PHASE1-PLAN.md](../done/RC-CONTROL-PHASE1-PLAN.md) §3/§4

## 1. Why

Phase 1 shipped the relay with three independent fixed-rate clocks between the stick and the wire,
none of them coupled to the moment new input actually arrives, plus an `engaged.rateHz` that was a
constant mirrored by hand rather than the rate the adapter actually sends at.

### 1.1 Measured budget (before)

| Stage | Mechanism | mean / worst |
|---|---|---|
| Gamepad → signal | `rc-input.service.ts` rAF loop | 8.3 / 16.7 ms |
| Signal → WS frame | `setInterval(sendIntervalMs(33))`, free-running | 15 / 30 ms |
| WS → mailbox | handler → session → port | ~1 ms |
| Mailbox → wire | `RcLinkRuntime.tick()` @30 ms, free-running | 15 / 30 ms |
| **total** | | **≈ 39 / 78 ms** |

The two 15/30 rows are pure quantization. A frame landing 1 ms after a tick waits a full period for
a clock that had no reason to be where it was.

### 1.2 The knob that is not the answer

Raising `vision.rc.override-hz` 33 → 50 buys ~10 ms of the ~39 and costs 50% more UDP frames
*continuously*, including for a parked stick. It leaves all three clocks uncoupled. Rejected.

## 2. Design

### A. Send-on-arrival, coalesced at the ceiling (`mavlink-core`)

`RcLinkRuntime` gains a transmit-on-write path. `setChannels` computes the elapsed time since the
last transmit; if it is at least the **coalescing period** (`1/maxOverrideHz`, 20 ms by default) it
schedules an immediate one-shot transmit, otherwise the newest value simply sits in the mailbox and
the next tick carries it — the pre-existing behaviour, now the fallback rather than the rule.

The periodic tick is demoted from *the* send loop to a **keepalive**: it still fires every
`1/clampedOverrideHz` (30 ms) and still transmits, so an aircraft never sees a gap that could expire
its own override timeout, and the release burst keeps working unchanged.

Two rates, previously conflated in one number:

| | property | default | role |
|---|---|---|---|
| `overrideHz` | keepalive / floor | 33 Hz | an unchanged stick still transmits this often |
| `maxOverrideHz` | ceiling | 50 Hz | never transmit faster than this, however fast input arrives |

Client frames arrive ~30 ms apart, which exceeds the 20 ms ceiling, so in practice **every frame
transmits on arrival** and the mailbox wait goes to ~0.

`TxScheduler` has only `repeat`; A needs a one-shot, so it gains
`void submit(String name, Runnable task)` — same pool, same catch-and-log contract.

### B. Couple the client send to the sampling loop (`vision-web`)

`ManualControlClient` drops its independent `setInterval` and sends from an `effect()` on
`RcInputService`'s rAF-updated `axes`/`buttons` signals. A byte-identical frame is skipped unless
the keepalive interval (`1/rateHz`, 30 ms) has elapsed — so a parked stick still feeds the 300 ms
watchdog with 10× margin, and a moving stick reaches the socket in the same frame it was sampled.

The two client-side quantizations collapse into one; only the rAF half-frame remains.

### C. `rateHz` read live (`vision-flight` → `vision-api`)

`ManualControlEngagedFrame.DEFAULT_RATE_HZ = 33` was a hand-mirrored constant because vision-api may
not depend on adapter-mavlink. The seam that removes it without a new dependency is the port itself:

```
MavlinkManualControlSender.AdapterLink#rateHz()   coreRc.clampedOverrideHz() -- the real number
  -> ManualControlLink#rateHz()                   vision-flight domain port
  -> ManualControlSession#rateHz()                vision-flight application
  -> ManualControlWebSocketHandler                emits it in the engaged frame
```

The wire shape stays frozen: still one `rateHz` field on `engaged`, now meaning the keepalive
cadence, now true.

### 2.1 Budget (after)

| Stage | mean / worst |
|---|---|
| Gamepad → signal → WS frame (one rAF quantum) | 8.3 / 16.7 ms |
| WS → mailbox | ~1 ms |
| Mailbox → wire (transmit on arrival) | ~0 |
| **total** | **≈ 9 / 18 ms** |

## 3. Safety invariants preserved

- Release burst: unchanged — `release()` still writes the sentinel, sleeps `releaseFrames *
  tickPeriod`, then stops the task. Coalescing never short-circuits it; `setChannels` still no-ops
  once releasing.
- Watchdog: unchanged at 300 ms. B's keepalive guarantees a `channels` frame every ~30 ms even from
  a motionless stick, so the watchdog's meaning ("input loss") is not quietly redefined into
  "the operator stopped moving".
- Reachability: unchanged — engage still fails fast when no source address has been heard.
- v1 scope: unchanged — channels 1..8, 9..18 forced IGNORE.
- Wire ceiling: a moving stick now transmits at up to 50 Hz where it used to be pinned at 33 Hz.
  Within what ArduPilot's RC path expects; `maxOverrideHz` is the operator's cap.

## 4. Waves (disjoint file scopes)

| Wave | Module | Files |
|---|---|---|
| L1 | `drone-link/mavlink-core` | `TxScheduler`, `DefaultTxScheduler`, `ManualControlService` + tests |
| L2 | `contexts/vision-flight` | `ManualControlLink`, `ManualControlSession`, `DefaultManualControlService` + fakes/tests |
| L3 | `drone-link/mavlink` | `MavlinkManualControlSender` + tests |
| L4 | `station/vision-api` | `ManualControlEngagedFrame`, `ManualControlWebSocketHandler` + tests |
| L5 | `station/vision-web` | `manual-control-logic.ts`, `manual-control-client.ts` + specs |
| L6 | docs/config | `application.yaml` comments, 4× MODULE.md |

## 5. Verification

Per-module scoped builds (`./mvnw -B -pl <path> -am test`) plus `npm test` for L5. The end-to-end
number is a **SITL measurement the operator runs** — RC-CONTROL-PHASE1-PLAN §"SITL verification
steps 6-7" is still the instrument, and Phase 2 (real vehicle) stays gated on explicit user go.
This plan changes the budget's structure; it does not claim a measured glass-to-stick figure.
