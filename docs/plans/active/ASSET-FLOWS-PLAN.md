# ASSET-FLOWS-PLAN — cycle 1 (tier 1 safety + tier 2 quick wins)

**Status:** BUILT + MERGED to master 2026-09-01 · **Branch:** `feat/asset-flows-1` · **Owner picked:** tier 1+2,
decisions per Fable recommendation (see P-PROPOSAL §Decided).
**Reads with:** [asset-flows/P-PROPOSAL.md](asset-flows/P-PROPOSAL.md) (ranking + decisions),
[asset-flows/O1-SYNTHESIS.md](asset-flows/O1-SYNTHESIS.md) (evidence + dependency constraints).

## 1. Scope

Build: S1, S3, S4, S6 (safety) + B4, B2, D1p (pilot on AssetUsage), D3r (replay picker),
A3, C4 (verify+merge `feat/controller-setup-c15`), C5. Nothing else — S5/B3, CREW-CONTROL,
missions-tasking are later cycles.

## 2. Frozen contracts (cross-wave; do not renegotiate inside a wave)

- **Battery thresholds (D6):** properties `vision.ops.battery.warning-percent` (default **25**)
  and `vision.ops.battery.critical-percent` (default **10**), documented in root
  `application.yaml` (BK3 owns the yaml edit this cycle — no other wave edits it except BK7's
  own `vision.media.*` block). Exposed as `GET /api/ops/thresholds` →
  `{"battery":{"warningPercent":25,"criticalPercent":10}}`. These become the ONE severity
  source: cockpit OSD (was 20/45) and fleet attention-logic (was 20/10) both consume them.
- **New system-event kinds (S4):** `LINK_LOST` and `BATTERY_LOW`, entering the existing bell
  taxonomy with the same envelope as current kinds (assetId + human summary). `LINK_LOST` is
  produced by consuming the already-merged FLEET-RADIO typed link-failure event (carries
  `PeerId`); `BATTERY_LOW` is a rising-edge event on crossing critical-percent (edge, not level
  — no spam; re-arms when back above warning).
- **S1 gate semantics:** an asset with custody grounding or an open flight-blocking
  `MaintenanceRecord` (`GROUNDING`/`INSPECTION_DUE`, mirroring `DefaultReadinessService`
  `MAINTENANCE_BLOCKER_PREFIX` logic) refuses **arm** and **usage/session-open**. Refusal idiom:
  existing `IllegalStateException`/409 + audited `REFUSED:maintenance-grounded`, exactly like
  `DefaultManualControlService#engage` already does. **Never gated:** disarm, emergency-stop,
  RTH, mode — energy-reducing/recovery verbs must always work on a vehicle that is somehow
  already moving. Web surfaces grounding from data it already receives
  (`MAINTENANCE_GROUNDED:` blockers pass through the readiness wire verbatim today).
- **`pilot` on AssetUsage (D1p):** nullable `pilot_id` UUID column (Flyway) on `asset_usages`,
  `AssetUsage` gains the field per repo record idiom, populated at session open wherever the
  acting user is known (engage / stream start), surfaced read-only in usage/replay API
  responses. CREW-CONTROL later extends with PIC/OBSERVER — plain field now.
- **A3 scanner health:** the found-devices/inbox API response gains
  `sources: [{id, status: "OK"|"UNREACHABLE"}]` (per finder: mediamtx push registry, lobby,
  ONVIF/mDNS/V4L2); web renders "mediamtx unreachable" instead of an ambiguous empty list.
- **S6 auth model:** mediamtx gets credentials — **read auth on all paths** (one viewer
  account used by the HLS/WHEP playback path and any external player), **publish auth on all
  paths except the `ingest/` prefix** (the zero-config funnel stays open-publish by design —
  candidates are quarantined until an operator accepts them; documented as such). Credentials
  live in `docker-compose.yml` env + `vision.media.auth.*` properties; `adapter-publish-hls`
  and the playback proxy authenticate. Existing bookmarked raw URLs break — release note.

## 3. Waves

