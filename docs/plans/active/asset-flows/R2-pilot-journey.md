# R2 — Pilot's actual journey through the UI (ASSET-FLOWS research)

Code-grounded walk of the pilot's path through `station/vision-web` (Angular 21 SPA, hub IA
Operate/Monitor/Manage superseded by five nav groups — `features/hubs/nav-entries.ts`) and
`station/vision-api` controllers. Every claim below cites a file; "friction" entries are concrete,
not speculative, except where marked **unverified**.

## §1 "New rover arrives"

Two independent paths exist, gated differently.

### 1a. Zero-config (device announces) — the fast path

| # | Step | File |
|---|---|---|
| 1 | Device announces (mDNS/similar) → surfaces as a card in "Found devices", which renders **fleet-wide, above the tab bar**, on `/assets` regardless of active tab | `features/inventory/found-devices.ts`, mounted at `inventory.html:115` |
| 2 | Click **Add** on the card | `features/inventory/found-device-card.ts` → `candidateActions()` (`core/discovery/discovery-inbox-logic.ts:114`) |
| 3 | `AddCandidateDialog` opens, name + category **prefilled** from the candidate; click **Confirm** | `features/inventory/add-candidate-dialog.ts` |
| 4 | Registers, then navigates straight to `/assets/:id` | `found-devices.ts#onRegisterSubmit` → `router.navigate(['/assets', result.assetId])` |
| 5 | On asset-detail, click **Open cockpit** | `asset-detail.html:104-116` (cockpit band) |
| 6 | Click **Start stream** | `cockpit.html:504` |

**~4 real clicks, zero protocol/URI knowledge.** A second candidate action, **Attach to
existing asset** (`AttachCandidateDialog`), lets a found device (e.g. a second camera) be bolted
onto an already-registered vehicle instead of creating a new one — device spec is built entirely
from the candidate's own suggested stream, never typed (`discovery-inbox-logic.ts#buildDeviceSpecFromCandidate`).

