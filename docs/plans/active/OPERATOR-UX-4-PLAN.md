# OPERATOR-UX-4 — Monitor & Manage honesty pass

Fourth cycle of the operator-UX series (after CONTROLLER-UX cycles 1–2 and OPERATOR-UX-3). Found by a live Chrome walkthrough of the running app on 2026-08-29 (17 assets, three real ESP32 rovers, all offline). Same rule as cycle 3: the UI never impersonates data it does not have.

## 1. Findings

| # | Where | What the operator sees | Why it is wrong |
|---|---|---|---|
| N1 | `/command` (select a rover), `/assets/:id` | Map recentres on open ocean off West Africa; position card reads `0.00000, 0.00000` | `lastKnownPosition` is `{0,0,0}` — a MAVLink `GLOBAL_POSITION_INT` with no GPS fix. Null Island is "no fix", not a place. Web renders it as a fix; the adapter records it as one |
| N2 | `/command` rail vs. its detail panel | Rail chip `CRIT` on "ESP32 Rover (paired)"; panel says "All quiet — nothing needs attention" | Two derivations of the same attention reasons disagree (the rail's `gps-degraded` from the fleet marker, the panel's from `selectedMarker()`). One asset, one verdict |
| N3 | `/command` rail | 17 alphabetical rows, real rovers mixed with 14 simulated, two rows both named "ESP32 Rover" | Same triage failure OPERATOR-UX-3 T1 fixed on `/fly`; the Command rail did not get it |
| N4 | `/assets/:id` sample, `/monitor/alerts` rows, `/command` age label | `323353s ago`, `170h 20m ago`, `230h 05m ago` | Three more raw-duration formatters. `core/telemetry/telemetry-logic.ts#humanAge` (cycle 3) is the one age vocabulary |
| N5 | `/monitor/alerts` | Every row's source is a hash `7fd88790`; the asset filter offers hashes | Events reference devices that no longer exist; `describeEventSource` falls back to a truncated id with no word saying so |
| N6 | `/operate/missions`, `/monitor/layouts`, `/manage/health` | Blank page | `ComingSoon` renders its card (DOM present, 1445×293 at y=0) but it is not visible — it sits under the top bar / has no page padding. Also an inline-template component, against the three-file rule |

## 2. Design

**N1 — a fix is a fix.** `core/geo/geo-logic.ts#hasFix(position)` — false for `undefined`, non-finite, or exactly `(0,0)` (Null Island is not a legal vehicle position for this product). `map-logic.ts#bucketForAsset`/`buildEntityRows` treat a no-fix `lastKnownPosition` as `no position`; the asset page's position card shows `No GPS fix yet` (structural label register, no map recentre); the cockpit not-streaming card omits its position line. Backend, adapter-mavlink: `PositionAndPowerState` records a position only when the vehicle's GPS fix type is ≥ 2D (`GPS_RAW_INT.fix_type`), else the sample carries no lat/lon — so no future `{0,0}` is ever persisted. Existing rows stay as they are; the web predicate covers them.

**N2 — one attention verdict per asset.** `CommandFacade` computes `attentionRows` once (asset × marker → reasons, severity); the rail row and `AssetPanel` both read that row — the panel gets `[reasons]` as an input instead of re-deriving. Rule kept from `attention-logic.ts`: `gps-degraded` and `telemetry-stale` are *live-only* reasons — an offline asset is never CRIT for a fix it is not trying to get; it is just offline, with its age.

**N3 — the rail triages like `/fly`.** Move `groupAndSort`, `isSimulated`, `offlineLabel` from `features/fly/drone-picker-logic.ts` to `core/fleet/triage-logic.ts` (fly imports from there; no behaviour change on `/fly`). Rail: group headers **Your vehicles (3)** / **Simulated (14)** in the structural-label register, streaming → needs-attention → last seen desc, the same `Hide simulated` flag (`vision.fly.hideSimulated` — one preference, both pages). The row's right-hand text becomes the age chip (`Offline · 3d`) when offline; category stays as the muted word.

**N4 — one age vocabulary.** `relativeTimeLabel` (events-logic), `attentionAgeLabel` (command-logic) and the asset page's `…s ago` all render `humanAge(seconds) + ' ago'`. `formatDuration` stays for *durations* (session length), never for ages.

**N5 — say what the source is.** `describeEventSource` returns `Removed device · 7fd88790` when the device is not in the fleet, and the asset filter lists the same text. No fabricated names.

**N6 — coming-soon pages are visible.** Split `ComingSoon` into `.ts/.html/.css`, render inside the standard page frame (`vision-page-bar` title + `vision-empty`-style card) so it gets the same padding and stacking as every other page; spec asserts the card is in the layout flow below the page bar.

## 3. Waves (disjoint files)

| Wave | Agent | Files |
|---|---|---|
| **W1 geo + age + alerts** | web-ui | `core/geo/geo-logic.ts` (+spec), `core/map/map-logic.ts` (+spec), `core/events/events-logic.ts` (+spec), `features/asset-detail/**`, `features/alerts/**`, `features/fly/cockpit-facade.ts` + `cockpit.html` (position line only) |
| **W2 command rail** | web-ui | `core/fleet/**` (new `triage-logic.ts` + attention-logic), `features/command/**`, `features/fly/drone-picker-logic.ts` + `drone-picker-facade.ts` (re-export/import only) |
| **W3 coming-soon** | web-ui | `features/hubs/coming-soon.*` (+spec), `hubs.routes.ts` if needed |
| **W4 no-fix ingest** | adapter-builder | `drone-link/mavlink/**` (`PositionAndPowerState`, its tests), `drone-link/mavlink/MODULE.md` |

Every wave: tsc both configs, `npm run test:ci` (W1–W3) / `./mvnw -B -pl drone-link/mavlink test` (W4), prod build, MODULE.md, own files only.

Status: W1–W4 open.