| Wave | Agent | Item | Scope (disjoint) | Gate |
|---|---|---|---|---|
| C4 | verify (worktree) | controller-setup-c15 green? | read-only verification, report to orchestrator; Fable merges | `npm run test:ci` + scoped mvn |
| BK1 | application-service | S1 gate | `contexts/vision-flight` (FlightCommandService arm + tests), `contexts/vision-perception` (UsageTracker session-open gate), `station/vision-app` wiring/tests | `-pl` each touched module `-am test` |
| BK2 | application-service | S4 events | event taxonomy module + producers (flight telemetry battery edge; link-failure consumer), vision-app wiring. **No application.yaml edits** | scoped tests |
| BK3 | spring-integrator | D6+S3 backend | root `application.yaml` (`vision.ops.*`), properties record, `GET /api/ops/thresholds` in `station/vision-api` | vision-api/app scoped tests |
| BK5 | adapter-builder | C5 supports() | `drone-link/mavlink` only | `-pl drone-link/mavlink -am test` |
| BK6 | adapter-builder | A3 health | `device-discovery/onvif-mdns-v4l2` + the discovery/inbox DTO path | scoped tests |
| BK4 | spring-integrator | D1p pilot | **after BK1** (shares UsageTracker/session-open): warehouse domain field, Flyway, persistence, api read surface | scoped tests |
| BK7 | adapter-builder | S6 mediamtx | **after BK3** (shares application.yaml): mediamtx config, docker-compose, `video-output/publish-hls`, playback proxy creds | scoped + docker ITs where present |
| WB1 | web-ui | S1 surface + S3 cockpit + B2 | `station/vision-web` cockpit/fly + readiness pages: grounded banner + arm-blocked reason, OSD severity from `/api/ops/thresholds`, two-way nav links | `npm run test:ci` |
| WB2 | web-ui | S4 bell + B4 + D3r + A3 UI | `station/vision-web` non-cockpit: bell kinds, picker-by-assignment, replay recent-usages picker, inbox health hint, attention-logic threshold consumption | `npm run test:ci` |

WB1 ∥ WB2 (disjoint web files), both **after** their backend contracts are committed and after
C4's merge reaches this branch (controller-setup touches vision-web).

## 4. Repo rules that bind every wave

Foreground builds only (backgrounded builds die with the agent's turn) · scoped `-pl <module>
-am` · `git add` only your scoped paths · update each touched module's MODULE.md in-wave ·
no new constructor overloads — update call sites or bundle a settings record (CLAUDE.md §10) ·
vision-web tests via `npm run test:ci`, never bare vitest · commit your wave with a descriptive
message when your gate is green.

## 5. Out of scope, explicitly

Gating disarm/e-stop/RTH/mode (see §2) · CREW-CONTROL claim arbitration (next plan) ·
S5 live-telemetry readiness / B3 checklist · missions tasking (decision recorded, unscheduled) ·
probe.enabled flip (needs its own verification wave) · pilot self-onboard route change.

## Close-out (2026-09-01)

All waves green and merged; final web gate on the combined tree 174 files / 3411 tests.

| Wave | Commit | Result |
|---|---|---|
| BK1 | `cb565688` | arm + engage refuse on grounding (recovery verbs proven ungated); perception gates engage only (pipeline-leak rationale in MODULE.md) |
| BK2+BK2b | `1e41432b`+`18911098` | LINK_LOST/BATTERY_LOW kinds + producers; link-loss wired to SupervisedPublisher outage edge (exactly one event per outage, tested) |
| BK3 | `36ecbbd0` | `vision.ops.battery.*` (25/10) + `GET /api/ops/thresholds` |
| BK4 | `c6f29b7b` | `AssetUsage.pilotId` (V28 column already existed — no new migration); engage populates, promote backfills, device-push stays null |
| BK5 | `c9f954f4` | firmware-honest `supports()`; Betaflight decoy can no longer shadow ArduPilot; +`5d52f80c` doc fix (P1-era gotcha closed by P4) |
| BK6 | `a79bc222` | inbox response `{candidates, sources[]}` — UNREACHABLE vs empty |
| BK7 | `ddfd572d` | mediamtx viewer/publisher accounts, `ingest/` tilde-regex exception, creds through push/WHEP/HLS-proxy, real-container IT |
| C4/C4b | `34e298d0` on master | c15 reconciled (21 conflicts, V25→V32) + merged; unblocks FLEET-RADIO R2/R3 web halves |
| WB1 | `3176d67a` | grounded banner + arm reason + readiness nav both ways + preflight maintenance cell |
| WB2 | `2afedf2b` | one severity source consumed by OSD+attention, bell kinds, assignment picker, replay list, inbox warnings |

Residuals, deliberate: Command/Inventory attention consumers use the same default thresholds but aren't
wired to the live store yet (named in vision-web MODULE.md) · mediamtx yaml/env creds are two sources
of truth the operator must change together (`.env.example` documents it) · evidence-package DTO not
widened with pilotId. Shared-tree lesson recorded: three concurrent-staging races this cycle — next
cycle, waves sharing a folder get worktree isolation or strict serialization.