This path only works for hardware that implements the announce protocol (ZERO-CONFIG-ONBOARDING
Z1-Z5 scope, e.g. the project's own rover firmware — memory `rover-firmware`). Anything else falls
to 1b.

### 1b. Manual wizard (`/add-source`) — the general path

**Gated `managerOnly` in the nav + `orgGuard` on the route itself** (`onboarding.routes.ts`,
`nav-entries.ts`'s "Add vehicle" entry) — **a PILOT-role user cannot reach this route at all**,
zero-config or not. Five visible steps (`features/onboarding/onboarding-logic.ts#WizardStep`):

| Step | What it does | Knowledge required |
|---|---|---|
| **Identify** | name/category/photo/serial/make/model/registration | none |
| **Connect** | fit-out table, one row each for Sense (telemetry) / Sight (video) — `core/onboarding/fit-out-logic.ts` | per row: either a **scan** (`discover` for video, `drone` MAVLink scan for telemetry — no typing) or **manual register/listen**, which needs an exact protocol + URI (`features/onboarding/protocols.ts`, e.g. `rtsp://192.168.1.50:554/stream`, `srt://0.0.0.0:8890` + "set mode=listener" free text, `udp://0.0.0.0:14550` for MAVLink) |
| **Prove** | runs Test+Verify probes per filled row; skipped if nothing needs proving | none, but only reachable after Connect is filled correctly |
| **Register** | `POST /api/assets` with identity + N device specs | none |
| **Hand-over** | "Issue to" a custodian or "Leave in stock"; ends in a named next step, no auto-redirect | none |

A hidden sixth step, **`sysid`**, interposes only when the newly-created asset's Prove-step probe
collides with a sysid an already-registered device claims (`sysid-collision-logic.ts`); resolving
it writes a real flight-controller parameter via `POST /api/assets/{id}/parameters`
(`onboarding-store.ts:877`) — the **only** UI path in the whole app that ever writes an FC
parameter (see §6).

**Friction found**
- **F1 — manual onboarding is manager-only, no exception for a pilot's own drone.** A pilot who
  buys hardware the announce protocol doesn't cover cannot self-serve; they must find a
  manager/admin. (`onboarding.routes.ts`, `provisioning.routes.ts` — both `orgGuard`.)
- **F2 — the manual Connect step demands protocol literacy** (exact URI scheme, port, and for SRT a
  free-text `mode=listener` option) with no live URI builder — the hint text in `protocols.ts` is
  the only guidance offered, read once in a `<select>` dropdown.

## §2 "Daily flight"

| # | Step | File |
|---|---|---|
| 1 | Open app → `landingGuard` sends PILOT to `/fly` directly (ADMIN/MANAGER to `/command`) | `core/shell/landing-guard.ts`, `app.routes.ts:66` |
| 2 | `/fly` — `flyRedirectGuard` may skip the picker entirely: `?asset=` drill-down or a still-streaming remembered drone redirects straight to `/fly/:assetId` | `features/fly/fly-redirect-guard.ts` |
| 3 | Otherwise: `DronePickerPage`, cards grouped "Your vehicles"/"Simulated" (by `isSimulated`, **not** by assignment — see §6) | `features/fly/drone-picker.ts`, `drone-picker-logic.ts#groupAndSort` |
| 4 | Click a card → `/fly/:assetId`, `CockpitPage` mounts | `features/fly/fly.routes.ts` |
| 5 | Pre-arm: `PreflightChecklist` card (Video/Telemetry/GPS/Battery/Armable), shown until FC reports armed, then collapses | `shared/ui/preflight-checklist.ts`, `cockpit.html:226` |
| 6 | Open the **Controller** (`rc`) tool-rail drawer — Arm/Disarm/Mode live *inside* it, not visible by default | `features/fly/rc-monitor.ts`, `flight-command-panel.ts` |
| 7 | Arm: two-stage confirm (`warn` → `final`), reserves the app's one "undiluted danger" color | `features/fly/arm-confirm-dialog.ts` |
| 8 | Fly: video (`shared/player/player.ts`), OSD chip bar, RC/keyboard via `RcInputService`/`KeyboardRcInputService`/`ManualControlClient`, map inset, CV drawer (`cv-control-panel.ts`+`cv-setup-modal.ts`), marks drawer | all mounted inside `cockpit.html`, same route |
| 9 | Land/RTL via the same Controller drawer's mode picker / Return-home button | `shared/ui/return-home-button.ts` |
| 10 | Click **Stop stream** (confirm dialog) | `cockpit.html:497` |

**Steps 5-10 never leave `/fly/:assetId`** — every panel is a `UiStore`-managed tool-rail drawer,
state persisted to `localStorage` (`ACTIVE_PANEL_KEY = 'vision.fly.activePanel'`), and Start/Stop
is a server-side command independent of the page's lifecycle (`cockpit-facade.ts` doc comment) —
**navigating away and back does not stop a running flight or lose panel layout.** This is a real
strength, not friction.

**Friction found**
- **F3 — the readiness surfaces are a dead end relative to the cockpit.** `/operate/preflight`
  (fleet board) links to `/assets/:assetId/readiness` per row; that page has **zero `routerLink`s**
  to `/fly` anywhere (`grep` on `readiness.html` — no matches) — a pilot who checks readiness before
  flying must navigate away by hand (nav rail, or back-button) to actually fly. Contrast:
  asset-detail *does* link forward to both Readiness and Cockpit (`asset-detail.html:116,125`), but
  Readiness itself links to neither.
- **F4 — cockpit has no link back to asset management.** `cockpit.html`'s only `routerLink`s are
  `/command`, `/wall`, a replay deep-link, and `/fly` (picker) — there is no way to reach
  asset-detail (edit the asset, see full usage history, or the readiness report) from inside the
  cockpit; the relationship asset-detail → cockpit is one-directional.
- **F5 — Arm/Disarm/Mode are one drawer-open away, not visible on load** (minor): a pilot who wants
  to arm immediately must first click the Controller rail icon (`rc-monitor.ts` is "body-only,
  inside the Controller drawer").

## §3 "After the flight"

| # | Step | File |
|---|---|---|
| 1 | The moment a stream stops, cockpit's own control row shows **"Replay last flight"**, driven by `facade.latestFinishedUsageEntry()` | `cockpit.html:491-494` |
| 2 | One click → `/assets/:assetId/replay/:usageId` | `features/replay/replay.routes.ts` |
| 3 | `ReplayPage`: position map, playback scrub bar, telemetry/detections facts, plus `<vision-after-action-panel>` mounted **twice** (loaded + still-open branches, since the evidence package is servable mid-flight too) | `features/replay/replay.ts`, `after-action-panel.ts` |
| 4 | The after-action manifest reports six parts (telemetry/detections/marks/recording/passport/audit), each `PRESENT`/`TRUNCATED`/`ABSENT` with a caveat note quoted verbatim from the backend | `core/after-action/after-action-logic.ts` |

**One click from cockpit to full evidence package** — this is the best-designed leg of the whole
journey. Other entry points exist: the Monitor group's `/replay` library (`ReplayLibraryPage`),
asset-detail's own usage-history table (`asset-detail.html:303`), and every bell/toast deep-link
for a detection event or geofence breach (`?asset=&usage=&t=`, wired in `notification-bell.ts`,
`wall-facade.ts`, `alerts-facade.ts`).

**Friction found**
- **F6 — "Replay last flight" only ever points at the single most recent finished usage.** A pilot
  who wants an *older* session from the same cockpit visit has no in-page way to reach it — they
  must go to `/replay` (library) or asset-detail's usage-history table instead.
- Marks: a captured map click during flight auto-opens the Marks drawer even if it was closed
  (`cockpit.ts` constructor `effect()`), but the after-action manifest's own `marks` caveat notes
  "a mark is not bound to a flight" — the underlying data model doesn't scope marks to a usage
  window, only the manifest's query does; a mark placed just outside the reported window won't
  appear in a flight's own evidence package even though it's spatially/temporally adjacent.

## §4 "Something is wrong"

**What's surfaced, and where:**

| Signal | Surfaced in | Not surfaced in |
|---|---|---|
| Failsafe/RTH/landing | Cockpit `FailsafeBanner`, states aircraft's action never an instruction (`flight-state-logic.ts#flightBanner`) | — |
| Detection events | Bell dropdown + toasts, Wall rail, `/monitor/alerts`, fleet map markers | — |
| Geofence breach | Bell toast (`core/geofence/geofence-logic.ts`), Command zones panel | — |
| System events (`PIPELINE_ERROR`/`GEOFENCE_BREACH`=danger, `DEVICE_OFFLINE`=warn, `DEVICE_ONLINE`/`STREAM_STARTED`/`STREAM_STOPPED`/`TRAINING`=neutral) | Bell dropdown | Cockpit itself (a different feed) |
| Battery / telemetry age | Cockpit OSD chip bar (color-escalated) | — |
| Fleet-wide **attention verdict** (failsafe > battery-critical/telemetry-stale > battery-low > gps-degraded > pipeline-error, one ranked function) | Command entity rail, Inventory reports (`core/fleet/attention-logic.ts`) | **Not read anywhere in `features/fly/**`** (grep-verified) — the cockpit runs a parallel, independently-thresholded battery/telemetry check instead |
| Pre-flight checklist (Video/Telemetry/GPS/Battery/Armable) | Cockpit, pre-arm only | Fleet-wide readiness pages ask a *different* question ("does the last-observed link satisfy platform features"), advisory, never blocking |

**Real defects found (not speculative):**

- **D1 — battery-severity thresholds disagree between the pilot's own view and everyone else's.**
  Cockpit OSD (`core/telemetry/telemetry-logic.ts#batterySeverity`): critical ≤ **20%**, low ≤
  **45%**. Fleet attention used by Command/Inventory
  (`core/fleet/attention-logic.ts#batteryAttentionSeverity`): critical < **10%**, warning < **20%**.
  At 15% battery the pilot's cockpit reads **critical** (red) while a manager watching Command sees
  only **warning** for the same asset at the same instant — the two screens disagree exactly when
  urgency matters most.
- **D2 — a "grounded" maintenance record is invisible everywhere a pilot would decide to fly.**
  `features/maintenance/**`/`MaintenanceFacade` owns grounding; grep across
  `features/fly/*.ts`/`cockpit.html` and both readiness pages finds zero references to grounding or
  maintenance state. Nothing in the cockpit, the pre-arm checklist, or either readiness report warns
  a pilot that a manager has flagged this exact vehicle as grounded — Start stream and Arm both stay
  fully available.
- **D3 — no dedicated battery-low or link-failure notification.** The bell's system-event taxonomy
  (`system-events-logic.ts`) has seven kinds; none is "battery low" or "link lost" — a lost MAVLink
  heartbeat only shows up as `telemetry-stale` coloring on Command's rail or the cockpit's own OSD.
  A manager not looking at Command at that moment receives no toast, no bell entry, nothing.
- **Unverified** — no evidence either way was found for whether a cockpit session notices (live) an
  asset being archived/deleted by someone else mid-flight; `cockpit-facade.ts`'s own doc comment
  notes the richer `AssetDetails` fetch "goes stale for the length of the live connection," which
  suggests it might not re-check until the SSE connection drops, but this was not traced further.

## §5 "Handoff/crew"

- **Watch-only viewing works cleanly.** Command's asset panel has a "Watch live" action
  (`features/command/asset-panel.ts:63`) that navigates to `/fly/:assetId?watch=1`. `watch=1` is
  read by `CockpitFacade#isWatchMode`, which **hides Start/Stop and drops the pre-arm checklist**
  (`cockpit-facade.ts:296,335-340` — "hidden once watch-mode drops the controls entirely"); a second
  person gets full video/telemetry/map with zero command surface.
- **No control exclusivity or handoff exists.** `flight-command-panel-logic.ts#canShowCommandPanel`
  gates Arm/Mode purely on device *capability* and firmware/telemetry age — there is no per-user
  authority check, no "someone else is already flying this" indicator, and no takeover flow. Any
  number of signed-in users who each open `/fly/:assetId` (without `?watch=1`) get simultaneous full
  command capability over the same drone, with no UI awareness of each other. Matches memory
  (`ops-ux`): "crew control specced-only."
- **Crew page (`/manage/roster`) is static roster management, not live presence.** `features/roster/crew.ts`
  ("Crew") shows pilot↔asset assignments and org membership; it is a management surface, not a
  who's-watching-now view.

## §6 Endpoints with no cockpit/pilot UI surface

- **`GET /api/me/assignments`** (`AssignmentController`, "the assets they may fly") — the API
  client method exists (`core/api/vision-api.ts:1322` `myAssignments()`) but has **zero callers**
  anywhere in `vision-web` (grep-verified). The drone picker's "Your vehicles"/"Simulated" grouping
  (`drone-picker-logic.ts#groupAndSort`) is based on `isSimulated`, not assignment — a pilot
  assigned to exactly one drone sees the same undifferentiated list as everyone else. Cheapest fix
  in the whole survey: the backend already answers "which drones are mine," nothing in the UI reads it.
- **`POST /api/assets/{id}/parameters`** (`AssetParameterController`) — writes an FC parameter. Its
  only caller is the onboarding wizard's hidden `sysid` collision-resolution step
  (`onboarding-store.ts:877`); there is no general "tune a flight-controller parameter" surface
  anywhere a pilot or manager would look (asset-detail, cockpit, readiness).
- **`GET /api/system/network`** (`SystemNetworkController`) — used only inside the onboarding wizard
  (to prefill the authoritative MAVLink port); no standalone network-diagnostics page reads it, even
  though `/manage/system` (System status) exists as a natural home.
- Everything else checked (geofence CRUD, control-profiles, camera-pose/calibration, CV
  trackers/models, map tracks, asset stats/session/image, inventory export, usage timeline) has a
  confirmed frontend caller.

## §7 Ranked friction list

**High impact**
1. **D2 — grounded vehicles are flyable with zero warning** (§4). A maintenance record meant to
   stop flight has no presence in the one place a pilot decides to fly. Safety-relevant, silent.
2. **D1 — battery-critical thresholds disagree between cockpit and fleet views** (§4). Same reading,
   two different urgency verdicts depending on which screen you're looking at.
3. **F1 — pilots cannot onboard their own drone outside the zero-config path** (§1). Hard
   `managerOnly` wall on `/add-source`/`/provision-wifi`, no exception, no self-serve fallback.

**Medium impact**
4. **F3 — readiness pages are a dead end back to the cockpit** (§2). Forces a manual detour exactly
   at the pre-flight moment the page exists to serve.
5. **D3 — no dedicated low-battery/link-failure notification** (§4). Relies entirely on someone
   already watching the right screen at the right time.
6. **§6 — `GET /api/me/assignments` unused** — cheapest available fix; would let the picker actually
   answer "which drone is mine" instead of showing everyone the same flat list.
7. **§5 — no control exclusivity/handoff** — two operators can both command the same drone
   unknowingly; specced but not built per memory (`ops-ux`).

**Low impact**
8. **F4 — cockpit has no link back to asset management** (§2). Minor asymmetry, workaround exists
   (nav rail, browser back).
9. **F2 — manual Connect step needs protocol/URI literacy** (§1). Real friction but scan-based
   finders cover the common cases; only the escape-hatch path is hard.
10. **F6 — "Replay last flight" only surfaces the latest session** (§3). Library/usage-history table
    are one extra click away.
11. **F5 — Arm/Disarm hidden behind the Controller drawer** (§2). One extra click, arguably correct
    given Arm's deliberate two-stage friction design.
