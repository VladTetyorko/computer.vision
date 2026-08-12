# MEDIA-SOT — execution context

Working ledger for [MEDIA-SOT-PLAN.md](MEDIA-SOT-PLAN.md). The plan is the spec; this file
is state. Update the status column as waves land; keep decisions that the plan did not
foresee in §3 so the next agent (or the next context window) inherits them.

**Branch:** `feat/media-sot`, cut from `feat/cv-rate-control` at `610b114`.
Waves commit onto this branch with disjoint file scopes. One commit per wave.

## 1. Wave status

| Wave | Scope | Depends on | Status |
|---|---|---|---|
| M0 | `cv-service/spikes/pull/` — measure, decide decoder + clock mode | — | **done — GO** |
| M1 | `proto/vision/v1/cv.proto` + `vision-proto` | — | **done** |
| M2 | `vision-domain` — `PulledDetectionPort`, `proxiesSource` | — | **done** |
| M3 | `cv-service/cv_service/pull/` — the worker | M0, M1 | blocked |
| M4 | `adapters/adapter-cv-grpc` — Java pull port | M1, M2 | blocked |
| M5 | `vision-application` + `vision-api` — pull-mode pipeline | M2, M4 | blocked |
| M6 | `adapters/adapter-publish-hls` — proxy publisher + frame grab | M2 | blocked |
| M7 | `vision-app` + `docker-compose.yml` — wiring, flags | M5, M6 | blocked |
| M8 | `vision-web` — client overlay truth + WHEP box timing | M1 | **done** |
| M9 | `docs/` — measure, amend CV-SCALE §S5 | M7, M8 | blocked |

## 2. Gate

**M0 is a gate, not a formality.** A NO-GO on decode cost or clock drift stops M3/M5/M6;
M1/M2/M8 are safe either way. Nothing downstream of M0 may assume a decoder or a clock
mode that M0 did not measure.

## 3. Decisions taken during execution (not in the plan)

- **`burnedIn` on `StartStreamResponse`/`ActiveStreamResponse` is M5's**, not M7's or M8's.
  §5.4 froze the field but §8 assigned no owner; M5 already owns the `vision-api` DTOs.
  M8 therefore treats an absent `burnedIn` as `true` — today's behaviour, per D1.

## 4. Working rules for wave agents

- Read the module's `MODULE.md` and its direct dependencies' before touching code; update
  it in the same wave. Do not re-read sources for surface the doc already gives.
- Scoped builds only (`-pl <module>`). Never a reactor-wide build — other waves run
  concurrently and may hold their modules red.
- Stay inside your wave's file scope. Scopes are disjoint by design; crossing one is how
  two agents lose each other's work.
- Do not commit. The orchestrator commits per wave, by pathspec.
