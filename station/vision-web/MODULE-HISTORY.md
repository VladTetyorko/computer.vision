# vision-web — history

Wave-by-wave narrative for `vision-web`: what each plan wave changed, with its test counts, bundle
deltas and the deliberation behind decisions that are now just current behaviour.
**`MODULE.md` is the contract — read that first, every time.** Come here only to learn *why* the
contract looks the way it does. Oldest entry first (the order it accumulated in).

- **2026-08-26, docs/plans/active/CONTROLLER-UX-PLAN.md wave X1:** `shared/ui/transmitter-view/` + `shared/ui/switch-gauge/` + `core/rc/transmitter-view-logic.ts` built and tested, but **not yet wired into any page** — no import exists outside their own files, so the production bundle is unaffected (delta 0 kB) until wave X2 (`rc-monitor` rebuild, also retires `features/fly/virtual-control-surface.*`) and wave X4 (`controller-setup`'s review step) consume them.
- **2026-08-26, docs/plans/active/CONTROLLER-UX-PLAN.md wave X2:** `features/fly/rc-monitor.*` rebuilt around `TransmitterView` (first real consumer, alongside `SidePanel`'s previously-unused `[footer]` slot); `flight-command-panel` gained `modeAlsoOn`/`armAlsoOn` inputs; `virtual-control-surface.*` deleted. `tsc --noEmit` clean on both configs; `npm run test:ci` green (144/144 files, 2645/2645 tests). Production build re-measured as part of wave X4 below, once the two waves' overlapping edits to `features/controller/**` both landed.
- **2026-08-26, docs/plans/active/CONTROLLER-UX-PLAN.md wave X4:** `/manage/controller` rebuilt as the step-by-step wizard described under `features/**` → `controller/` above (`StepRail`, `WizardStep`, `AllControls`, `ControllerSetupFacade#replaceDraft`). `tsc --noEmit` clean on both configs; `npm run test:ci` green (145/145 files, 2649/2649 tests, incl. 4 new `step-rail.spec.ts` + 4 new `wizard-step.spec.ts` TestBed specs covering rover/copter step lists, detection→defaults preselection, Next writing into the draft, the no-gamepad manual picker, and U12 built-in read-only). `ng build --configuration production` green; `controller-setup` lazy chunk grew from wave X2's own baseline (27.58 kB raw / 7.12 kB transfer — `TransmitterView`/`SwitchGauge` were not yet wired into this page) to 60.75 kB raw / 14.21 kB transfer (**+33.17 kB raw / +7.09 kB transfer**) now that the wizard's review step actually renders `TransmitterView`; initial bundle unchanged (410.19 kB, same pre-existing over-budget warning noted above). Not built at the time: a real mode-name source for the Mode step's datalist (free text still worked) — closed by wave M below. The manual picker's 8-axis/16-button range is still a UI convenience, not read from any device capability.
- **2026-08-28, docs/plans/active/CONTROLLER-UX-PLAN.md §5 wave M:** the Mode step's datalist gained a real source — `ControllerSetupFacade#knownModeNames` + `core/rc/controller-wizard-logic.ts#modeNamesFor`/`#modeSourceHint`, wired into `wizard-step.*` (see the `controller/` bullets above for the read path and copy). Free text stays the fallback everywhere; nothing here changes what a saved layout can express, only what the datalist suggests. `tsc --noEmit` clean on both configs; `npm run test:ci` green (146/146 files, 2689/2689 tests — 3 new `controller-wizard-logic.spec.ts` cases for the two pure functions, 4 new `wizard-step.spec.ts` TestBed cases for the populated datalist, the honest zero-names hint, the quiet unknown-name note, and its absence once the typed value matches). `ng build --configuration production` green, same two pre-existing budget warnings only; `controller-setup` lazy chunk 60.75 kB → 62.89 kB raw (+2.14 kB), 14.21 kB → 14.83 kB transfer (+0.62 kB); initial bundle 410.19 kB → 410.29 kB (+0.10 kB, from the wizard-step template's new hint/note markup — `KnownModeNames` itself is a type, erased at build time). No new endpoint: `loadKnownModeCapabilities()` reuses `VisionApi#flightCapabilities`, the same call `cockpit-facade.ts` already made, just fanned out over every currently-`STREAMING` asset instead of the one asset a cockpit has open.
- **2026-08-28, docs/plans/active/CONTROLLER-UX-PLAN.md §5 waves K+R:** `core/rc/keyboard-rc-input.service.ts` (new, `KeyboardRcInputService`) is a third per-host `RcSourceKind`; `rc-monitor.ts`/`.html`/`.css` gained the Keyboard segmented button, the key-legend line, and the readiness rows under Take control (see the `rc-monitor` bullets above for both waves' full detail). `RcSource#keyboard` is `{ optional: true }`-injected (Gotchas entry above). `tsc --noEmit` clean on both configs; `npm run test:ci` green (147/147 files, 2730/2730 tests — 17 new `keyboard-rc-input.service.spec.ts` cases, 4 new `rc-source.service.spec.ts` keyboard-source cases, 10 new `rc-monitor-logic.spec.ts` cases for `keyLegendLines`/`rcReadinessRows`/`engageBlock`, 7 new `rc-monitor.spec.ts` TestBed cases for the Keyboard button, the key legend, and the readiness rows' priority/degrade behavior). `ng build --configuration production` green, same two pre-existing budget warnings only; initial bundle 410.29 kB → 411.96 kB raw (+1.67 kB), 115.28 kB → 115.65 kB transfer (+0.37 kB); `cockpit` lazy chunk (the one that pulls `rc-monitor` in) 135.48 kB → 141.86 kB raw (+6.38 kB), 29.68 kB → 31.23 kB transfer (+1.55 kB). No new endpoint: `assetReadiness` reuses the same `VisionApi` call `readiness-facade.ts` already makes. Not built: wave M's own doc note already covers the Mode step; this pair leaves it untouched.
- **2026-08-28, docs/plans/active/OPERATOR-UX-3-PLAN.md finding P1 + §2 P1, wave P1:** `/operate/preflight`'s "needs attention" cell now names the **first blocking check's own label plus one `+N` chip** (new `features/preflight/preflight-logic.ts`: `blockerSummary`/`sortWorstFirst`/`applyVerdictFilter`/`emptyFilterTitle`), replacing the old comma-joined `readiness-logic.ts#fleetRowAttention` sentence for this page — frontend-style §5's "one chip per row max" (the verdict chip is the row's other one). Kept feature-local rather than added to `core/readiness/readiness-logic.ts` per this wave's own file scope (`fleetRowAttention`/`sortReadinessRows` there are untouched and now unused outside their own spec — a future second consumer still has them). Rows sort worst-first: `NO_GO`, then `UNKNOWN` ordered by most failing checks first, then `GO`, ties by name (`sortWorstFirst`, replacing `sortReadinessRows`'s plain verdict-then-name order for this page). The three verdict stat cards are now the table's own filter: `PreflightFacade#verdictFilter` is a bare signal *in the facade* (the same shape `alerts-facade.ts`'s `labelFilter`/`assetFilter` already use — a filter selection isn't a mutually-exclusive overlay, so it doesn't belong in a `UiStore` group, and `core/ui/architecture.spec.ts` only forbids an `Open`/`Menu`/`Confirm`/`Editing`-named bare signal on the routed page component itself); clicking a card selects it (this app's one selection language — 2px `--color-info` inset bar + `--color-info-soft` tint — applied by wrapping the shared, otherwise-unmodified `vision-stat` in a plain `<button>` and reaching its encapsulated `.stat-tile` via `:host ::ng-deep`, the same technique `cockpit.css` already uses for `vision-player`'s `.frame`), clicking again clears; keyboard-operable via the real `<button>` (global `:focus-visible`, `aria-pressed`). A filter matching zero rows renders `vision-empty` with a per-verdict title (`NO_GO`'s is the plan's own copy verbatim) plus a "Clear filter" action; the pre-existing "no assets at all" empty state is unchanged. `tsc --noEmit` clean on both configs; `npm run test:ci` green (151/151 files, 2787/2787 tests, including this wave's 21 new cases — 14 `preflight-logic.spec.ts` + 7 `preflight.spec.ts` TestBed cases for the sort/filter/chip/empty-state wiring, the first component-level spec this page has had). `ng build --configuration production` green, same two pre-existing budget warnings only; `preflight` lazy chunk 4.59 kB → 7.69 kB raw (+3.10 kB), 1.83 kB → 2.66 kB transfer (+0.83 kB); initial bundle unchanged (412.34 kB — this is a lazy route chunk). No new endpoint: still `GET /api/fleet/readiness` via `VisionApi#fleetReadiness`, unchanged.
- **2026-08-28, docs/plans/active/OPERATOR-UX-3-PLAN.md finding T1 + §2 T1, wave T1:** `/fly` grouped into **Your vehicles** / **Simulated** with a persisted **Hide simulated** toggle and honest per-card offline ages, replacing 17 identical "Offline" cards in no order — see the `fly/` → `DronePickerPage`/`DronePickerFacade` bullet above for the full detail (`drone-picker-logic.ts` new: `isSimulated`/`groupAndSort`/`offlineLabel`/`humanAge`; `DronePickerCard` new, three files; `drone-picker-facade.ts` gained `groups`/`totalPickerAssets`/`hideSimulated`/`toggleHideSimulated`, dropped the flat `orderedPickerAssets`). **Built on a shared working tree with two concurrent waves** (H1 on `core/telemetry/telemetry-logic.ts`/`fly-osd`/`rc-monitor`, P1 on `features/preflight/**`, both mid-flight, uncommitted) — `npx tsc --noEmit` reported one pre-existing error in `features/fly/rc-monitor.ts` (a signature mismatch against H1's own in-progress `telemetry-logic.ts` edit, not this wave's files — every `drone-picker*`/`drone-picker-card*` file compiles clean on both configs) that did not block `npm run test:ci` (esbuild's isolated-module transform, not full type-checking) or `ng build --configuration production` (green both times this wave's own build was run). `npm run test:ci` green on the shared tree: 152/152 files, 2804/2804 tests — this wave's own share is 2 new spec files (`drone-picker-logic.spec.ts`, `drone-picker.spec.ts`) totalling 28 new cases (19 + 9) covering grouping/sort order/never-seen-last/tie-breaks, `humanAge`/`offlineLabel` tiers, and the page's group headers/toggle-collapse/toggle-persistence/empty-leg/chip-text wiring. `ng build --configuration production` green, same two pre-existing budget warnings only; isolated via `git stash push -u` on just this wave's own files, the `drone-picker` lazy chunk measured 6.20 kB → 9.52 kB raw (**+3.32 kB**), 2.21 kB → 3.07 kB transfer (**+0.86 kB**); initial bundle unchanged (412.34 kB, 115.34 kB transfer — everything here is lazy). No new endpoint: still `VisionApi#listAssets`, unchanged.
  - **`isSimulated` reads `AssetSummary.category`, not a device-level fact** — `AssetSummary` (this picker's own `listAssets()` shape) still has no `devices` field at all (only `AssetDetails` does), so a category check is the only signal available today; `SOURCE-ONBOARDING-CONTEXT.md` §4 tracks moving "simulated" to a device-level fact, at which point this function should read that instead.
- **2026-08-28, docs/plans/active/OPERATOR-UX-3-PLAN.md finding H1 + §2 H1, wave H1:** stale telemetry no longer reads as live anywhere in the cockpit — see the `fly/` → "Stale telemetry never impersonates live" bullet above for the full per-surface detail (`core/telemetry/telemetry-logic.ts#freshness`/`humanAge`, new `features/fly/fly-osd-logic.ts`, `rc-monitor-logic.ts#armedChip`/`sampleIsStale`, the cockpit's new not-streaming card). **Built on the same shared working tree as concurrent waves T1 (`features/fly/drone-picker*`) and P1 (`features/preflight/**`)** — disjoint file lists, no conflicts; T1's `drone-picker-logic.ts` briefly carried its own `humanAge` — folded into this one function post-wave (zero remainders dropped: `4h`, `6d`, never `4h 0m`). `tsc --noEmit` clean on both configs; `npm run test:ci` green (152/152 files, 2804/2804 tests) — this wave's own share: `telemetry-logic.spec.ts` gained `freshness`/`humanAge` cases (boundary + the exact `353099s → 'stale'/'4d 2h'` H1 rover example), new `fly-osd-logic.spec.ts` covers `isStaleReading`/`osdGroupLabel`/`armedOsdText` (no `fly-osd.spec.ts` TestBed spec exists or was added — no codebase precedent renders a `TelemetryStore`-injecting component under TestBed, so the new stale-derivation logic was extracted to a pure `*-logic.ts` file instead, per this repo's own "favor pure-logic vitest" convention), `rc-monitor-logic.spec.ts` gained `armedChip`'s new `ageSeconds` cases (incl. exact `Armed 4d 2h ago`/`Disarmed 4d 2h ago` text) + `sampleIsStale` cases, `rc-monitor.spec.ts` gained two state-strip TestBed cases (stale vs. aging-not-stale). `ng build --configuration production` green, same two pre-existing budget warnings only; isolated via `git stash push` on just this wave's own 11 tracked files (holding T1/P1's files constant, same isolation technique T1 used) — initial bundle 411.96 kB → 412.34 kB raw (+0.38 kB, effectively flat), 115.65 kB → 115.36 kB transfer (a rounding decrease, not a regression); `cockpit` lazy chunk (pulls in `fly-osd`+`rc-monitor`+the new not-streaming card markup) 141.86 kB → 144.79 kB raw (**+2.93 kB**), 31.24 kB → 31.77 kB transfer (**+0.53 kB**). No new endpoint: `notStreamingLastSeen`/`notStreamingPosition` (`cockpit-facade.ts`) read `TelemetryStore`/`weatherPosition`, both already fetched for the OSD/map inset. Verified live against the dev server's own 4-day-stale rover (`/fly/99c4722a-9e47-4d5d-bf6e-e2c0d2c9fb73`) in both themes via Chrome MCP screenshots — the cockpit stays `.surface-dark` regardless of the app-level light/dark toggle (VISUAL-REFRESH-PLAN.md F3/W4), so both themes render identically and correctly: `LAST KNOWN · 4D 2H` OSD group labels + dimmed values + `ARMED?`, the drawer's neutral `Armed 4d 2h ago` chip + faint `Loiter` mode chip, and the not-streaming card (`Not streaming` / `Last seen 4d 2h ago` / `50.4381, 30.5183` / `Open on map ›` / `Start stream`).
- **2026-08-29, docs/plans/active/OPERATOR-UX-4-PLAN.md finding N6 + §2 N6, wave W3:** `features/hubs/coming-soon.*` split into `.ts`/`.html`/`.css` (the last inline-`template:`/`styles:` component in this feature) and now renders inside the standard page frame — `<vision-page-bar [title]="title()">` first, then the `.card.empty` primitive carrying only the eyebrow ("`<Mode>` · Coming soon"), the description, and the optional nearest-link button. The card no longer repeats `title()` as its own `<h3>` — the page bar is now the one place the title renders, matching every other routed page's own anatomy (one `<h1>`, once). All four scaffold routes (`/operate/missions`, `/monitor/layouts`, `/manage/health`, `/manage/firmware`) share this one component; `hubs.routes.ts`'s `data:` bindings are unchanged. **Root cause, per a live Chrome walkthrough this wave:** the finding's own DOM measurement (`1445×293` card, `y≈0`) reproduced exactly (`getBoundingClientRect` matched to the pixel across all four routes, both themes, at the desktop viewport this session could force — the `resize_window` tool did not take effect against this session's actual browser window, so a narrow/mobile viewport couldn't be forced to confirm or rule out the shell's mobile hamburger — `app-sidebar.css`'s `.sidebar-hamburger`, `position: fixed; top: calc(--shell-banner-h + 16px); left: 16px`, `z-index: 110` — as a second contributing overlap there), but the card painted legibly every time in this session — no console errors, `elementFromPoint` at the card's centre resolved to its own `<h3>`/text, `visibility`/`opacity`/`position` all nominal. The two real, verified-by-reading defects the finding also named stand regardless of whether the blank-page symptom reproduces on a given viewport: `ComingSoon` never used `vision-page-bar` (no sticky header, no page's own reserved top padding/stacking context — every other route gets both for free) and was a single-file inline-template component against this codebase's three-file rule. Shipping the standard page-frame pattern removes both structural deviations outright rather than chasing the exact repro further. `tsc --noEmit` clean on both configs (confirmed twice, stable — one interleaved run briefly showed an unrelated `TS2304` in `core/fleet/attention-logic.ts`, gone on rerun: a concurrent OPERATOR-UX-4 wave saving mid-edit, not this wave's file). `npm run test:ci`: this wave's own 3 spec files / 36 tests pass both in isolation (`ng test --include='src/app/features/hubs/**/*.spec.ts'`) and inside the full 152-file suite; the full-suite run on this shared, multi-wave working tree also showed 2 failures **outside this scope** — `core/fleet/asset-stats-logic.spec.ts` (`"10m 00s ago"` vs `"10m ago"`) and `features/asset-detail/asset-detail-logic.spec.ts` (`withFixOnlyPosition`) — both mid-flight edits from this same plan's concurrent N4/N1 waves (age-vocabulary and no-fix-position work respectively), neither touching `features/hubs/**`. `ng build --configuration production` green, same two pre-existing budget warnings only; the shared `coming-soon` lazy chunk (all four scaffold routes) measured 1.13 kB → 1.24 kB raw (**+0.11 kB**), 575 B → 627 B transfer (**+52 B**, isolated via `git stash push -u` on just this wave's own 4 files); initial bundle unchanged (still lazy). No new endpoint: still route `data:`-driven, no `VisionApi` call of any kind.
- **2026-08-29, docs/plans/active/OPERATOR-UX-4-PLAN.md findings N1 + N4 + N5, §2 N1/N4/N5, wave W1:** Null Island (`(0,0)`) no longer reads as a real vehicle position anywhere this wave touched, and ages / removed-device labels now share one vocabulary. New `core/geo/geo-logic.ts#hasFix(position)` — `false` for `undefined`, non-finite, or exactly `(0,0)` (a no-fix `GLOBAL_POSITION_INT`, never a real vehicle fix) — is now the one predicate for "does this position mean anything". `map-logic.ts#bucketForAsset`/`buildMarker` route through it (a no-fix `lastKnownPosition` buckets and plots exactly like none — no position bucket, no marker). **`features/command/command-logic.ts#buildEntityRows` was intentionally NOT touched**: despite the plan text describing it as living in `map-logic.ts`, it actually lives in `command-logic.ts` (W2's exclusive scope this wave) and is unrelated to position/fix logic (it ranks attention reasons) — a plan-text defect, flagged rather than fixed silently. The asset detail page's Position card and the "Full telemetry" drawer's per-device rows now render `No GPS fix yet` in the faint structural register (never a fabricated `0.00000, 0.00000`) when a sample carries lat/lon but no fix, still the pre-existing `—` when it carries no position field at all (`asset-detail-logic.ts#positionFact`, new `TelemetryFactRow.faint`). A new `AssetDetailFacade#mapAssets` sanitizes every asset's trail/latest sample through `hasFix`/the new `withFixOnlyPosition` before handing them to `TacticalMap`'s follow marker, so the mini-map keeps its previous/default view instead of recentring onto Null Island (`shared/map/tactical-map/tactical-map-logic.ts#followMarker`'s own separate, unpatched fix check now simply never sees a `(0,0)` sample). Sample ages everywhere `events-logic.ts#relativeTimeLabel` and the new `asset-detail-logic.ts#sampleAgeLabel` touch now read `<humanAge> ago` (`10m ago`, not the old zero-padded `10m 00s ago`) — one age vocabulary end to end; `stream-info-logic.ts#formatDuration` is untouched, confirmed to have no age callers left among its own actual call sites (only real session durations). `events-logic.ts#describeEventSource`'s final fallback now names `Removed device · <8-char id>` instead of a bare hash, for both the alerts list rows and the alerts asset-filter dropdown options — **zero code changes were needed inside `features/alerts/**`** despite it being in this wave's file scope: `alerts-facade.ts`/`alerts.html`/`alert-detail-panel.html` already call through `describeEventSource`/`relativeTimeLabel` via the facade's own `sourceLabel()`/`relativeTime()`, so both fixes propagated automatically. Cockpit's not-streaming card (`cockpit-facade.ts#notStreamingPosition`) now omits the position line + `Open on map` entirely when `!hasFix(position)`, reusing the predicate rather than re-deriving the `(0,0)` check.
  - Verified live against the dev server's known `(0,0)` rover (`/assets/40dd46d8-be99-451f-b8e2-c03a291aea33`) and the hash-named alert rows (`/monitor/alerts`) via Chrome MCP, both light and dark theme: Position card shows `No GPS fix yet` (faint) + `3d 18h ago` (the existing stale red tone, untouched) with the mini-map parked at its default world view rather than Null Island; alerts rows read `Removed device · ec17772a` / `Removed device · 7fd88790`. Incidentally reproduced the identical Null Island symptom this wave fixed, on `/fly`'s own drone picker (two "Your vehicles" cards show `POSITION 0.0000, 0.0000` for an offline rover) — that page is `features/fly/drone-picker-logic.ts`/`drone-picker-card`, W2's exclusive scope this wave, left untouched and flagged for a future cycle.
  - `tsc --noEmit` clean on both configs. **Built on a shared working tree with a concurrent wave (W2, `features/command/**` + `core/fleet/**`) mid-refactor** — a several-minute window where the whole app failed to compile (`asset-panel.ts`'s `attentionReasons`, `RailRow`/`TriageCandidate` mismatches in `command-facade.ts`/`command-logic.ts`) blocked `npm run test:ci`/`ng build` entirely; this wave's own 7 spec files (`geo-logic`, `map-logic`, `events-logic`, `asset-detail-logic` + their `*-store` siblings pulled in by the same entrypoints) were proven green in isolation during that window via `ng test --watch=false --include=<this wave's own spec globs>` (191/191 tests, still going through the real Angular builder, never bare `vitest run`), and the full suite was re-run once W2's edits landed. `npm run test:ci` green except one **out-of-scope** failure directly caused by this wave's authorized `relativeTimeLabel` change: `core/fleet/asset-stats-logic.spec.ts:93` still expects `'10m 00s ago'`, needs a one-line change to `'10m ago'` — that file is W2's exclusive `core/fleet/**` scope, left untouched by design (151/152 files, 2828/2829 tests). `ng build --configuration production` green, same two pre-existing budget warnings only (initial bundle over its 390 kB budget by 22.50 kB; `tactical-map.css` over its 8 kB budget by 1.86 kB). Bundle delta isolated via `git stash push -u`/`pop` on just this wave's own 12 files (same technique prior waves used on this shared tree): initial bundle 412.48 kB → 412.50 kB raw (+0.02 kB, effectively flat), 115.41 kB → 115.45 kB transfer (+0.04 kB); `asset-detail` lazy chunk 69.40 kB → 69.87 kB raw (**+0.47 kB**), 15.54 kB → 15.70 kB transfer (**+0.16 kB**); `cockpit` lazy chunk 144.82 kB → 144.86 kB raw (+0.04 kB), 31.82 kB → 31.74 kB transfer (-0.08 kB, rounding noise). No new endpoint anywhere in this wave.
  - Also found, out of scope: `features/fly/fly-logic.ts#lastSeenLabel` and `core/fleet/attention-logic.ts#attentionAgeLabel` both still render an age via `stream-info-logic.ts#formatDuration` (the zero-padded duration formatter, not `humanAge`) — the same class of bug as N4, on files outside this wave's assignment (`fly-logic.ts` unassigned any wave this cycle; `attention-logic.ts` is W2's `core/fleet/**`). Left untouched, flagged for a future cycle. **Resolved by W2 below** — `attentionAgeLabel` is this wave's own N4 task; `fly-logic.ts#lastSeenLabel` remains open (unassigned, still `formatDuration`) until this cycle's own W5, resolved there.
- **2026-08-29, docs/plans/active/OPERATOR-UX-4-PLAN.md findings N2 + N3 + N4, §2 N2/N3/N4, wave W2:** One attention verdict shared by the Command rail and detail panel, and the rail now triages like `/fly` — see the `command/` bullet above for the full detail (`CommandFacade#attentionByAssetId`/`#selectedAttentionReasons`, `AssetPanel`'s new `[reasons]` input, `gpsDegradedReason`'s live-only gate, `#railGroups`/`buildRailGroups`, the shared `vision.fly.hideSimulated` flag, `<vision-command-rail-row>`, `attentionAgeLabel` → `humanAge`) and the `fly/` → `DronePickerPage` sub-bullet for the `core/fleet/triage-logic.ts` extraction (`groupAndSort`/`isSimulated`/`SIMULATED_CATEGORY`/`offlineLabel`/`PickerGroups`, generalized with a `TriageCandidate` bound + optional `attentionRank`; `drone-picker-logic.ts` is now a re-export shim).
  - **N2 root cause, confirmed live** (`/command?asset=40dd46d8-be99-451f-b8e2-c03a291aea33`, Chrome MCP, both themes): the rail's CRIT chip was a `geofence-breach` reason (`KEEP-IN breach — Demo operating area`) — threaded into `buildEntityRows` from `LiveStore.liveEvents()` via `CommandFacade#geofenceBreachesByAssetId`, but never reachable by `AssetPanel`'s own pre-fix `attentionReasons(asset, marker()?.gpsFixType)` call, which only ever threaded `gpsFixType`. Both the rail and the panel now read `CommandFacade#attentionByAssetId`/`#selectedAttentionReasons` — the exact same `EntityRow` objects, computed once — so this class of drift (two independent derivations of the same fact, free to disagree) is now structurally impossible, not just fixed for this one asset. Confirmed live: after the fix, the panel shows `CRITICAL — KEEP-IN breach — Demo operating area` matching the rail's CRIT chip exactly, in both light and dark theme.
  - **Plan defect found:** the plan's own read-first list cites `command-facade.ts`'s `attentionAssetIds`/`markers`/`selectedMarker` as the pre-existing shape to build from, but doesn't mention that `AssetAttention` (the rail/panel's DTO) and `AssetSummary` (`/fly`'s DTO) share no field names at all (`categoryId`/`streaming`/`telemetryAgeMs` vs `category`/`status`/`lastUsedAt`) — `groupAndSort`'s generalization to `<T extends TriageCandidate>` plus `RailRow`'s arithmetic-derived synthesis (`lastUsedAt` recovered from `telemetryAgeMs`) was needed and not called out in the design; flagging since a future extension of this shared sort to a third DTO shape should expect the same friction.
  - Dev parity: nothing role-gated or auth-conditional changed — `attentionByAssetId`/`railGroups`/`hideSimulated` all read data the page's own existing pollers already fetch regardless of `vision.auth.enabled`. Degrades honestly: `selectedAttentionReasons` is `[]` (not a fabricated "quiet") whenever nothing is selected or the selection has no row yet; `RailRow.offlineAge` is `undefined` while streaming and `'Never seen'` (never a fabricated age) when `telemetryAgeMs` is absent.
  - `tsc --noEmit` clean on both configs (one intermediate error surfaced mid-edit — `RailRow` missing `displayName` against `TriageCandidate`'s bound — fixed by hoisting `asset.displayName` onto `RailRow` itself, since `TriageCandidate` reads it at the top level the way `AssetSummary` does, one level shallower than `EntityRow.asset.displayName`). First full-suite run (W1's `core/events/events-logic.ts#relativeTimeLabel` edit still uncommitted at that point) showed 151/152 files, 2828/2829 tests — the one failure, `core/fleet/asset-stats-logic.spec.ts` (`'10m 00s ago'` vs `'10m ago'`), was outside this wave's own touched files and matched W1's own MODULE.md bullet above naming the identical failure; once W1 committed (`f42f4ff3`) the failure persisted (a genuine stale expectation, not an in-progress artifact) and, since the file sits inside this wave's own `core/fleet/**` scope and the fix is a one-line, unambiguously-correct expectation update matching W1's already-shipped `humanAge` behavior (`NOW` − `lastFlownAt` = exactly 10:00 → zero-seconds remainder dropped), fixed it here rather than leave the suite red. `npm run test:ci` now green: **152/152 files, 2829/2829 tests**. `ng build --configuration production` green, same two pre-existing budget warnings only; isolated via `git stash push -u`/`pop` on just this wave's own 16 files (same technique prior waves used on this shared tree): `command` lazy chunk 60.76 kB → 64.20 kB raw (**+3.44 kB**), 14.12 kB → 14.80 kB transfer (**+0.68 kB**); `drone-picker` lazy chunk 9.33 kB → 8.78 kB raw (**−0.55 kB**), 2.99 kB → 2.75 kB transfer (**−0.24 kB**) — net *smaller*, since `triage-logic.ts`'s shared sort/group logic is now hoisted into a common chunk both `command` and `drone-picker` import rather than inlined per-chunk; initial bundle 412.50 kB → 412.50 kB (flat — both chunks are lazy). No new endpoint: still `GET /api/fleet/summary` (`buildEntityRows`) and the existing `LiveStore`/`GeofenceStore` reads, unchanged. Verified live against the dev server in both themes via Chrome MCP (own tab, parallel agents' tabs left untouched): rail groups `YOUR VEHICLES (3)`/`SIMULATED (14)`, `Hide simulated` checkbox, `Offline · 14h 58m` row age matching the panel's `14h 58m ago` telemetry age exactly, selection state and CRIT chip correct in dark theme.
- **2026-08-29, docs/plans/active/OPERATOR-UX-4-PLAN.md, wave W5 (four small honesty fixes, solo agent):**
  - **`/fly` picker cards no longer print `POSITION 0.0000, 0.0000` for a no-fix rover.** `fly-logic.ts` gained `PositionFact`/`positionFact(position)` — `undefined` (fact omitted, unchanged) when the asset has never reported a position at all, the faint structural `'No GPS fix yet'` (matching `features/asset-detail/asset-detail-logic.ts#positionFact`'s own wording/register for the identical fact, per finding N1's `core/geo/geo-logic.ts#hasFix`) when a position exists but carries no real fix, or the real `lat, lon` via the unchanged `positionLabel` in the `.mono` register otherwise. `drone-picker-card.ts#position()` now returns this instead of a bare string; `drone-picker-card.html`'s `<dd>` binds `[class.mono]`/`[class.faint]` off it (the same pattern `asset-detail.html`'s own fact grid already uses) — `drone-picker-card.css` gained one local `.picker-card-facts dd.faint { color: var(--text-faint) }` override, since that file's own base `.picker-card-facts dd` rule already sets `color` at higher specificity than the shared global `.faint` class alone. `positionLabel`/`fly-osd.ts#positionText` (the cockpit OSD's own live position readout) are untouched — reproduced live but out of scope this wave (unassigned file), flagged below.
  - **One age vocabulary, closing the last two `formatDuration`-as-age gaps.** `fly-logic.ts#lastSeenLabel` now renders through `core/telemetry/telemetry-logic.ts#humanAge` instead of `stream-info-logic.ts#formatDuration` (flagged open by W1's own MODULE.md bullet above — see that bullet's amended last sentence). A repo-wide `grep 'ago'` sweep (excluding real *duration* renderers — `replay-facade.ts`/`replay-library-logic.ts`/`asset-detail.ts`/`stream-info-panel.ts`, all session/flight lengths, unchanged) found one more: `shared/ui/weather-chip.ts`'s own "updated N ago" tooltip, also moved to `humanAge`. Both now read `4h 2m ago`/`3d 18h ago` rather than `formatDuration`'s zero-padded, hour-capped wording. `shared/map/tactical-map/tactical-map.ts:844`'s marker-popup `Updated ${sampleAgeSeconds.toFixed(0)}s ago` is a **different** bug class (a raw, unformatted second count — never called `formatDuration` at all, so outside this wave's literal "formatDuration-based age" scope) — flagged, not fixed, for a future cycle.
  - **`geofence-breach` is now live-only, like `gps-degraded`/`telemetry-stale`.** `core/fleet/attention-logic.ts#geofenceBreachReason` gained the identical `!asset.streaming → undefined` gate `gpsDegradedReason` already had from W2 — an offline asset's leftover `LiveEvent` breach (which never self-clears; `activeGeofenceBreaches` only closes a breach on a matching `exit` event, and nothing emits a synthetic one when a vehicle simply goes offline mid-breach) no longer outranks every other reason forever. Reproduces and fixes the exact N2 rover named in this wave's own brief (an ESP32 rover offline 3 days, still CRIT for a `KEEP-IN` breach recorded from Null Island before it went offline). `attentionReasons`' call site now threads `asset` through; `command-logic.ts`'s re-export and `CommandFacade#attentionByAssetId`/`#selectedAttentionReasons` (both W2's own "one verdict" plumbing) needed no changes — the gate lives entirely inside the one function every caller already shares.
  - **Map markers: no separate fix needed, confirmed rather than assumed.** `core/map/map-logic.ts`/`shared/map/tactical-map/tactical-map.ts` grep clean for `breach`/`GEOFENCE_BREACH` — there is no independent "breach marker" glyph; a breaching asset's marker recolors `--color-danger` purely via `CommandFacade#attentionAssetIds` (`entityRows`-derived, the same one attention verdict W2 already unified). Fixing `geofenceBreachReason` above is therefore the whole fix for the map too, by construction, not a second edit.
  - Dev parity: nothing role-gated or auth-conditional touched — `positionFact`/`lastSeenLabel`/`geofenceBreachReason` all derive from data every caller already had; behavior with `vision.auth.enabled=false` is unchanged. Degrades honestly: a position never reported still omits the fact (never a fabricated "—"); an offline asset's old breach reads as quiet, not as a *false* "resolved" claim — the breach event itself is untouched, only whether it counts as a live attention reason.
  - `tsc --noEmit` clean on both configs. `npm run test:ci` green: **152/152 files, 2835/2835 tests** (+6 over W2's 2829 — 4 new `fly-logic.spec.ts` cases for `positionFact` + the new multi-day `lastSeenLabel` case, 1 new `attention-logic.spec.ts` live-only-breach case, 1 new `command-logic.spec.ts` `buildEntityRows` case for the same gate; three pre-existing `geofence-breach`/`buildEntityRows` breach cases updated to pass `streaming: true`, since the default test fixture is `streaming: false`). `ng build --configuration production` green, same two pre-existing budget warnings (now 24.73 kB / 1.86 kB over, up from 22.50 kB / 1.86 kB — the tactical-map.css one untouched). Bundle delta isolated via `git stash push -u`/`pop` on just this wave's own 9 tracked files: initial bundle 412.50 kB → 414.73 kB raw (**+2.23 kB**), 115.45 kB → 115.87 kB transfer (**+0.42 kB**) — `weather-chip.ts` itself is lazy (its own shared chunk, not initial), so this is the build's chunk-graph shifting some already-shared module boundary now that `weather-chip.ts` imports `telemetry-logic.ts` instead of `stream-info-logic.ts`, the same kind of hoisting effect W2's own bundle note above describes for `triage-logic.ts`; `cockpit`/`command` lazy chunks effectively flat (144.86 kB → 144.86 kB, 64.20 kB → 64.17 kB — rounding noise); `drone-picker` lazy chunk 8.78 kB → 8.94 kB raw (**+0.16 kB**), 2.75 kB → 2.79 kB transfer (**+0.04 kB**), from `PositionFact`'s own small shape. No new endpoint anywhere in this wave. Verified live against the dev server (`Browser 1 (Linux)`, Chrome MCP, own tab) at `/command?asset=40dd46d8-be99-451f-b8e2-c03a291aea33` — the N2 rover: rail row reads `ESP32 Rover (paired) · Offline · 15h 18m` with no chip, the panel reads `STREAM Offline` / `TELEMETRY AGE 15h 18m ago` / `All quiet — nothing needs attention on this asset right now.` A one-time `KEEP-IN breach — Demo operating area` toast appeared on load and self-dismissed — `NotificationBell`'s own transient alert for the historic `LiveEvent` replaying over SSE, unrelated to (and unchanged by) the attention-verdict gate fixed here.
- **2026-08-29, OPERATOR-UX-4 post-wave sweep:** the last raw `Ns ago` renderers (`fly-osd.ts#positionText` now via `fly-logic.ts#positionFact` so the cockpit OSD says `No GPS fix yet` instead of `0.0000, 0.0000`; `tactical-map.ts` popup, `command/asset-panel` telemetry age, `live/telemetry-osd.ts`, `shared/player/stream-info-panel.ts`) all render through `core/telemetry/telemetry-logic.ts#humanAge`. Only `detection-overlay-logic.ts`'s sub-minute pause notice keeps raw seconds — it is a ms-scale live counter, not an age.
- **2026-08-29, docs/plans/active/OPERATOR-UX-5-PLAN.md findings U1 + U3 + U4, §2 U1/U3/U4, wave W2:** three independent honesty fixes — the replay library's open-usage status, the system-status banner's verdict, and the notification bell's geofence-breach toast — see the `replay/`, `system-status/`, and `shared/ui/` → `NotificationBell` bullets above for full detail on each. Touched only `features/replay/**`, `features/system-status/**`, `shared/ui/notification-bell*`, `shared/ui/notification-logic.*`, `core/system-status/system-status-logic.*` — per this wave's own scope, alongside two parallel agents on the same shared tree (W1, Java `vision.usage.idle-close`; W3, `core/audit`/`core/fleet/triage-logic`/`features/activity`/`features/assets`/`features/audit`), all disjoint file lists, no conflicts.
  - **U4 root cause, confirmed against this plan's own §2 hint and a prior wave's own live observation:** `NotificationBell`'s old breach effect used a "seed everything present on the first tick as already-toasted, only toast what arrives after" idiom — sound only if `liveEvents()` is fully populated the instant the effect first runs. It isn't: the SSE backlog/snapshot for a historic breach can arrive on a *later* tick than the effect's first run, so that breach is never marked seeded and gets toasted as if fresh. This is exactly what OPERATOR-UX-4 wave W5's own MODULE.md bullet above already reported without diagnosing ("`A one-time KEEP-IN breach — Demo operating area toast appeared on load and self-dismissed`") — reproduced again here on a fresh `http://localhost:4200` load. Fixed by construction, not by patching the race: `shouldToast(event, mountedAtMs, assetStreaming)` replaces the seed/toasted-boolean pattern with two direct, timestamp/streaming-based gates that are correct regardless of *when* any given tick's data arrives, immune to the race rather than papering over one ordering of it.
  - **Plan accuracy:** §2's behavioral rules for U1/U3/U4 all held up as written and needed no correction; the one gap was factual, not a defect — the plan's read-first list for U1 assumes `UsageSummary` might carry a last-activity field and asks the wave to check, which it doesn't (documented above and in `usageStatus`'s own comment) — not a plan error, an open question the plan itself flagged and this wave resolved.
  - Dev parity: nothing role-gated or auth-conditional touched in any of the three fixes — `usageStatus`, `verdictFor`, and `shouldToast` all derive from data every existing poll/SSE subscription already fetched regardless of `vision.auth.enabled`; behavior with the dev-admin principal (`vision.auth.enabled=false`) is unchanged. Degrades honestly: a `usageStatus` estimate never invents a last-activity time the wire didn't provide (falls back to `startedAt`, documented); `verdictFor` never claims `'ok'` from an `'UNKNOWN'` subsystem or a status poll that hasn't completed; `shouldToast` never fabricates urgency for a pre-existing breach replayed after the bell mounts, and a failed read anywhere in this wave's own new code paths inherits the pre-existing silent-degrade posture of the stores/effects it extends (no new fetch was added).
  - `npx tsc --noEmit -p tsconfig.app.json` and `-p tsconfig.spec.json` both clean. `npm run test:ci` green on the shared tree: **153/153 files, 2893/2893 tests** — this wave's own share is 14 new cases (7 `usageStatus` + 3 `sortUsagesForDisplay` in `replay-library-logic.spec.ts`, ~11 `verdictFor` cases in `system-status-logic.spec.ts`, 4 `shouldToast` cases in `notification-logic.spec.ts`, 5 TestBed cases in `notification-bell.spec.ts` for the breach-toast gates using `vi.useFakeTimers()`); the delta over W5's own 2835 baseline also includes concurrent W3 work (`core/audit/summary-logic.spec.ts`, new) landing on the same shared tree mid-session, confirmed by name rather than assumed. `ng build --configuration production` green, same two pre-existing budget warnings only (initial bundle 25.52 kB over its 390 kB budget; `tactical-map.css` 1.86 kB over its 8 kB budget). Bundle delta isolated via `git stash push -u`/`pop` on just this wave's own 13 tracked files (same technique prior waves on this shared tree used): initial bundle 414.73 kB → 415.52 kB raw (**+0.79 kB**), 115.91 kB → 116.22 kB transfer (**+0.31 kB**, from `NotificationBell`'s eagerly-loaded shell code); `replay-library` lazy chunk 12.24 kB → 13.49 kB raw (**+1.25 kB**), 3.70 kB → 4.08 kB transfer (**+0.38 kB**); `system-status` lazy chunk 7.57 kB → 7.63 kB raw (**+0.06 kB**), 2.50 kB → 2.51 kB transfer (**+0.01 kB**); the `replay` chunk (`ReplayPage`, untouched by this wave) measured byte-identical both times as a sanity check. No new endpoint anywhere in this wave: `usageStatus` reads the existing `GET /api/usages` response shape, `verdictFor` reads the existing `GET /api/system/status` poll, `shouldToast` reads the existing SSE `liveEvents()`/`FleetStore` fleet snapshot.
- **2026-08-29, docs/plans/active/OPERATOR-UX-5-PLAN.md finding U2 + U5, §2 U2/U5, wave W3:** raw actor/target ids in activity/audit prose now read as names, and the Assets grid triages instead of listing in plain insertion order — see the `core/api/`/`assets/devices/asset-detail/` bullets' new siblings below for the two pieces.
  - **U2 — names in prose.** New `core/audit/summary-logic.ts` (second consumer from the start — `AuditFacade` and `ActivityFacade` both need it — so it lives in `core/` rather than either feature): `buildNameMap(assets, users?)` (id → displayName), `humanizeSummary(summary, names)` (every 8-4-4-4-12 UUID the map resolves is swapped for its display name in place; an unmatched UUID shortens to its first 8 characters — the same "never fabricate, degrade to a short fragment" idiom `audit-logic.ts`'s pre-existing `actorLabel`/target-name fallbacks already used), and `actorLabel(actorId, names)` (the synthetic root/system principal `00000000-0000-0000-0000-000000000000` — `DevPrincipal.USER_ID`/`LayerResolver.SYSTEM_USER_ID` in the Java, confirmed by grep — reads as `Station`; otherwise the same resolve-or-shorten rule). `audit-logic.ts#buildAuditRows` now builds one `names` map (assets + users) per call and uses it for both `actorLabel` (replacing the old file-local `actorLabelFor`, users-only) and a new `AuditRow.summaryLabel` field — `AuditRow.summary` stays raw, kept only to back the SUMMARY cell's `[title]` tooltip; `audit.html`'s SUMMARY `<td>` now renders `row.summaryLabel`. `ActivityFacade` gained its own best-effort `listAssets()` read (`.catch(() => [])`, alongside the page's existing `myActivity()` fetch — a failed read degrades to an empty names map, every id then shortens to 8 characters, never blocks or fails the page) feeding the identical `buildNameMap`/`humanizeSummary`, since this page's own summaries name asset targets by raw id the same way ("Flight command 'ARM' for asset 40dd46d8-…"); `ActivityRow` gained the matching `summaryLabel` field, `activity.html` renders it with `summary` kept for `[title]`. No `listUsers()` call added to `ActivityFacade` — grepped every Java summary-text call site and confirmed none names a user by id, only asset targets, so a names source was added only where actually needed, not speculatively.
  - **U5 — the Assets grid triages.** New `core/fleet/triage-logic.ts#triageOrder(a, b, nowMs, attentionRank?)` generalizes the file's existing per-group `sortByFreshness` (real vehicles before simulated as the outermost tier, then streaming first, then — when supplied — `attentionRank` descending, then `lastUsedAt` descending/never-seen-last, then case-insensitive alphabetical) into one flat `Array#sort` comparator for a caller with no Your-vehicles/Simulated section split to carry the grouping visually — `features/assets/**` is the third fleet list to need this triage after `/fly`'s picker and Command's rail, and the first with no group headers. `sortByFreshness` itself now delegates to `triageOrder` (verified behaviorally identical: within either of `groupAndSort`'s two pre-split homogeneous groups, `isSimulated` never differs between any two rows, so the new outer tier is always a no-op there — the pre-existing `groupAndSort` spec stayed green unmodified through the refactor). New `features/assets/assets-logic.ts#sortAssetListRowsByTriage(rows, nowMs)` (applies `triageOrder` to `AssetListRow.asset`, which structurally satisfies `TriageCandidate`) and `#lastSeenLabel(lastUsedAt, nowMs)` (`<humanAge> ago` / `Never seen` — a deliberately different register from `triage-logic.ts#offlineLabel`'s `Offline · 2h 29m`, since this grid's own STATE column already carries the Offline/Streaming word as its dot+text, so repeating it here would be redundant; mirrors `fly-logic.ts#lastSeenLabel`'s wording, not its contract — that one returns `undefined` for "never seen" instead of the ready-to-render string this page wants). `AssetsFacade#allRows` now sorts through `sortAssetListRowsByTriage` once at the unfiltered level (filters preserve order, so this is equivalent to and cheaper than re-sorting each filtered view). A new **Last seen** column sits after STATE in the dense list (`assets.html`); the cards view shows the identical text as a new line under the status/device-count row (`.asset-card-lastseen`, `assets.css`, `font-size: 0.78rem`, no new color — inherits the existing `.muted` token). This page has no column-header sort today (grepped `assets.html`/`assets.ts`/`assets-facade.ts` clean) — `sortAssetListRowsByTriage`'s own doc comment records the contract for one added later ("run after this and override it, never replace it") so U5's own "any existing sort keeps working" clause is honestly satisfied by there being nothing to conflict with yet.
  - Dev parity: nothing role-gated or auth-conditional touched — `buildNameMap`/`humanizeSummary`/`actorLabel`/`triageOrder`/`sortAssetListRowsByTriage`/`lastSeenLabel` all derive from data every existing fetch/poll already returns regardless of `vision.auth.enabled`; the dev-admin principal's own actions attribute to `Station` exactly like the real synthetic root principal would in a `vision.auth.enabled=true` deployment, so parity holds both ways. Degrades honestly: an unresolvable id shortens to 8 characters rather than vanishing or showing a placeholder; a failed `listAssets()` read on `/activity` degrades the names map to empty (every id then shortens) rather than blocking the page; `lastSeenLabel` never fabricates an age for an asset with no `lastUsedAt`.
  - `npx tsc --noEmit -p tsconfig.app.json` and `-p tsconfig.spec.json` both clean. `npm run test:ci` green on the shared tree: **153/153 files, 2893/2893 tests** — this wave's own share is 60 new cases (`summary-logic.spec.ts` new, 14 cases; `audit-logic.spec.ts` +3; `triage-logic.spec.ts` +5 `triageOrder` cases; `assets-logic.spec.ts` +8 across `sortAssetListRowsByTriage`/`lastSeenLabel`). Hit one shared-tree false-negative mid-wave: a W2-owned in-flight file (`shared/ui/notification-bell.ts`) briefly failed the full-suite build with an unrelated `TS2551`; proved this wave's own 4 spec files green in isolation (`ng test --watch=false --include=…`, 101/101) while W2's edit was mid-flight, then re-ran the full suite clean once it landed — never touched that file. `ng build --configuration production` green, same two pre-existing budget warnings only (initial bundle 25.52 kB over its 390 kB budget; `tactical-map.css` 1.86 kB over its 8 kB budget, both unchanged by this wave). Bundle delta isolated via `git stash push -u`/`pop` on just this wave's own 15 tracked files (post-W2-merge baseline, same technique prior waves on this shared tree used): initial bundle 415.52 kB → 415.52 kB raw (flat), 116.19 kB → 116.22 kB transfer (+0.03 kB, noise); `assets` lazy chunk 25.21 kB → 25.74 kB raw (**+0.53 kB**), 6.10 kB → 6.26 kB transfer (**+0.16 kB**); `audit` lazy chunk 9.06 kB → 9.05 kB raw (flat, noise), 2.83 kB → 2.85 kB transfer (+0.02 kB, noise); `activity` lazy chunk 5.50 kB → 5.72 kB raw (**+0.22 kB**), 1.94 kB → 2.02 kB transfer (**+0.08 kB**). No new endpoint: `ActivityFacade`'s new `listAssets()` call reuses the existing `VisionApi#listAssets`, the same call `AuditFacade`/`AssetsFacade` already made. Verified live against the dev server (Chrome MCP, own tab, closed after): `/activity` and `/monitor/audit` both render `Flight command 'ARM' for asset ESP32 Rover (paired)` in place of the raw `40dd46d8-…` id, `/monitor/audit`'s ACTOR column reads `Station` for the dev-admin's own actions; `/assets` lists all 3 real ESP32 Rovers ahead of every simulated asset regardless of individual last-seen age (confirming the real-before-simulated outer tier), each sorted last-seen-descending/never-seen-last within its own tier, with the new `Last seen` column in List view and the matching line under status in Cards view.
- **2026-08-29, docs/plans/active/OPERATOR-UX-6-PLAN.md finding M1, §2 M1, wave W2:** the `night` basemap renders again — every dark-theme map (`/command`, `/assets/:id`, replay) had been showing "API KEY REQUIRED" tiles since CARTO started gating `dark_all` behind a key this app doesn't have, and dark theme picks `night` by default (`defaultMapLayerIdForTheme`), so the *default* map of the *default* theme was dead. `night` is now the same OSM raster tiles as `standard` (`https://tile.openstreetmap.org/{z}/{x}/{y}.png`, OSM attribution only, `maxZoom: 19`, no key), run through the standard Leaflet dark-mode CSS trick instead of a different tile source. `leaflet-loader.ts#MapLayerDef` gained `tileFilter?: string` (only `night` sets it, to the new `NIGHT_TILE_FILTER` constant: `invert(1) hue-rotate(180deg) brightness(0.85) contrast(0.9) saturate(0.6)`); `CARTO_ATTRIBUTION` is deleted (grepped clean — its only other reference was a `night (CARTO Dark Matter)` mention in `core/settings/settings-store.ts:141-145`'s doc comment, out of this wave's file scope, now stale and flagged below, not fixed). `TacticalMap` applies the filter without touching `applyBasemap()`'s DOM code at all: a new `activeBasemapTileFilter` computed (`mapLayerDef(activeBasemapId()).tileFilter ?? null`) feeds two `host` bindings alongside the pre-existing `[class.follow-mode]` — `[class.basemap-filtered]` and `[style.--basemap-tile-filter]` — the same host-binding idiom already proven there, so it re-evaluates on every signal read exactly like `[class.follow-mode]` does, with no new effect. `tactical-map.css` gained `:host.basemap-filtered ::ng-deep .leaflet-tile-pane { filter: var(--basemap-tile-filter); }`: a CSS custom property inherits down the real DOM regardless of Angular's emulated encapsulation, and `.leaflet-tile-pane` is a sibling of `.leaflet-marker-pane`/`.leaflet-overlay-pane`/`.leaflet-popup-pane`/`.leaflet-tooltip-pane` in Leaflet's own pane structure, so the filter's specificity never reaches markers, mark symbols, drawings, tracks, geo corrections, or popups — confirmed by reading Leaflet's pane DOM, not just asserted. **Tile cache key: sharing is harmless, confirmed rather than assumed.** `tile-cache-logic.ts#tileCacheKey(layerId, z, x, y)` already keys by layer id ahead of z/x/y (`night/4/2/9` vs `standard/4/2/9`), specifically so two layers tiling the same coordinates with different imagery never collide — `night` and `standard` now happen to fetch byte-identical tiles, so this costs a harmless doubled cache entry per pair of coordinates either basemap has visited (extra IndexedDB storage, well inside the existing 300 MB cap machinery), never a stale-tile or wrong-tile bug; no change needed to `tile-cache-db.ts`/`tile-cache-logic.ts`. Role-gating: none — the basemap picker has never been role-gated, and this fix changes no permission surface. Dev parity: unaffected — `vision.auth.enabled=false` never touched map rendering. Degrades honestly: a `night`-basemap tile fetch failure still falls through to `mapLayerTileLayer`'s pre-existing `onStatus(false)`/"Tiles unavailable" badge, unchanged — this wave only changed which URL is requested and how the response is painted, not the offline-fallback path. **Flagged, not fixed (out of file scope):** `core/settings/settings-store.ts:141-145`'s `DEFAULT_MAP_LAYER` doc comment still says `night (CARTO Dark Matter)` — factually stale now, but the default *value* (`'night'`) and its reasoning ("this console is dark by default, `night` is the closer match out of the box") are still correct, so nothing behavioral is wrong, only the tile-provider name in a comment; a future touch to that file should update it. `npx tsc --noEmit -p tsconfig.app.json` and `-p tsconfig.spec.json` both clean. `npm run test:ci` green: **154/154 files, 2897/2897 tests** (+4 over the prior 2893 — new `leaflet-loader.spec.ts` cases: `night` carries the exact `tileFilter` string and is the only layer that does, `night`/`standard` share `url`/`attribution`, `night`'s attribution never mentions CARTO). `ng build --configuration production` green, same two pre-existing budget warnings: initial bundle 415.52 kB → 415.52 kB (still 25.52 kB over its 390 kB budget, unchanged) — expected, since the TS/JS delta here is a few bytes (one new field, one new computed, two host bindings) and the CSS lives in a component `styleUrl`, not the initial bundle; `tactical-map.css` 8.00 kB budget now over by **1.95 kB** (up from the immediately-prior wave's own last-measured 1.86 kB, i.e. **+0.09 kB** from the new `.basemap-filtered` rule and its comment). Per this wave's own explicit instruction, bundle delta was read directly off this build rather than isolated via `git stash push`/`pop` — three agents share this working tree this cycle and stashing risks corrupting a concurrent agent's in-progress files; the before/after above instead compares against the immediately-preceding wave's own recorded numbers on this same shared tree (both budget warnings unaffected except the CSS one, exactly as expected from a component-scoped stylesheet addition).
- **2026-08-29, docs/plans/active/OPERATOR-UX-6-PLAN.md findings E1-E4, §2 E1-E4, wave W1:** `ReplayPage` now leads with the replay (map + video + transport), not the evidence package; the evidence panel's own actor id reads as a name; its plural counts are all explicit; and its operator-visible copy says "session", not "flight" — see the `replay/` bullets above (under `features/**`) for full per-finding detail, and the `shared/ui/` bullet above for the new `text-logic.ts`. Touched only `features/replay/**` (`replay.html`/`.css`/`.ts`, `after-action-panel.html`/`.css`/`.ts`, `replay-library.ts`) and the new `shared/ui/text-logic.ts`(+spec), alongside two parallel agents on the same shared tree this cycle (W2 `shared/map/tile-cache/leaflet-loader.*`+`shared/map/tactical-map/**`; W3 `core/readiness/**`+`features/readiness/**`+`features/org-settings/**`), all disjoint file lists, no conflicts observed.
  - Role-gating: none — `ReplayPage`/`AfterActionPanel` have never been role-gated (any authenticated viewer who can reach `/assets/:id/replay/:usageId` could already see this page), and this wave changes no permission surface. Dev parity: unaffected — `collapsed`/`scopedToLabel`/the reordered grid/the copy changes are all pure presentation over data every existing fetch (`ReplayFacade#load`/`loadAfterAction`) already returns regardless of `vision.auth.enabled`; the dev-admin principal's own root-scoped manifests already read `scopedTo: '00000000-…'` today and now render `Built for Station` exactly as a real deployment's root/system principal would. Degrades honestly: `scopedToLabel` never fabricates a display name for an id it can't resolve (falls back to the same 8-char short id `actorLabel` already uses everywhere else in this app with no roster in hand); the evidence panel's own loading/error states are unchanged by the collapse — `loading`/`errorMessage` still render (behind the same disclosure) exactly as before, never silently hidden by starting collapsed.
  - `npx tsc --noEmit -p tsconfig.app.json` and `-p tsconfig.spec.json` both clean. `npm run test:ci` green on the shared tree: **154/154 files, 2903/2903 tests** — this wave's own share is 4 new cases (`text-logic.spec.ts`, new); no existing spec asserted any of the changed template copy or the pre-existing `pluralize`/`page-bar.ts` behavior this wave left untouched, so nothing needed updating there. `ng build --configuration production` green, same two pre-existing budget warnings only (initial bundle 25.52 kB over its 390 kB budget; `tactical-map.css` 1.95 kB over its 8 kB budget, both unchanged by this wave — `replay`/`replay-library`/`asset-detail` are all lazy chunks, so nothing here could move the initial bundle regardless). Per this cycle's own updated instruction (three agents sharing this tree, stashing risks corrupting a concurrent agent's in-progress files), bundle delta was read directly off this build rather than isolated via `git stash push`/`pop`: initial bundle measured 415.52 kB raw / 116.20 kB transfer, byte-for-byte the same as the immediately-preceding W2 wave's own last-recorded number on this shared tree, confirming this wave's own changes never touch the initial bundle; `replay-library` lazy chunk measured 13.51 kB raw / 4.08 kB transfer (flat against W2's own 13.49 kB/4.08 kB, the ~0.02 kB raw noise from this wave's `pluralize` import-path swap); `replay` lazy chunk (holding `ReplayPage`+`AfterActionPanel`, this wave's actual content) measured 709 bytes raw/transfer — most of this wave's own new code (the `.disclosure` toggle, `scopedToLabel`, the reordered template, `text-logic.ts` itself) lives in shared vendor/common chunks the build's own code-splitting already hoists rather than in `replay`'s own thin route-loader chunk, so a clean before/after isolation of this wave's specific byte cost wasn't obtainable without the disabled stash technique; reported as an absolute measurement instead. No new endpoint: this wave reads only `ReplayFacade`'s pre-existing `afterAction`/`asset`/`timeline` signals, all backed by fetches that already shipped.
  - **Plan defect, flagged not fixed:** finding E3's design text ("`pluralize(count, 'entry', 'entries')` in `shared/ui/text-logic.ts` (new, pure, spec'd)") reads as if no `pluralize` existed yet — `shared/ui/page-bar/page-bar.ts` already exports a byte-identical one (used by `reports/`, `assets/`, `asset-detail/`, `wall/`, `labeling/`, and — before this wave — `features/replay/**` itself), tested in `page-bar.spec.ts`. The actual E3 bug ("Included · 200 entrys") was `after-action-panel.ts` calling that existing `pluralize(count, 'entry')` with no explicit plural, not a missing function; this wave still created `text-logic.ts` exactly as instructed (E3's stated intent — "plurals from one place" — is honestly served by making it the one place `features/replay/**` imports from) but left `page-bar.ts`'s own copy alone, out of file scope. Finding E4's design text also claims "the replay library already says session" — `replay-library.html`/`.ts`/`replay-library-logic.ts` still say "flight" throughout (`countNoun="flight"`, `No flights match these filters`, "Flight details", etc.), confirmed by grep, not fixed here since every one of E4's own quoted examples is on `ReplayPage`, not the library list — flagged in the `replay/` bullet above for whoever picks up the library's own copy next. Also left open, out of file scope: `core/after-action/after-action-logic.ts#openFlightNotice` renders a second, near-identical "This flight is still in progress" string inside the evidence panel itself (not the `ReplayPage` empty state E4 quoted) — same wording defect, different file, flagged in the `replay/` bullet above.
- **2026-08-29, docs/plans/active/OPERATOR-UX-6-PLAN.md findings R1 + O1, §2 R1/O1, wave W3:** two hierarchy/honesty fixes on `/assets/:assetId/readiness` and `/org`, disjoint from the same cycle's W1 (`features/replay/**`, `shared/ui/text-logic.ts`) and W2 (`shared/map/tile-cache/leaflet-loader.*`, `shared/map/tactical-map/**`) — touched only `core/readiness/readiness-logic.ts`(+spec), `features/readiness/**`, `features/org-settings/**`, no conflicts observed.
  - **R1 — the readiness header stops implying an evaluation that never happened.** New `core/readiness/readiness-logic.ts#hasBeenProbed(report)` reads the one wire fact that already carries this (`profileObservedAt !== null` — verified against `DefaultReadinessService#evaluate`: `profile == null` is the single branch that both nulls this field *and* stamps every one of the eleven feature rows' own `detail` as "Never probed.", so checking the report-level field is equivalent to, and cheaper than, scanning all eleven rows). `readiness.html`'s verdict card now branches on it: probed renders the pre-existing `Evaluated …`/`Last profiled …` `.facts` dl unchanged ("when probes exist, the current layout is correct", per the plan's own design note); never-probed renders one `Not probed yet` line in the structural-label register (`class="label"`, the same global uppercase/muted token every form-field label already uses) instead — the feature table itself is untouched, its pre-existing `remedyLabel(row.remedy) ?? '—'` already rendered `—` for every "Never probed." row before this wave, so no table change was needed to satisfy the plan's "table stays … with `—` remedy" clause. New `probeBlockedReason(streaming, lastUsedAt, nowMs)` disables "Probe now" for a non-streaming asset with the reason beside it, `Needs a live link — vehicle is offline (18h)`, age formatted through `core/telemetry/telemetry-logic.ts#humanAge` (this app's one duration vocabulary, the same function `core/fleet/triage-logic.ts#offlineLabel` itself calls) — never a new formatter; a `lastUsedAt`-less asset (never reported at all) degrades to an honest `Needs a live link — this vehicle has never been online` rather than fabricating an age. `ReadinessFacade` gained `assetStreaming`/`assetLastUsedAt` (read off the *existing* `getAsset(assetId)` call already made for `displayName` — `AssetDetails extends AssetSummary`, so `status`/`lastUsedAt` were already on the wire response, just previously discarded; no new HTTP call) and `probeReason` (a `computed()`, not a plain alias like this facade's other logic re-exports, since it also needs the current clock — mirrors `DronePickerFacade#groups`'s own documented "`Date.now()` read directly inside this `computed()`" idiom). 6 new `readiness-logic.spec.ts` cases (`hasBeenProbed` ×2, `probeBlockedReason` ×4, including the exact "18h" text and a future-timestamp clamp to `0s`).
  - **O1 — roster leads, create-on-demand.** `/org` opened on a 5-to-7-field create form with the roster/tree starting below it, the same hierarchy inversion E1 named for the replay page. Both tabs' create forms now render inside an `@if (…FormOpen())` panel *above* their roster, revealed by a primary `Create user`/`Create group` button in `vision-page-bar`'s `[pageBarActions]` slot (its first real consumer on this page); the roster/tree itself is the first thing visible on load, matching the plan's E1-mirroring intent exactly. `OrgSettingsFacade` gained `userFormOpen`/`groupFormOpen` — plain, non-persisted, independent boolean signals, the same view-toggle carve-out this facade's own `tab` signal already documents (not a `UiStore` group: neither form is an overlay another route/component needs to stay consistent with) — plus `open…Form()`/`close…Form()` pairs; `submitUser()`/`submitGroup()` call the matching `close…Form()` only inside their existing `if (created)` success branch, so a failed create leaves the form open with whatever the operator typed still in it. Each form's `<form>` element gained `(keydown.escape)`, bubbling from whichever field has focus — the same local (non-`document`-level) listener idiom `zones-panel.html`/`layer-manager.html`'s own inline-cancel bindings use, appropriate here since this is inline page content, not a floating overlay — plus a `Cancel` button, both wired to the same `close…Form()`. `OrgStore`/`VisionApi` untouched — every fetch/mutation, its wire shape, and its one-toast error handling are byte-identical to before this wave; "keep the facade API unchanged" is satisfied at that layer, the only new surface is `OrgSettingsFacade`'s own local UI-state (same tier `tab` already lived at). The two empty-state messages ("No users/groups yet") were re-worded from "with the form above" (now false — the form isn't visible until the button is clicked) to name the `Create user`/`Create group` button instead. No component spec exists for this page (none existed before this wave either — pure-logic-only convention, and this page's own logic is entirely `core/org/org-logic.ts`, untouched); the new toggle state is covered by `tsc`+the architecture guard per this codebase's own no-facade-spec convention.
  - Role-gating: unchanged on both surfaces. `ReadinessPage` still has no client-side role gate (authority for probe/remediate is enforced server-side, D8 — a denial surfaces as an inline error, same as before this wave); `/org` is still gated entirely at the route level (`orgGuard`, ADMIN/MANAGER only) — neither page's guard/visibility logic was touched. Dev parity: unaffected on both — `assetStreaming`/`assetLastUsedAt`/`probeReason` derive from the same `getAsset()` response every deployment already returns regardless of `vision.auth.enabled`, and the dev admin (ADMIN/unbounded) reaches `/org` exactly as before, roster-first like any other ADMIN/MANAGER. Degrades honestly: `probeBlockedReason` never invents an age for a vehicle that's never reported; `hasBeenProbed` reads the backend's own null-vs-not fact rather than inferring it from row text that could drift; a failed `getAsset` read (already `Promise.allSettled`, pre-existing) degrades `assetStreaming`/`assetLastUsedAt` to `false`/`undefined` — "Probe now" reads as blocked rather than fabricating streaming state, same posture `displayName`'s existing null fallback already took.
  - `npx tsc --noEmit -p tsconfig.app.json` and `-p tsconfig.spec.json` both clean. `npm run test:ci` green on the shared tree: **154/154 files, 2903/2903 tests** (+6 over the prior 2897 recorded by W1 — this wave's own `readiness-logic.spec.ts` additions; W1's own reported 2903 total already reflects these, confirming no conflict on the shared tree). `ng build --configuration production` green, same two pre-existing budget warnings only (initial bundle 25.52 kB over its 390 kB budget; `tactical-map.css` 1.95 kB over its 8 kB budget — both untouched by this wave, which added no bytes to either the initial bundle or that stylesheet). Per this cycle's own updated instruction (three agents sharing this tree; stashing risks corrupting a concurrent agent's in-progress files), bundle delta was read directly off this build: initial bundle measured 415.52 kB raw / 116.22 kB transfer, byte-for-byte the same as the immediately-preceding waves' own last-recorded numbers on this shared tree, confirming this wave never touches the initial bundle (both surfaces are lazy routes). `org-settings` lazy chunk measured 15.83 kB raw / 3.66 kB transfer; `readiness` lazy chunk measured 9.52 kB raw / 3.02 kB transfer — first time either chunk is individually recorded in this file's Status log, so no true before/after delta is available without the disabled stash technique; reported as absolute measurements, same posture W1 already adopted for its own `replay` chunk. No new endpoint: `probeReason`/`assetStreaming` reuse the existing `VisionApi#getAsset`/`#assetReadiness`/`#probeAsset` calls; O1 adds no HTTP call at all, only local view state.
- **2026-08-29, docs/plans/active/OPERATOR-UX-7-PLAN.md findings B1 + W1, §2 B1/W1, wave W2 bell + rail:** the bell's unread badge now survives a reload, and the events rail defaults to the fleet's own events with one status indicator per row — see the `shared/ui/` bullets above (`NotificationBell`, `EventsRail`+`EventRow`) for full per-finding detail. Touched only `shared/ui/notification-bell.*`, `shared/ui/notification-logic.*`, `shared/ui/events-rail.*`, `shared/ui/event-row.*`, per this wave's own file scope, alongside two parallel agents on the same shared tree (W1 pre-flight, `features/preflight/**`; W3 devices, `features/devices/**`), all disjoint file lists, no conflicts.
  - Role-gating: none — the bell and the events rail have never been role-gated (any authenticated viewer who can reach a page rendering them could already see every event on it), and neither fix changes a permission surface. Dev parity: unaffected — `readIds`/`includeRemoved`/`isRemovedDeviceSource` are all pure client-side derivations over data every existing poll (`EventsStore`) already returns regardless of `vision.auth.enabled`; the dev-admin principal sees identical behavior to a real deployment. Degrades honestly: a corrupt or unparseable `vision.bell.readIds` value falls back to a cold-start seed rather than throwing or fabricating a partial read-set (`loadPersistedReadIds`'s own try/catch); a `localStorage` write failure (quota, private browsing) is swallowed — never worse than the pre-B1 in-memory-only behavior, never surfaced as a fake error; `removedCount`/`isRemovedDeviceSource` never invent a removed-device verdict — they read the exact same `describeEventSource` fallback string every row already renders.
  - **Plan defect found:** the plan's own wave table names this wave "W2 bell + rail" while W1's own finding text is P1 (a *different* plan, OPERATOR-UX-3's `/operate/preflight` triage) — no collision in practice since the two P1/W1 labels live in different plan documents read independently, but worth flagging since this cycle now has an OPERATOR-UX-7 "W1 pre-flight" wave *and* an unrelated OPERATOR-UX-3 finding "P1" both touching `features/preflight/**` in the same doc-comment neighborhood; a future reader grepping this file for "W1" alone will find bullets from at least three different plans.
  - `npx tsc --noEmit -p tsconfig.app.json` and `-p tsconfig.spec.json` both clean. `npm run test:ci` green on the shared tree: **154/154 files, 2956/2956 tests** — this wave's own share is 17 new cases (4 `seedReadIds` + 3 `pruneReadIds` + 3 `isRemovedDeviceSource` in `notification-logic.spec.ts`; 4 new TestBed cases in `notification-bell.spec.ts` for cold-start/persisted-restore/reload/corrupt-value; 3 new `event-row.spec.ts` cases for the dot-vs-chip exclusivity and DOM order); no `events-rail.spec.ts` exists or was added (no TestBed spec precedent for this component — its own pure helper is covered via `notification-logic.spec.ts` instead, per this codebase's "favor pure-logic vitest" convention). The remaining delta over the last-recorded 2903 baseline is concurrent W1/W3 work landing on this same shared tree mid-session (confirmed by `git status` showing only `features/preflight/**`/`features/devices/**` touched outside this wave's own 11 files) — hit one genuine shared-tree false-negative mid-verification: `features/preflight/preflight.ts` briefly carried an accidental `*/` inside a markdown-bold JSDoc phrase (`vehicles**/**Simulated**` — the `**` immediately followed by `/` forms a literal comment-close token, prematurely terminating the file's own outer `/** … */` block and cascading `tsc`/build parse errors through the rest of that comment), which failed the shared tree's `tsc --noEmit -p tsconfig.app.json` and the whole-app `npm run test:ci` build for several minutes; proved this wave's own 3 spec files green in isolation during that window (`ng test --watch=false --include=…`, 3/3 files, 52/52 tests, through the real Angular builder, never bare `vitest run`), then re-ran both `tsc` configs and the full suite clean once the concurrent W1 agent's own edit resolved it — never touched that file, out of this wave's scope entirely. `ng build --configuration production` green, same two pre-existing budget warnings (now 27.29 kB / 1.86 kB over, versus the prior wave's own last-recorded 25.52 kB / 1.95 kB — the `tactical-map.css` warning shrank slightly, from concurrent work outside this wave's scope, not from anything touched here). Per this cycle's own explicit instruction (three agents share this tree; do not stash), bundle delta was read directly off this build rather than isolated via `git stash push`/`pop`: initial bundle measured 417.29 kB raw / 116.47 kB transfer, versus the prior wave's own last-recorded 415.52 kB / 116.22 kB — **+1.77 kB raw / +0.25 kB transfer**, from `NotificationBell`'s eagerly-loaded shell code (the new persistence helpers/effect/methods) plus `EventsRail`'s new `isRemovedDeviceSource` import and checkbox markup, both in the initial bundle since neither component is lazy. This isn't stash-isolated against the concurrent W1/W3 waves' own in-flight edits, so the true isolated cost may be marginally smaller; reported as the best available absolute measurement, the same posture the two immediately-preceding waves on this shared tree already adopted for the identical reason. No new endpoint anywhere in this wave: `readIds` persistence reads/writes only `localStorage`; `isRemovedDeviceSource` reads `sourceLabel`'s own already-computed string, no new fetch.
- **2026-08-29, docs/plans/active/OPERATOR-UX-7-PLAN.md finding P1, §2 P1, wave W1 pre-flight:** `/operate/preflight` triages like `/fly`/Command and stops fabricating a checklist for a row that was simply never probed — fixing the live-walkthrough finding that eighteen assets (real rovers, one IP camera, 14 simulated aircraft) all rendered as identical `Unknown` rows with a nonsensical `Map position +10` attention cell. **Built on the same shared tree as concurrent W2 bell + rail (`shared/ui/notification-bell.*`/`notification-logic.*`/`events-rail.*`/`event-row.*`) and W3 devices (`features/devices/**`)** — touched only `features/preflight/**`, disjoint file lists, no conflicts. `preflight-logic.ts` gained: `isNeverProbedRow(row)` (true when `verdict === 'UNKNOWN'` **and** every evaluated feature reads `UNKNOWN` — the exact wire signature `DefaultReadinessService#evaluateFeature` produces for *both* "no `VehicleProfile` at all" and "profile incomplete", verified against source); `hasTelemetryCapableDevice(devices)`; `FleetVehicleRow` (a `ReadinessRow` enriched with `category`/`status`/`lastUsedAt`/`hasTelemetryDevice`) + `buildFleetVehicleRows(rows, detailsById)`; `vehicleStatusOverride(row)` (`'Not probed yet'` / `'No telemetry device'` / `undefined`); `buildBoardGroups(rows)` (Your vehicles / Simulated via `core/fleet/triage-logic.ts#isSimulated`, each re-sorted worst-verdict-first by this file's own `sortWorstFirst` rather than kept in `groupAndSort`'s bundled streaming-first order — this board's whole point is severity, not what's flying right now); `boardCounts(rows)` (adds a `notProbed` count alongside `go`/`noGo`/`unknown`). `sortWorstFirst`/`applyVerdictFilter` widened to `<T extends ReadinessRow>` so they work on the enriched row too; `applyVerdictFilter` gained the `'NOT_PROBED'` filter and now excludes never-probed rows from `'UNKNOWN'` (P1: "UNKNOWN counts only probed-but-undecided rows"). `preflight.html`/`.css` gained the two `.group-label` sections (mirrors `command.css`'s `.rail-group-label`/`drone-picker.css`'s `.picker-group-label` exactly — structural-label register, frontend-style §8) with a "Hide simulated" checkbox (shown only once a simulated row exists) persisted under the *same* `vision.fly.hideSimulated` key `/fly`'s picker and Command's rail already use; a 4th `Not probed` stat tile/filter; and a `.verdict-note` class for the plain-text override (zero chips for that row, frontend-style §5's one-chip-per-row rule, vs. the muted `Unknown` chip before this wave) with its attention cell rendering `—`.
  - **Plan defect #1 — `hasBeenProbed` is not reusable as instructed.** The finding's own text says to reuse `core/readiness/readiness-logic.ts#hasBeenProbed` (built one wave earlier, OPERATOR-UX-6-PLAN.md R1, several bullets above in this log) — that function reads `ReadinessReport#profileObservedAt`, a field that exists only on the **per-asset** report (`GET /api/assets/{id}/readiness`), not on the fleet board's own compact `ReadinessRow` (`GET /api/fleet/readiness`: `assetId`/`displayName`/`verdict`/`features` only, verified against `models.ts`). Fetching the full report per row just to read one boolean would double this page's own network cost for no visible gain, so `isNeverProbedRow` reads the equivalent fact straight off the wire shape the board already has instead — flagged for whoever owns the plan next; either the finding should say "derive it from every feature reading `UNKNOWN`" or the fleet board's own DTO should grow the field. A side effect: `ReadinessRow` carries no `detail` text, so the board **cannot** distinguish "never probed" from "probed but incomplete" the way the per-asset report can — both fold into the same `Not probed yet`/`No telemetry device` copy, which this wave treats as an honest approximation (an incomplete probe has produced no more usable per-feature verdict than no probe at all) rather than a defect.
  - **Plan defect #2 — "keep the facade's service calls unchanged" could not be honored literally.** Triage grouping needs `category` and the no-telemetry-device check needs `devices`/`capabilities` — neither exists on `ReadinessRow`, `AssetSummary`, or `AssetAttention` (every bulk endpoint this facade previously called); only `AssetDetails` (`GET /api/assets/{id}`) carries all of `category`/`status`/`lastUsedAt`/`devices` in one shape. `PreflightFacade#loadVehicleDetails` therefore fans a `getAsset` read out over every row after `fleetReadiness()` resolves — precedented verbatim by `features/assets/assets-facade.ts#refreshAssets`'s own `Promise.all(summaries.map(asset => api.getAsset(asset.assetId)))` — and degrades silently on failure (`buildFleetVehicleRows`'s documented safe fallbacks: `category: ''`, `status: 'OFFLINE'`, `hasTelemetryDevice: undefined` → reads the softer `Not probed yet`, never a guessed `No telemetry device`); a failure here never blocks the page or fabricates data, it just leaves that row's enrichment absent, same posture `AssetsFacade` already takes.
  - **A known-unreachable count, kept honest rather than hidden.** Under the current backend, `verdict` can only be `UNKNOWN` when `isNeverProbedRow` is also true (`DefaultReadinessService#evaluate` has no path to an evaluated-but-`UNKNOWN` verdict) — so `boardCounts().unknown` (and the `Unknown` stat/filter) is expected to read `0` on every deployment today. Kept as its own separately-computed count rather than hard-coded to `0`, so it starts reporting real rows the moment the backend ever grows one, with no client change — a forward-compatible design choice, not a bug in this wave's own code.
  - Role-gating: unchanged — `/operate/preflight` has never been role-gated (reading a drone's own readiness is not a management action), and this wave adds no permission surface. Dev parity: unaffected — `isNeverProbedRow`/`hasTelemetryCapableDevice`/`buildFleetVehicleRows`/`isSimulated` all derive from data every existing fetch already returns regardless of `vision.auth.enabled`; the dev admin (ADMIN/unbounded) sees the same triaged board any ADMIN would. Degrades honestly: a failed `fleetReadiness()` read still shows the pre-existing full-page error state (unchanged); a failed/pending per-row `getAsset` enrichment never fabricates `category`/`status`/telemetry-device evidence, it defaults to the safe values above.
  - `npx tsc --noEmit -p tsconfig.app.json` and `-p tsconfig.spec.json` both clean (one shared-tree false-negative found and fixed along the way: an early doc-comment draft wrote `**Your vehicles**/**Simulated**`, whose adjacent `**/` is a literal `*/` that closed the enclosing JSDoc block early and threw `preflight.ts`'s trailing lines into raw syntax, briefly failing the shared tree's `tsc`/`test:ci` for the concurrent W2 agent too — see W2's own bullet above; fixed by spacing the bold markers apart, `**Your vehicles** / **Simulated**`, then grepping every touched file for the same `**/` trap, which came back clean). `npm run test:ci` green: **154/154 files, 2956/2956 tests** (this wave's own share: `preflight-logic.spec.ts` gained 34 new cases across `isNeverProbedRow`/`hasTelemetryCapableDevice`/`buildFleetVehicleRows`/`vehicleStatusOverride`/`buildBoardGroups`/`boardCounts` plus widened `applyVerdictFilter`/`emptyFilterTitle` coverage for `NOT_PROBED`; `preflight.spec.ts` gained 7 new TestBed cases covering the "vehicles" page-bar copy, the never-probed/no-telemetry-device verdict text + dash attention cell, the two triage groups' headers/counts, "Hide simulated" collapsing only the Simulated rows, and the `Not probed` stat tile's count/filter — the total matches W2's own reported 2956, confirming no conflict on the shared tree). `ng build --configuration production` green, same two pre-existing budget warnings only (initial bundle unaffected — `preflight` is a lazy route). `preflight` lazy chunk 7.69 kB → 13.61 kB raw (**+5.92 kB**), 2.66 kB → 3.89 kB transfer (**+1.23 kB**) — the triage/grouping/override logic this wave added. No new endpoint: `fleetReadiness()` unchanged; the new `getAsset` fan-out reuses the existing `VisionApi#getAsset` call every asset-detail/grid page already makes, no new REST surface.
- **2026-08-29, docs/plans/active/WAREHOUSE-UX-PLAN.md §3.1, wave W1 (branch `feat/warehouse-ux`):** the sidebar's three role-based hubs (Operate/Monitor/Manage) became **five job-based groups** — OPERATE, MONITOR, FLEET, VISION, SYSTEM — and every `badge: 'soon'` stub left the rail outright. `features/hubs/nav-entries.ts` is rewritten: `NavModeId` is now `'operate' | 'monitor' | 'fleet' | 'vision' | 'system'` (was `'operate' | 'monitor' | 'manage'`); `NavEntry.badge`/the `'soon'` literal type, `NavEntry.group`, `NavTiers`, `navTiers()`, and `isAdvanced()` are all deleted outright (grepped clean — nothing outside `nav-entries.ts`/its spec/`app-sidebar.*` referenced any of them). `NavMode` gained one field, `footer?: boolean`, true only for `system` — `AppSidebar#bodyModes`/`#systemMode` (new computeds, replacing the flat `modes` the template used to `@for` over directly) split the five groups by it, and `app-sidebar.html` renders `system`'s `.nav-group` inside `.sidebar-foot`, between `<vision-demo-button>` and `<vision-identity-chip>`, with byte-identical markup to a body group (same label-not-link `.group-label`, same flat `@for` entry loop) — satisfies NAV-IA-REDESIGN §2.1 rule 1 (group headers are labels, never links) in both locations. The old `primary`/`advanced`/`upcoming` three-tier-per-group rendering (`<details class="disclosure">`, `SidebarStore#advancedOpen`/`upcomingOpen`, `AppSidebar#onAdvancedToggle`/`onUpcomingToggle`) is deleted from `app-sidebar.ts`/`.html`/`.css` — every group now renders one flat entry list; `system` **is** the new "advanced" tier (WAREHOUSE-UX-PLAN.md §3.1 rule 1's own framing), and there is no replacement for "upcoming" — the four routes that used to carry `badge: 'soon'` (Flight plans/missions, Saved Wall layouts, Firmware, Maintenance/health) keep their route + the `ComingSoon` page (`hubs.routes.ts`, untouched — no deep link/bookmark 404s) but have **no nav entry anywhere**, per rule 1: "Maintenance stays out of the rail (arrives wave W7)" generalized to every unbuilt area, not just Maintenance.
  - **Four renames** (route unchanged, label only): Assets → **Inventory** (`/assets`), Add source → **Add vehicle** (`/add-source`), Pilots / roster → **Crew** (`/manage/roster`), Pre-flight checklist → **Readiness** (`/operate/preflight` — `preflight.html`'s own `<h1>` already read "Fleet readiness", so no page change was needed, only the nav label). "Cockpit" (`/fly`) is **not** renamed to "Fly" — the plan's own §3.1 group listing ("OPERATE (Fly, Wall, Readiness)") names groups' contents in shorthand prose, not literal entry labels; only the four "X → Y" arrows above are real rename instructions, and `nav-entries.spec.ts`'s own frozen "canonical cockpit label" test already asserted "Cockpit" before this wave.
  - **Detection defaults (`/settings/detection`) and Controller (`/manage/controller`) left the rail entirely** (rule 5) — both routes are untouched and ungated (neither page has manager-only content), but neither has a nav entry any more. `features/settings/account-settings.html` grew a new "Settings" card with a plain link list — Account (the page itself, rendered as inert text, not a self-link), Detection defaults, Controller, and (new) Organization, the last shown only when `AccountSettingsFacade#canManage` (new `computed(() => canManageOrg(auth.user()?.topRole))`, the same predicate `identity-chip.ts`/`app-sidebar.ts` already gate their own `/org` affordance on — a pilot session never sees the Organization link, mirroring the route's own `orgGuard`). `AccountSettingsPage` now imports `RouterLink` alongside `PageBar`; still injects only its facade (`core/ui/architecture.spec.ts`'s guard unaffected).
  - **Route guards now mirror `managerOnly` nav entries (rule 5).** `canActivate: [orgGuard]` (`core/org/org-guard.ts`, unchanged) added to seven routes that had a `managerOnly` nav entry but no route-level gate: `features/devices/devices.routes.ts` (`/devices`), `features/debug/debug.routes.ts` (`/debug`), `features/categories/categories.routes.ts` (`/manage/categories`), `features/reports/reports.routes.ts` (`/manage/reports`), `features/labeling/labeling.routes.ts` (all three routes — `/manage/training` and its two drill-ins, now inside the VISION group where every entry is `managerOnly`), and `features/models/models.routes.ts` (`/manage/training/models`), `features/onboarding/onboarding.routes.ts` (`/add-source`). `features/audit/audit.routes.ts`, `features/roster/roster.routes.ts`, and `features/geo/geo.routes.ts` already carried the guard from earlier waves — confirmed unchanged. `features/system-status/system-status.routes.ts` (`/manage/system`) is **deliberately left ungated** (SYSTEM-STATUS-PLAN §5.1 — an operator whose CV pipeline died needs to see why), matching its nav entry's own non-`managerOnly` status; `/assets` stays ungated too.
  - **Entry-count reconciliation (rule 6): a PILOT sees 10, exactly as instructed; a MANAGER/ADMIN sees 20, not the plan's own "15".** WAREHOUSE-UX-PLAN.md §3.1's own illustrative "25 → 15 for a manager, 11 → 10 for a pilot" is the plan's *eventual*, post-W7 estimate — it bakes in `Maintenance` (wave W7, not built) and the merged Inventory page's tab consolidation (wave W4, not built), and summing the plan's own §3.1 group listing independently gives 17-18 for "15" even before placing Asset categories/Inventory reports/Devices anywhere (the plan's own mermaid diagram never names a group for any of the three). This wave is a pure IA regroup (OQ5: "W1 ships first as pure IA"), not a feature removal, so those three fully-built, still-working pages stay in FLEET rather than vanishing with no replacement — `nav-entries.spec.ts`'s new count test asserts the true 10/20 split and documents this reconciliation in its own doc comment, per the task's own "if your count differs, assert the true count and report the difference" instruction.
  - Role-gating: unchanged in spirit, tightened in mechanism — every `managerOnly` nav entry (`canManageOrg(topRole)`, ADMIN/MANAGER) now has a matching route guard, closing the gap where a pilot who guessed/bookmarked a gated URL (e.g. `/devices`, `/manage/training`) could previously reach the page directly even though the rail never showed it. Dev parity: unaffected — `vision.auth.enabled=false`'s dev principal still resolves to ADMIN, so `canManageOrg` is `true` and every gated route/link (including the new Settings-page Organization link and all seven newly-guarded routes) stays reachable exactly as before. Degrades honestly: unchanged — no new fetch, no new fabricated state; the Settings link list is static markup plus one boolean gate, and a failed `AuthStore` load simply leaves `canManage()` `false` (Organization link hidden), never a broken link.
  - **Left incomplete, out of this wave's declared file scope** (`features/hubs/nav-entries*`, `shared/ui/app-sidebar/**`, `app.routes.ts`, `features/*/*.routes.ts` guards-only, plus `features/settings/**` for the link list): `core/shell/sidebar-store.ts`'s `advancedOpen`/`upcomingOpen` signals and their setters/togglers are now unused (nothing in `app-sidebar.ts` reads them any more) but were left in place rather than deleted, since removing them would also require editing `core/shell/sidebar-store.spec.ts`, outside this wave's file list — flagged for whoever next touches that store.
  - `npx tsc --noEmit -p tsconfig.app.json` and `-p tsconfig.spec.json` both clean. `npm run test:ci` green: **154/154 files, 2968/2968 tests** (up from the prior wave's own last-recorded 2956 — this wave's own share is net +12: `nav-entries.spec.ts` was rewritten wholesale around the five-group model, `app-sidebar.spec.ts` gained a new footer-placement test and a new "no disclosure/no dimmed row anywhere" test while losing its two disclosure-specific tests, and `app.routes.spec.ts`'s hub-redirect test was narrowed to the three legacy ids instead of iterating all five `NAV_MODES`). `ng build --configuration production` green, same two pre-existing budget warnings only (now 23.35 kB over the 390 kB initial-bundle budget, down from the prior wave's own 27.29 kB — this wave's deletions outweigh its additions; `tactical-map.css`'s own 1.86 kB budget overage is untouched by this wave). Bundle delta isolated via a disposable `git worktree add <tmp> HEAD` baseline build (not `git stash`, per this file's own shared-tree convention — a stash would have caught two other agents' unrelated in-progress dirty files, `core/rc/manual-control-client*` and files outside `station/vision-web` entirely): **initial bundle 417.36 kB → 413.35 kB raw (−4.01 kB), 116.47 kB → 115.86 kB transfer (−0.61 kB)** — net *smaller*, since deleting the tier/disclosure machinery (CSS + template + two component methods) outweighs the new Settings-page link list and the (already-shared) `orgGuard` import added to seven route files. No new endpoint anywhere in this wave: every guard reuses the existing `orgGuard`/`canManageOrg`, and the Settings link list adds no `VisionApi` call.
- **2026-08-29, docs/plans/active/WAREHOUSE-UX-PLAN.md §3.3/§4, wave W7 (Maintenance page + Crew tabs):** new `/fleet/maintenance` (`MaintenancePage`/`MaintenanceFacade`, `core/maintenance/maintenance-logic.ts`) and `/manage/roster` reworked into **Crew** (`CrewPage`, `?tab=roster|org`) — see the `maintenance/` and `roster/, org-settings/` bullets above for full detail. `core/api/models.ts`/`vision-api.ts` gained the client mirror of W3's own D1/D2 wire contract: `InventoryState`, `AssetIdentity`, `AssetCustody` (new optional fields on `AssetSummary`), `MaintenanceKind`, `MaintenanceRecord`, `CreateMaintenanceRecordRequest`, `InventoryAction`/`InventoryActionRequest`, and `VisionApi#listAssetMaintenance`/`#createMaintenanceRecord`/`#closeMaintenanceRecord`/`#setAssetInventory`. **`AssetSummary`'s new `identity`/`custody`/`inventoryState` fields are optional in TS despite the backend always sending them** — required would have broken ~12 out-of-scope spec files' `asset({...})` fixture helpers across `features/**`; flagged for a future mechanical sweep to tighten them back to required once those fixtures are updated. Role-gating: `orgGuard` on both new/changed routes (`/fleet/maintenance`, `/manage/roster`), unchanged from before — grounding/closing/releasing is a manager-level action. Dev parity: unaffected, `vision.auth.enabled=false`'s dev admin stays ADMIN/unbounded. Degrades honestly: a per-asset maintenance-history read that fails drops that asset from the KPI/table counts rather than fabricating a row (mirrors `RosterFacade.load`'s existing `listAssetPilots` precedent); the empty state ("Nothing grounded · every vehicle is in stock or in the field") only ever renders once assets have actually loaded. **No fleet-wide maintenance endpoint exists** (flagged again as a follow-up for W3/`vision-warehouse` in `docs/plans/active/WAREHOUSE-UX-CONTEXT.md`'s new "W7 status" section) — `MaintenanceFacade.load()` is O(assets currently `MAINTENANCE`) requests, not O(1). **Handoff to W4:** this wave's own exit criterion ("`/manage/health` redirects to a real page") is not met by this wave alone — `features/hubs/hubs.routes.ts` (out of this wave's file scope) still needs the redirect to `/fleet/maintenance`, and `nav-entries.ts` still needs its own "Maintenance" entry; see the context doc's W7 section for the explicit handoff. `npx tsc --noEmit -p tsconfig.app.json`/`-p tsconfig.spec.json` — zero errors in any file this wave owns; both configs still fail on two other in-progress waves' own uncommitted files on this shared tree, confirmed by `git status` (`features/inventory/inventory.routes.ts` — W4, `import('./inventory')` resolving to a component file that doesn't exist on disk yet; `features/reports/reports-logic.spec.ts` — a `CategoryCounts` fixture not yet updated for W4's own in-progress `inStock`/`issued`/`inField`/`maintenance`/`retired` additions to that same `models.ts`). `npm run test:ci`/`ng build --configuration production` both fail identically on `inventory.routes.ts`'s unresolved import — esbuild can't resolve the module at all, which blocks the whole-project bundle outright, confirmed to be a whole-graph compile rather than scoped to the failing files in an earlier pass of this same wave (`ng test --include='src/app/core/maintenance/**'` surfaced the identical errors) — the same class of shared-tree blocker this file's W1/W2 Status entries above already document and resolve once the other wave lands. This wave's own new `core/maintenance/maintenance-logic.spec.ts` (21 `it` cases, pure functions/no DI) was reviewed by hand rather than run to green; recommend re-running the full suite once `features/inventory/inventory.ts` lands to confirm it and get a real bundle delta for the two new lazy chunks (`maintenance`, and `crew` replacing the old `roster` chunk).
- **2026-08-30, docs/plans/active/WAREHOUSE-UX-PLAN.md §3.4/§4, wave W6 (add-vehicle wizard rebuild):** the onboarding wizard is now **Identify · Connect · Prove · Register · Hand over** — see the `onboarding/` bullet above (features section) for the full per-step rewrite, the fit-out table, the legacy-simulate-path limitation, the `?deviceId=` prefill contract, and the registration-attribute-key discrepancy; `docs/plans/active/WAREHOUSE-UX-CONTEXT.md`'s new "W6 status" section carries the same detail plus the exact shared-file diffs. File scope kept to `features/onboarding/**`, `core/onboarding/**` (new); this wave's small additive edits to the shared `core/api/models.ts`/`vision-api.ts` (`CreateAssetRequest.identity?`/`AssetEdit.identity?`; `vision-api.ts` ended up with **no net change** — this wave's own `assetCustody` method was found to duplicate W4's concurrently-added `setAssetCustody`, so it was removed and its doc-comment folded into W4's method instead) are **not committed by this wave's own commit** — `models.ts`'s fields are already present in `HEAD` (swept into W7's commit `cae23506` incidentally) and `vision-api.ts`'s doc-comment merge sits inside W4's own still-uncommitted method with no clean hunk boundary to isolate, so it rides along whenever W4 commits that file (see `WAREHOUSE-UX-CONTEXT.md`'s W6 status section for the full reasoning) — did not touch `features/inventory|assets|devices|asset-detail/**` (W4), `app.routes.ts`, `nav-entries.ts`, or `features/maintenance|roster|org-settings/**` (W7).
  - Role-gating: unchanged — the wizard has never been role-gated beyond its existing route guard (`orgGuard` was already on `/add-source` per wave W1's own route-guard sweep), and this wave adds no new permission surface; the Hand-over custodian picker reuses the pre-W6 pilot-candidate read (`creatorOwnershipGroup`/`pilotsInGroup`), unchanged. Dev parity: unaffected — `vision.auth.enabled=false`'s dev admin (ADMIN/unbounded) reaches every step exactly as before; the pre-existing "Dev-parity trap" gotcha (Root group id mismatch, documented above) still applies unchanged to the Hand-over picker, same as it did to the old Assign step. Degrades honestly: every new background read (`categories` for `categoryConnected`, the `?deviceId=` prefill, `listUsers` for custodian candidates) is silent-degrade on failure, matching this wizard's own pre-existing convention for `loadCategoryOptions`/`loadSystemNetwork` — never a blocked page, never a fabricated value; a failed Hand-over write is worded so it's never mistaken for a failed asset creation (the asset demonstrably already exists by the time Hand-over can render at all).
  - `npx tsc --noEmit -p tsconfig.app.json`/`-p tsconfig.spec.json` — zero errors in any file this wave owns (two pre-existing spec-file issues fixed along the way, both mine: `fit-out-logic.spec.ts` passing `{}` instead of the full `{sense,sight}` shape to `canAdvanceFromFitOutProve`, and `onboarding-logic.spec.ts`'s `row()` helper writing `role` twice, TS2783). Both configs still fail on the same two other in-progress waves' own files W7's own Status entry above already documents: `features/inventory/inventory.routes.ts` (W4, component file not yet on disk) and `features/reports/reports-logic.spec.ts` (a `CategoryCounts` fixture not yet updated for W4's own in-progress fields) — confirmed via `git status` neither is in this wave's scope. `npm run test:ci`/`ng build --configuration production` both fail identically on `inventory.routes.ts`'s unresolved import, retried repeatedly across this wave's session (including after a session-limit reset) with the identical result each time — the same whole-graph blocker W7 already hit, not something that cleared on its own. An isolated `ng test --include='src/app/features/onboarding/*.spec.ts' --include='src/app/core/onboarding/*.spec.ts'` was attempted (W2's own precedent) but hit the identical module-resolution error — unlike W2's type-only blocker, a missing module fails the whole-graph bundle before any test can run, so this wave's spec files were reviewed by hand against the rewritten `onboarding-logic.ts`/`fit-out-logic.ts`/`onboarding-store.ts`/`onboarding-facade.ts` APIs rather than run to green. No bundle delta available for the same reason (the production build itself doesn't complete on this shared tree yet); recommend re-running the full chain once `features/inventory/inventory.ts` lands.
- **2026-08-30, docs/plans/active/WAREHOUSE-UX-PLAN.md §3.1 rule 3/§3.3/§3.4, wave W4 (Inventory page — the wave W6 and W7 both handed off to):** `/assets` is now the tabbed Inventory page — see the `inventory/`, `devices/`, `asset-detail/` bullets above (features section) for the full per-tab rewrite, the Identity/Custody/Maintenance detail-panel additions, the KPI-strip provenance, the double-page-bar trade-off, and the two Firmware/Hours backend discrepancies. `features/assets/**` and `features/reports/**`'s page are deleted outright (their routes now redirect — see Routes above); `features/devices/**`/`features/categories/**` are unmoved, just mounted as tab content. New `core/fleet/inventory-logic.ts` (tabs + state chip + export filename) and `features/inventory/{inventory-facade,inventory-page-logic,vehicles-logic}.ts` (+ `vehicles-table.*`) are this wave's own pure-logic surface, alongside `features/categories/categories-logic.ts`'s new create/rename write-half functions.
  - **Closes both W6's and W7's own handoffs to this wave**: `/manage/health` → `/fleet/maintenance` (`hubs.routes.ts`); `nav-entries.ts`'s Fleet group gained the **Maintenance** entry (`managerOnly`, targeting `/fleet/maintenance`) and dropped **Asset categories**/**Inventory reports**/**Devices** (folded into Inventory's own tabs) — `nav-entries.spec.ts`'s manager count moves from W1's own recorded 20 to **18** (20 − 3 folded + 1 new Maintenance); the pilot count is unaffected at **10** (none of the three folded entries nor Maintenance was ever pilot-visible). Also closed in passing (found while re-reading W6's own onboarding bullet, not itself in this wave's file list): the persistence-layer V28 migration bug that bullet flagged (`attributes ->> 'registration'` instead of the frontend's actual historical `'registrationNumber'` key) — landed as its own standalone commit `280b208e` (`storage/persistence/**`, outside this wave's vision-web scope but the same session), which now coalesces both keys before dropping them.
  - **Role-gating**: unchanged in outcome, moved one level down — Links/Categories used to carry `orgGuard` on their own routes; now that both routes redirect, the gate lives *in* the page instead (`core/fleet/inventory-logic.ts#visibleInventoryTabs`/`isInventoryTabVisible`, `InventoryFacade#setTabFromQueryParam` clamping an unauthorized `?tab=` guess back to `vehicles`) — a pilot who bookmarks `?tab=links` still never sees gated content, just enforced by the tab bar instead of `canActivate`. Vehicles/Equipment stay open to every signed-in role, as `/assets` always was.
  - **Dev parity**: unaffected — `vision.auth.enabled=false`'s dev admin resolves `canManageOrg` `true`, so every tab, every kebab verb, and the Categories write-half all render exactly as they would for a real ADMIN/MANAGER session.
  - **Degrades honestly**: a failed `loadAll()` shows one `vision-notice` (danger) scoped to the Vehicles/Equipment tabs only — Links/Categories mount their own independent facades and keep working even when Inventory's own fleet fetch fails (never a blocked page). A per-asset maintenance-history read that fails clears the drawer to `[]` rather than showing stale/wrong records (`InventoryFacade#loadMaintenance`'s `catch`). Firmware/Hours render `'—'`, never fabricated, per the backend-discrepancy bullets above.
  - `npx tsc --noEmit -p tsconfig.app.json` and `-p tsconfig.spec.json` both clean — the whole-graph blocker W6's and W7's own Status entries above both hit (`inventory.routes.ts` resolving to a component file that didn't exist yet) is gone now that `inventory.ts`/`.html`/`.css` are in place. `npm run test:ci` green: **160/160 files, 3042/3042 tests** — the first fully-green run since W1's own last-recorded 154/154, 2968/2968, now confirming W1 through W7 all together on one tree, not just this wave's own share (W6/W7 each recorded their own spec additions by hand-review only, never a green run). New/changed spec files this wave: `core/fleet/inventory-logic.spec.ts` (new), `features/inventory/{vehicles-logic,inventory-page-logic}.spec.ts` (new), `features/hubs/nav-entries.spec.ts` (Fleet group rewritten), `features/categories/{categories-logic,categories.routes}.spec.ts` (write-half + redirect), `features/devices/devices.routes.spec.ts` (new, redirect), `features/reports/reports.routes.spec.ts` (new, redirect — `reports-logic.spec.ts` deleted with the rest of that page), `features/asset-detail/asset-detail-logic.spec.ts` (Identity/Since-service cases added, `effectiveRegistration`'s own cases moved out), `core/fleet/asset-attributes.spec.ts` (`effectiveRegistration` cases moved in from `asset-detail-logic.spec.ts` once the function itself moved there — second-consumer-to-`core/`, the same precedent this file cites throughout). `ng build --configuration production` green, same two pre-existing budget warnings only (23.46 kB over the 390 kB initial-bundle budget; `tactical-map.css`'s own 1.86 kB overage untouched): **initial bundle 413.35 kB → 413.46 kB raw (+0.11 kB), 115.86 kB → 115.83 kB transfer (−0.03 kB)** versus W1's own last-recorded numbers — effectively flat, since almost everything this wave touches is lazy. New/changed lazy chunks: **`inventory` 81.57 kB raw / 16.40 kB transfer** (replaces the old `assets` chunk — no prior recorded baseline to diff against, since `features/assets/**` predates this file's own bundle-tracking discipline; also statically absorbs `DevicesPage`/`CategoriesPage`, since a redirect route no longer gives either its own `loadComponent` chunk — this is *why* the double-page-bar trade-off above exists, both pages compile straight into `inventory`'s own chunk now rather than lazy-splitting as its children), **`asset-detail` 71.91 kB raw / 16.12 kB transfer** (small increase from the Identity editor + Since-service tile). `onboarding` (98.09 kB / 23.60 kB), `crew` (35.06 kB / 7.74 kB), `controller-setup` (62.92 kB / 14.84 kB) are each measured here for the first time on a successful build — W6/W7 own Status entries above both admit their own builds never completed on this shared tree, so there is no prior number for any of the three to diff against; nothing in this wave's own diff touches any of them beyond the shared `core/api/models.ts`/`vision-api.ts`/`core/fleet/asset-attributes.ts` edits, which cost none of them anything measurable. No new endpoint: every mutation this wave's UI calls (`setAssetCustody`/`setAssetInventory`/`listAssetMaintenance`/`createMaintenanceRecord`/`closeMaintenanceRecord`/`createCategory`/`updateCategory`/`inventoryExportUrl`) is W3's/W6's own already-shipped `VisionApi` surface.
  - **What couldn't be fully absorbed, and why** (full detail in the `inventory/` bullet above): Reports' bar chart and needs-attention list are **not** carried forward (the table's own columns/filters already answer both questions, a design choice, not an omission); Links/Categories show a **double page-bar** (`DevicesPage`/`CategoriesPage` mounted verbatim, each keeps its own `<vision-page-bar>` — fixing it needs an `embedded` input on both, mirroring W7's own `RosterPage`/`OrgSettingsPage` precedent, not built this wave); **Firmware** and **Hours** columns are always `'—'` (no fleet-wide source for either exists on the backend today — an N+1 per-asset fetch was deliberately not added for either).
- **2026-08-30, docs/plans/active/WAREHOUSE-UX-PLAN.md §3.3/§4, wave W9 (consume W8's backend facts — one-call maintenance, firmware + hours columns, embedded Links/Categories):** closes three of W4's own open items above in one pass, all backend-facts-driven by W8's new wire surface (`docs/plans/active/WAREHOUSE-UX-CONTEXT.md` "W8 → W9 handoff") — see the `maintenance/` and `inventory/` bullets above (features section) for the full per-surface rewrite.
- **2026-08-30, docs/plans/active/WAREHOUSE-UX-CONTEXT.md W10 (live-walkthrough sweep — six findings, all frontend-only), wave W10:** fixed six findings from a live click-through of the wave W4/W7/W9 surface, entirely inside `station/vision-web` (no backend change, no `VisionApi` shape change). **N1** (`shared/ui/app-sidebar/**` only) — see the `ui/**` bullet above. **A1** — "+ Add source"/"Add a source" → "Add vehicle" consistently across `inventory.html`'s button, `onboarding.html`'s title, and `onboarding.routes.ts`'s route title; see the `inventory/` bullet above. **E1** — Readiness column hidden on Inventory's Equipment tab; see the `inventory/` bullet above. **C1** — three Crew-page fixes, see the `roster/, org-settings/` bullet above for C1(b)/C1(c) (`countAssetsWithoutPilot`'s new `connectedCategorySlugs` param, the new `CustodyStatus` union); C1(a) ("Roster/Org tabs render off-screen right") never reproduced across an 8-width live sweep (1416→375px) and is covered defensively by the shared O1 page-bar fix instead of a page-specific patch, since `shared/ui/page-bar/**` is C1(a)'s own named file scope. **M1** — three Maintenance-page fixes, see the `maintenance/` bullet above (`actorLabel`/`ROOT_ACTOR_ID` → "Station" for "Opened by", shared `pluralize` for the "N record(s)" subtitle, `isLastOpenRecordForAsset`-driven Close/Release button treatment). **O1** — page-bar actions stranding at 1416px on Inventory/Maintenance/Categories; see the `ui/**` bullet above (same fix as C1(a)).
  **Live-verification limitation, flagged honestly:** M1's fixes (and O1's Maintenance-page instance) could not be confirmed against the page's actual rendered content — the dev backend on `localhost:8080` (`ng serve`'s proxy target, PID belonging to a concurrent IntelliJ-launched session, not restarted since doing so would disrupt that session) is running from a `target/classes` snapshot predating `/api/maintenance`'s controller mapping (confirmed via `strings` on the freshly-recompiled `.class` file vs. the loaded JVM's stale classpath), so `GET /api/maintenance` 404s in the dev proxy regardless of frontend correctness. N1/O1/C1(a)'s shared `page-bar`/`app-sidebar` fixes *were* live-verified (Chrome-devtools iframe injection, `getBoundingClientRect()`/`scrollWidth` measurement — not screenshot inspection) across Inventory/Categories/Maintenance/Crew, since those pages' own chrome renders before any `/api/maintenance` call resolves. M1/E1/A1/C1(b)/C1(c) were implemented via careful reading of the existing wave W7/W9 code (`maintenance-facade.ts`, `roster-facade.ts`, `vehicles-table.ts`, `core/audit/summary-logic.ts`'s pre-existing `actorLabel`/`ROOT_ACTOR_ID` precedent) plus new pure-logic spec coverage, and validated via the full `tsc`+`test:ci`+`ng build` chain rather than a live click-through.
  **Design/reuse notes:** M1's "Opened by" fix deliberately reuses the app's *existing* root-principal vocabulary (`core/audit/summary-logic.ts#actorLabel`/`ROOT_ACTOR_ID`, already shown as "Station" on the Audit page for the identical `UUID(0,0)` fact) rather than inventing a second word ("system") for the same concept — one owner, same rule this wave's own `pluralize` fix and the pre-existing E3 finding both establish. Role-gating unchanged everywhere in this sweep (`orgGuard` on Maintenance/Crew, unchanged); dev-parity unaffected (`vision.auth.enabled=false`'s dev admin — itself `ROOT_ACTOR_ID` — now reads "Station" in the Maintenance table rather than a raw id fragment, which is *more* honest under dev-parity, not a behavior change). Degrades honestly throughout: `custodyStatusFor`/`custodyLabel`/`custodyTitle` never fabricate a state, `countAssetsWithoutPilot` reads zero (not "everything") while categories haven't resolved yet, `isLastOpenRecordForAsset` only changes which button is *styled* primary, never which backend call either one makes.
  **File scope respected:** touched only `core/maintenance/maintenance-logic.{ts,spec.ts}`, `core/audit/summary-logic.ts` (read-only, reused as-is — no edit), `features/maintenance/{maintenance-facade.ts,maintenance.page.html,maintenance.page.ts}`, `features/roster/{roster-facade.ts,roster.html,roster-logic.ts,roster-logic.spec.ts}`, `features/inventory/{inventory.html,vehicles-table.html,vehicles-table.ts}`, `features/onboarding/{onboarding.html,onboarding.routes.ts}`, `shared/ui/app-sidebar/{app-sidebar.html,app-sidebar.css}`, `shared/ui/page-bar/{page-bar.html,page-bar.css}` — never touched `drone-link/**`, `infra/rover-sim/**`, or `core/rc/manual-control-client.*` (another concurrent session's own dirty files on this shared tree, explicitly out of scope) or `features/devices/**` (a third session's own pre-existing dirty files, also untouched).
  **Verify chain, all green:** `npx tsc --noEmit -p tsconfig.app.json` — 0 errors. `npx tsc --noEmit -p tsconfig.spec.json` — 0 errors. `npm run test:ci` — **160 spec files / 3065 tests, all passing** (up from 3042 tests recorded at the wave-W4 baseline note above — every one of this wave's new `it` cases: 4 in `maintenance-logic.spec.ts#isLastOpenRecordForAsset`, 2 new + updated in `roster-logic.spec.ts#countAssetsWithoutPilot`, 6 in a new `custodyStatusFor` describe block, 4 in a new `custodyStatusLabel`/`custodyStatusTitle` describe block). `npx ng build --configuration production` — green, exit 0; the same two pre-existing budget warnings as every prior wave's own Status entry (initial bundle 413.80 kB vs. 390 kB budget, `tactical-map.css` vs. its own 8 kB budget) — neither is new or worsened by this sweep (this wave's shared-file edits, `page-bar.css`/`app-sidebar.css`, are a few dozen bytes of CSS each, and every feature-page change landed inside its own already-lazy chunk: `maintenance-page` 13.10 kB raw/3.75 kB transfer, `crew` 35.87 kB/7.92 kB, `inventory` 84.10 kB/16.90 kB). No isolated before/after bundle diff was taken for this wave (would have required `git stash push` scoped to `station/vision-web` on a tree shared with two other live sessions' own uncommitted work — judged not worth that risk for a CSS-only/copy-only/pure-logic sweep); the absolute sizes above are this wave's own final numbers.
  **Not committed** (per this task's own instruction) — every file above is staged-ready.
  - **`core/api/models.ts`/`vision-api.ts`** gained the client mirror of W8's own additions: `Firmware` (`{name?, version?}`, nested on `AssetSummary#firmware`), `AssetSummary#totalFlightSeconds`, `MaintenanceListState` (`'open'|'closed'|'all'`), `FleetMaintenanceRecord` (mirrors `dto.FleetMaintenanceRecordResponse` — carries its own `assetName`/`categoryId`, the endpoint's whole point), and `VisionApi#fleetMaintenance(state='open', limit?)` → `GET /api/maintenance`.
  - **Maintenance page is now one fleet-wide read, not an N-per-`MAINTENANCE`-asset fan-out** — `MaintenanceFacade.load()` calls `fleetMaintenance('all')` once, inside the same blocking `Promise.all` as `listAssets`/`listUsers` (a deliberate honesty change: a failed read now shows the page's own error empty-state rather than a silently-incomplete table, since maintenance records are this page's primary content, not enrichment). `core/maintenance/maintenance-logic.ts` rewritten to operate on the flat `FleetMaintenanceRecord[]` directly — the pre-W9 `AssetMaintenanceRow{asset, record}` join type is gone, `openRecordRows`/`recentlyClosedRecordRows` renamed to `openRecords`/`recentlyClosedRecords` and no longer asset-joining, `maintenanceKpis` groups internally via a new private `groupByAssetId`. `hoursSinceClose` is retyped to a minimal `{closedAt?: string}` structural interface (not `FleetMaintenanceRecord`) so it still serves `asset-detail-logic.ts#sinceServiceTile`'s unrelated per-asset `MaintenanceRecord` call — found by grep before it could become a compile break, not after.
  - **Vehicles/Equipment table's Firmware/Hours columns render real values** — `vehicles-logic.ts#firmwareLabel(firmware)` (`"ArduPilot 4.5.1"`, a small name-code label map, `'—'` only when both fields are absent) and `formatFlightTime(asset.totalFlightSeconds ?? null)` (the pre-existing `core/fleet/asset-stats-logic.ts` duration formatter, reused rather than re-implemented, per the task's own instruction). Equipment tab still hides both columns (no "flown"/firmware concept for non-vehicle rows, unchanged from W4).
  - **Links/Categories tabs' double page-bar is fixed** — `DevicesPage`/`CategoriesPage` both gained `embedded = input(false)` (`[class.page]="!embedded()"`, own `<vision-page-bar>` swapped for a plain non-sticky `.embedded-toolbar` row via `<ng-template>`+`NgTemplateOutlet`), the exact pattern W7 set for `RosterPage`/`OrgSettingsPage`; `inventory.html` passes `[embedded]="true"` to both mounts.
  - **Item 3(b), the two flagged empty-state quick-adds — investigated, nothing to delete.** `features/assets/**` no longer exists (deleted outright by W4); `devices-facade.ts` has no quick-add of any kind, only `goToAddSource()` (navigates to the wizard). The one remaining bypass button, `InventoryFacade#registerSimulator` on the Vehicles/Equipment empty state, is a *different* facade than either named target and is already documented above (W4's own bullet) as the deliberate, accepted relocation of the old `assets-facade.ts` quick-add — not a second bypass this wave was asked to remove.
  - **Role-gating**: unchanged — `orgGuard` on `/fleet/maintenance` (grounding/closing/releasing stays manager-level); Links/Categories tab visibility unchanged (`InventoryFacade#visibleTabs`, `canManageOrg`-gated in the page).
  - **Dev parity**: unaffected — `vision.auth.enabled=false`'s dev admin resolves `canManageOrg`/every kebab verb exactly as before; none of this wave's reads or writes are auth-conditional beyond the pre-existing gates above.
  - **Degrades honestly**: `firmwareLabel`/`formatFlightTime` render `'—'` only when the underlying field is genuinely absent — a real single-field firmware value or genuine zero flight-hours (`formatFlightTime(0)` → `'0m'`) is never coerced to a dash. `MaintenanceFacade.load()`'s folded `Promise.all` (see above) is this wave's one honesty *trade*: previously a failed per-asset maintenance read silently dropped that one asset from the counts; now any failure blocks the whole page behind one error notice — reasoned as more honest for primary content, documented in the facade's own class doc comment.
  - `npx tsc --noEmit -p tsconfig.app.json`/`-p tsconfig.spec.json` both clean — one real compile-break was caught and fixed mid-wave (`hoursSinceClose`'s retyping above, found by grepping every caller before assuming the rewrite was safe). `npm run test:ci` green: **160/160 files, 3049/3049 tests** (+7 over W4's own last-recorded 3042 — `maintenance-logic.spec.ts` rewritten for the flat-array signatures plus two new honesty cases, `vehicles-logic.spec.ts` gained a `firmwareLabel` describe block plus real-value/genuine-zero cases replacing the old always-`'—'` assertion). `ng build --configuration production` green, same two pre-existing budget warnings only (23.58 kB over the 390 kB initial-bundle budget; `tactical-map.css`'s own 1.86 kB overage untouched) — versus W4's own last-recorded numbers (the immediately-preceding vision-web wave on this branch; W8 was backend-only, no `ng build` of its own to compare against): **initial bundle 413.46 kB → 413.58 kB raw (+0.12 kB), 115.83 kB → 115.88 kB transfer (+0.05 kB)** — effectively flat. **`inventory` lazy chunk 81.57 kB → 83.93 kB raw (+2.36 kB), 16.40 kB → 16.90 kB transfer (+0.50 kB)** — `firmwareLabel`/`formatFlightTime` plus the new embedded-toolbar templates for the statically-absorbed `DevicesPage`/`CategoriesPage` (per W4's own note, both compile into this same chunk, not their own). **`maintenance-page` lazy chunk 12.71 kB raw / 3.62 kB transfer** — measured here for the first time: W7 (which built the page) never got a clean build on this shared tree to record one, and W4's own entry didn't name it. No new endpoint beyond W8's own already-shipped `GET /api/maintenance`.
  - **Commit not run this wave** — the launching task specified an exact commit message/trailers, but this agent's own governing instructions say not to `git commit` autonomously; every file below is staged-ready, not committed. Suggested message: `feat(vision-web): one-call maintenance page, firmware + hours columns, embedded links/categories (WAREHOUSE-UX W9)`.
- **2026-08-30, docs/plans/active/CV-SETTINGS-PLAN.md §4/§5, wave W6 (Profiles page — CV config hierarchy editor):** built entirely against the plan's own frozen wire contract, on a shared tree with three concurrent Java agents (W2 perception, W3 persistence, W4 learning) actively writing `contexts/vision-learning/**`/`contexts/vision-perception/**`/`storage/persistence/**` — no backend for any of this wave's endpoints exists yet. See the `vision-profiles/` bullet above (features section) for the full surface.
  - **New feature**: `features/vision-profiles/{vision-profiles-logic.ts,.spec.ts,vision-profiles-facade.ts,vision-profiles.ts,.html,.css,vision-profiles.routes.ts}` — profiles list (built-in rows marked read-only, Fork copies into an editable draft), profile editor (model picker off `GET /api/cv/models`, confidence/fps/label allow-deny lists/detection toggle/tracking fields, event rule shown read-only with a note that it's start-time only), bindings panel (ORGANIZATION/CATEGORY/ASSET scope + target picker), coverage table (`GET /api/cv/coverage`, per-row Clear for CATEGORY/ASSET only).
  - **`core/api/models.ts`**: widened `CvModel` with optional `version`/`taskType`/`runtime`/`classes`/`status`/`availability`/`metrics`/`provenance`/`source` (+ new `CvModelMetrics`/`CvModelProvenance`) — every addition `?:` so the pre-existing `features/fly/cv-control-panel-logic.spec.ts` fixture (out of scope) still compiles unchanged. New: `BindingScope`, `CvProfile`/`CvProfileTracking`/`CvProfileEventRule`/`CvProfilesResponse`/`CvProfileRequest`, `CvProfileBinding`/`CvProfileBindingRequest`, `EffectiveCvProfile`, `CvCoverageRow`/`CvCoverageResponse`, `TrainingRun`/`TrainingRunsResponse` (the last pair for W8's future use, unconsumed this wave).
  - **`core/api/vision-api.ts`** gained 8 methods: `getCvProfiles`/`createCvProfile`/`updateCvProfile`/`deleteCvProfile`, `setCvProfileBinding`/`deleteCvProfileBinding` (`DELETE` carrying a JSON body via `{body:...}`), `getEffectiveCvProfile`, `getCvCoverage`. The plan's registry promote/rollback/training-runs endpoints are deliberately **not** implemented — CV-SETTINGS-PLAN.md scopes those to wave W8.
  - **Rail**: `nav-entries.ts`'s VISION group gained a new first entry, **Profiles** (`/vision/profiles`, `managerOnly: true`, icon `layers`) — `nav-entries.spec.ts`'s manager-visible-entry count moved 18→19 (doc-comment math updated alongside). `app.routes.ts` imports `VISION_PROFILES_ROUTES`; the route itself is `orgGuard`-guarded (confirmed by reading `core/org/org-guard.ts`: it *is* exactly `canManageOrg` at the route level, resolving `AuthStore.ready` first).
  - **Deleted outright** (not repointed): `features/settings/detection-settings.{css,ts,html}` + `detection-settings-facade.ts` + `detection-settings-logic.{ts,spec.ts}` — 6 files. `features/settings/settings.routes.ts`'s `/settings/detection` route is now `{path:'settings/detection', pathMatch:'full', redirectTo:'vision/profiles'}` (the exact `redirectTo` shape already used by `hubs.routes.ts`'s `/manage/health` redirect). `account-settings.html`'s own "Settings" card link list dropped its "Detection defaults" `<li>`. `core/ui/architecture.spec.ts`'s `ROUTED_PAGES` swapped `settings/detection-settings` for `vision-profiles/vision-profiles`.
  - **No `GET /api/cv/bindings` endpoint in the frozen contract** — "what's bound to profile X" is derived client-side from `GET /api/cv/coverage` (`summarizeProfileBindings`, grouped by `profileId`) rather than a second read. Per-row Clear on the coverage table is scoped to `CATEGORY`/`ASSET` sources only (`coverageRowClearTarget` returns `null` for `ORGANIZATION`/`PLATFORM` — clearing an org default from one fleet-wide row would have organization-wide blast radius); a separate "Clear organization default" button targets the caller's own primary group (`primaryGroupId(memberships)`, a documented single-org simplification — `MeResponse` carries a membership list but no page in this app has a multi-org switcher).
  - **Role-gating**: write actions (create/edit/fork/delete/bind) gated `canManageOrg` in the facade; the whole page is additionally route-gated `orgGuard` and rail-gated `managerOnly` — a pilot never reaches `/vision/profiles` at all (stricter than "reads for everyone with org scope," reasoned in the page's own doc comment as resolving an ambiguity between the plan's read-gate wording and the task's explicit `orgGuard` route instruction).
  - **Dev parity**: unaffected — `vision.auth.enabled=false`'s dev admin resolves to ADMIN/unbounded, so `canManageOrg`/`orgGuard` both pass exactly as they do for every other manager-only page.
  - **Degrades honestly**: `VisionProfilesFacade.load()` is one `Promise.all` over all 7 reads (`getCvProfiles`/`getCvModels`/`getCvTrackers`/`listCategories`/`listAssets`/`listAssetGroups`/`getCvCoverage`) — any failure shows one page-level error notice, never a partially-fabricated table (there is no meaningful "partial" state for a bindings/coverage page where every section cross-references the others). `isModelMissingOnWorker` surfaces a profile referencing an unavailable model as a visible notice rather than silently failing to save. Every mutation degrades through the shared `describeHttpError` → `ToastService.error` path, same as every other facade in this codebase.
  - **Backend does not exist yet** — every `VisionApi` method above is coded against §5's frozen JSON shapes exactly (field names, wrapped-list responses), not against a live server; a 404/500 today just exercises the ordinary error-notice path.
  - **W6 → W7/W8 handoff** (docs/plans/active/CV-SETTINGS-CONTEXT.md): `SettingsStore` (`core/settings/settings-store.ts`) is still imported by 20+ files across `shared/player/`, `shared/map/`, `features/wall/`, `features/replay/`, `features/inventory/`, `features/command/`, `features/fly/` (`cv-control-panel.ts`, `stream-state-logic.ts`, `cockpit-facade.ts`, `cv-setup-modal.ts`, `fly-logic.ts`, `fly-redirect-guard.ts`), `features/asset-detail/`, `features/live/`, `features/onboarding/`, `features/settings/account-settings-facade.ts`, `features/devices/`, `core/events/` — none of it touched this wave (fly/live/wall dual-write is W7's own scope). W7/W8 should build against this wave's new TS type names: `CvProfile`/`CvProfileRequest`/`CvProfileBinding`/`CvProfileBindingRequest`/`BindingScope`/`EffectiveCvProfile`/`CvCoverageRow`/`CvCoverageResponse`/`CvProfilesResponse`/`CvProfileTracking`/`CvProfileEventRule`, the widened `CvModel`/`CvModelMetrics`/`CvModelProvenance`, and `TrainingRun`/`TrainingRunsResponse` (typed, unconsumed — W8's to wire up).
  - **Verify chain, all green**: `npx tsc --noEmit -p tsconfig.app.json` — 0 errors. `npx tsc --noEmit -p tsconfig.spec.json` — 0 errors (one fixed mid-wave: `CvProfileRequest as Record<string,unknown>` needed an `as unknown` intermediate cast in `vision-profiles-logic.spec.ts`). `npm run test:ci` — **160/160 files, 3086/3086 tests**. `npx ng build --configuration production` — green, same pre-existing budget warning only (initial bundle a few kB over its 390 kB budget, unrelated to this wave). **Bundle delta**, measured via a pathspec-scoped `git stash push --include-untracked -- <this wave's 15 files>` baseline (chosen over a full-tree stash specifically to avoid disturbing the three concurrent Java agents' own uncommitted work, verified via `git status --porcelain` before/after at each step): initial bundle **413.80 kB → 414.69 kB raw (+0.89 kB), 115.91 kB → 116.09 kB transfer (+0.18 kB)** — from the eager `vision-api.ts` methods, the new `nav-entries.ts` rail entry, and `app.routes.ts`'s new import (all necessarily eager, part of the app shell). New lazy chunk **`vision-profiles` 32.35 kB raw / 7.45 kB transfer**.
- **2026-08-30, docs/plans/active/CV-SETTINGS-PLAN.md §3.5/§6, wave W7 (Fly/Live/Wall dual-write removal + profile honesty):** built entirely inside `station/vision-web`, on a shared tree with a still-running Java W5 agent (`station/vision-api`/`station/vision-app` — never touched, confirmed disjoint throughout via `git status`). See the `fly/` bullet's own new sub-bullet above (feature section) for the full per-control detail; the summary here is the wave's own build-requirements checklist.
  - **Requirement 1 (stop the dual-write)**: `SettingsStore`'s entire CV-defaults slice (`model`/`confidenceThreshold`/`inferenceFps`/`labelFilter`/`labelDenyFilter`/`detectionEnabled`/`tracking`, `effective()`/`adjust()`, `PipelineSettings`) is deleted — no in-flight knob writes it or `localStorage` any more. `StartStreamRequest` now sends no CV body; the server resolves the initial config from the profile hierarchy. Genuinely personal view prefs (`flyAssetId`, `advancedMode`, `autoTts`) are kept, plus the new `declutterLevel` (requirement 4).
  - **Requirement 2 (profile line + save)**: `CockpitFacade` gained `effectiveProfile`/`streamConfig`/`resolvedCvConfig` signals and `refreshEffectiveProfile()`/`refreshStreamConfig()` methods; `cv-control-panel-logic.ts#effectiveProfileLine` renders the one-line disclosure; `CvSetupModal`'s new `canManage`-gated "Save to this asset's profile" footer action does the explicit create-or-update-then-bind. `CvControlPanel`/`CvSetupModal`/`DetectionsStrip` all gained a `configChanged` output, emitted only after a PATCH actually succeeds, wired in `cockpit.html` to `facade.refreshStreamConfig()`; `CvSetupModal` also gained `profileSaved`, wired to `facade.refreshEffectiveProfile()`.
  - **Requirement 3 (H6)**: `CvSetupModal`'s `capabilityLevel`/`verifyEveryMillis`/`followFps` now read back from `GET /api/streams/{id}/config` (`config()?.tracking`, via a constructor `effect()`) instead of being assumed from the last PATCH sent. The faster, pre-existing `detections.tracks()?.stats`-sourced tracking-mode/engine-id readback was deliberately left as-is.
  - **Requirement 4 (H12)**: one persisted `SettingsStore.declutterLevel` (`vision.settings.declutterLevel`, validated on restore via a new `isBoxesMode` guard) replaces `CockpitFacade`/`LiveFacade`/`WallTile`'s three previously-unshared in-memory `boxesMode` signals — all three now `readonly boxesMode = this.settings.declutterLevel;` (the identical signal instance), so every wall tile app-wide now shares one declutter level by design (confirmed intentional against the plan's literal "one shared… replacing the three" wording, not a bug). Labelled "View · Boxes (shortcut: B)" per §3.5.
  - **Requirement 5 (H8)**: `LiveFacade.onConfidence`/`onFps`/`onModel` deleted — grep-confirmed zero template bindings first, then removed as the unused `SettingsStore`-writing duplicates the plan described.
  - **Deviations from the pre-implementation plan** (both reasoned in the touched files' own doc comments): `CvSetupModal#onModelChange` sends two sequential PATCHes (model alone, then a hot-knob-shaped PATCH carrying the seeded `labelFilter`) rather than one combined PATCH — `UpdateStreamConfigRequest`'s own doc comment freezes model-change and hot-knob edits as mutually exclusive on the wire. `cv-setup-modal.html`'s controls stay enabled pre-start (not disabled as first planned) — a new component-local `pendingEdits`/`liveConfig` overlay makes pre-start edits genuinely meaningful (they stage values the new save action writes) and independently fixes a real correctness gap where `debounce()`'s last-call-wins semantics would otherwise drop an earlier field's edit inside one debounce window.
  - **Necessary collateral, outside the declared scope**: `features/devices/devices-facade.ts` (3 lines) — it called the now-deleted `SettingsStore.effective()` as `FleetStore.start()`'s second argument; fixed to keep the build green, confirmed via `git status` the file was otherwise untouched by any concurrent agent.
  - **Role-gating**: "Save to this asset's profile" is gated `canManageOrg(topRole)` via `CockpitFacade#canManage` (renamed from `canManageOrg` to resolve a naming collision, matching `VisionProfilesFacade`'s own `canManage` precedent), threaded into `CvSetupModal` as an input rather than re-derived — one source of truth, matching W6's own role-gating pattern for `/vision/profiles`.
  - **Dev parity**: `vision.auth.enabled=false`'s dev admin resolves `canManage` exactly as before (ADMIN/unbounded) — the save action is visible in dev exactly as it will be for a real manager.
  - **Degrades honestly**: a failed `getEffectiveCvProfile`/`getStreamConfig` read leaves the respective signal `undefined` (logged via `console.warn`, never thrown/toasted — background enrichment, not the primary act) — the "From profile" line and the H6 tracking fields simply don't update rather than showing a stale or fabricated value.
  - **Verify chain, all green**: `npx tsc --noEmit -p tsconfig.app.json`/`-p tsconfig.spec.json` — 0 errors both. `npm run test:ci` — **160/160 files, 3079/3079 tests** (down 7 from W6's 3086 — this wave deleted `SettingsStore`'s CV-defaults describe blocks along with the code they tested; no new spec files were needed, since every change routed through existing `*-logic.ts` files whose specs already covered the touched pure functions). `npx ng build --configuration production` — green, same two pre-existing budget warnings only. **Bundle delta**, measured via a pathspec-scoped `git stash push -u -- station/vision-web` baseline (same isolation technique W6 used, for the same reason — multiple concurrent agents on this shared tree): initial bundle **414.69 kB → 419.78 kB raw (+5.09 kB), 116.09 kB → 118.20 kB transfer (+2.11 kB)** — from the new `resolvedCvConfig`/`effectiveProfile`/`streamConfig` plumbing and the "Save to this asset's profile" action, all inside the eagerly-loaded `cockpit`/`fly` surface (no new lazy chunk this wave). No new endpoint: every call this wave makes (`getEffectiveCvProfile`, `getStreamConfig`, `createCvProfile`/`updateCvProfile`/`setCvProfileBinding`) was already on `VisionApi` from W6. **Commit not run this wave** — governing instructions say not to `git commit` autonomously; every file is staged-ready. See `docs/plans/active/CV-SETTINGS-CONTEXT.md`'s "W7 → W8" handoff for the full per-requirement detail.
  - **Not committed** (per this task's own instruction) — every file above is staged-ready, not committed.
- **2026-08-30, docs/plans/active/CV-SETTINGS-PLAN.md §3.2/§3.3/§4/§5.2/§6, wave W8 (Models registry rewrite + shared CV sub-nav + persisted training-run history — the plan's final wave):** built inside `station/vision-web`, on a shared tree with a still-running Java W5 agent (`station/vision-api`/`station/vision-app` — never touched, confirmed disjoint via `git status` before and after). See the new `labeling/, models/, training-jobs/` bullet above (features section) for the full per-page detail; the summary here is the wave's own build-requirements checklist.
  - **Requirement 1 (three missing `VisionApi` endpoints)**: `promoteModel(id, version)` widened to `Promise<PromotionResultResponse>`; new `rollbackModel()` (`POST /api/cv/registry/rollback`); new `getTrainingRuns()`/`getTrainingRun(runId)` (`GET /api/cv/training/runs[/:runId]`). Coded directly against the real, concurrently-evolving W5 Java DTOs (`CvModelResponse`/`CvModelMetricsResponse`/`CvModelProvenanceResponse`/`PromotionResultResponse`, read off the shared tree's own uncommitted files, not the plan's §5.2 text alone) — this surfaced and fixed a real bug-in-waiting: W6's `CvModel.status` union used `'ARCHIVED'`, which the backend never actually sends; corrected to the real `'DRAFT'|'CANDIDATE'|'LIVE'|'RETIRED'`.
  - **Requirement 2 (Models page rewrite)**: status/runtime/availability/metrics/provenance/source-notice/promote/roll-back — see the features-section bullet above for the full column-by-column detail and the `canAdministerRegistry` (ADMIN-only, narrower than `canManageOrg`) gating rationale.
  - **Requirement 3 (run history + run detail)**: new `RunHistoryPage`/`RunDetailPage`; `training-jobs.routes.ts` gained `manage/training/runs`/`manage/training/runs/:runId`, both `orgGuard`.
  - **Requirement 4 (shared sub-nav)**: new `shared/ui/cv-subnav.*` (`CvSubnav`, +spec, 5 cases), wired into `ModelsPage`, `DatasetsPage`, and the new `RunHistoryPage` — the Vision rail entries themselves are untouched.
  - **Deviations from the launching task's own working plan** (each reasoned in the touched file's own doc comment): no separate `run-detail-logic.ts` — `run-history-logic.ts` (+spec, 10 cases) serves both `RunHistoryPage` and `RunDetailPage`, since every pure helper the detail page needs was already there once the list page had it; a `RUNNING` run's produced-model notice deliberately does **not** link to `/manage/training/jobs/:runId` — nothing on the frozen wire contract guarantees `TrainingRun#runId` equals the separate, in-memory `TrainingJobResponse#jobId`, and fabricating that link would violate CLAUDE.md's "degrade honestly" rule; Roll back's confirm dialog names only the model being retired, not a guessed restoration target, for the same reason.
  - **Role-gating**: Promote/Roll back gated `models-logic.ts#canAdministerRegistry` (ADMIN/unbounded scope only, matching `ModelRegistryController#promote`/`#rollback`'s own `canAdminister()` — narrower than the page's own `orgGuard`); the Training tree (`manage/training/runs[/:runId]`) gated `orgGuard` (`canManageOrg`, matching `TrainingJobService#runs`/`#run`); the Models roster read and all of Labeling stay open to any signed-in user, unchanged.
  - **Dev parity**: `vision.auth.enabled=false`'s dev admin is ADMIN/unbounded, so both `orgGuard` and `canAdministerRegistry` pass exactly as they do for a real admin — every gated affordance in this wave is visible/usable in dev exactly as it will be in production for the matching real role.
  - **Degrades honestly**: `ModelsFacade.source === 'config'` renders every row plus one notice rather than a blocked page (the roster read never errors by contract); `isMissingOnWorker` is an explicit warning line, never hidden; metrics are always labelled "Training mAP50", never presented as held-out evaluation; a `409` on Promote/Roll back reads as its own plain-language notice, a `404` as "not enabled here" — never a raw error dump; `RunHistoryFacade`/`RunDetailFacade` both render `vision-empty` on a `404` (feature disabled / unknown run), never a blocked page or a fabricated row.
  - **Verify chain, all green**: `npx tsc --noEmit -p tsconfig.app.json`/`-p tsconfig.spec.json` — 0 errors both. `npm run test:ci` — **162/162 files, 3121/3121 tests** (+42 over W7's 3079/3079). `npx ng build --configuration production` — green, same two pre-existing budget warnings only (initial bundle over its 390 kB budget, `tactical-map.css` over its own 8 kB budget — neither new nor worsened this wave). **Bundle delta**, measured via a pathspec-scoped `git stash push -u -- station/vision-web` baseline (same isolation technique W6/W7 used, for the same reason — a live Java agent on this shared tree): initial bundle **419.78 kB → 420.28 kB raw (+0.50 kB), 118.20 kB → 118.37 kB transfer (+0.17 kB)** — effectively flat; every substantial addition this wave lands inside already-lazy or newly-lazy chunks, not the eager shell. Changed/new lazy chunks (measured directly off `dist/` by content-grep, since `ng build`'s own summary table truncates past its top 15 and none of these four are named): **`models` chunk ~11.14 kB raw** (Promote/Roll back/provenance/`CvSubnav` markup added to the pre-existing page), **`labeling`/`datasets` chunk ~8.83 kB raw** (`CvSubnav` swapped in for the old header link), new **`run-history` chunk ~5.34 kB raw**, new **`run-detail` chunk ~5.88 kB raw**. No separate `cv-subnav` chunk was split out — small enough that esbuild inlined it into each of the three consuming chunks rather than extracting a shared one.
  - **Commit not run this wave** — governing instructions say not to `git commit` autonomously; every file below is staged-ready, not committed.
  - **W8 → (closing)**: docs/plans/active/CV-SETTINGS-PLAN.md is now fully implemented client-side — waves W1–W8 all built. Backend behavior for several endpoints this wave calls (`getTrainingRuns`/`getTrainingRun`, model promote/rollback's real cv-service semantics) still depends on the concurrently-landing W5 Java work on this same shared tree; every client call here is coded against the plan's own frozen §5.2 contract plus the real DTOs read directly off W5's uncommitted files, not verified against a live server. See `docs/plans/active/CV-SETTINGS-CONTEXT.md`'s own closing handoff (added alongside this MODULE.md update) for the full deviation list.
- **2026-08-30, fix/stream-start-latency:** fixed the Stop→Start latency bug — after restarting a stream the picture ran several seconds behind reality (only a browser refresh brought it near-live). Root cause: the UI attaches `<vision-player>` the instant `POST /stream` returns, but mediamtx has no publisher path yet (opens lazily on first frame); the WHEP offer's resulting 404 threw, landed in `handleWhepFailure`, and `reduceTransportRecovery`'s `webrtc && neverPlayedYet && isWhepFailure` rule downgraded `transport` to `'hls'` **permanently** — `scheduleColdStartRetry` only ever retried HLS, and once HLS started playing, nothing in `Player` ever tried WHEP again for the rest of the page session. HLS's ~5s glass-to-glass vs WebRTC's ~0.4s (docs/conclusions/MEDIA-SOT-RESULTS.md:220-237) was the delay. Five changes, all detailed in the `player/`/Gotchas bullets above: (A) a pre-play WHEP miss now dispatches the same `'playlistNotReady'` event HLS's own cold-start miss uses (new `player-recovery.ts#isWhepPrePlayMiss`), which `reduceTransportRecovery`'s existing `isWhepFailure` exclusion already keeps off the permanent-downgrade path — no reducer change needed, `scheduleColdStartRetry` generalized to a shared `retry` callback so WHEP rides the identical 1.5s cadence/give-up budget; (B) a stream parked on HLS with a `whepUrl` configured re-probes WHEP every `WHEP_RETRY_COOLDOWN_MS` via an isolated, generation-safe probe (new `player-recovery.ts#shouldAttemptWhepUpgrade`, `Player#scheduleWhepUpgradeAttempt`/`attemptWhepUpgrade`/`whepUpgradeInFlight`/`teardownHlsAfterWhepUpgrade`) — an HLS fallback is no longer permanent for the page session; (C) a fresh attach's first FRAG_BUFFERED/`'playing'` now snaps to the live edge, not just a reconnect (new `'firstAttach'` reason in `live-edge-logic.ts#shouldSnapToLive`, `Player#hasPlayedThisAttach`); (D) `teardownMedia` now also nulls `video.srcObject`, and the dead `// --- THE FIX GOES HERE ---` marker in `handleWhepFailure` is gone, replaced by a real `whepUpgradeInFlight` probe-failure guard; (E) `beginWhepAttach` sets `receiver.jitterBufferTarget = 150` (ms) on the video receiver, feature-detected. Touched only `shared/player/player-recovery.ts`, `shared/player/live-edge-logic.ts`, `shared/player/player.ts`, and their `.spec.ts` files — no template changed (`live.html`/`cockpit.html`/`wall-tile.html`/`asset-panel.html` untouched), no Java module touched. Role-gating: none — the player has never been role-gated, and this fix changes no permission surface. Dev parity: unaffected — every change is pure transport-recovery/live-edge logic independent of `vision.auth.enabled`; the dev admin sees the identical faster reconnect. Degrades honestly: a WHEP path that never comes up within the cold-start budget still falls back to HLS exactly as before (bounded, not infinite retry); a failed background upgrade probe never disturbs the visible HLS playback or flickers the transport badge, it just re-arms silently. `npx tsc --noEmit -p tsconfig.app.json` and `-p tsconfig.spec.json` both clean. `npm run test:ci` green: **154/154 files, 2968/2968 tests** (+12 over the prior 2956 baseline — this wave's own new cases: 1 `reduceTransportRecovery` scenario spec, 4 `isWhepPrePlayMiss` cases, 4 `shouldAttemptWhepUpgrade` cases in `player-recovery.spec.ts`; 1 `'firstAttach'` case in `live-edge-logic.spec.ts`; `player.ts` itself has no spec file, per this file's own pre-existing convention — too much WebRTC/hls.js/DOM to mock, covered instead by its pure `player-recovery.ts`/`live-edge-logic.ts` dependencies). `ng build --configuration production` green, same two pre-existing budget warnings only. Bundle delta measured by `git stash`/rebuild/`git stash pop` (isolated, no concurrent agents on this worktree): initial bundle unchanged at 417.36 kB raw / 116.49–116.50 kB transfer both before and after — this wave's ~150 net new lines round to 0 kB at this build's reporting precision. No new endpoint: no `VisionApi` call added or changed.

- **2026-08-31, docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md §3 P4, wave Z1 (Fly cockpit engage/disengage — the session verb gets its first caller):** built entirely inside `station/vision-web`, on a branch whose HEAD already carried the backend half (`feat(zero-config Z1): engage opens telemetry subscriptions — closes TELEMETRY-ONLY B4`, `contexts/vision-perception`/`AssetSessionController`, not this task's scope). See the `fly/` bullet's own new "Wave Z1" sub-bullet above (features section, under `rc-monitor`) for the full surface, naming, and honesty-design detail; this entry is the wave's own build-requirements checklist.
  - **The affordance**: an "Engage link"/"End session" button in `rc-monitor.html` (the Controller drawer), gated by new `rc-monitor-logic.ts#resolveSessionAffordance` — visible only for a `TELEMETRY`-capable asset with no video stream currently `live`, exactly the case that previously had no working path to commandability at all.
  - **No session-status read endpoint exists** — `AssetSessionController` only ever exposed the two write verbs. Rather than the task's suggested "local state tracks the operator's intent" fallback, `CockpitFacade#operatorEngaged` derives the fact honestly off `AssetDetails#recentUsages` (already polled every 5s): `selectOpenUsage(usages)?.origin === 'OPERATOR'`, reusing the existing `telemetry-logic.ts#selectOpenUsage` rather than duplicating it. `core/api/models.ts#AssetUsage` widened with `phase?`/`origin?` (new `UsagePhase`/`UsageOrigin` unions, 1:1 mirrors of the Java DTO) to expose fields that were already on the wire.
  - **`core/api/vision-api.ts`** gained 2 methods: `engageAssetSession`/`disengageAssetSession`, documented as fire-and-confirm (their own return is logged, never rendered as truth). **`core/fleet/fleet-store.ts`** gained `engageAsset`/`disengageAsset`, `run()`-wrapped like every other mutation there. **`features/fly/cockpit-facade.ts`** gained `operatorEngaged` (computed), `sessionBusy` (signal), `engageSession()`/`endSession()` (force an immediate `loadAsset()` re-fetch on success, so the chip flips within one round trip rather than up to a full 5s poll period). **`features/fly/rc-monitor-logic.ts`** gained `resolveSessionAffordance`/`SessionAffordance` (+4 new spec cases in `rc-monitor-logic.spec.ts`) — the single rule deciding `'none'|'engage'|'end'`, deliberately distinct in name from that same file's pre-existing `engage()`/`release()` (`ManualControlClient`'s unrelated RC take-control verb).
  - **Role-gating**: none beyond `AssetSessionController`'s own server-side visibility scope (read-scope, not `canManage` — an operator act on an aircraft the caller may fly, per that controller's own javadoc); the button itself is gated purely on `hasTelemetryDevice`/`live`/`operatorEngaged`, facts every viewer of this drawer already has.
  - **Dev parity**: unaffected — `vision.auth.enabled=false`'s dev admin resolves visibility as unbounded exactly as before; the affordance behaves identically in dev and prod for a given telemetry/live state.
  - **Degrades honestly**: `operatorEngaged`/`live` are read from the same poll every other cockpit fact already trusts, never a local "I clicked it" flag; a failed engage/disengage surfaces through `FleetStore`'s existing `run()` → `describeHttpError` → one toast, and the affordance stays in its pre-click state — no optimistic flip, matching CLAUDE.md's "degrade honestly" rule.
  - **Verify chain, all green**: `npx tsc --noEmit -p tsconfig.app.json`/`-p tsconfig.spec.json` — 0 errors both. `npm run test:ci` — **162/162 files, 3135/3135 tests** (+14 over the prior 3121 baseline). `npx ng build --configuration production` — green, same one pre-existing budget warning only (initial bundle over its 390 kB budget — unrelated to this wave, present at baseline). **Bundle delta**, measured via a pathspec-scoped `git stash push -- <this wave's 10 files>` baseline (isolated from `infra/rover-sim/**`/`drone-link/**`'s own pre-existing unrelated uncommitted changes on this shared tree, confirmed via `git status --porcelain` before/after): initial bundle **420.28 kB → 420.77 kB raw (+0.49 kB), 118.33 kB → 118.38 kB transfer (+0.05 kB)** — the two new eager `VisionApi`/`FleetStore` methods. Lazy **`cockpit` chunk 151.43 kB → 153.82 kB raw (+2.39 kB), 33.06 kB → 33.54 kB transfer (+0.48 kB)**.
  - **Commit**: `station/vision-web/**` only, message prefixed `feat(zero-config Z1): Fly cockpit engage/disengage — session verb gets its first caller`; the tree's pre-existing unrelated uncommitted changes under `infra/rover-sim/**`/`drone-link/**` were left untouched (explicit file paths, never `git add -A`).
- **2026-08-31, docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md §2/§4/§8, wave Z5:** new route `/provision-wifi` — Wi-Fi-over-USB device provisioning via the open Improv Serial standard, entered from `onboarding.html`'s drone-config step via a single new "Provision Wi-Fi over USB" link. See the `provisioning/` bullet above (under `features/**`) for the full file-by-file breakdown (`improv-serial-protocol.ts`, `web-serial-gateway.ts`, `provisioning-logic.ts`, `provisioning-facade.ts`, `provisioning.ts`/`.html`/`.css`, `provisioning.routes.ts`). Touched outside that new folder: `app.routes.ts` (+2 lines, `PROVISIONING_ROUTES` spliced next to `ONBOARDING_ROUTES`), `core/ui/architecture.spec.ts` (`'provisioning/provisioning'` added to `ROUTED_PAGES`), `onboarding.html` (one `<p class="muted hint">` + link inside the existing `@case ('config')` block — no `.ts` change, `RouterLink` was already imported there).
  - **Pure frontend, no new endpoint.** No `VisionApi` call anywhere in this feature — credentials go straight from the browser to the device over USB; the device is never told this platform's own address (§2's research finding), closing with the page's own "what happens next" note (cited in `provisioning.ts`'s class doc): once on Wi-Fi, the device announces itself and the existing discovery inbox picks it up on its own.
  - **Role-gating**: route-guarded `orgGuard` (`core/org/org-guard.ts`, `canManageOrg`) — the same gate `/add-source` uses, since this page does the same fleet-onboarding job as that wizard and is reached from inside it; a pilot who cannot reach `/add-source` cannot reach this by URL guess either.
  - **Dev parity**: unaffected — `vision.auth.enabled=false`'s dev principal resolves to ADMIN, so `orgGuard` passes exactly as it does for `/add-source` today (`org-guard.ts`'s own doc comment).
  - **Degrades honestly**: an unsupported browser/insecure context renders `vision-empty`'s explainer, never a broken "Connect" button (`WebSerialGateway#isSupported`, checked live, never cached). A cancelled device picker (`DOMException('NotFoundError')`) returns to `idle` quietly, not as an error banner (`isCancelledPickerError`); every other `requestPort()`/`open()` failure gets specific, non-generic copy (`describeSerialRequestError`). A device that vanishes mid-flow (`onDisconnect`) renders its own distinct `link-lost` banner, never confused with an operator-initiated Disconnect. An unrecognized Improv state/error code renders its own raw value rather than a guess (`improvCurrentStateLabel`/`improvErrorMessage`), and a device-reported redirect URL is shown only when the device actually sent one — never fabricated.
  - **Serial port test seam**: `ImprovSerialConnection` (`onData`/`onDisconnect`/`write`/`close`) is the one interface `ProvisioningFacade` depends on for hardware — `WebSerialGateway` (the only file touching `navigator.serial`) is DI-swappable (`{ provide: WebSerialGateway, useValue: fake }`), so nothing in this feature needs real hardware, a secure context, or a Chromium browser to test. No `provisioning-facade.spec.ts`/component TestBed spec was added — this codebase has zero `*-facade.spec.ts` precedent anywhere (grep-confirmed before writing this wave), so the two new spec files instead cover the two genuinely pure layers thoroughly: `improv-serial-protocol.spec.ts` (36 cases — frame build/parse round-trip, checksum rejection, each RPC result shape incl. zero-length data, every error state's own message, the incremental byte-stream reducer's resync-past-noise and never-wedge-on-bad-checksum behavior) and `provisioning-logic.spec.ts` (19 cases — `canSubmitWifiSettings`'s every gating branch, `deviceStateChipTone`, `describeSerialRequestError`'s every DOMException name incl. fallbacks, `isCancelledPickerError`).
  - **Verify chain, all green**: `npx tsc --noEmit -p tsconfig.app.json`/`-p tsconfig.spec.json` — 0 errors both. `npm run test:ci` — **164/164 files, 3193/3193 tests** (this wave's own share: 55 new cases, 36 + 19 above; the architecture guard's three `it.each` suites also gained a `provisioning/provisioning` row each, all passing). `npx ng build --configuration production` — green, same two pre-existing budget warnings only (initial bundle over its 390 kB budget, `tactical-map.css` over its 8 kB budget — both present at baseline, unrelated to this wave). **Bundle delta**, measured via a pathspec-scoped `git stash push -u -m … -- <this wave's exact files>` baseline (the tree's pre-existing unrelated uncommitted changes elsewhere in the repo — `infra/rover-sim/**`, several Java modules — were never touched by the stash, confirmed via `git status` before/after): initial bundle **420.77 kB → 420.94 kB raw (+0.17 kB), 118.38 kB → 118.42 kB transfer (+0.04 kB)** — just the new route's `import()` call site in `app.routes.ts`; everything else in this feature is lazy. New lazy **`provisioning` chunk: 16.65 kB raw, 5.08 kB transfer** (no prior chunk to diff against — this route didn't exist before).
  - **Commit**: `station/vision-web/**` only, message prefixed `feat(zero-config Z5): Improv Wi-Fi provisioning over Web Serial`; the tree's pre-existing unrelated uncommitted changes outside `station/vision-web/**` were left untouched (explicit file paths, never `git add -A`).
- **2026-08-31, docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md §3 P2/§6/§11, wave Z2d (found-device inbox cards — one-click add):** built entirely inside `station/vision-web`, against the already-merged backend contract (commit f09ec9b4: `DiscoveryCandidateResponse`/`RegisterDiscoveryCandidateRequest`/`RegisterDiscoveryCandidateResponse`, `GET /api/discovery/inbox` + `POST .../register` + `POST .../dismiss`, all `manageOrg`-gated, no SSE topic). See the `inventory/` bullet's own new "`<vision-found-devices>`" sub-bullet above (features section) and the `discovery` row in the `core/**` stores table above for the full surface.
  - **New:** `core/discovery/discovery-inbox-logic.ts` (+`.spec.ts`, pure) — method labels, `candidateAgeLabel` (`humanAge`-based), `visibleCandidates`/`DiscoveryInboxVisibility`, `newCandidateCount`/`registeredCandidateCount`/`dismissedCandidateCount`, `candidateActions`/`CandidateActions`, `RegisterDraft`/`defaultRegisterDraft`/`canSubmitRegisterDraft`/`buildRegisterCommand`, `buildDeviceSpecFromCandidate`. `core/discovery/discovery-inbox-store.ts` — `DiscoveryInboxStore`, refcounted `activate()`/`release()`, 30s `PollScheduler` poll, `refresh()`/`register()`/`attach()`/`dismiss()`, private `run()` toast boundary (mirrors `FleetStore`). `features/inventory/found-devices.{ts,html,css}`, `found-device-card.{ts,html,css}`, `add-candidate-dialog.{ts,html,css}`, `attach-candidate-dialog.{ts,html,css}`.
  - **Changed:** `core/api/models.ts` (added `DiscoveryCandidateStatus`/`DiscoveryCandidate`/`RegisterDiscoveryCandidateRequest`/`RegisterDiscoveryCandidateResponse`; fixed a stale doc comment on `RegisterDeviceRequest` that claimed a blanket `[VIDEO]` capability default — it's actually protocol-aware, `mavlink→[TELEMETRY]`, else `[VIDEO]`), `core/api/vision-api.ts` (+3 methods: `listDiscoveryInboxCandidates`/`registerDiscoveryCandidate`/`dismissDiscoveryCandidate`), `features/inventory/inventory.ts`/`.html` (+`<vision-found-devices />` mount, doc comment).
  - **Attach flow deviates from the task brief's literal wording** ("use the existing attach endpoint... with a device spec built from the candidate's suggestedStream") because `AssignDeviceRequest` only ever accepted `{deviceId}`, never a raw protocol/uri/options spec — resolved by reading the real `RegisterDeviceRequest`/`AssignDeviceRequest` Java DTOs and implementing the two-call flow `registerDevice()` → `assignDevice()` documented in the `inventory/` bullet above; this is both correct against the real API and consistent with the brief's own capability-defaulting hint.
  - **Role-gating**: none added client-side beyond the existing `/assets` route (ungated) — `GET/POST /api/discovery/inbox/**` are `manageOrg`-gated server-side; a pilot who lacks `manageOrg` gets an empty/erroring inbox exactly as the backend decides, never a client-side guess.
  - **Dev parity**: unaffected — `vision.auth.enabled=false`'s dev admin resolves `manageOrg` unbounded exactly as before, so the section behaves identically in dev and prod.
  - **Degrades honestly**: `refresh()` on a failed poll `console.warn`s and keeps the last-known candidate list rather than blanking the section or fabricating one; `register()`/`attach()`/`dismiss()` all route errors through `run()` → `describeHttpError` → one toast, with no optimistic status flip — `attach()`'s implicit REGISTERED transition is deliberately left to the next 30s poll (see `buildDeviceSpecFromCandidate`'s own TSDoc), never faked client-side.
  - **Verify chain, all green**: `npx tsc --noEmit -p tsconfig.app.json`/`-p tsconfig.spec.json` — 0 errors both. `npm run test:ci` — **165/165 files, 3217/3217 tests** (+24 over the prior 3193 baseline). `npx ng build --configuration production` — green, same two pre-existing budget warnings only (initial bundle over its 390 kB budget, `tactical-map.css` over its 8 kB budget — both present at baseline, unrelated to this wave). **Bundle delta**, measured via a pathspec-scoped `git stash push -u -- station/vision-web` baseline (the tree's pre-existing unrelated uncommitted changes elsewhere in the repo — `infra/rover-sim/**`, several Java modules from concurrently-landing Z2a/Z2b/Z2c waves on this same branch — were never touched by the stash, confirmed via `git status` before/after): initial bundle **420.94 kB → 421.25 kB raw (+0.31 kB), 118.42 kB → 118.47 kB transfer (+0.05 kB)** — negligible, since the new code is only reachable through the already-lazy `inventory` route. Lazy **`inventory` chunk 84.06 kB → 103.08 kB raw (+19.02 kB), 16.90 kB → 20.49 kB transfer (+3.59 kB)**.
  - **Commit**: `station/vision-web/**` only, message prefixed `feat(zero-config Z2d): found-device inbox cards — one-click add`; the tree's pre-existing unrelated uncommitted/untracked changes outside `station/vision-web/**` (concurrent Z2a/Z2b/Z2c work in `drone-link/**`, `station/vision-app/**`, `device-discovery/**`, `mediamtx.yml`, plus pre-existing `infra/rover-sim/**` changes) were left untouched — explicit file paths staged, never `git add -A`.
- **2026-09-01, docs/plans/active/MAVLINK-COMMANDS-PLAN.md decisions D2d/D3, wave W1 (keyboard action keys — e-stop, hold-to-arm, mode digits through the action dispatcher):** built entirely inside `core/rc/`, on top of the existing axis-key ramp (`W`/`S`/`A`/`D` unchanged) and the existing switch hold-to-fire machinery, no new command path. See the two new Gotchas bullets above (`Keyboard action keys route through ControlActionDispatcher…`, plus its three sub-bullets) for the full design writeup — this entry is the wave's own build/verify record.
  - **Key map**: `Space` = `EMERGENCY_STOP`, fires immediately on `keydown` (auto-repeat suppressed by the edge check, never held) even though a bound switch's own e-stop is usually catalogued dangerous — an accidental e-stop is the safe outcome (D3). `Shift`+`Enter` = `TOGGLE_ARM`, always the 600 ms dangerous hold, resolved against **live** telemetry (`armed()`) at the moment it fires, never a remembered client toggle. Digits `1`-`4` = `SET_MODE` against the vehicle's own `selectableModes[0..3]` (never a hardcoded list); a digit past what the vehicle actually reports resolves to nothing rather than fabricating a mode name; whether it holds is read straight from the catalogue's `SET_MODE` dangerous flag, same rule a bound switch's `SET_MODE` position already follows.
  - **New:** `core/rc/keyboard-action-logic.ts` (+`.spec.ts`, pure, 21 cases) — `actionKeyIdFor` (chord parsing off `KeyboardEvent.code`+`shiftKey`), `resolveActionKey`/`ResolvedActionKey`, `toggleArmVerb`, `newActionKeyPresses` (edge detection, deliberately **no** settled-first-frame swallowing — see the Gotchas writeup), `holdContinues`, and `isTypingTarget` (moved here from `keyboard-rc-input.service.ts`, now shared).
  - **Changed:** `keyboard-rc-input.service.ts` — new `actionKeysDown: Signal<ReadonlySet<ActionKeyId>>`, populated in `onKeyDown`/`onKeyUp` alongside the existing axis-key handling (same `isTypingTarget` guard, same `blur`/`visibilitychange(hidden)`/`setEnabled(false)` deadmen), republished with a fresh `Set` identity every `tick()` while any action key is held so a reactive consumer keeps re-evaluating the hold-duration check; `TICK_MS` exported for spec use. `control-action-dispatcher.ts` — new keyboard hold/edge fields (`keyboardPrev`/`keyHoldId`/`keyHoldSince`, kept separate from the switch's own `holdKey`/`holdPosition`/`holdSince` — see the Gotchas writeup on why sharing one map would be a race, not a simplification), a second constructor `effect()` that refetches `FlightCapability` on `assetId` change (`refreshSelectableModes`, race-guarded, degrades to `[]`), and a keyboard section appended to `onFrame` after the unchanged switch-handling block.
  - **Role-gating**: none beyond what a bound switch already requires — action keys are gated only on `bind()`'s own `enabled` flag (the same asset + command permission `rc-monitor.ts` already resolves before providing this drawer at all); Space/`Shift`+`Enter`/`1`-`4` work whether or not the operator has ever bound a switch layout, since they are not part of any `ControlProfile.actionMap`.
  - **Dev parity**: unaffected — nothing here reads `vision.auth.enabled`; the dev admin's unbounded `enabled` flag reaches this exactly the same way a real operator's scoped one does.
  - **Degrades honestly**: a failed/missing `flightCapabilities` read degrades `selectableModes` to `[]` (`console.warn`s once, never a toast) — mode digits simply do nothing rather than sending a guessed mode name; `armed`/`undefined` telemetry reads as `false` in `toggleArmVerb` (the safer assumption when the platform genuinely doesn't know yet); every fired command still goes through `send()`'s existing ACCEPTED/NO_ACK/error toast split, so a keyboard shortcut is never less honest about its outcome than a bound switch.
  - **Safety edges verified by spec**: a `blur`/`visibilitychange(hidden)`-cleared held-set cancels an in-progress `Shift`+`Enter` hold before it completes (a lost `keyup` can never let a later re-focus finish an abandoned arm); `isTypingTarget` blocks every action key exactly as it blocks axis keys (typing `1` in a form field never switches modes).
  - **Verify chain, all green**: `npm run test:ci` — **166/166 files, 3256/3256 tests** (+39 over the prior 3217 baseline — 21 new `keyboard-action-logic.spec.ts` cases, 10 new `keyboard-rc-input.service.spec.ts` action-key cases, 7 new `control-action-dispatcher.spec.ts` keyboard cases; found and fixed two real defects surfaced only by the full gate, never by a bare `vitest run` on the three touched files alone: `newActionKeyPresses` spec's `.sort()` on a `readonly` array needed `[...pressed].sort()`, and `HTMLElement.isContentEditable` reads `undefined` rather than `false` under this project's DOM test environment, so `isTypingTarget` now wraps it in `Boolean(...)`). `npx tsc --noEmit -p tsconfig.app.json`/`-p tsconfig.spec.json` — 0 errors both. `ng build --configuration production` — green, same two pre-existing budget warnings only. **Bundle delta**, measured against a disposable `git worktree` of `master` (symlinked `node_modules`, isolated from this shared tree's own pre-existing unrelated uncommitted changes in `infra/rover-sim/**`/`drone-link/**`): initial bundle **421.25 kB → 421.25 kB raw (unchanged), 118.45 kB → 118.46 kB transfer (+0.01 kB, rounding noise)** — every touched/new file is only reachable through the already-lazy cockpit route. Lazy **`cockpit` chunk 153.82 kB → 157.20 kB raw (+3.38 kB), 33.50 kB → 34.27 kB transfer (+0.77 kB)**.
  - **Not built / left for a later wave**: `rc-monitor.html`'s own key-legend line does not yet list the action keys (Space/`Shift`+`Enter`/`1`-`4`) alongside the existing axis-key legend — out of this wave's file scope (`rc-monitor.ts`/`.html` were concurrently owned elsewhere); the action keys are fully wired and fire correctly, just not yet advertised in that legend copy. **Closed by wave W2 below.**
  - **Commit**: `station/vision-web/src/app/core/rc/{keyboard-rc-input.service.ts,keyboard-rc-input.service.spec.ts,control-action-dispatcher.ts,control-action-dispatcher.spec.ts,keyboard-action-logic.ts,keyboard-action-logic.spec.ts}` + this `MODULE.md`, message `feat(web): keyboard action keys — e-stop, hold-to-arm, mode digits through the action dispatcher`; the tree's pre-existing unrelated uncommitted/untracked changes outside this list (`infra/rover-sim/**`, `drone-link/mavlink-core/**`, `docs/plans/active/MAVLINK-COMMANDS*`) were left untouched — explicit file paths staged, never `git add -A`.
- **2026-09-01, docs/plans/active/MAVLINK-COMMANDS-PLAN.md wave W2 (keyboard action keys surfaced in the transmitter view — closes wave W1's own "key legend doesn't list them" gap):** presentation-only, built in `shared/ui/transmitter-view/` and `features/fly/rc-monitor{.ts,.html,-logic.ts}` — **no file inside `core/rc/` was touched**, per this wave's own instruction to treat W1's `core/rc/` surface as frozen; every new signal read (`KeyboardRcInputService#actionKeysDown`, `ControlActionDispatcher#holding`, `ControlProfileStore#rules().dangerous`, `FlightCapability.selectableModes`) already existed.
  - **Where it renders**: the transmitter picture (`vision-transmitter-view`), not the plain-text `.key-legend` line below it (which still only covers the axis keys W/S/A/D/arrows, untouched) — the task's own instruction was to reflect hold progress "where the transmitter picture already shows switch hold-to-fire", so the action-key legend is a second `tv-row` list (`.tv-keys`, reusing `.tv-switches`/`.tv-row`/`.tv-row-id`/`.tv-row-actions`/`.tv-cell-text` verbatim — no new CSS idiom, only a hairline `.tv-switches + .tv-keys` divider when a bound-switch list precedes it) directly below the existing bound-switch rows, gated on `source.kind() === 'keyboard'` in `rc-monitor.ts` (`RcSource` only calls `keyboard.setEnabled(true)` — i.e. only attaches the window listeners `actionKeysDown` depends on — while that source is selected, so showing the rows outside that selection would advertise chords that, right now, do nothing).
  - **New**: `shared/ui/transmitter-view/transmitter-view.ts` exports `ActionKeyRow` (`id`/`keyLabel`/`text`/`dangerous`/`pressed`/`holding`) and a `keyRows: readonly ActionKeyRow[]` input (default `[]`, non-breaking for every other caller — the setup wizard's review step, `wizard-step.html`, passes nothing and is unaffected); a single shared `keyGaugePositions: readonly SwitchPosition[] = ['HIGH']` feeds `vision-switch-gauge` per row (a chord is either down or not, the same one-detent shape a bound `BUTTON` draws). `features/fly/rc-monitor-logic.ts` gained `actionKeyRows(selectableModes, dangerousActions, armed, pressedKeys, holding)` (pure, 8 new spec cases) — iterates the fixed `EMERGENCY_STOP`/`TOGGLE_ARM`/`MODE_1..4` order, calling `core/rc/keyboard-action-logic.ts#resolveActionKey` per id (an `undefined` resolution — a mode digit past what `selectableModes` reports — is simply omitted, never a placeholder row) and `toggleArmVerb`/`core/rc/transmitter-view-logic.ts#holdingMatchesRow` for the Arm/Disarm row's live verb and the hold-sweep match, so this legend can never disagree with what `ControlActionDispatcher` will actually do with the same key. `rc-monitor.ts` gained `keyActionRows` — a `computed` wired to `[]` outside the keyboard source, else `actionKeyRows(capabilities()?.selectableModes ?? [], profiles.rules().dangerous, armed(), keyboard.actionKeysDown(), dispatcher.holding())` — and threads it into `<vision-transmitter-view [keyRows]="keyActionRows()">`.
  - **Role-gating**: unchanged from W1 — action keys still gate only on the same asset + command permission (`bind()`'s `enabled` flag) a bound switch already requires; this wave adds no new gate, only visibility of what already fires.
  - **Dev parity**: unaffected — nothing here reads `vision.auth.enabled`; the legend reads the same `capabilities()`/`armed()` inputs the rest of the drawer already receives identically in dev and prod.
  - **Degrades honestly**: a digit past the vehicle's own `selectableModes` renders no row at all (never a fabricated mode name); `capabilities()` still loading or a failed read reads as `[]`, so only Space/`Shift`+`Enter` show until a real capability arrives — never a guessed mode list; `armed() === undefined` reads through `toggleArmVerb` as `'Arm'`, the safer assumption, exactly like W1's own dispatcher-side resolution.
  - **Verify chain, all green**: `npm run test:ci` — **166/166 files, 3273/3273 tests** (+17 over W1's own 3256 baseline — 8 new `rc-monitor-logic.spec.ts#actionKeyRows` cases, 5 new `transmitter-view.spec.ts` `keyRows` cases, 4 new `rc-monitor.spec.ts` TestBed cases covering the keyboard-source gate, live mode names + armed-state verb, the past-the-list omission, and a real `keydown`/`keyup` dispatch lighting the row). `npx tsc --noEmit -p tsconfig.app.json`/`-p tsconfig.spec.json` — 0 errors both. `ng build --configuration production` — green, same two pre-existing budget warnings only. **Bundle delta**, measured against a disposable `git worktree add <tmp> HEAD` baseline (symlinked `node_modules`, isolated from this shared tree's own pre-existing unrelated uncommitted changes in `infra/rover-sim/**`/`drone-link/**`/`station/vision-app/**`): initial bundle **421.25 kB → 421.25 kB raw (unchanged), 118.47 kB → 118.47 kB transfer (unchanged)** — every touched file is only reachable through the already-lazy cockpit route. Lazy **`cockpit` chunk 157.20 kB → 157.83 kB raw (+0.63 kB), 34.26 kB → 34.46 kB transfer (+0.20 kB)**.
  - **Commit**: `station/vision-web/src/app/features/fly/{rc-monitor.ts,rc-monitor.html,rc-monitor-logic.ts,rc-monitor-logic.spec.ts,rc-monitor.spec.ts}`, `station/vision-web/src/app/shared/ui/transmitter-view/{transmitter-view.ts,transmitter-view.html,transmitter-view.css,transmitter-view.spec.ts}` + this `MODULE.md`, message `feat(web): keyboard key map surfaced in the transmitter view`; no file under `core/rc/` was touched (frozen per this wave's instruction); the tree's pre-existing unrelated uncommitted/untracked changes outside this list (`infra/rover-sim/**`, `station/vision-app/**`, `drone-link/**`) were left untouched — explicit file paths staged, never `git add -A`.
- **2026-09-01, docs/plans/active/ASSET-FLOWS-PLAN.md §2 "S1 gate semantics" + B2, wave WB1 (grounded is visible — cockpit banner, Arm reason, readiness nav):** built on `feat/asset-flows-1` alongside a concurrent wave WB2 (severity thresholds, notification bell, drone picker, replay, discovery inbox) on the same shared tree, disjoint file lists — see the `fly/`, `readiness/`, and `preflight/` bullets above for the full per-surface detail (`core/readiness/readiness-logic.ts`'s new grounding parser; `GroundingStore`; `GroundedBanner`; `flight-command-panel.ts#armDisabled`; `readiness-facade.ts#groundedText`/`featureBlockers` fix; `preflight-logic.ts#isInMaintenance`; the B2 cockpit⇄readiness nav links).
  - **Two pre-existing architectural guards drove the final shape, not just style preference.** `core/telemetry/preflight-readiness-independence.spec.ts` (drone-onboarding wave O6) literal-scans `cockpit-facade.ts`'s source for 8 readiness-API tokens and fails if any appear — protects the live-telemetry preflight checklist's independence from the readiness API. `core/ui/architecture.spec.ts` separately regex-scans every `ROUTED_PAGES` entry's own source for `inject(...)` calls and forbids `VisionApi`/any `*Store` (`core/ui/architecture.spec.ts:97`: `injects.filter((name) => name === 'VisionApi' || (name.endsWith('Store') && name !== 'UiStore'))`) — a routed page must go through its facade. A first attempt put the readiness fetch directly in `cockpit-facade.ts` (tripped the first guard) and, after extracting it to `GroundingStore`, injected that store directly in `CockpitPage` (tripped the second). The shape that satisfies both simultaneously — confirmed by reading each guard's actual matching logic before landing it, not just plausibly assumed — mirrors this file's own pre-existing `GeoStore` precedent exactly: `GroundingStore` is its own class (name/API contains none of the 8 banned literal tokens), `CockpitFacade` injects it and drives `track()`/`reset()` from its own `activeAssetId`-keyed `effect()` (co-located with the existing `geo.track`/`geo.reset()` effect), exposing one plain `groundedReason` computed pass-through; `CockpitPage` injects only `CockpitFacade`, listing `GroundingStore` in its `providers:` array (shared injector with `CockpitFacade`, exactly how `GeoStore` is listed there today) without injecting it itself.
  - **Role-gating**: none beyond what every other readiness/preflight surface already enforces server-side (`GET /api/assets/{id}/readiness`/`GET /api/fleet/readiness`, read-scope) — grounding is a visibility fact every pilot viewing an asset already has access to, same as any other readiness row.
  - **Dev parity**: unaffected — `vision.auth.enabled=false`'s dev admin resolves visibility as unbounded exactly as before; `GroundingStore`/`isInMaintenance` read only asset-scoped readiness/inventory data that was already fetched identically in dev and prod.
  - **Degrades honestly**: a failed `GET .../readiness` read leaves `GroundingStore#groundedReason` at `undefined` (`console.warn`, never a toast, never a fabricated "grounded" the read itself couldn't confirm, and never a blocked Arm button over a read failure unrelated to grounding) — Arm stays enabled unless a real blocker was actually observed. `isInMaintenance`'s fleet-board proxy is documented in its own doc comment as broader-than-precise (every `MaintenanceKind` sets `InventoryState.MAINTENANCE`, not only the two that block flight) rather than silently trusted as exact.
  - **Verify chain, all green**: `npx tsc --noEmit -p tsconfig.app.json`/`-p tsconfig.spec.json` — 0 errors both. `npm run test:ci` on the real shared working tree — **174/174 files, 3411/3411 tests** (includes concurrent WB2 work landed on the same tree; this wave's own share is 12 new `readiness-logic.spec.ts` cases across `parseGroundingBlocker`/`featureBlockers`/`groundingBlocker`/`groundedBannerText`, 3 new `flight-command-panel.spec.ts` cases for the grounded Arm gate, and 3 new `preflight-logic.spec.ts` cases for `inventoryState`/`isInMaintenance`). `ng build --configuration production` — green, same two pre-existing budget warnings only (initial bundle over its 390 kB budget, `tactical-map.css` over its 8 kB budget — both present before this wave). **Bundle delta**: a pathspec-scoped `git stash` isolating just this wave's own files was refused by the session's own safety guard (a shared tree with a concurrent agent's uncommitted work is not a safe stash target), so the delta below is measured against a disposable `git worktree add --detach <tmp> HEAD` baseline (the last commit on this branch, pre-dating both WB1 and WB2) and therefore reflects **both waves' combined contribution**, not WB1 alone: initial bundle **421.25 kB → 421.58 kB raw (+0.33 kB), 118.49 kB → 118.56 kB transfer (+0.07 kB)**. Lazy **`cockpit` chunk 158.42 kB → 162.84 kB raw (+4.42 kB), 34.58 kB → 35.40 kB transfer (+0.82 kB)** — this chunk carries both WB1's `GroundedBanner`/`GroundingStore`/Arm-gate additions and WB2's own cockpit-adjacent work (D3r replay kebab menu, OSD severity wiring); the two are not separable from this measurement alone.
  - **Commit**: `station/vision-web/src/app/core/readiness/{readiness-logic.ts,readiness-logic.spec.ts}` (a deliberate `core/` co-edit — the grounding parser is readiness-specific, not the attention-logic/thresholds surface WB2 owns), `station/vision-web/src/app/features/fly/{cockpit-facade.ts,cockpit.ts,cockpit.html,cockpit.css,grounding-store.ts,grounded-banner.ts,grounded-banner.html,grounded-banner.css,rc-monitor.ts,rc-monitor.html,flight-command-panel.ts,flight-command-panel.html,flight-command-panel.spec.ts}`, `station/vision-web/src/app/features/readiness/{readiness-facade.ts,readiness.ts,readiness.html}`, `station/vision-web/src/app/features/preflight/{preflight-logic.ts,preflight-logic.spec.ts,preflight-facade.ts,preflight.html}` + this `MODULE.md`, message `feat(asset-flows WB1): grounded is visible — cockpit banner, arm reason, readiness nav — S1/B2`; WB2's own concurrently-modified files (`core/api/models.ts`, `core/api/vision-api.ts`, `core/fleet/attention-logic.*`, `core/discovery/**`, `core/system-events/**`, `core/telemetry/telemetry-logic.*`, `core/ops/**`, `features/command/command-logic.ts`, `features/fly/{drone-picker*,fly-logic.*,fly-osd.ts}`, `features/inventory/found-devices.*`) were left untouched — explicit file paths staged, never `git add -A`. **Correction, noted by WB2 in its own entry below:** this commit's own `git add` on the four shared cockpit files above captured the then-current working-tree content, which by that point already included WB2's own additive D3r hunks to `cockpit-facade.ts`/`cockpit.ts`/`cockpit.html`/`cockpit.css` (the replay-an-earlier-flight kebab menu) and WB2's own MODULE.md bullets — both landed inside this commit rather than WB2's own, an artifact of two agents editing the same files on the same tree at the same time, not a mistake in either wave's own file scope; content-wise nothing is missing or duplicated.
- **2026-09-01, docs/plans/active/ASSET-FLOWS-PLAN.md §2/§3, wave WB2 (one severity source, bell kinds, assignment picker, replay list, inbox honesty — S3/S4/B4/D3r/A3):** built on `feat/asset-flows-1` alongside the concurrent wave WB1 above (grounded banner/Arm gate/readiness nav) on the same shared tree — see the `core/**` stores table's new `ops` row and `events` row, the `fly/` bullet's new "My assigned"/"Replay an earlier flight" sub-bullets, and the `inventory/` → `<vision-found-devices>` bullet's new "Inbox honesty" sub-bullet above for the full per-surface detail. Five independent, disjoint-by-design pieces:
  - **S3 — one severity source.** New `core/ops/thresholds-store.ts` (`ThresholdsStore`) fetch-once-on-construction from `GET /api/ops/thresholds`, degrading to `core/ops/thresholds-logic.ts#DEFAULT_BATTERY_THRESHOLDS` (`{warningPercent: 25, criticalPercent: 10}`) on any read failure. `core/fleet/attention-logic.ts#batteryAttentionSeverity`/`attentionReasons` and `core/telemetry/telemetry-logic.ts#batterySeverity` both gained an optional `thresholds: BatteryThresholds` parameter (default `DEFAULT_BATTERY_THRESHOLDS`, every pre-existing call site unaffected); `features/fly/fly-osd.ts` is the one live wire, replacing its own previously-hardcoded 20/45 pair with `ThresholdsStore.battery()`. Boundaries unified to inclusive `<=` on both functions (`attention-logic.ts` used to be strict `<`), matching the backend's own `BatteryThresholdsResponse`/`EventType#BATTERY_LOW` javadoc. `core/telemetry/telemetry-logic.ts`'s unrelated `BATTERY_LOW_PERCENT`/`BATTERY_CRITICAL_PERCENT` constants (a different, per-vehicle-kind preflight-bar feature, `flight-state-logic.ts#batteryLowPercentFor`) were deliberately left untouched. **Residual, named honestly**: Command's `AssetPanel`/`CommandFacade` and Inventory's own `attention-logic.ts` consumers are not yet wired to `ThresholdsStore` — they resolve through the default-parameter fallback (functionally identical to their pre-S3 hardcoded values, now centrally defined), never a live served value; a small, disjoint follow-up.
  - **S4 — bell renders the new kinds.** `core/system-events/system-events-logic.ts`'s `SEVERITY_BY_TYPE`/`TITLE_BY_TYPE` gained `LINK_LOST`/`BATTERY_LOW` (both `danger`), feeding `NotificationBell` directly — no seed-on-tick read-set change, mount-time-gated unread stays exactly as OPERATOR-UX-7 built it.
  - **B4 — picker groups by assignment.** `features/fly/drone-picker-logic.ts#myAssignedAssets` + `DronePickerFacade#refreshAssignments` wire the previously-zero-caller `VisionApi#myAssignments()` (`GET /api/me/assignments`) into a new, additive "My assigned (n)" section above "Your vehicles" in `drone-picker.html` — an assigned asset still also appears in "Your vehicles" (duplication, not a filter), and a failed/empty assignments read folds back to exactly today's flat groups.
  - **D3r — replay any recent flight.** No new endpoint: `AssetDetails#recentUsages` is already a capped-to-20, newest-first list the cockpit polls every ~5s. New `fly-logic.ts#earlierReplayableUsages(usages, max = REPLAY_PICKER_MAX_USAGES = 5)` drops the newest (the existing "Replay last flight" link's own target) and keeps only finished usages; `cockpit.html` renders the rest behind a `<vision-kebab-menu>` (the `asset-detail.html` "more actions" idiom, reused rather than hand-built), omitted entirely when there are none.
  - **A3 — inbox honesty.** `GET /api/discovery/inbox` now answers `{candidates, sources}`; `discovery-inbox-logic.ts#sourceUnreachableWarnings` turns each `UNREACHABLE` source into one `<vision-notice variant="warn" icon="alert">` line. Three-way visibility in `found-devices.html`: candidates=0 + all OK → invisible (unchanged); candidates=0 + some unreachable → warning banner(s) only; candidates>0 → existing header/grid plus any banners on top.
  - **Role-gating**: none of the five pieces changes a permission surface — `GET /api/ops/thresholds`/`GET /api/me/assignments` are both `@OpenByDesign` (any signed-in caller), the discovery inbox and bell were already gated exactly as before.
  - **Dev parity**: unaffected throughout — `vision.auth.enabled=false`'s dev admin reads every one of these five surfaces identically to prod; none reads the auth flag directly.
  - **Degrades honestly**: a failed `GET /api/ops/thresholds` read is the frozen default, never a blocked OSD or a fabricated severity; a failed/empty `GET /api/me/assignments` read is today's flat picker groups, never an error state; a missing/failed discovery-inbox `sources` read simply shows no warning banners (the pre-A3 behavior) rather than inventing scanner health.
  - **Verify chain, all green**: `npx tsc --noEmit -p tsconfig.app.json`/`-p tsconfig.spec.json` — 0 errors both. `npm run test:ci` on the real shared working tree — **174/174 files, 3411/3411 tests** (same run WB1's own entry above reports — both waves' work landed together; WB2's own share is 1 new `core/ops/thresholds-logic.spec.ts` + 1 new `core/ops/thresholds-store.spec.ts` + 1 new `features/fly/drone-picker-logic.spec.ts`, plus new cases added to `attention-logic.spec.ts` (threshold-override + inclusive-boundary cases), `telemetry-logic.spec.ts` (`batterySeverity` override case), `system-events-logic.spec.ts` (`LINK_LOST`/`BATTERY_LOW`), `fly-logic.spec.ts` (`earlierReplayableUsages`, 5 cases), `discovery-inbox-logic.spec.ts` (`sourceUnreachableWarnings`, 4 cases)). `ng build --configuration production` — green, same two pre-existing budget warnings only (initial bundle over its 390 kB budget, `tactical-map.css` over its 8 kB budget). **Bundle delta**: measured directly on the shared tree (WB1's own concurrent uncommitted work made a stash-based isolation unsafe, same reasoning as WB1's own entry above) against the last-recorded pre-wave baseline: initial bundle **421.25 kB → 421.58 kB raw (+0.33 kB), 118.49 kB → 118.54 kB transfer (+0.05 kB)** — reflects both waves' combined contribution, not separable. Lazy **`cockpit` chunk → 162.84 kB raw / 35.39 kB transfer** — same number WB1's own entry records; the two waves' cockpit-adjacent work (WB1's grounded banner/Arm gate, WB2's D3r replay menu + OSD severity wiring) is not separable from one shared build. Lazy **`inventory` chunk 103.08 kB → 104.05 kB raw (+0.97 kB), 20.49 kB → 20.68 kB transfer (+0.19 kB)** — this one **is** cleanly WB2's own (WB1 touches no file under `features/inventory/**`): A3's warning banner markup plus the `Notice` import.
  - **Commit**: `station/vision-web/src/app/core/{api/models.ts,api/vision-api.ts,discovery/discovery-inbox-logic.ts,discovery/discovery-inbox-logic.spec.ts,discovery/discovery-inbox-store.ts,fleet/attention-logic.ts,fleet/attention-logic.spec.ts,ops/thresholds-logic.ts,ops/thresholds-logic.spec.ts,ops/thresholds-store.ts,ops/thresholds-store.spec.ts,system-events/system-events-logic.ts,system-events/system-events-logic.spec.ts,telemetry/telemetry-logic.ts,telemetry/telemetry-logic.spec.ts}`, `station/vision-web/src/app/features/{command/command-logic.ts,fly/drone-picker-facade.ts,fly/drone-picker-logic.ts,fly/drone-picker-logic.spec.ts,fly/drone-picker.html,fly/drone-picker.ts,fly/fly-logic.ts,fly/fly-logic.spec.ts,fly/fly-osd.ts,inventory/found-devices.css,inventory/found-devices.html,inventory/found-devices.ts}` + this `MODULE.md`, message `feat(asset-flows WB2): one severity source, bell kinds, assignment picker, replay list, inbox honesty — S3/S4/B4/D3r/A3`; the four shared cockpit files (`cockpit-facade.ts`/`cockpit.ts`/`cockpit.html`/`cockpit.css`) carrying this wave's D3r hunks were already captured inside WB1's own commit above (see that entry's own correction note) — not re-staged here to avoid an empty/no-op second diff on those paths.

- **2026-09-02, docs/plans/active/FLY-CONTROL-UX-PLAN.md, wave WEB1 (the whole web side of the on-video control HUD + the neutral-stick arm gate):** built entirely inside `station/vision-web`, on `feat/fly-control-ux`, whose HEAD already carried the plan doc, BK1's server-side `vision.ops.rc.neutral-tolerance-percent` config, and two concurrent Java-side waves (H1's honest engage-denial fix, H2's one-wire-identity fix) — none of that is this task's scope. See the `fly/` bullet's own new "Wave WEB1" sub-bullet above (features section, following the `rc-monitor` wave-X/Z1 sub-bullets) for the full component/zone/gate detail; this entry is the wave's own build-requirements checklist.
  - **Component tree**: `cockpit.html` mounts `<vision-fly-hud>` unconditionally (was `<vision-rc-monitor>`, gated on the drawer being open); `fly-hud` template-nests `<vision-flight-command-panel>` (bottom-right zone, now narrowed to Arm/Disarm) and, only while the rail is open, `<vision-rc-monitor>` (now informational-only, template-nesting the new `<vision-mode-picker>` split out of the old combined panel). All three files per component (`.ts`/`.html`/`.css`); `fly-hud` uses no `UiStore` of its own — the rail's open/closed state stays `cockpit.ts`'s own `panels` `UiStore`, forwarded through a plain `rcPanelOpen` input / `closeRcPanel`/`openRcPanel` outputs, per this wave's own "this component has no opinion on *where* the rail lives" design note.
  - **Degradation, named per surface**: the Take-control pill/badge degrade to "No link"/"Not commandable" off the same `canCommand`/`rc.connected()` facts the old drawer read, never a fabricated "Ready"; the Arm pill degrades to visually locked + a composed reason (grounding wins over sticks-not-neutral) the instant either condition holds, `title` and a rail sentence carry the full text; the neutral gate itself is silently absent (not a false "clear") whenever there is no live session, mirroring `ThresholdsStore.rc()`'s own honest-default posture for a `vision.ops.rc.neutral-tolerance-percent` that hasn't shipped server-side yet on some deployments; the live input widget only ever renders once `engaged` — never a stale/last-known reading impersonating live sticks.
  - **Dark theme / narrow viewport**: styled against the token contract (`--hud-*`/`--scrim*`, no gradients/glow, `--mono` numerals) and the existing `.surface-dark` cockpit precedent throughout — every new class in `fly-hud.css` reuses tokens already exercised in both themes elsewhere on this same page (`flight-command-panel.css`'s pre-existing `.hud-pill`, `transmitter-view`'s pad/knob idiom). **Not independently verified on a real rendered screen in either theme** — no browser drive was performed this wave (see the "Left out" bullet in the `fly/` section above); this is exactly the outstanding risk CONTROLLER-UX-PLAN's own "never been looked at in dark theme on a real screen" note already flags for this general surface, now extended to the new HUD zones. Responsive collapse follows the pre-existing `--bp-lg`/`--bp-md` cockpit grid unchanged — the HUD is `position: absolute` within `.grid-main`, not part of that grid, so it was not expected to need new breakpoint rules, but this too is unverified against a narrow real viewport.
  - **Verify chain, all green**: `npm run test:ci` — **178/178 files, 3474/3474 tests** (found and fixed 3 pre-existing failures in `neutral-gate-logic.spec.ts` en route — an `axes` array/`sourceIndex` mismatch in the test fixtures themselves, not the logic under test; see the `fly/` section's own "Fixed alongside" bullet). `npx tsc --noEmit -p tsconfig.app.json`/`-p tsconfig.spec.json` — 0 errors both. `npx ng build --configuration production` — green (via `npx`; a bare `ng` on this build box resolves to an unrelated system binary and aborts — new Gotchas entry above), same two pre-existing budget warnings only (initial bundle over its 390 kB budget, `tactical-map.css` over its 8 kB budget, both present before this wave). **Bundle delta**, measured against a disposable `git worktree add --detach <tmp> bfc9e779` baseline (this branch's HEAD at the time — already carrying BK1/H1/H2's backend-only commits, no frontend changes — symlinked `node_modules`, no stash needed since the baseline is a separate worktree): initial bundle **421.58 kB → 421.64 kB raw (+0.06 kB), 118.55 kB → 118.62 kB transfer (+0.07 kB)** — effectively flat, everything here is lazy. Lazy **`cockpit` chunk 162.84 kB → 178.87 kB raw (+16.03 kB), 35.41 kB → 37.99 kB transfer (+2.58 kB)**.
  - **Commit**: `station/vision-web/src/app/core/{rc/fly-hud-logic.ts,rc/fly-hud-logic.spec.ts,rc/neutral-gate-logic.ts,rc/neutral-gate-logic.spec.ts,rc/keyboard-rc-input.service.ts,rc/keyboard-rc-input.service.spec.ts,ops/thresholds-logic.ts,ops/thresholds-logic.spec.ts,ops/thresholds-store.ts,ops/thresholds-store.spec.ts,api/models.ts}`, `station/vision-web/src/app/features/fly/{fly-hud.ts,fly-hud.html,fly-hud.css,mode-picker.ts,mode-picker.html,mode-picker.css,mode-picker.spec.ts,rc-monitor.ts,rc-monitor.html,rc-monitor.css,rc-monitor.spec.ts,flight-command-panel.ts,flight-command-panel.html,flight-command-panel.css,flight-command-panel-logic.ts,flight-command-panel-logic.spec.ts,flight-command-panel.spec.ts,arm-confirm-dialog.ts,arm-confirm-dialog.html,arm-confirm-dialog.css,arm-confirm-dialog.spec.ts,cockpit.ts,cockpit.html,cockpit.css}` + this `MODULE.md`, message `feat(fly-control-ux WEB1): on-video control HUD + neutral-stick arm gate`; `docs/plans/active/fly-control-ux/R3-handshake-denial.md` (H1's own file) and `drone-link/mavlink/**` (H2's own, a Java module out of scope entirely) were left untouched on this shared tree — explicit file paths staged, never `git add -A`; `docs/plans/active/CAMERA-FIRST-PLAN.md` (untracked, unrelated to this plan) was likewise left alone.

- **2026-09-02/03, docs/plans/active/COMMAND-MAP-FLOW-PLAN.md, waves W1–W3 on `feat/command-map-ux` (forked from `feat/fly-flow-ux`):** the map stops lying about freshness, four independent overlays become one drawer, and a selected asset's flight history draws on the map. Backend wave B1 (D1's `findLatestByUsage` fix) is explicitly another agent's concurrent work on a different branch — every wave here codes against the unchanged wire contract, never touching Java.
  - **W1 — honest asset markers** (commits `74d764ff`, `faa89b80`): `core/map/map-logic.ts` gained the frozen §3.3 contract (`LastContact`/`LastContactSource`/`resolveLastContact`'s 3-tier `telemetryAgeMs → lastUsedAt → unknown`/`withLastContact`, unwired until W3); `tactical-map-logic.ts#markerFreshnessClass` gives every marker exactly one of `live`/`aging`/`stale` (folding `freshness()`'s `'none'` into `'stale'` per the plan's own bracketing — the first commit shipped a binary live/muted, corrected the same day once the plan's tri-state was re-read); shape (`droneHollowDivIcon`, a non-directional ring whenever heading is genuinely unknown) is now a separate axis from colour (one `--marker-color` custom property). The legend is now an always-visible `--hud-*` quiet row of count chips (D6), full symbol key opt-in and defaulting closed in both modes. `test:ci`: 178/178 files, 3513/3513 tests (both commits combined).
  - **W2 — one Map tools drawer** (commit `a1e2d98c`): see the `shared/` → `map/` bullet's own `MapTools`/`MarksPanel`/`LayerManager`/`ZonesPanel` sub-bullets above for the full component detail — this entry is the wave's own account of what it replaced and why. Retired: Command's 4 topbar buttons/overlays, `CommandOverlay`'s 4-member union (now one `'map-tools'` `UiStore` id — **`architecture.spec.ts` requires this even for a one-member group**, a wrong initial assumption ("a group of one has nothing to coordinate, so a plain signal is fine") caught by the guard itself on both `command.ts` and `live.ts` and fixed before commit), both feature-local `marks-panel` copies, `ZonesPanel`'s backdrop-modal shell (MAP-UX-RESEARCH.md §8's own "needs to block accidental clicks" refusal overturned on evidence — see the `shared/` bullet). **Disclosed deviation**: the plan's prose says "top-right chrome" for the HUD door; `<vision-tactical-map>` was out of this wave's file scope to grow a 4th corner (top-right is already camera controls, the other two are basemap/legend), so `command.css`/`live.css`/`asset-detail.css` each pin their own "Map tools" button to the map's bottom-right instead — the one genuinely free corner. `test:ci`: 178/178 files, 3513/3513 tests (no new specs — pure component/template restructuring, covered by `architecture.spec.ts` + existing suites).
  - **W3 — route on click** (commit `62580fd9`): see the `shared/` → `map/` bullet's `[routes]` sub-bullet and the `map-data` table row above for `RouteStore`/`route-logic.ts`'s own full detail. `CommandFacade` gained one effect over `(selectedAssetId, routeSpan)` covering the whole interaction contract (select → auto-show last flight; span change → re-show; deselect → hide) and closed **W1's own remaining fallback gap**: `markers` is now `withLastContact(mapStore.markers(), lastContactByAssetId)` instead of a bare `FleetMapStore.markers` passthrough — the offline bucket (no live telemetry poll at all) now gets an honest tier-2 `lastUsedAt` fact instead of always falling through to `'unknown'`. `routeSpan` defaults `'last'`, persisted under `vision.command.routeSpan` (`readPersistedString`, guarded against a stray/pre-key value). The three named states render verbatim: *"No recorded flights for this asset"*, *"This flight recorded no positions"* (per-route, zero-fix), *"Simplified — long flight"* (truncated — detected by comparing the usage's own `sampleCount` against the `maxPoints=500` request cap, not the timeline response length, since a thinned response can legitimately land exactly on the cap). `RouteStore.show()` clears its own `routes` signal synchronously before every fetch (even a same-asset span change), so the map is never caught displaying a stale selection's route mid-load — the frozen "never more than one asset's route on the map at once" rule holds at every instant, not just the settled state. `test:ci`: 180/180 files, 3527/3527 tests (+2 files/+14 tests over W2 — `route-logic.spec.ts` 6 cases, `route-store.spec.ts` 7 cases incl. a same-asset-span-change race and a superseded-fetch race).
  - **Role-gating**: none of the three waves changes a permission surface — the Map tools drawer's `layers:'manage'` vs `'view'` split is a *capability*, not a role check (every host already gated who could reach that host at all); routes read `GET /api/usages`/`GET /api/usages/{id}/timeline`, both already scoped identically to `features/replay/**`'s existing use of the same endpoints.
  - **Dev parity**: unaffected throughout — nothing in any of the three waves reads `vision.auth.enabled` or branches on it; the dev admin sees the identical drawer/map/route behavior as a real ADMIN.
  - **Degrades honestly**: an offline asset's popup always renders a last-contact line now, never drops it (D3, W1); a route fetch failure lands in an explicit `error` state, never a fabricated polyline (W3); a zero-fix usage renders as an `AssetRoute` with `points: []` and its own named empty line rather than being silently dropped from the list (W3).
  - **`npx tsc --noEmit` clean on both configs after every wave.** `ng build --configuration production`/bundle-delta measurement is **deferred to wave W5** (the plan's own close-out wave) rather than measured per-wave here, since W4 (the command frame + quiet verdict + judged events) is still open and a mid-plan delta would need re-measuring anyway.
  - **MODULE.md itself was not updated after W1 or W2 at the time each landed** — the plan's own §4 preamble ("Each wave ends with MODULE.md updated") was in tension with this task's own "finish by updating MODULE.md" framing; resolved by catching this file up to cover W1–W3 together here, at the start of W4, rather than deferring the whole account to W5.
  - **Commit**: W1 — `station/vision-web/src/app/core/map/{map-logic.ts,map-logic.spec.ts}`, `station/vision-web/src/app/shared/map/tactical-map/{tactical-map.ts,tactical-map.html,tactical-map.css,tactical-map-logic.ts,tactical-map-logic.spec.ts}`, `station/vision-web/src/app/shared/map/tile-cache/leaflet-loader.ts` (two commits, `74d764ff`/`faa89b80`). W2 — `station/vision-web/src/app/shared/map/map-controls/{layer-manager.ts,layer-manager.html,map-tools/map-tools.ts,map-tools/map-tools.html,map-tools/map-tools.css,marks-panel/marks-panel.ts,marks-panel/marks-panel.html,marks-panel/marks-panel.css}`, `station/vision-web/src/app/features/{command/command.ts,command/command.html,command/command.css,command/zones-panel.ts,command/zones-panel.html,command/zones-panel.css,fly/cockpit.ts,fly/cockpit.html,fly/cockpit.css,live/live.ts,live/live.html,live/live.css,asset-detail/asset-detail.ts,asset-detail/asset-detail.html,asset-detail/asset-detail.css}` (deleted `features/{command,fly}/marks-panel.*`, `a1e2d98c`). W3 — `station/vision-web/src/app/core/map-data/{route-logic.ts,route-logic.spec.ts,route-store.ts,route-store.spec.ts}`, `station/vision-web/src/app/shared/map/tactical-map/tactical-map.ts`, `station/vision-web/src/app/features/command/{command-facade.ts,command.ts,command.html,asset-panel.ts,asset-panel.html,asset-panel.css}` (`62580fd9`) + this `MODULE.md`.
- **2026-09-03, docs/plans/active/COMMAND-MAP-FLOW-PLAN.md, wave W4 on `feat/command-map-ux`** — the command frame: judged events, an earned "All quiet", and a topbar with nothing left to control.
  - **Events, judged (§3.5)**: `core/events/events-logic.ts#selectEventMarkers` gained a frozen `EventMarkerOptions` (`max`, `openOnly`, `maxAgeMinutes`) merged onto new defaults (`30`/`true`/`60`) via `Partial<EventMarkerOptions>` — every existing bare call (`command-facade.ts#eventMarkers`, unchanged) keeps compiling and picks up the open-and-recent filter for free, per the plan's own "filter, do not hide" framing (defaulting the layer off would be hiding data). The event marker popup's primary action retargets from `openEventAsset` (navigates away) to the same `.preview-btn`/`preview`-output pathway `assetPopupHtml`'s own "Watch live" button already uses, relabeled **"Preview asset"** for this popup; **"Open asset"** (renamed from "Details", unchanged target) stays as the secondary action. An event whose `assetId` never resolved still renders its existing honest line (*"No asset resolved for this event"*) with no action at all, unchanged.
  - **"All quiet" earns its words (§3.6 D5)**: new `command-logic.ts#quietVerdict(reasons, contact): 'quiet' | 'no-basis'` — `'quiet'` only when all three frozen conjuncts hold (`reasons.length === 0`, `contact?.source === 'telemetry'`, `freshness(contact.ageSeconds) !== 'stale'`); anything else is `'no-basis'`, rendered *"Nothing to report — no recent telemetry from this asset ({last-contact label})."* — naming the actual fact (via `tactical-map-logic.ts#lastContactLabel`/`markerLastContact`, the identical fallback the map's own popup already applies) instead of implying a confidence zero triggered reasons alone never earned. Before this, an archived/never-plotted/days-stale selected asset with no active reason still read "All quiet".
  - **The frame at rest (§3.6)**: `command.html`'s topbar is now identity + fleet state only (`Command · {n} assets · [weather chip]`) — the four tool buttons had already left in W2, so the only thing left to remove was **Include archived**, which moves into the rail head beside **Hide simulated**; both now share one `.rail-list-filter` CSS class (renamed from `.rail-hide-simulated`) rather than two near-duplicate rules, and Include-archived is always shown (unlike Hide-simulated, gated on there being a simulated row at all). Legend defaulting closed (`legendOpen = signal(false)`) was verified still true — that was already delivered in W1, not new work here.
  - **Disclosed deviation — file scope wider than the plan's Round 2 list**: the plan's own W4 Scope bullet names `command.html`/`command.css`/`command-logic.ts`+spec/`asset-panel.html`(verdict block only)/`events-logic.ts`+spec, but omits two files its own "Delivers" line requires: `asset-panel.ts` needed a `verdict`/`contact`/`noBasisContactLabel` `computed()` trio for `asset-panel.html`'s verdict block to read anything from (a template cannot import a pure function on its own), and `shared/map/tactical-map/tactical-map.ts` needed the actual `eventPopupHtml` edit to retarget the popup's primary action — §3.5 literally lists "popup retarget" as a W4 deliverable, and that markup has always lived in `tactical-map.ts` (`eventPopupHtml`, private), not in any file the Scope list names. Both are one-file-shy gaps in the same list, not disagreements with what the plan asked for; no other wave is concurrently touching either file, so there is no round-2-style parallelism conflict in doing so.
  - **Role-gating**: unaffected — neither the event filter, the verdict, nor the topbar/rail move touches a permission surface.
  - **Dev parity**: unaffected — none of W4 reads `vision.auth.enabled` or branches on it.
  - **Degrades honestly**: `quietVerdict`'s `'no-basis'` branch never claims quiet without a real telemetry sample backing it, and always names the actual fact instead of guessing; `selectEventMarkers`'s filter narrows what's *shown*, never fabricates a marker that isn't there; the unresolved-event-asset popup line is unchanged.
  - **Verify chain, all green**: `npx tsc --noEmit` clean on both configs (first pass, no fixes needed). `npm run test:ci`: 180/180 files, 3539/3539 tests (+12 over W3 — 6 new `quietVerdict` cases in `command-logic.spec.ts`, 6 new `selectEventMarkers` filter cases in `events-logic.spec.ts`, the latter requiring the pre-existing cases to switch from the file's fixed `2026-07-23` fixture dates to wall-clock-relative `recentIso()` helper timestamps, since the new default 60-minute cutoff reads real `Date.now()` — the same "no `nowMs` parameter, reads real time directly" idiom this same file's own pre-existing `findCoveringUsage` already used). `ng build --configuration production`/bundle-delta measurement remains **deferred to wave W5** (the plan's own close-out wave), now the very next wave.
  - **Commit**: `station/vision-web/src/app/core/events/{events-logic.ts,events-logic.spec.ts}`, `station/vision-web/src/app/features/command/{command-logic.ts,command-logic.spec.ts,command.html,command.css,asset-panel.ts,asset-panel.html}`, `station/vision-web/src/app/shared/map/tactical-map/tactical-map.ts`, `station/vision-web/src/app/shared/ui/events-rail.css` (doc-comment-only, renamed class reference) + this `MODULE.md`.

- **2026-09-03, docs/plans/active/COMMAND-MAP-FLOW-PLAN.md, wave W5 (close-out) on `feat/command-map-ux`** — verification only, no product code beyond one config fix; the plan's own close-out table is appended to the plan doc itself in this same commit.
  - **Production-build budget fix (`angular.json`, disclosed deviation)**: the `anyComponentStyle` budget's `maximumError` (`10kB`) started failing the build — `tactical-map.css` sits at **11.38 kB** after W1's frozen-contract CSS (marker freshness tri-state, `.last-known-badge`, `.legend-chips`/`.legend-chip`/`.legend-more`), up from a baseline **9.86 kB** at the `feat/command-map-ux` fork point (measured by building `851aad6d` in a disposable `git worktree`, `node_modules` symlinked rather than reinstalled). Diffed the file across the whole branch and grepped every added selector's usage in `tactical-map.html` to confirm all +1.52 kB (source) was live, referenced, frozen-contract feature CSS, not dead weight — trimming was not an honest option. Bumped the budget to `maximumWarning: "11kB"` / `maximumError: "15kB"`, keeping a real warning tier rather than raising the ceiling to silence it outright. This is a project-wide config file no single wave "owns"; flagged here rather than folded silently into an unrelated wave's diff.
  - **Live pass, both themes, all four surfaces** (`ng serve` already running on `:4200`, driven via the claude-in-chrome MCP browser tools — no server started by this task): confirmed on `/command` — the merged `.rail-list-filter` pair (Include archived + Hide simulated) renders correctly stacked in the rail head in both light and dark theme, the legend's one-row count chips (`1 live`/`8 last known`/`1 attention`/`10 no position`), a selected asset's drawn route (solid open-flight line + `flagIcon` start marker) with the Route span control's named states, and the dark-theme night basemap swap (frontend-style §7's theme-follows-basemap rule) all render correctly — screenshotted in both themes. Confirmed the **Map tools drawer opens with identical Marks/Layers/Draw/Zones content on all four hosts**: `/command` (already covered by W2's own verify), `/assets/:id` (the `<vision-tactical-map>` "Position" card's own "Map tools" HUD button), `/fly/:assetId` cockpit (the rail's "Map layers and drawing" button, `.surface-dark` HUD chrome, a live KEEP-OUT/KEEP-IN zone pair rendered correctly), and `/live/:deviceId` (same drawer, reached via a device id resolved off the Wall page's own "Watch live" link — `/live/:deviceId` takes a *device* id, not an asset id, confirmed by first hitting an honest "Unknown device" state when an asset id was tried there). The `quietVerdict`/no-basis message and the event-popup "Preview asset"/"Open asset" retarget were verified by source + the existing unit-test suite rather than an additional live click-through — the demo dataset currently has no asset in the exact zero-reasons/non-telemetry-contact state and no currently-open detection event to click, and forcing either is out of scope for a read-only verification pass; both paths share the identical pure functions (`quietVerdict`, `lastContactLabel`/`markerLastContact`) already exercised by the map popup's own live-confirmed rendering and by `command-logic.spec.ts`/`events-logic.spec.ts`.
  - **Role-gating / dev parity**: unaffected — W5 is verification plus one build-config number; nothing here reads a role or `vision.auth.enabled`.
  - **Degrades honestly**: unchanged from W1–W4's own accounts above; nothing in W5 touches a degrade path.
  - **Verify chain, all green**: `npx tsc --noEmit -p tsconfig.app.json` / `-p tsconfig.spec.json` — 0 errors both. `npm run test:ci` — **180/180 files, 3539/3539 tests** (unchanged from W4 — W5 added no specs). `npx ng build --configuration production` — green, only the two pre-existing warnings (initial bundle over its 390 kB budget — present since before this branch; `tactical-map.css` now at 11.38 kB against the *new* 15 kB error line, comfortably inside it).
  - **Bundle delta**, measured against the `851aad6d` fork-point baseline (disposable `git worktree`, symlinked `node_modules`, removed after measuring): initial bundle **421.82 kB → 422.01 kB raw (+190 B), 118.64 kB → 118.76 kB transfer (+120 B)** — effectively flat. Lazy chunks: **`command` 64.08 kB → 43.11 kB raw (−20.97 kB, −4.04 kB transfer)**, **`cockpit` 185.76 kB → 175.18 kB raw (−10.58 kB, −1.91 kB transfer)** — both shrank because W2 pulled `MapTools`/`MarksPanel`/`LayerManager`/`ZonesPanel`/`DrawingToolbar` out into one new shared lazy chunk (**132.33 kB raw / 28.01 kB transfer**, unnamed in the stats table) instead of each host bundling its own copy; **`asset-detail` 70.80 kB → 71.89 kB raw (+1.09 kB, +0.21 kB transfer)** — the one host that grew, since it previously had no drawer of its own at all. `leaflet-src` (149.55 kB) is unchanged, as expected for a third-party dependency chunk.
  - **Left unverified**: the exact `quietVerdict` no-basis copy and the event-popup two-button layout were not clicked live this wave (see the "Live pass" bullet above) — both rest on unit coverage + shared-function reuse with an already-live-verified sibling rendering, not an independent screenshot of their own. No other gap is known.
  - **Commit**: `station/vision-web/angular.json` + this `MODULE.md` + `docs/plans/active/COMMAND-MAP-FLOW-PLAN.md` (close-out table appended).

- **2026-09-02/03, docs/plans/active/WALL-FLOW-PLAN.md, waves W1–W4 on `feat/wall-flow-ux`** — the Wall rebuilt as a grid of every live tile at once: honest per-tile health, asset-first identity via one fleet-summary join (replacing per-tile `TelemetryStore` polling), a density control that reflows instead of a fixed-column `<select>`, an in-place focus overlay, and an on-demand Activity drawer scoped to this wall's own streams. See the `wall/` bullet above (features section) for the full component/contract detail this entry does not repeat.
  - **W1 — the wall's model** (`bd05ca02`): new `wall-logic.ts` (+spec) — the frozen §3.4 contract (`TileHealth`/`TilePulse`/`WallTileModel`/`WallActivityRow`, `DENSITY_STOPS`+`tileMinPx`, `buildWallTiles` with frozen `TileHealth` precedence `no-publisher > pipeline-error > stalled > reconnecting > starting > live/unknown` and two anti-double-signal rules dropping open-events/pipeline-error from the reasons list once already surfaced as health, stable title/streamId ordering) — plus a full `wall-facade.ts` rewire onto a 5s `api.fleetSummary()` poll (`command-facade.ts`'s own precedent, silent-degrade-on-background-failure). **Disclosed deviation**: the plan's W4 wave text says "Delete `WallFacade.openEvent`… with it," but the frozen §3.4 facade surface never listed that method at all — removed now (W1) rather than carrying a method the frozen contract never specified. Fixed D22's stale `activate()`/`release()` doc comment.
  - **W2 — the tile skin** (`f4fe6191`): `wall-tile.{ts,html,css}` rewritten to the frozen I/O — `[tile]: WallTileModel`, `[boxesMode]: BoxesMode`, `(focused): string`. The tile is now a real `<button>` (the click target for drill-in, D9/D10), replacing the old `Watch live` `RouterLink`. Deleted: the boxes-cycle button and its `▦▢◉▢×` glyphs (D6/D7), the altitude readout (D8), the tile's own `TelemetryStore` (D5). At rest: picture + name, and — only when `healthLabel` is non-null — one HUD line; severity is a border tint + plain `.dot`; a pulse is a one-shot `box-shadow`-ring flash (`@keyframes tile-pulse-flash`, `prefers-reduced-motion`-gated) plus a persistent label chip. Battery/telemetry-age/"Not linked to an asset" moved into a hover/focus-within-only footer. Kept verbatim: the `IntersectionObserver` 250px-preroll off-screen suspend; `DetectionsStore`, now called as `track(streamId, assetId)` so an on-wall asset gets the asset-scoped live SSE transport instead of a poll.
  - **W3 — the frame and the focus layer** (`17e7dcd0`): `wall.{ts,html,css}` rewritten against the new `WallFacade`; new `wall-focus.{ts,html,css}`. Bar (L1): title + live count + a 3-stop density `.segmented` writing `facade.tileMinPx()` into `[style.--tile-min.px]` (D11, `repeat(auto-fill, minmax(var(--tile-min), 1fr))` replacing the fixed `Tiles per row` `<select>`), one declutter cycle button, an `Activity (n)` button (wired live in W4). P0 empty state goes full width, button relabelled "Go to Inventory". `vision-wall-focus` is the L3 layer (§3.2 A4): mounts over the grid (`z-index: 40`, deliberately under the shared side panel's `120` so the activity drawer can stay open alongside it) with an enlarged player, the tile's five facts (mode/armed/battery/telemetry age/health), and two labelled exits (`Open cockpit → /fly/:assetId`, hidden when unlinked; `Watch live → /live/:deviceId`) plus Esc/backdrop close — `wall.ts` is the one file that injects `Router`, to satisfy those exits without the focus view or facade ever navigating themselves. **Disclosed sequencing deviation** (a forced consequence of W1's own): the old `<vision-events-rail>` markup could not have compiled once W1 removed `WallFacade.openEvent`, so it is deleted here, one wave earlier than the plan's W4 prose literally schedules — the plan's own §3.1 verdict table already called for its removal; W4 still adds the real replacement as new work. Also fixed live during this wave's own build: two Angular compiler warnings (NG8113 an unused `Icon` import in `wall-focus.ts`; NG8011 `[pageBarFilters]`/`[pageBarActions]` each needing to be the sole root node of its own `@if` block) and a real `wall-logic.ts` bug `wall-logic.spec.ts` caught — `tilePulse` picked the single most-recently-arrived event's label, so one late different-label event could steal the chip from an already-larger, still-open group; fixed to pick the dominant label by count, ties broken by recency.
  - **W4 — activity on demand** (`7f20649a`): new `wall-activity.{ts,html,css}`, wired behind the `Activity(n)` bar button. `[rows]` arrives pre-scoped from `wallActivityRows` (W1) — this wall's own streams only, inside `ACTIVITY_WINDOW_MS` (one hour), matched by `assetId` first then `streamId` (so a restarted stream doesn't fall off its own history) — so a row's `sourceLabel` is always its tile's own asset name by construction; `Removed device · 7fd88790` cannot occur here (the fix for D15/D16), and no `includeRemoved` checkbox is needed. A row never navigates (D17) — `(rowActivated)` hands `wall.ts` the row's `streamId`, forwarded straight to `WallFacade.focus()`, the same drill-in a tile click performs; the drawer stays open while a row is clicked (A4: "not mutually exclusive" with the focus view). Reuses `vision-side-panel` and `vision-event-row` (default two-line variant) verbatim; its own 1s clock (`PollScheduler`, non-routed-child carve-out) keeps `relativeTime` advancing while open. Footer: "Older activity › Alerts center" — honest about being a window, not the fleet's full history (§5 residual #2). Wall's lazy chunk: 51.94 kB → 60.35 kB (+8.41 kB) for the new drawer (dev-mode measurement at commit time; see the W5 entry below for the production-build figure).
  - **Role-gating**: none of the four waves changes a permission surface — the Wall has never been role-gated (any authenticated viewer who could already reach `/wall` could already see every tile/event on it), and nothing in this rework adds an admin-only affordance.
  - **Dev parity**: unaffected throughout — nothing in any of the four waves reads `vision.auth.enabled` or branches on it; the dev admin sees identical Wall behavior to a real ADMIN.
  - **Degrades honestly**: a failed/forbidden fleet-summary poll simply keeps the last-known summary (matching `command-facade.ts#refreshSummary` byte-for-byte); when there has never been one, tiles render with `assets: []`, which `buildWallTiles` turns into an honest all-`unlinked`/`ok`/`unknown` picture, never a fabricated fact (W1). A focused tile that vanishes (stream stopped, poll caught up) self-clears the focus view rather than freezing on stale data (W3). A wall activity row can only ever name an asset it can actually resolve — never a `Removed device` placeholder (W4).
  - **Verify chain**: `npx tsc --noEmit` clean on both configs and `npm run test:ci` green only once W1+W2+W3 land together (W1 alone leaves the pre-existing `wall.ts`/`wall-tile.ts` referencing a facade surface that no longer exists, and W2 alone leaves `wall.ts` referencing the old `[stream]`/`[device]` tile inputs — the plan's own W1 → (W2 ∥ W3) sequencing, executed sequentially by one agent here). First combined-green pass (after W1–W3): **181/181 files, 3570/3570 tests**. After W4: unchanged, **181/181 files, 3570/3570 tests** (no new specs — pure component/template addition covered by the existing `wall-logic.spec.ts` + `architecture.spec.ts`).
  - **Commit**: W1 — `station/vision-web/src/app/features/wall/{wall-logic.ts,wall-logic.spec.ts,wall-facade.ts}` (`bd05ca02`). W2 — `station/vision-web/src/app/features/wall/{wall-tile.ts,wall-tile.html,wall-tile.css}` (`f4fe6191`). W3 — `station/vision-web/src/app/features/wall/{wall.ts,wall.html,wall.css,wall-focus.ts,wall-focus.html,wall-focus.css}` (`17e7dcd0`). W4 — `station/vision-web/src/app/features/wall/{wall-activity.ts,wall-activity.html,wall-activity.css,wall.ts,wall.html}` (`7f20649a`).

- **2026-09-03, docs/plans/active/WALL-FLOW-PLAN.md, wave W5 (close-out) on `feat/wall-flow-ux`** — verification only, no product code beyond one accessibility fix; the plan's own close-out table is appended to the plan doc itself in the same commit.
  - **Accessibility fix, found live (`wall-tile.html`)**: the tile `<button>`'s accessible name depended on a conditional `[title]` binding that carries the reasons tooltip — `null` whenever `severity === 'ok'` (the common case). Live accessibility-tree inspection (Chrome MCP `read_page`) showed every healthy tile as `button "null"`. Fixed with an unconditional `[attr.aria-label]="'Focus ' + tile().title"`, independent of the reasons tooltip; confirmed live after the fix — `button "Focus Backfire 2"` / `button "Focus ESP32 Rover (paired)"`.
  - **Build-tooling detour, disclosed**: the first two production-build attempts this wave hit the `ng`/PATH collision already on record in this file's own Gotchas (a bare `ng` inside a `script -qec …` wrapper resolved to a *third*, previously-undocumented colliding binary, `/usr/bin/ng-latin`, which hangs silently rather than panicking — see the Gotchas entry above, extended this wave). Once diagnosed, the verify chain below runs the real Angular CLI by explicit path (`node node_modules/@angular/cli/bin/ng.js`), the same fix `npx ng`/`npm run build` already apply automatically.
  - **Live pass, both themes** (`ng serve` already running on `:4200`, driven via the claude-in-chrome MCP browser tools — no server started or restarted by this task): confirmed P0 (empty state, "Go to Inventory" routes to `/assets`), P1 (grid reflow via `--tile-min`, density segmented control, boxes-cycle button), P2 (focus overlay — enlarged player, five facts, both labelled exits, Esc/backdrop close), and the Activity drawer (rows, relative-time ticking, footer link to `/monitor/alerts`) all rendering correctly in light and dark theme, with **two temporarily-started simulated streams** (via the app's own `POST /api/devices/{id}/stream`/`DELETE /api/streams/{id}` endpoints — no process touched, no dev-server/backend restarted) exercising a real multi-tile grid; both streams stopped and the theme reverted to its original state afterward, so the live environment ended the pass unchanged (0 streaming assets). **Not achieved live**: a genuinely stalled/no-publisher tile — forcing one would mean deliberately breaking the backend or a publisher mid-session, judged too invasive for a read-only verification pass; accepted as the plan's own named residual (§5 residual #3: "No live/SITL screenshot verification exists until W5 runs… W5's acceptance list is the gate, not `tsc` green" — the health-precedence ladder itself is unit-covered in `wall-logic.spec.ts`, including the `no-publisher`/`stalled`/`reconnecting` states, just not screenshotted).
  - **Role-gating / dev parity**: unaffected — W5 is verification plus one template-only accessibility fix; nothing here reads a role or `vision.auth.enabled`.
  - **Degrades honestly**: unchanged from W1–W4's own accounts above; nothing in W5 touches a degrade path.
  - **Verify chain, all green**: `npx tsc --noEmit -p tsconfig.app.json` / `-p tsconfig.spec.json` — 0 errors both. `npm run test:ci` — **181/181 files, 3570/3570 tests** (unchanged from W4 — the aria-label fix is template-only, no new spec). `node node_modules/@angular/cli/bin/ng.js build --configuration production` — green, same two pre-existing warnings only (initial bundle over its 390 kB budget by 31.60 kB; `tactical-map.css` over its 11 kB budget by 376 bytes — both present before this branch, neither touched by this plan).
  - **Bundle delta**, measured against the `102951d9` fork-point baseline (disposable `git worktree` at `102951d9`, `node_modules` symlinked rather than reinstalled, removed after measuring — the same methodology `COMMAND-MAP-FLOW-PLAN.md`'s own W5 used): initial bundle **422.01 kB → 421.60 kB raw (−0.41 kB), 118.76 kB → 117.85 kB transfer (−0.91 kB)** — effectively flat (every wall-flow-ux change lives inside the `wall` lazy chunk; nothing moved into the eager bundle). Lazy `wall` chunk **9.80 kB → 21.95 kB raw (+12.15 kB), 3.30 kB → 6.20 kB transfer (+2.90 kB)** — the net cost of two new components (`wall-focus`, `wall-activity`) and `wall-tile`'s rewritten skin, offset partly by deleting the tile's own `TelemetryStore`, the glyph/altitude/RouterLink markup, and no longer importing `vision-events-rail` into this chunk at all (W3's early deletion). (Dev-mode `test:ci` bundle-report figures recorded mid-wave — W3→W4: 51.94 kB → 60.35 kB — are a different measurement mode, unminified/untreeshaken, not directly comparable to the production numbers above; both are accurate to their own build mode.)
  - **Left unverified**: the stalled/no-publisher tile visual state (see "Live pass" above) — rests on `wall-logic.spec.ts`'s unit coverage of the health-precedence ladder, not an independent screenshot. No other gap is known.
  - **Commit**: `station/vision-web/src/app/features/wall/wall-tile.html` (aria-label fix) + this `MODULE.md` (Gotchas extension + `wall/` bullet + this Status entry) + `docs/plans/active/WALL-FLOW-PLAN.md` (close-out table appended) + `docs/plans/README.md` (row added).

- **2026-09-03, two live-verification fixes on `feat/wall-flow-ux` (post-W5)** — the plan's own W5 close-out had already declared the branch done; both defects below were caught by a live check afterward, each fixed and re-verified in isolation, scope held to `features/wall/**` both times.
  - **Fix 1 — `unlinked` asserted before the join had a chance to run** (`451ab7bb`): live repro had a matching `streamId` on both `/api/streams` and `/api/fleet/summary` for the same device/asset, yet the tile rendered `"Skyfall Vampire 2 · telemetry"` (the device's own name) and "Not linked to an asset." The `streamId`-keyed join in `buildWallTiles` was and is correct — the bug was `WallFacade` itself: request-scoped (§3.4, no `providedIn: 'root'`), so its `summarySignal` restarts `undefined` on every `/wall` mount even when `FleetStore`'s streams/devices are already warm, and `buildWallTiles` treated that cold-start `assets: []` identically to a confirmed empty summary. Fix: `BuildWallTilesInput.summaryLoaded: boolean`, flipped by a new `WallFacade#summaryLoadedSignal` the first time `refreshSummary()` settles (success or failure); `unlinked` is now `asset === undefined && summaryLoaded`. Full mechanism recorded in Gotchas above. Regression coverage: `wall-logic.spec.ts` — the exact live repro shape (shared streamId, asset name alone as the title, no "· telemetry" suffix, battery/mode facts present), the cold-mount window itself (`summaryLoaded: false` must never assert `unlinked`), and confirmation the pre-existing loaded-and-empty degrade path is unchanged.
  - **Fix 2 — battery rendered as a raw float** (this commit) — a live tile showed `"58.349999999999994%"` (`AssetAttention.batteryPercent`'s IEEE-754 value passed straight through to both `wall-tile.html` and `wall-focus.html`, each formatting it independently). `WallTileModel.batteryPercent?: number` replaced with `batteryLabel: string | null` — `` `${Math.round(asset.batteryPercent)}%` `` computed once in `buildWallTiles`, `null` when absent — mirroring the pre-existing `telemetryAgeLabel` precedent (a finished label, not a raw value the component formats itself) so both templates inherit the fix from one place; `batterySeverity` is unchanged, still classified from the raw value, never the rounded one. Regression coverage: the exact repro float (`58.349999999999994` → `"58%"`), `98.6` → `"99%"`, `undefined` → `null`, and a threshold-boundary case (`25.4` → label `"25%"` but severity `"ok"`, proving rounding never leaks into classification).
  - **Role-gating / dev parity**: unaffected by either fix — no permission surface or `vision.auth.enabled` branch touched.
  - **Degrades honestly**: both fixes are strictly *more* honest than what shipped in W1–W5 — an unresolved join no longer claims a false negative, and a battery reading is never shown with spurious float precision a wall watcher never asked for.
  - **Verify chain, both fixes, foreground**: `npx tsc --noEmit -p tsconfig.app.json` / `-p tsconfig.spec.json` — 0 errors both, each time. `npm run test:ci` — Fix 1: **181/181 files, 3574/3574 tests**. Fix 2: **181/181 files, 3578/3578 tests**.
  - **Commit**: Fix 1 — `station/vision-web/src/app/features/wall/{wall-logic.ts,wall-facade.ts,wall-logic.spec.ts}` (`451ab7bb`). Fix 2 — `station/vision-web/src/app/features/wall/{wall-logic.ts,wall-tile.html,wall-focus.html,wall-logic.spec.ts}` + this `MODULE.md` (Gotchas entry + `wall/` bullet update + this Status entry) (this commit).

## Status — CONTROLLER-SETUP wave C15: the setup page redesign + Guide me + live channel strip (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md) — 2026-08-24, reconciled here as part of merging `feat/controller-setup-c15`

Builds on the C7/C10/C11 setup page (`ControllerSetupPage`/`ControllerSetupFacade`, `src/app/core/rc/**`) already
documented above. Four pieces:

- **`channel-output-logic.ts`** (pure, spec'd) — `channelOutputs(draft, axes, buttons, catalog)` computes what
  each of CH1–CH8 would carry **right now**, in microseconds, mirroring `ControlBinding#toMicros` step for step
  (clamp → reverse → deadband → piecewise map → clamp; switches snap to detents). A channel nothing drives reads
  `undefined`, not a number. Feeds a live **"What the vehicle receives"** CH1–CH8 strip between the diagram and
  the mapped-controls list — duplicating the backend's mapping in TypeScript is deliberate: the operator is
  checking their *layout*, and a strip fed by the server would only prove the server agrees with itself.
- **`guided-setup.ts`** (pure, spec'd) — **Guide me**, the inverse of Autodetect: function-first. `guidedSteps`
  builds one question per channel function the vehicle kind's built-in declares ("Move the control you use for
  Steering"), `beginGuided`/`advanceGuided`/`skipGuided`/`currentStep`/`isGuidedDone` drive it through three
  phases (`off`/`watch`/`settle`); `SETTLE_TICKS = 9` (~0.15 s at 60 Hz) keeps a self-centring stick's spring-back
  from being read as the next answer, its quiet threshold (0.08) deliberately below `movedControl`'s trigger
  (0.5). Answering a step **takes over** any row already holding that function or channel — mutually exclusive
  with Autodetect by construction, since both claim the same flick.
- **The two-column work-area redesign** — diagram → the live CH1-CH8 strip → one line per mapped control, with a
  **sticky editor beside them** rendering only the selected control's fields, collapsing under the diagram at
  `--bp-lg`. Replaces the earlier column of full-height cards, which took roughly two screens of scrolling to
  reach a picked control.
- **Stick mode (1–4) and stick direction moved onto the saved profile** (`stickMode`/`forwardIsUp`) — they used
  to be browser preferences in `core/panel-state.ts`. That was wrong: the picture is of **the operator's own
  radio**, which follows them between browsers and machines while `localStorage` does not. They still change
  nothing about what the vehicle does — axis → function → channel decides that — and the page says so under the
  pickers.

### Tests

C15 adds 16 `channel-output-logic.spec.ts` + 17 `guided-setup.spec.ts` cases, plus cases for
`groupByKind`/`asStickMode`/`assignFunction`. `npm run test:ci` — **143 files, 2625 tests green** at the time of
this wave.

### Left undone, named honestly

- **The C15 backend half was not live on the running station at build time.** `stickMode`/`forwardIsUp` were
  compiled and tested against the migration then numbered `V25`, but the dev station ran an old build; a `PUT`
  carrying them answered `200` while ignoring them, falling back to mode 2 / forward-up until restarted. Both
  fields are optional in both directions precisely so that degrades quietly. (The migration is `V32` on the
  merged tree — see `storage/persistence/MODULE.md`'s schema ledger and Gotchas for the renumbering.)
- **Light theme only** — never verified against dark theme at the time of this wave.

## Status — AUTH-ROLES web wave W3: first-boot bootstrap, people/credentials surfaces, forced password change (docs/plans/active/AUTH-ROLES-PLAN.md §3.5) — 2026-09-05

Closes out the web half of AUTH-ROLES. Waves W1 (`67bf98b5`, session foundation: `Role` widened with `VIEWER`; `MeResponse.capabilities`/`scopeKind`/`mustChangePassword` mirrored 1:1; bootstrap/change-password/set-memberships API client calls; `AuthStore` capability signals + `sessionExpired`; the first `HttpInterceptorFn` — 401 off `/fly` clears the session and routes to `/login?returnUrl=`, 401 on `/fly` only flips `reauthRequired` with no navigation and the RC socket untouched, surfaced by the new `shared/ui/reauth-overlay.*`) and W2 (`22807c2f`, gate realignment: every live `topRole` comparison replaced by `canManageOrg`/`canAdminister` over `capabilities`/`scopeKind`, 14 call sites, incl. a region-manager fix where a MANAGER no longer sees ingest/delete it would silently 403 on) landed first but were never reconciled into this file — noted here rather than left permanently undocumented. This wave (W3) is additive on top of both, same file-scope discipline (`station/vision-web` only, no backend module touched).

New:

- **`features/setup/`** (new) — `SetupPage`/`SetupFacade`, the bootstrap-latch counterpart to `features/auth/login/`: username/displayName/email/password/confirmPassword form, calls `AuthStore#bootstrap()` then `router.navigateByUrl('/')` on success. Routed at `/setup` (`setup.routes.ts`, `canActivate: [setupGuard]`) — mirrors `login.css`'s centered-card layout byte-for-byte rather than sharing it (view encapsulation scopes `.login-*` to `LoginPage` alone; same "duplicate a small themed shell rather than break encapsulation" precedent). Registered in `core/ui/architecture.spec.ts#ROUTED_PAGES`.
- **`core/auth/auth-guard.ts` rewritten around the latch.** `authGuard` (the general "must be signed in" gate) now asks `AuthStore#bootstrapRequired()` only once a session is actually needed, and routes anonymous traffic through the new `core/auth/auth-logic.ts#anonymousDestination(bootstrapRequired): '/setup' | '/login'` rather than always `/login`. Two new guards close the loop: `loginGuard` (on `/login`) redirects to `/setup` while the latch is still open — an operator can't "sign in" to a station with no administrator yet; `setupGuard` (on `/setup`) redirects to `/` the instant the latch closes — the create-first-admin form is unreachable, not just hidden, once any admin exists. `AuthStore#bootstrapRequired()`/`#bootstrap()` both **fail safe and never throw**: an unreachable bootstrap-status check reads `false` (never blocks the app on a network hiccup — the ordinary `/login` path stays reachable), and a rejected `bootstrap()` call returns the server's own message string, mirroring `login()`'s own contract exactly. Neither guard has a dedicated spec file — thin wiring over already-tested pure/store functions, this codebase's established carve-out (no `auth-guard.spec.ts` existed before this wave either).
- **`shared/ui/force-password-change.*`** (new) — a global, app-shell-mounted overlay (`app.html`, alongside `<vision-reauth-overlay>`) that appears whenever `AuthStore#mustChangePassword()` is true (an admin used `adminSetPassword` on this account, or this is a brand-new bootstrap admin — either way the server always forces the flag), demanding current/new/confirm password via the same `changePassword()` call `account-settings`'s self-service form uses. **Not a dead end**: unlike `ReauthOverlay` (no escape), this one offers "Log out instead" — a user who can't or doesn't want to set a new password right now can still leave rather than being trapped behind a modal with no way out. Layered at `z-index: 190`, one below `reauth-overlay.css`'s `200` — deliberately: if the session itself has died (reauth needed) *and* a password change is pending, "your session is gone" must win, since a password-change submit against a dead session would just fail confusingly.
- **`features/org-settings/`** gained a per-user **manage panel** (`OrgSettingsFacade#openManageUser`/`closeManageUser`, a `Manage`/`Close` button per roster row) with two sections: **Memberships** — add/remove/change-role per group membership against a local draft, `Save memberships` calls the new `OrgStore#setMemberships` (wraps `PUT /api/users/{id}/memberships`, **wholesale replace, not a delta**, then refetches the roster and re-seeds the draft from the server's own response) — and **Reset password** — one field + button, the new `OrgStore#adminSetPassword` (wraps `POST /api/users/{id}/password`), toasts "they must change it at next sign-in" since the server always forces `mustChangePassword` on the target. `core/org/org-logic.ts#roleOptions()` widened to include **`VIEWER`** (was PILOT/MANAGER/ADMIN only) — the Wall/kiosk persona needs a grantable role; `roleOptions` remains the full set regardless of the caller's own scope, same "let the backend be the one authority on what a given inviter may grant" rule its own doc comment already stated.
- **Seat picker (PILOT/CREW) wired end-to-end.** `VisionApi#assignPilot`'s `role?: AssignmentRole` parameter (already on the wire, unused from the UI before this wave) is now actually reachable: `pilots-card.ts` (asset detail's pilot list) and `pilot-assignments-panel.ts` (roster's "By pilot" view) both gained a seat `<select>` — on the add-pilot form and per already-assigned row alike — calling the same idempotent `PUT` for both a new assignment and a role change (no separate "change role" endpoint or method; `assignPilot`'s own doc comment already reads that way). `pilot-assignments-panel`'s `assign` output widened from `output<string>()` to `output<{assetId, role}>()`; `RosterFacade#assignPilotToAsset` gained the matching optional third parameter. `core/roster/roster-pivot-logic.ts#PilotAssetAssignment` gained a `role: AssignmentRole` field + `assignmentRoleLabel(role)` (`'Pilot'`/`'Crew'`) so both surfaces render the seat with one shared label, not two spellings.
- **`features/settings/account-settings`** gained a **Security** section (between Notifications and Settings) — self-service password change (current/new/confirm, `AuthStore#changePassword()`, the same call the forced-change overlay uses) when `facade.authEnabled()`; otherwise an explanatory paragraph naming `vision.auth.enabled=true` / `VISION_AUTH_ENABLED=true` and that a restart is required — the honest answer for why there's no password to change under dev parity, not a hidden section.
- **`core/command/setup-checklist-logic.ts#buildSetupChecklist` gained a 5th row**, `'secure-station'` ("Secure this station" → `/settings`), `done: authEnabled` — the one row with no in-app action (there's no button that flips `vision.auth.enabled` at runtime; it's a config+restart). Its stale doc comment citing the now-deleted `AuthSeedRunner` was fixed in passing — the real seed path is `storage/persistence/src/main/resources/db/seed/dev/V90001__dev_accounts.sql`, gated by `vision.persistence.seed-dev-users` (now defaults **`false`**, not `true` as the old comment implied — a genuinely fresh station seeds zero users, which `hasOnlySeededUsers`/`isFreshStation` both already handle correctly as a vacuous-true empty case).

Role-gating: the manage-user panel and its two actions are reachable only through `/org`'s existing `orgGuard` (`canManageOrg` — MANAGER/ADMIN), unchanged; the seat picker inherits whatever gate already wrapped the pilot-assignment surface it sits on (asset detail, roster) — no new gate, no new capability introduced. Dev parity: `vision.auth.enabled=false`'s dev principal is ADMIN/unbounded exactly as before — `bootstrapRequired()` and the whole `/setup` flow are unreachable in that mode (the dev principal is never anonymous), `authEnabled()` is `false` so `account-settings`'s Security section shows its dev-parity explanation instead of a form, and every gated affordance above behaves exactly as it did before this wave. Degrades honestly: `bootstrapRequired()`/`bootstrap()` never throw (see above); a failed `setMemberships`/`adminSetPassword` call surfaces the server's own `{error, message}` verbatim via `describeHttpError` (no hardcoded password-policy copy) and leaves the manage panel open with the draft intact, never a silent no-op.

**Left out, named honestly:** the login page's `kiosk: true` flag (frozen wire, `POST /api/auth/login`) has no UI checkbox anywhere in this wave — treated as a "don't break this" constraint on the existing login call, not a deliverable this wave asked for. A future kiosk-login affordance is unscoped.

### Tests / build

`npx tsc --noEmit` clean on both `tsconfig.app.json`/`tsconfig.spec.json`. `npm run test:ci` — **182 files, 3616 tests green** (W2 last recorded 182 files/3602 tests — net +14 tests across `auth-store.spec.ts` (bootstrap/`bootstrapRequired`), `org-logic.spec.ts` (`VIEWER` in `roleOptions`), `roster-pivot-logic.spec.ts` (role passthrough + `assignmentRoleLabel`), `setup-checklist-logic.spec.ts` (5th row); `core/ui/architecture.spec.ts` gained `setup/setup` to `ROUTED_PAGES` (84/84 still green)); no new component `TestBed` spec anywhere — every new component this wave is thin wiring over an already-tested pure function or store method, matching this file's own established precedent (`pilots-card`/`pilot-assignments-panel`/route guards already carried no dedicated spec file either). `ng build --configuration production` — measured with a `git stash`-based before/after on this branch (safe here: single-agent file scope, no concurrent session sharing this tree): **W2 baseline 464.12 kB** (matches W2's own recorded figure exactly) **→ W3 469.50 kB, +5.38 kB** for five new UI surfaces — the pre-existing ERROR-level budget overrun (440 kB threshold) is unchanged in kind, not materially worsened by this wave. The `tactical-map.css` component-style-budget warning is byte-for-byte unchanged before/after — that file has no edits this wave.

## Status — SOURCE-ONBOARDING-2 web wave W4: per-device Simulated badge + Sense/Sight role status on asset-detail and the Links tab (docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md §4 W4) — 2026-09-05

File-scope-disjoint wave on `feat/source-onboarding-2`, touching only `features/devices/**`, `features/asset-detail/**` (`features/onboarding/**` and the rest of `features/inventory/**` are two other agents' concurrent scope on the same branch/tree). Two deliverables, D6 and P3.

**D6 — the "Simulated" badge is now per-device, not per-category.** `features/devices/simulate-logic.ts#isSimulatedAsset`/`SIMULATED_CATEGORY` are **deleted outright** — the exact coupling defect `Device#origin` (`core/api/models.ts`, `DeviceOrigin = 'LIVE' | 'SIMULATED'`) was introduced to replace but that no UI had actually read until now (SOURCE-ONBOARDING-2-PLAN.md's own U1 finding). `mapSimulatedDevices(assets)` is rewritten to scan every asset's own `devices` and key the returned `deviceId → {assetId, displayName}` map off `device.origin === 'SIMULATED'` directly, so a vehicle that is half real (a real autopilot + a synthetic camera on one ordinary-category asset — exactly what `core/onboarding/fit-out-logic.ts#fitOutRowToDeviceSpec`'s per-row `simulate` branch produces) now shows the chip on the one device that earned it, not the whole asset. `DevicesFacade#refreshWarehouseAssets` repoints its one production call site accordingly; "Stop simulation" stays `DELETE /api/simulations/{assetId}` (asset-scoped on the wire, unchanged). The `simulated` **category** itself is untouched (still a selectable, cosmetic label in Identify) — deleting it is a separate data-migration follow-up the plan defers (§5.3 N3). `features/fly/drone-picker-logic.ts`'s own `isSimulated`/`SIMULATED_CATEGORY` (re-exported from `core/fleet/triage-logic.ts` since the OPERATOR-UX-4 W2 move, not from this file) are a fully independent, category-based duplicate, out of this wave's scope and untouched — **this bullet also corrects the `fly/` section's own T1 sub-bullet above**, which still describes `isSimulated`/`SIMULATED_CATEGORY` as "mirroring `features/devices/simulate-logic.ts#isSimulatedAsset`/`SIMULATED_CATEGORY`" — that pair no longer exists in this file; the W2 sub-bullet immediately below it (the `core/fleet/triage-logic.ts` extraction) is the actually-current story and was already correct.

**P3 — Sense/Sight role status, read honestly, in two places.** `core/onboarding/fit-out-logic.ts#roleStatus`/`roleForDevice` (frozen, prior wave W1 — consumed only, not edited) now render on:
- **The Links tab's own two-pane detail panel** (`features/devices/devices.html`) — a new "Role" fact row (`deviceRoleLabel`, muted text alongside Capabilities/Protocol — classification, never a chip, frontend-style §5) plus one more status chip in `.detail-chips` (`deviceRoleStatus` → `roleStatusDescriptor`, both new in `devices-page-logic.ts`), unmerged from the existing lifecycle/streaming chip and the "Simulated" chip already there — same "one more independent fact, not folded into the merged verdict" precedent that chip already set. `DevicesFacade` gained `ownerDevices(assetId)` (the owning asset's full device list — `roleStatus` needs every device on the asset, not just the one row, to judge "not-fitted" vs a sibling device covering the role) and `telemetryAgeMsFor(assetId)` (reads a new `fleetSummaryData` signal, `GET /api/fleet/summary` — the only source of a per-asset `telemetryAgeMs`; fetched alongside `refreshWarehouse()`, silent-degrade on failure, unchanged `showArchived()` toggle).
- **Asset Detail's cockpit-band header and its "Hardware & devices" full-width subview** (`features/asset-detail/asset-detail.ts`/`.html`) — the same two chips (`Sense: …` / `Sight: …`) in both spots, fed by new `AssetDetailFacade#senseStatus`/`sightStatus` computeds (`roleStatus('sense'|'sight', asset.devices, fleet.streams(), telemetryAgeMs)`) and a new `telemetryAgeMs` computed reading a new `fleetSummaryData` signal (`GET /api/fleet/summary?includeArchived=true` — unconditionally `true`: this page has no archived-toggle of its own but can still be reached for an archived asset). Fetched via a new `loadFleetSummary()`, wired into both `load()` and `refresh()` (the existing 5s poll), same silent-degrade convention as `loadStats`/`loadMaintenanceRecords` on this page.

Both surfaces render the frozen `RoleStatus` vocabulary through an identical `roleStatusDescriptor()` mapping — `not-fitted` → `{kind:'muted', label:'—'}` (**no chip at all**, since a device was simply never fitted for that role, not a failure); `never-seen` → quiet dot+text "Never heard" (honest, not an error); `live` → the one green/live chip; `stalled`/`stale` → warn chip; `stopped` → quiet dot+text. **Deliberately duplicated**, not centralized in `core/onboarding/fit-out-logic.ts`: this wave's own file-scope allowlist excludes `core/**`, and this codebase already has a standing precedent for exactly this trade-off (`core/fleet/triage-logic.ts`'s own doc comment — no feature imports another feature's own `*-logic.ts`; a small mapping duplicated once is cheaper than a new cross-feature import or a `core/` move for a single extra reader) — so `roleStatusDescriptor`/`RoleStatusDescriptor`/`RoleStatusToneKind` now have two independent copies, one in `devices-page-logic.ts`, one in `asset-detail-logic.ts`.

**Scoping decisions, named:** `features/inventory/vehicles-table.html` (the Inventory page's Vehicles/Equipment tab) also has a two-pane detail panel, but the task named "the Links tab" specifically — left untouched to keep this wave's blast radius to exactly what was asked. The main Devices **table row** (not the detail panel) also stays untouched — it already carries its one merged state chip plus a possible "Simulated" chip, and frontend-style §5's "one chip per row max" governs *that* row; the detail panel is a different, already-multi-chip surface (Simulated sits there unmerged too) where a third independent fact chip is consistent with the existing pattern.

Dev parity: nothing here reads `topRole`/auth state — `device.origin`/`roleStatus` are both plain data-shape reads, identical whether `vision.auth.enabled` is on or off. Degrades honestly: a `fleetSummaryData`/`telemetryAgeMs` fetch failure leaves the signal at its previous value (or `undefined` before the first success) rather than blocking the page or fabricating a reading — `roleStatus` already treats an unknown age as `never-seen`/`stale` rather than `live`, so no separate fallback branch was needed at either call site.

### Tests / build

`npx tsc --noEmit -p tsconfig.app.json` / `-p tsconfig.spec.json` — both clean (zero errors anywhere in this wave's own file scope) at the moment this wave's edits were completed. `npm run test:ci` — **183 files, 3663 tests green** at that same moment (net +19 over this file's last recorded 183/3644: new coverage in `simulate-logic.spec.ts` for `mapSimulatedDevices`'s per-device-origin matching including a mixed/half-real asset, replacing the deleted `isSimulatedAsset` describe block; `devices-page-logic.spec.ts` gained `deviceRoleLabel`/`deviceRoleStatus`/`roleStatusDescriptor` coverage; `asset-detail-logic.spec.ts` gained `roleStatusDescriptor` coverage). No new component `TestBed` spec — both new UI surfaces are thin template wiring over already-tested pure functions/facade computeds.

**`ng build --configuration production` could not be completed or measured this wave** — not from anything in this wave's own diff (confirmed by a scoped `git stash push` isolating only this wave's touched files, then rebuilding: the identical failure reproduces with this wave's files completely absent from the tree), but from concurrent, still-in-progress edits to `features/onboarding/**` (outside this wave's file-scope allowlist, actively being worked by sibling agents in this same shared checkout at the time of this wave) leaving `WizardStep`/`onboarding-facade.ts`/`onboarding-store.ts` in a transiently-inconsistent state (`'connect'`/`'register'` not yet valid `WizardStep` members, a `StepContext` missing a field, etc.) — a hard compile error, not a lint/budget warning. A `grep -v features/onboarding` over both `tsc --noEmit` runs confirms zero errors anywhere else in the app at that moment. Recommend re-running `ng build --configuration production` for a bundle-size delta once the onboarding waves in flight land; this file's own prior AUTH-ROLES W3 entry above already separately documents the initial-bundle ERROR-level budget overrun (440 kB threshold, 464.12 kB → 469.50 kB there) as pre-existing and unrelated to either wave.

## Status — SOURCE-ONBOARDING-2 web waves W2+W3: the honest fork + the monolith retired into per-step components (docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md §3.1) — 2026-09-05

File-scope-disjoint wave on `feat/source-onboarding-2`, with sole, orchestrator-confirmed ownership of `features/onboarding/**` (every other concurrent agent on this shared tree — W4 `features/devices|asset-detail/**`, W5 `features/fly/**` — stood down on this directory; both of their own Status entries above independently document hitting this wave's own files mid-rewrite and recommend re-running once it lands). Consumed, unedited: `shared/ui/step-rail.*` and `core/onboarding/{fit-out-logic.ts,intake-logic.ts}` (W1, frozen).

**The pre-W2 1161-line `onboarding.html` monolith is retired outright** into one 3-file component per step — new `source-step.{ts,html,css}`, `prove-step.{ts,html,css}`, `identify-step.{ts,html,css}`, `attach-step.{ts,html,css}`, `sysid-step.{ts,html,css}`, `handover-step.{ts,html,css}` — plus a rewritten `onboarding.ts`/`.html`/`.css` (now a thin shell: `vision-step-rail` + a `@switch` delegating to each step component + one shared Back/Next footer for `source`/`prove`/`identify`, since `attach`/`sysid`/`handover` each own a fully custom inline footer instead) and rewritten `onboarding-store.ts`/`onboarding-facade.ts`. Every step component injects `OnboardingFacade` directly (never `OnboardingStore` — one single access path, `facade.xxx`/`facade.store.xxx`, matching the old facade's own precedent) — non-routed presentational children, licensed by `core/ui/architecture.spec.ts`'s own carve-out. See the `onboarding/` bullet in the features section above for the full per-step rewrite (the fork's four tiles + the fifth provision-Wi-Fi tile, the candidate-entrance prefill contract, the waiting room + P1 diagnostics + push-address card, Attach's new-vs-existing-asset fork, and Hand-over's D9 two-half terminal proof replacing `handoverNext()`).

**Two deliberate deviations from a literal reading of the plan, both reasoned through and recorded in `onboarding-store.ts`'s own doc comments:**
1. `scan` mode (the fork's "Find it for me" tile) auto-selects nothing for either fit-out row — an earlier draft auto-picked Sight's `discover` finder here (reasoning: "Sight's only remaining scanner once manual owns the register tile"), which turned out to make the legacy whole-vehicle Simulate demo path (the tile grid's own third, universal "Use a test source" tile) completely unreachable for Sight under both `manual` and `scan` modes. Reverted: both rows render their own tile grid under `scan`, narrowed only to exclude `register` (`OnboardingFacade#rowFindMethods`).
2. The rail's own one-way-door-past-creation rule (nothing should let an operator jump backward once the asset already exists) lives in `OnboardingStore#jumpToStep`, not in `vision-step-rail` itself — that shared component is deliberately, permanently unrestricted (its own doc comment: "every step is re-enterable by design"), so the onboarding-specific constraint has to live in the one place that already owns `createdAssetId`.

**Design/dataviz choices:** the fork's four tiles reuse the existing `.fork-tile`/`.method-tile` card idiom (border/radius/hover — no new visual language), each with a hand-drawn CSS glyph in the existing `.method-glyph` family (moved, not redesigned) rather than an icon-font addition. The waiting room's `.waiting-dot` uses the existing `--color-success`/pulse-then-solid convention (idle = a slow pulse in `--text-faint`, heard = solid `--color-success`, no new palette). The Hand-over terminal screen's two-half proof (`.proof-halves`/`.proof-half.proven`) is new — a two-column card grid (collapses to one column under `auto-fit, minmax(12rem, 1fr)`), an unproven half rendering muted (`--text-muted`) and a proven half rendering in `--color-success`/`--color-success-text`, mirroring the same tone `.chip.ok` already uses elsewhere — no invented colors.

**Degrades honestly:** the waiting room never claims `proven` on its own (`intakeState` itself is incapable of it — only `OnboardingFacade#intakeFor` layers a real recorded probe result on top); a pre-proven-but-never-actually-tested row's Hand-over proof reads "not yet verified", never a fabricated tick (`sightTerminalProof`/`senseTerminalProof` only read `lastProbeResult`/`lastVerifyResult`, never `preProvenRoles`); the P1 diagnostic panel renders every `DiscoveryStatusResponse` fact as given, including `NEVER_SCANNED` → "Never scanned", never inventing "broken"; the push-address card is omitted entirely (with an honest reason) rather than guessing a host when `videoPushPort`/`videoPushPathPrefix` is absent. **Dev parity:** nothing in this wave's own diff reads `topRole`/auth state beyond the pre-existing `orgGuard` on the route (unchanged) — every new signal/computed is a plain data-shape read, identical whether `vision.auth.enabled` is on or off.

### Tests / build

`npx tsc --noEmit -p tsconfig.app.json` / `-p tsconfig.spec.json` — both clean, zero errors, confirmed on the completed rewrite (the transient cross-agent compile errors W4's and W5's own Status entries above both hit mid-flight are gone now that this wave's own files are done). `npm run test:ci` — **183 files, 3695 tests green**; no new component `TestBed` spec for any of the six new step components (thin wiring over `OnboardingFacade`/`OnboardingStore`, matching this file's own established precedent) — the pure logic underneath (`onboarding-logic.ts`'s `sightTerminalProof`/`senseTerminalProof`/`prefillFromDiscoveryCandidate`/`composePushAddress`/`roleForDiscoveryMethod`/`relativeAge`, `core/onboarding/intake-logic.ts`'s `intakeState`/`freshestNewCandidate`) already carries its own `.spec.ts` coverage, added alongside `onboarding-logic.ts`'s own rewrite earlier in this same wave.

`ng build --configuration production` — bundle generation itself completes (every chunk, including the six new step components, compiles and is written to disk); the CLI's own non-zero exit comes solely from the pre-existing initial-bundle ERROR-level budget gate (440 kB threshold) — **470.36 kB**, essentially unchanged from the AUTH-ROLES W3 entry's own last-recorded 469.50 kB (+0.86 kB, from unrelated concurrent work on this shared tree) — confirmed unrelated to this wave since `onboarding.routes.ts` lazy-loads `OnboardingPage` via `loadComponent`, so every file this wave touched compiles into its own lazy chunk, never the initial one. **`onboarding` lazy chunk: 98.09 kB → 123.31 kB raw (+25.22 kB), 23.60 kB → 28.52 kB transfer (+4.92 kB)** versus the last successful measurement of this chunk (features section above) — the expected cost of retiring a single 1161-line template into six components plus genuinely new surface (the fork, the waiting room, the P1 diagnostics panel, the push-address card, Attach's existing-asset fork, and Hand-over's two-half terminal proof), not a regression.

**Nothing left incomplete** against this wave's own scope (the 5 owned files plus new files under `features/onboarding/**`) — every plan item in §3.1 is built: the fork (4 tiles + provision-Wi-Fi), candidate-entrance prefill, the retired monolith, the waiting room + P1 diagnostics, the push-address card, Attach's new-vs-existing fork, and the D9 terminal proof with its "Open cockpit ›" link. The cockpit's own `?autostart=1` consumption is explicitly out of scope (W5's job) and was not touched here.

## Status — TRACK-FOLLOW web wave W4: the follow-lock glass + D1 fixed, a closed drawer no longer un-follows the target (docs/plans/active/TRACK-FOLLOW-PLAN.md §3, wave W4) — 2026-09-05

Built the wave's file-scoped slice on top of W3's frozen wire (`follow` object on `GET /api/streams/{id}/tracks`, already shipped). `core/api/models.ts` gained `FollowState`/`FollowStatus` + `StreamTracksResponse.follow?`. `core/detections/detections-store.ts` gained `followTracks(streamId, wanted)`, a thin wrapper over the pre-existing `trackTracks`/`untrackTracks`. `features/fly/cockpit-facade.ts#lockedTrackId` moved off the tracks-poll echo onto the per-frame detections feed (`computed(() => detections.results()[0]?.tracking?.lockedTrackId ?? 0)`) — this **is** defect D1's fix, the wave's headline: the old plumbing zeroed the overlay's lock the instant the Vision drawer closed even though the server-side lock was untouched. The facade also gained `follow`/`lostBox` reads and `releaseFollow()`/`reacquireFollow()` writes (reusing the existing `buildReleaseLockPatch()`/`followTrack()`), plus a `wantsTracksPoll`-gated constructor effect driving `followTracks`. `features/fly/cv-control-panel.ts` lost poll ownership entirely — `lockedTrackIdChange` output deleted, its one `cockpit.html` call site deleted with it, the panel's own chip unchanged (still reads the slower tracks-poll copy, fine since it only renders while mounted). New `shared/player/follow-hud/` — `follow-hud.{ts,html,css}` (three files) + `follow-logic.ts`/`.spec.ts` — mounted first-child inside `cockpit.html`'s `.main-left` (its `column-reverse` layout puts DOM-first at the visual bottom). `shared/player/player.ts` gained a `lostBox` input + a tier-independent `drawLostBox` pass: the pre-existing detection-drawing pipeline was extracted verbatim into a new private `drawDetections` method so `redrawOverlay`'s own top gate could narrow from "no video, or nothing to draw" to just "no video" — `detection-overlay-logic.ts` itself untouched, per the plan's own file-scope fence reserving that region for wave W6. `features/fly/cockpit.ts` needed one mechanical, state-free addition outside the plan's literal file list: registering `FollowHud` in its own `imports: []` array (Angular standalone components require this for any new element tag) — no signal/store/injection added, flagged here since the plan text didn't name that file.

**Deviation** (disclosed in-code at `CockpitFacade#wantsTracksPoll`'s own doc comment): the plan's `wanted` formula for the tracks poll is "the Vision drawer is open OR the lock is non-zero OR the last read was LOST"; `cockpit.ts`'s drawer-open state lives in its own host-owned, deliberately non-injectable `UiStore` (`core/ui/ui-store.ts`'s own doc comment), unreachable from the facade without either injecting a store into a page component (an `architecture.spec.ts` invariant this wave must not touch) or adding a new `CvControlPanel` output (out of scope — "keeps its chip but loses tracks-poll ownership" only). `detectionOn()` substitutes for the drawer-open clause: it fails toward *more* polling, never toward silently missing a `LOST` recovery window, the one honesty-critical case a dropped poll would break. A follow-up wave touching `cockpit.ts` should wire the real boolean through instead.

**Degrades honestly:** `<vision-follow-hud>` renders nothing while `follow` is `null` (no lock ever issued, or after release) — no placeholder pill, nothing to say. `lostBox` only ever draws while `follow.state === 'LOST'`; the moment recovery lands or the lock is released it disappears, never a stale ghost box. "No other box dims during `LOST`" (the plan's own D3 requirement) falls out for free rather than needing new code: `lockedTrackId` reads `0` in `LOST` too (the identical per-frame fact), so `detection-overlay-logic.ts`'s pre-existing `tierAlphaPercent(tier, lockActive)` lock-dims-rest rule already stops dimming everything else — zero changes to that file were needed for it. **Role-gating:** none of this wave is role-gated — Release/Re-acquire gate on `canRelease` (`!facade.watchMode()`, an existing watch-mode predicate), not a new role check; a future `/live`/Wall/crew mount supplies its own `canRelease` answer, per the component's own "injects nothing" contract. **Dev parity:** unaffected — every new read/write is a plain wire-shape computation, identical whether `vision.auth.enabled` is on or off.

### Tests / build

`npx tsc --noEmit -p tsconfig.app.json` / `-p tsconfig.spec.json` — both clean. `npm run test:ci` (via `ng test --watch=false`) — **185 files, 3711 tests green** (+1 file, +13 tests over this wave's own isolated `git stash`-measured baseline of 184 files/3698 tests: 10 new cases in `follow-logic.spec.ts` covering all five `FollowState` values, the `""`→`"#7"` label fallback, the tone mapping, and the `showReacquire` gate; 3 new cases in `detections-store.spec.ts` — two for `followTracks`'s wrapper behavior, one the required D1 regression proving `results()[0]?.tracking?.lockedTrackId` reads non-zero with the tracks poll never started).

`ng build --configuration production` — fails on the identical **pre-existing** initial-bundle ERROR-level budget gate every recent wave's own entry above has hit (440 kB threshold); confirmed unrelated to this wave by isolated `git stash`/rebuild/`git stash pop`: baseline (pre-wave) **470.40 kB** raw / 129.32 kB transfer initial, this wave's own **470.41 kB** / 129.34 kB (+0.01 kB raw — noise). This wave's real cost lands entirely in the lazy `cockpit` chunk (`CockpitFacade`/`CvControlPanel`/`FollowHud` are all Fly-route-only): **176.67 kB → 179.70 kB raw (+3.03 kB), 37.29 kB → 37.82 kB transfer (+0.53 kB)**.

**Nothing left incomplete** against this wave's own file scope (`models.ts`, `detections-store.ts`, `cockpit-facade.ts`, `cockpit.html`, `cv-control-panel.ts`, the new `follow-hud/`, `player.ts`) — every plan item is built: D1's fix, the follow-hud mount with Release/Re-acquire wired to the existing PATCH builders, and D3's tier-independent lost-box draw pass.

## Status — TRACK-FOLLOW web wave W5: the second door, and the other seats (docs/plans/active/TRACK-FOLLOW-PLAN.md §3.4, wave W5) — 2026-09-05

Built the wave's three-part file-scoped slice: the target list (Fly's second door onto Follow), and Follow's reach into `/live` and Wall. New `features/fly/target-list.{ts,html,css}` + `target-list-logic.ts`/`.spec.ts` — `<vision-target-list>` (inputs `tracks: readonly StreamTrack[]` required, `followedTrackId: number` default `0`; output `trackSelected: number`), mounted inside `cv-control-panel.html` above this panel's own body (§3.4's placement, achieved without touching `cockpit.html` — `cv-control-panel` is already `cockpit.html`'s own sibling below `<vision-detections-strip>`). `cv-control-panel.ts` gained `liveTracks`/`selectTrack(trackId)` (calling the existing `buildFollowLockPatch()` builder — the same one `CockpitFacade`'s glass-click path and `releaseLock()` already share — a third call site, same shared function, so the wire shape can never drift across the app's three doors onto Follow). D7's muted "click a box in the video to lock onto it" hint is deleted from `cv-control-panel.html`. `features/live/live-facade.ts` gained `lockedTrackId`/`follow`/`lostBox` + `followTrack`/`releaseFollow`/`reacquireFollow`, computed off its own `DetectionsStore` exactly as `CockpitFacade` does, gated by a `wantsTracksPoll` reduced to `live()` alone (no drawer to gate on); `live.ts`/`live.html` wire `<vision-player>`'s `lockedTrackId`/`lostBox`/`trackFollowed` and mount `<vision-follow-hud>` with `[canRelease]=true`. `features/wall/wall-tile.ts` gained the read-only mirror — `lockedTrackId`/`follow` off its own per-tile `DetectionsStore`, gated by the tightest form of the formula in this plan (`lockedTrackId()!==0 || follow()?.state==='LOST'`, no drawer/liveness broadening — Wall can show dozens of tiles at once); `wall-tile.html`/`.css` mount `<vision-follow-hud>` as a DOM sibling of `.tile` (never a descendant — avoids nesting one `<button>` inside another), `[canRelease]=false`, neither `(release)` nor `(reacquire)` bound.

**Deviations** (all disclosed in-code): (1) `cv-control-panel.ts` needed a mechanical edit beyond the plan's literal `.html,.css` file list for Fly — registering `TargetList` in its own `imports: []` (an unavoidable Angular requirement for any new element tag; no signal/store/injection added), the same category of deviation W4's own entry above flagged for `cockpit.ts`/`FollowHud`. (2) `live-facade.ts` imports `buildFollowLockPatch`/`buildReleaseLockPatch` from `features/fly/cv-control-panel-logic.ts` — a deliberate cross-feature import, confirmed unrestricted by `architecture.spec.ts` (it only scans routed pages' own store/API injection, not a facade's plain-function imports), chosen over duplicating the PATCH builder to guarantee the two host pages can never disagree on wire shape. (3) `wall-tile.css` was touched even though the plan's literal scope for Wall named only `.{html,ts}` — required to position the sibling `<vision-follow-hud>` pill (`:host{position:relative}` + a `::ng-deep .follow-hud` rule mirroring the file's own pre-existing `.severity-dot`/`.pulse-chip` absolute-badge idiom) and to avoid a nested-`<button>` HTML hazard (Angular's renderer constructs DOM via direct API calls, so a nested interactive element actually persists in the DOM and can bubble clicks, unlike a browser HTML-parser's auto-correction). (4) Found, not fixed: `follow-hud.html`'s Re-acquire button ignores `canRelease` (Gotchas, above) — `follow-hud/*` is outside every file list this wave was handed; Wall's mount works around it defensively by never binding `(reacquire)`, so the defect can only ever render a dead button, never send a write.

**Degrades honestly:** the target list's empty state is `<vision-empty title="No tracked objects right now.">`, never a blank table; `DetectionsStore.tracks()` already degrades to `undefined`/no rows silently on a failed or empty fetch, per its own pre-existing contract, unchanged by this wave. `<vision-follow-hud>` on both new hosts inherits its own frozen degrade rule verbatim (renders nothing while `follow` is `null`). **Role-gating:** none of this wave's surfaces are role-gated. Live's `canRelease=true` is not a gating gap — grep-confirmed Live has no `watchMode`/role concept anywhere in the feature, so every viewer there already holds full Start/Stop control; hardcoding `true` states that honestly rather than fabricating a narrower permission that doesn't exist yet. Wall's `canRelease=false` is the plan's own explicit "watch surface stays a watch surface" rule, not a role check. **Dev parity:** unaffected — every new read/write in this wave is a plain wire-shape computation or a PATCH reusing an existing builder; behavior is identical whether `vision.auth.enabled` is on or off.

### Tests / build

`npx tsc --noEmit` / `-p tsconfig.spec.json` — both clean. `npm run test:ci` (via `ng test --watch=false`) — **186 files, 3720 tests green** (+1 file, +9 tests over this wave's own W4-measured baseline of 185 files/3711 tests: all 9 new cases in `target-list-logic.spec.ts`, covering followed-first ordering, most-recently-seen ordering among the rest, the `followedTrackId=0` sentinel, row-to-`followedTrackId` mapping, `humanAge` formatting, and all four `TrackState`→tone/label mappings).

`ng build --configuration production` — fails on the identical **pre-existing** initial-bundle ERROR-level budget gate every recent wave's own entry above has hit (440 kB threshold); confirmed unrelated to this wave by an isolated `git stash`/rebuild/`git stash pop` (only `station/vision-web/` paths stashed): baseline (pre-wave) **470.41 kB** raw / 129.34 kB transfer initial, this wave's own **470.41 kB** / 129.31 kB — byte-for-byte identical raw total; none of this wave's code reaches the initial bundle since all three touched pages are lazy routes. Lazy-chunk deltas: `live` **22.84 kB → 23.99 kB raw (+1.15 kB)**, 6.24 kB → 6.51 kB transfer (+0.27 kB); `wall` **22.09 kB → 22.68 kB raw (+0.59 kB)**, 6.21 kB → 6.38 kB transfer (+0.17 kB); `cockpit` **179.70 kB → 174.11 kB raw (-5.59 kB)**, 37.82 kB → 36.20 kB transfer (-1.62 kB) — a *decrease* despite this wave adding `target-list.*` to it, which is esbuild's automatic chunk-splitting finding a different boundary between `cockpit`'s own lazy chunk and an adjacent shared chunk (the identical byte-for-byte initial total confirms nothing actually moved into or out of the eagerly-loaded bundle); not a real content reduction.

**Left incomplete:** nothing against this wave's own file scope (`target-list.*` + `.spec`, `cv-control-panel.{html,ts}`, `live-facade.ts`/`live.{ts,html}`, `wall-tile.{html,ts}` + the necessarily-touched `wall-tile.css`) — every §3.4/D6/D7 item is built: the target list, its mount + D7's hint deletion, Live's read-write reach, and Wall's read-only reach. The one residual is the pre-existing `follow-hud.html` Re-acquire/`canRelease` defect (Gotchas, above), out of scope by file ownership and left for whichever wave next touches that frozen component.

## Status — TRACK-FOLLOW web wave W6: F2 digital crop-follow, a client-side ×2 re-frame with an honest label (docs/plans/active/TRACK-FOLLOW-PLAN.md §3.1 item 4/D10, wave W6) — 2026-09-05

Built the wave's file-scoped slice: a client-side-only re-frame of the *view* toward whichever box the FOLLOW lock is bound to — the same video pixels, cropped and CSS-scaled ×2, never a camera move and never extra detail. New `shared/player/crop-follow-logic.ts` + `.spec.ts` owns the pure math: `stepCropFollow(state, input)` advances an eased pan/zoom (`CropFollowState`, `centerX`/`centerY`/`scale` plus a **deadband-gated anchor** — `CROP_FOLLOW_DEADBAND_RADIUS = 0.06`, roughly a twelfth of the frame, so ordinary per-detection box jitter never retriggers a re-centre) toward the locked box's centre, a first-order exponential ease (`CROP_FOLLOW_EASE_TIME_CONSTANT_MS = 220`, ~1s to settle) rather than a spring that could overshoot; `input.targetBox === null` (no lock, `REQUESTING` before the first confirming frame, or `LOST`) snaps the scale back to `1` immediately, bypassing the ease — a lingering zoomed frame with nothing confirming it would misstate where the camera is actually looking. `cropFollowTransform(state)` derives the actual `{scale, offsetX, offsetY}`, clamping the offset against the *current* (possibly mid-ease) scale so the crop window is never momentarily invalid while easing in or out; at `scale === 1` the clamp collapses the offset to exactly `0`, so the identity case falls out of the same formula rather than a special case. `applyCropFollowToContentRect(content, transform)` maps `player.ts#letterboxRect`'s plain CSS-pixel rect into an *effective*, already-cropped one.

`player.ts#redrawOverlay` is the entire integration: it now independently selects the current detection result (`selectDetectionResult`), finds the locked track's raw box (`lockedTrackId !== 0 ? result?.detections.find(...) : null` — reads `0` in `LOST` too, the same per-frame fact `tierAlphaPercent`'s lock-dims-rest rule already relies on, so `followedBox` is naturally `null` in exactly the states that must not re-frame, no separate `follow.state` read needed), steps `cropFollowState`, and substitutes the resulting `effectiveContent` for the plain `content` everywhere box math reads it — `drawDetections`'s box-rect loop, `drawTrails`, and `drawLostBox`. **This is D10, solved with zero new code in the other three named sites**: `onOverlayMouseMove`/`onOverlayClick` only ever read back `drawnBoxes`, which `drawDetections` already populates from whatever content rect it was handed — once that rect is the effective one, the click hit-test is correct at ×2 for free, with no separate screen→video coordinate inversion. `crop-follow-logic.spec.ts`'s own "hit-test round trip at ×2 (D10 acceptance)" test proves this end-to-end with local `boxToScreenRect`/`hitTest` helpers that mirror `player.ts`'s exact box math: a click inside the zoomed followed box hits it, and a box the crop pushed off-canvas does not (asserted via `otherRect.x + otherRect.width < 0`, not just a missed hit-test — proof the transform, not test arithmetic, separates the two). `Player` gained one `cropFollowEnabled: boolean` input (default `false`) and two private redraw-loop fields (`cropFollowState`, `lastCropFollowStepAt`, reset to identity in `teardown()` so a fresh stream never inherits a previous one's pan/zoom); `detection-overlay-logic.ts` itself is untouched, per the plan's own file-scope fence.

The toggle: `<vision-follow-hud>` gained `cropFollowEnabled: boolean` (input, default `false`) / `cropFollowEnabledChange: boolean` (output, the `CvControlPanel#detectionEnabledChange` "carries the desired new value" idiom) and a `.btn.secondary.small` "Zoom ×2" button between the state text and Re-acquire/Release, rendered only while `follow-logic.ts#followPresentation(...).showCropToggle` is true — `state !== 'LOST'`, covered in `follow-logic.spec.ts`. **Honesty**: the button's `title`/`aria-label` both read `"Digital zoom ×2 — crop, no extra detail"` — never phrased as optical zoom. Active state is a solid `--color-info` fill (`follow-hud.css`'s new `.btn.active` rule), the same recipe `tactical-map.css`'s own standalone toggle buttons already use — no new token invented. **Deliberately not gated by `canRelease`**, unlike Release/Re-acquire: crop-follow is a purely client-side rendering preference with no server write, so a read-only watcher (Wall, `/live`) gets the toggle too; only `follow`'s own state decides whether it renders. The setting itself is `SettingsStore.cropFollowEnabled` (`core/settings/settings-store.ts`) — persisted, off by default, same category as `declutterLevel`: a genuinely personal, client-only rendering choice with no server-side counterpart.

`prefers-reduced-motion` (new `player.ts#prefersReducedMotion()`, read fresh every redraw via `window.matchMedia`, guarded for an environment with none) makes `stepCropFollow` snap straight to the target scale/centre in one step instead of easing fractionally — the first TS-level (not pure-CSS) reader of that media feature in this codebase, per frontend-style §9's anti-slop rule.

**Deviation (disclosed, not silently exceeded or silently dropped):** the task's file scope was `shared/player/{player.*, detection-overlay-logic.ts}`, the new `crop-follow-logic.*`, `core/settings/settings-store.ts`, and `shared/player/follow-hud/*` only — explicitly **not** `features/fly/cockpit.html`/`cockpit-facade.ts`, `features/live/live.*`, or `features/wall/wall-tile.*`. The toggle capability is fully built, tested, and safe-by-default (every existing host that mounts `<vision-follow-hud>` today simply doesn't bind `[cropFollowEnabled]`/`(cropFollowEnabledChange)`, so it renders the button but any click has nowhere to write — a real usability gap, not a crash or a lie, since the button correctly shows `active=false` and nothing happens until a host wires it) but was not wired into any host page by this wave itself. **Resolved immediately after (same branch, follow-up wiring commit):** all three hosts now bind it — Fly (`cockpit.html` via `facade.settings.cropFollowEnabled`, on both the main `<vision-player>` and the HUD), `/live` (`live.html` via its facade's public `settings`), and Wall per its facade-owns-settings idiom (`WallFacade#cropFollowEnabled` aliases the `SettingsStore` signal; `wall.html` binds it into each `<vision-wall-tile>`, which passes it to its player — together with the previously-unbound `[lockedTrackId]`, without which the crop could never engage on a tile — and echoes HUD toggles back up via a new `cropFollowEnabledChange` output).

**Degrades honestly:** the crop drops to the identity transform (scale `1`, full frame, byte-identical to the pre-wave view) the instant `follow.state === 'LOST'` — the HUD hides the toggle in the same instant (`showCropToggle`), so there is never a moment where the control is visible but re-framing on a frozen box. A host that never binds `cropFollowEnabled` (every host, until the follow-up wave above) gets the exact pre-wave rendering — not a degraded one, the literal same code path, since `stepCropFollow` with `enabled: false` always eases back to identity. **Role-gating:** none — crop-follow has no server write and no permission surface; `canRelease`/watch-mode gates Release/Re-acquire only, unchanged. **Dev parity:** unaffected — every read/write here is pure client-side rendering math with no wire call and no role check, identical whether `vision.auth.enabled` is on or off.

### Tests / build

`npx tsc --noEmit -p tsconfig.app.json` / `-p tsconfig.spec.json` — both clean. `npm run test:ci` (via `ng test --watch=false`) — **187 files, 3737 tests green** (+1 file, +17 tests over W5's own recorded 186 files/3720 tests: 13 new cases in `crop-follow-logic.spec.ts` — identity/off-centre-at-scale-1 transform, identity/doubled-size `applyCropFollowToContentRect`, deadband hold + re-aim, high/low edge clamp, identity-before-target + snap-on-loss + never-eases-while-disabled, reduced-motion snap, and the D10 hit-test round trip; 2 new cases in `follow-logic.spec.ts` (`showCropToggle` true for every state but `LOST`, false for `LOST`); 2 new cases in `settings-store.spec.ts` (`cropFollowEnabled` default+persist-across-reload, ignores a corrupt persisted value)).

`ng build --configuration production` — fails on the identical **pre-existing** initial-bundle ERROR-level budget gate every recent wave's own entry above has hit (440 kB threshold), confirmed unrelated to this wave by an isolated `git stash`/rebuild/`git stash pop` (only the 8 touched `station/vision-web/` files stashed, `docs/plans/active/CAMERA-FIRST-PLAN.md` — an unrelated pre-existing untracked file on this tree — left alone): baseline (pre-wave) **470.41 kB** raw / 129.30 kB transfer initial, this wave's own **470.57 kB** / 129.40 kB (**+0.16 kB raw / +0.10 kB transfer** — `SettingsStore`'s new eagerly-loaded `cropFollowEnabled` signal + its `restore()`/`persist()` branches; `SettingsStore` is `providedIn: 'root'`, so it always lands in the initial bundle no matter which route is active). The named `cockpit` lazy chunk is unchanged at this build's reporting precision (**174.11 kB → 174.11 kB raw**, 36.20 kB → 36.19 kB transfer) — this wave's real cost instead landed in one of esbuild's automatically-split *unnamed* shared chunks (used by more than one lazy route — `Player`/`FollowHud`/`crop-follow-logic` are common to the `cockpit`, `live`, and `wall` lazy chunks): **41.47 kB → 42.98 kB raw (+1.51 kB), 11.58 kB → 12.06 kB transfer (+0.48 kB)**, the same "cost lands in whichever chunk esbuild's boundary happens to draw, not necessarily the named one you'd guess" behavior W5's own entry above already documented for its own `cockpit` measurement.

## Status — CREW-CONTROL web wave W3: the crew seat, `/crew/:assetId` (docs/plans/active/CREW-CONTROL-PLAN.md §3.4/§3.6, wave W3) — 2026-09-05

Built the wave's full file-scoped slice: NEW `features/crew/**` (`crew.routes.ts`, `crew.ts`/`.html`/`.css`, `crew-facade.ts`, `crew-logic.ts` + `.spec.ts`) and NEW `core/seat/**` (`seat-logic.ts` + `.spec.ts`, `seat-store.ts` + `.spec.ts`), plus the four single-line registrations named by the plan's own §4 W3 row: `app.routes.ts` (`CREW_ROUTES` imported + spread beside `FLY_ROUTES`), `features/hubs/nav-entries.ts` (one ungated Operate-group entry), `core/ui/architecture.spec.ts` (`'crew/crew'` added to `ROUTED_PAGES`), and the seat DTOs + three `VisionApi` calls in `core/api/{models.ts,vision-api.ts}`. See the `features/` → `crew/` and `core/seat/` bullets above for the full design (stage/dock derivation, the dual-cadence `SeatStore`, the `.panel-inert`/`[streamId]`-conditional C2 read-only posture, and the `cameraHeldByOther` honesty fix). **Design**: full-bleed L0-L3 layered composition mirroring `cockpit.html`'s own grid/dock/rail idiom (one layer lighter — no banner row, no OSD shelf), `.surface-dark` in both themes, `--hud-*`/`--scrim` frosted-glass tokens for every on-glass control, one selection language (the follow-hud/target-list precedent, reused not reinvented) — no raw hex, no off-grid `px` (`--space-12` does not exist anywhere in `styles.css`'s actual token scale despite eight pre-existing files already referencing it; `crew.css` deliberately does not add a ninth, using `--space-8`/`--space-16` instead).

**Degrades honestly**: a failed/absent `SeatStore` read (404, `vision.crew.enabled=false`, or a genuine network failure) falls back to `seat-logic.ts#singleOperatorSeats` — both seats free, every `may*` true — indistinguishable from §3.8's own guardrail contract, so a lone operator's `/crew/:assetId` visit (today's only real user) behaves exactly as `C3`/full-control throughout, with no separate feature-flag branch anywhere in the frontend. A later poll failure keeps the last-known-good seat state rather than blanking it (the `GeoStore`/`ThresholdsStore` precedent). The dock never fabricates a holder name (`cameraHolderLabel` falls back to "another operator" if the wire ever violated its own non-null contract) and never shows both an action and a reason at once. **Role-gating**: none of this page's own gating is role-based — `SeatsResponse.may*` are the only authority signals ever read, and they are taken as given, never cross-checked against `MeResponse.topRole`/`AuthCapability` (§3.7 IC-2, the frozen rule this page must not violate). The CV control body renders visible-but-inert at `C2` (dimmed + `pointer-events:none` + a stated reason), never hidden — matching §3.4's own table ("CV controls render disabled with that reason," not "absent"). **Dev parity**: `vision.crew.enabled=false` (default) makes every seat read report both seats free server-side, which the frontend already treats identically to a fetch failure — no separate code path to keep in parity, so the dev-admin/unbounded posture is unaffected by construction, not by a special case.

**Deviations, disclosed:**
- **`CrewSeatPage`, not `CrewPage`** — `features/roster/crew.ts` already exports `CrewPage` for the pilot-assignment roster at `/manage/roster` (`app.routes.spec.ts` asserts this by name). Reusing that name for this page's own routed component would compile (different modules) but reads as a real collision; renamed to `CrewSeatPage` throughout `features/crew/**`.
- **Nav entry named "Crew seat", not "Crew"** — same collision, one layer up: `nav-entries.ts`'s Fleet group already has a `Crew` entry (`/manage/roster`, MODULE.md's own Routes line: "`/roster` (now **Crew**"). `nav-entries.spec.ts`'s own "no duplicate entry name within one group" guard is per-group and would not have caught this, so the rename is a judgment call, not a forced one — disclosed rather than silently made. Updated the two `nav-entries.spec.ts` regression tests that hardcode the Operate group's exact entry list/count to include it (10→11 pilot-visible entries, 19→20 manager-visible).
- **`/crew` (bare) redirects to `/wall`** — no crew-specific landing/picker page exists yet (§0.3's own "landing → the assets assigned to them" journey step is out of this wave's §4 W3 scope, which names only `crew/:assetId`); the nav entry needed *some* real, resolving, not-already-used destination (`nav-entries.spec.ts`'s F1 dedup guard rules out reusing `/wall`'s own `NavEntry`), so `crew.routes.ts` gained one extra `redirectTo` route, disclosed as an interim shape to revisit once a real crew landing page is built.
- **`GeoStore` not injected** — the original task brief's illustrative `providers:[TelemetryStore, DetectionsStore, GeoStore, …]` was not followed literally: nothing in this page's composition table reads visual-geo corrections (no divergence chip, no map inset), confirmed by reading `cockpit-facade.ts`/`cockpit.html`'s own separate use of it before omitting it here.
- **Telemetry corner overlay placement** — the plan's own L0-L3 layer vocabulary names no shelf for the read-only telemetry readout; placed as a `.crew-telemetry` corner overlay (gated on `hasTelemetryDevice()`), mirroring `live.html`'s identical "Telemetry" card gate rather than inventing new layout vocabulary.
- **`canRelease` on this page's `<vision-follow-hud>` is a real `stage() === 'C3'` gate** — resolving `LiveFacade.canRelease`'s own pre-existing doc comment (which explicitly named this plan as the source of a future tightening), but only on this page; `features/live/**` itself is untouched, out of scope.

### Tests / build

`npx tsc --noEmit -p tsconfig.app.json` / `-p tsconfig.spec.json` — both clean. `npm run test:ci` (via `ng test --watch=false`) — **190 files, 3781 tests green** (+3 files, +44 tests over W6's own recorded 187 files/3737 tests: `core/seat/seat-logic.spec.ts`, `core/seat/seat-store.spec.ts`, `features/crew/crew-logic.spec.ts` are the three new files; `features/hubs/nav-entries.spec.ts` was modified, not added, to account for the new nav entry).

`ng build --configuration production` — fails on the identical **pre-existing** initial-bundle ERROR-level budget gate every recent wave's own entry above has hit (440 kB threshold), confirmed unrelated to this wave by an isolated `git stash -u`/rebuild/`git stash pop` (scoped to `-- station/vision-web`, leaving the concurrently-running W2 backend agent's own uncommitted Java changes on `contexts/vision-flight`/`vision-api`/`vision-app` completely untouched throughout): baseline (pre-wave) **470.57 kB** raw / 129.36 kB transfer initial, this wave's own **471.20 kB** / 129.49 kB (**+0.63 kB raw / +0.13 kB transfer** — the three new `VisionApi` methods; `VisionApi` is `providedIn:'root'`, so it always lands in the initial bundle regardless of route). This wave's own route is a properly code-split lazy chunk, confirmed **not** part of the regression: new named `crew` lazy chunk, **43.88 kB raw / 9.23 kB transfer**, unreachable until `/crew/:assetId` is actually navigated to.

**Left incomplete:** nothing against this wave's own file scope — every §3.4 composition-table row, every §3.2/§3.3 frontend-observable rule, and the full `SeatStore` renew-while-mine cadence are built and tested. The pre-existing production-budget failure (documented above, inherited from before this wave and unrelated to it) is left unfixed — reducing an already-over initial bundle is unrelated work outside `features/crew/**`/`core/seat/**` and the four named single-line registrations, and touching `angular.json`'s budget thresholds was never part of this wave's mandate.

**Left incomplete:** the host-wiring gap named in Deviations above — no page binds the new toggle inputs/outputs yet, so an operator cannot reach F2 today even though every layer beneath the HUD (the settings signal, the pure crop math, the render-loop integration, D10's hit-test correctness) is built and tested. Nothing else against this wave's own file scope is outstanding.

## Status — CREW-CONTROL web wave W4: cockpit crew awareness + the D1 watch-mode fix (docs/plans/active/CREW-CONTROL-PLAN.md §2.3 D1/§3.5/§3.6, wave W4) — 2026-09-05

Built the wave's file-scoped slice entirely inside `features/fly/**` (no `core/seat/**` change — `SeatStore` from W3 is reused unmodified). Three deliverables:

**(a) The dock's crew line** — `cockpit-facade.ts` injects `SeatStore` (`readonly seats = inject(SeatStore)`) and tracks it off `activeAssetId()` alone in a new constructor `effect()` sitting beside the existing geo/grounding tracking effects (`track(assetId)` / `reset()`). `readonly crewCameraLine = computed(() => crewCameraDockLine(this.seats.camera()))` renders `cockpit.html`'s one new dock line — `Crew · <name> on camera` — as a sibling inside the existing `.dock` video-health `@if/@else if` chain (after the detection-off-chip branch, before the idle/starting card), reusing the pre-existing `.stream-state-chip` pill CSS verbatim. `null` (zero pixels) whenever the camera seat is free or held by this pilot themself.

**(b) The Vision drawer's camera-held state** — `<vision-cv-control-panel>` in `cockpit.html` is now wrapped in `<div [class.panel-inert]="facade.cameraSeatHeldByOther()">` (new `.panel-inert`/`.panel-inert-reason`/`.camera-seat-notice` rules in `cockpit.css`, mirroring `features/crew/crew.css`'s own C2 idiom byte-for-byte). While held-by-other, a `.camera-seat-notice` block above it states the reason (`Camera held by {{ facade.cameraSeatHolderLabel() }} — controls are read-only.`) and renders the wave's one new CTA, `Take camera`, wired to a new `CockpitFacade.takeCameraSeat()` (`POST .../seats/CAMERA` via the existing `VisionApi.takeAssetSeat`, wrapped in `try/finally` so `seats.refreshNow()` always runs regardless of outcome — a 409 self-corrects by re-reading rather than surfacing an error). `cameraSeatPending` signal disables the button and swaps its label to "Taking…" while in flight, the same one-shot-button idiom every other command button on this page already uses (`busy`/`sessionBusy`/`detectionPending`). Per §3.2 rule 3 this button is legibility, not a requirement — the pilot's own camera writes already auto-take server-side.

**(c) D1 fix** — `cockpit.html:481`'s `<vision-fly-hud [canCommand]>` used to read `facade.canShowCommands()` alone (capability + firmware + telemetry-freshness, never watch mode), so a `?watch=1` viewer on an otherwise-commandable vehicle got a fully-functional Take-control pill and Arm/Disarm zone. Fixed by extracting the gate into a named pure predicate, `fly-logic.ts#commandSurfaceVisible(canShowCommands, watchMode) = canShowCommands && !watchMode`, exposed as `CockpitFacade.flyHudCanCommand` and bound as `[canCommand]="facade.flyHudCanCommand()"` — the diff is exactly these two lines (the input binding, plus the new computed feeding it). Because `<vision-flight-command-panel>` and `<vision-mode-picker>` already fully hide their own DOM on `@if (canCommand())`/`@if (canCommand() && capabilities())`, ANDing watch mode into this one shared input closes both the Arm/Disarm zone (the plan's own named defect) and the Controller drawer's mode picker (a bonus fix, same evidence) without touching `<vision-fly-hud>`'s informational content — the at-rest badge (now honestly reads "Not commandable" while watching), the engaged widget's own telemetry text, and `<vision-rc-monitor>`'s state-strip/transmitter-picture/diagnostics are not gated on `canCommand` at all and are unchanged, matching §0.2's "loses command affordances, not information." Every one of the plan's 9 named pre-existing `watchMode()` consumer sites in `cockpit.html` (dock detection-off Turn-on button, follow-hud `canRelease`, and 7 others) is confirmed unchanged — this wave touches exactly one binding.

**Design**: no new visual language — the crew line reuses `.stream-state-chip`, the camera-held notice reuses `crew.css`'s `.panel-inert` C2 recipe (dimmed + `pointer-events:none` + a stated reason), and the Take-camera button is a plain `.btn.small`, all `--hud-*`/existing tokens, sentence case, zero new chips clusters, zero toasts, zero new stages/CTAs beyond the one button the plan's own §3.5 table names.

**Degrades honestly**: a failed/absent seat read degrades through `SeatStore`'s own pre-existing `singleOperatorSeats` fallback (both seats free) — unchanged by this wave, so a lone operator sees no crew line and a fully-live drawer, identical to before W3 existed. `takeCameraSeat()` never fabricates success — a 409 or any other failure is swallowed into a console warning and answered with a fresh `seats.refreshNow()` read, so the UI reflects the server's actual seat state rather than an optimistic guess. **Role-gating**: none of this wave's own gates are role-based — `cameraSeatHeldByOther`/`Take camera` read only `SeatsResponse` fields, never `MeResponse.topRole`, matching §3.7 IC-2 exactly as W3's crew page already does; the D1 fix gates on `watchMode()` (a URL query flag, not a role) exactly as the plan specifies. **Dev parity**: unaffected — `vision.crew.enabled=false` (default) makes every seat read report both seats free server-side, which this wave's own `cameraSeatHeldByOther`/`crewCameraLine` already treat identically to "no crew" (both computeds are `false`/`null`), so the dev-admin/unbounded posture is untouched by construction.

**Deviations, disclosed:**
- **`cameraHeldByOther`/`cameraHolderLabel` duplicated, not imported** — both already exist in `features/crew/crew-logic.ts` (W3). This wave's exclusive file scope (`features/fly/**` only) made a new fly→crew dependency inappropriate even though the reverse direction already exists (`crew-facade.ts` imports `cv-control-panel-logic.ts` from `../fly/`); re-implemented verbatim in `fly-logic.ts` with a doc comment explaining the duplication.
- **The D1 gate was extracted into a named pure function** (`commandSurfaceVisible`) rather than left as an inline template `&&` — done specifically so the "watch mode does not mount the command surface" requirement has a direct pure-logic unit test. This was a deliberate call: no `*-facade.spec.ts` file exists anywhere in this codebase (confirmed by `find`), so there is no established pattern for TestBed-testing `CockpitFacade` directly, and its ~20-dependency constructor graph (`FleetStore`/`SettingsStore`/`PollScheduler`/`TelemetryStore`/`DetectionsStore`/`SeatStore`/`EventsStore`/`GeofenceStore`/`GeoStore`/`GroundingStore`/`MarksStore`/`LayersStore`/`DrawingsStore`/`WeatherStore`/`LiveStore`/`AuthStore`/`Router`/`ActivatedRoute`/`VisionApi`) would make a first-of-its-kind harness disproportionate to this one gate.
- **`takeCameraSeat()` itself has no dedicated unit test** — same reasoning: no facade-spec precedent exists to extend, and the method's one behavioral property worth asserting (seat state is always re-read after the call, success or failure) mirrors `SeatStore`'s own already-tested "a write's own outcome is not authoritative, re-read is" contract. The observable "re-enable" condition — `cameraSeatHeldByOther` flipping `false` once a fresh read reports `mine:true` — is covered directly by the pure `cameraHeldByOther`/`crewCameraDockLine` tests in `fly-logic.spec.ts`.

### Tests / build

`npx tsc --noEmit` (`-p tsconfig.app.json` / `-p tsconfig.spec.json` both invoked via the plain `--noEmit` call) — clean. `npm run test:ci` (via `ng test --watch=false`) — **190 files, 3793 tests green** (+0 files, +12 tests over W3's own recorded 190 files/3781 tests: `fly-logic.spec.ts` gained four new `describe` blocks — `cameraHeldByOther` (3 cases), `cameraHolderLabel` (2 cases), `crewCameraDockLine` (4 cases, matching §3.5's own worked example string), `commandSurfaceVisible` (3 cases, covering all four truth-table corners) — no new spec file, the module's own existing fly-logic spec absorbed all of it).

`ng build --configuration production` — fails on the identical **pre-existing** initial-bundle ERROR-level budget gate every recent wave's own entry above has hit (440 kB threshold), confirmed unrelated to this wave by an isolated `git stash -u`/rebuild/`git stash pop` (scoped to `-- station/vision-web/src/app/features/fly`): baseline (pre-wave, i.e. W3's own post-wave state) **471.20 kB** raw / 129.49 kB transfer initial, this wave's own **471.42 kB** / 129.58 kB (**+0.22 kB raw / +0.09 kB transfer** — the new `flyHudCanCommand`/camera-seat computeds and `SeatStore` injection touch `CockpitFacade`, which is page-provided, not root-provided, so this small delta is chunk-splitting noise rather than a new eager dependency; `SeatStore` itself was already reachable from the initial bundle's shared-chunk graph via W3's `crew` route). The named `cockpit` lazy chunk moved from **136.62 kB → 138.44 kB raw (+1.82 kB), 28.88 kB → 29.20 kB transfer (+0.32 kB)** — the expected cost of this wave's own new template/facade code.

**Left incomplete:** nothing against this wave's own file scope — all three of (a)/(b)/(c) are built exactly to §3.5's one-CTA table (one text line, one drawer button, zero new stages, zero new CTAs, no toast), and the D1 fix is verified against every one of the plan's own named consumer sites. The pre-existing production-budget failure (documented above, inherited from before this wave) is left unfixed — out of `features/fly/**`'s mandate, and touching `angular.json`'s budget thresholds was never part of this wave's scope.

### 2026-09-05, pre-merge fix: `@angular/forms` out of the initial bundle

**Why this exists:** the four-feature stack (`feat/auth-roles` → `source-onboarding-2` → `track-follow` →
`crew-control`) was green on every Java module and on `test:ci`, but `ng build --configuration production`
failed the 440 kB initial-bundle ERROR gate at **471.44 kB**. Measured against `master` in a disposable
worktree, `master` was **421.60 kB, exit 0** — so the stack, not history, broke it, and merging would have
left `master` unbuildable. See the corrected Gotchas entry above for how five wave entries came to call it
"pre-existing".

**Cause:** `shared/ui/reauth-overlay.ts` and `shared/ui/force-password-change.ts` (AUTH-ROLES) were the only
components in the eagerly-loaded shell tree importing `FormsModule`, and `app.ts` renders both
unconditionally in `app.html`. That pulled the whole **36.30 kB** `@angular/forms` chunk into the *initial*
bundle to serve two dialogs almost nobody sees.

**Fix:** both were already binding **one-way** into signals (`[ngModel]` + `(ngModelChange)="x.set($event)"`),
so forms bought them nothing — swapped to native `[value]` + `(input)="x.set($any($event.target).value)"`,
`(ngSubmit)="submit()"` → `(submit)="$event.preventDefault(); submit()"`, and dropped the import. `required`
stays for a11y only: `canSubmit()` already requires non-empty and `submit()` re-checks, so the native
validation path FormsModule used to suppress (it auto-adds `novalidate`) is unreachable — no behavior change.

**Rejected alternative:** `@defer (when …; prefetch on idle)` on the two hosts. It reached the same size
(434.94 kB) but makes `App` require `TestBed.compileComponents()`, failing **20 `app.spec.ts` tests** and
forcing 26 test callbacks to async — and it would fetch a chunk at the exact moment a session expires.
Dropping `FormsModule` gets the identical saving with no test churn and no runtime risk.

**Verify chain, all green:** full `./mvnw -B verify` — **BUILD SUCCESS**, all 36 modules (`vision-app` 334
tests; earlier attempts never reached it). `npm run test:ci` — **190/190 files, 3793/3793 tests**, unchanged
count (neither dialog has its own spec). `ng build --configuration production` — **exit 0**, initial
**471.44 kB → 434.99 kB (−36.45 kB)**, now 5.01 kB under the 440 kB gate, leaving only the two pre-existing
warnings (390 kB initial, `tactical-map.css`). Still +13.39 kB over `master` — the four features' legitimate
eager growth, within budget.

**Gotcha worth keeping:** `npm-build` binds to `process-classes`, which runs **before** the `test` phase, so a
bundle-budget failure means `npm run test:ci` **never runs** in a reactor build. A red `vision-web` therefore
proves nothing about the web tests — run them directly before concluding anything.

---

## Status — ALWAYS-ON-FLOW wave C: the UI stops being a live pipe (docs/plans/active/ALWAYS-ON-FLOW-PLAN.md §4 waves C1/C2/C3) — 2026-09-06

The owner's third statement, verbatim: *"the mediamtx is capable of getting many streams, but the UI -
is not."* Before this wave the UI was the thing that decided how much the whole platform ran: `/wall`
mounted one `<vision-player>` per running stream with no cap, and five `providedIn:'root'` map stores
started a 30 s poll in their constructors and never stopped it. This wave makes video an explicit
gesture and makes poll lifetime track actual demand.

### C1 + C2 — `/wall` video is opt-in per tile, capped wall-wide

`wall-logic.ts` gains the pure half: `MAX_CONCURRENT_WALL_PLAYERS` (6) plus `requestWallVideo` /
`releaseWallVideo`, both plain array transforms with no Angular in sight. `WallFacade` owns the raised
set (`isVideoUp` / `toggleVideo`, backed by a `Set` computed so a `@for` over every tile stays O(1)),
and `wall-tile.ts` gains a required `videoUp` input plus a `videoToggled` output; `false` renders a
state-only placeholder and mounts **no player at all** — `@if`, not `[suspended]`, because a suspended
player still runs its RAF loop and `ResizeObserver`.

Two decisions worth keeping, both argued in `requestWallVideo`'s own doc comment:

- **Full cap evicts LRU rather than refusing.** A wall exists so an operator can act on whatever just
  became relevant; refusing a fresh explicit click in favour of a tile that has sat live and unattended
  the longest would silently block the gesture the wall promises to honour.
- **`severity === 'critical'` deliberately does NOT auto-raise video.** §1's own plane table says the
  View plane's governor is genuine viewer demand, full stop — severity is a State-plane fact, and the
  wall already escalates it without pixels (border colour, health line, reasons, pulse chip). Auto-raising
  would spend decode cost on a screen nobody may be watching and let an alarm burst evict tiles the
  operator explicitly chose.

The load-shedding that matters most is not the pixels: `videoUp` also gates `DetectionsStore.track` and
`followTracks`, and every detections feed a tile opens **is CV demand on the backend**. A tile with no
player must not keep one warm either. A raised tile whose stream leaves `tiles()` is pruned by an
effect, mirroring the existing focus-pruning effect — otherwise it would occupy a capped slot forever.

### C3 — five root stores stop polling for the whole session

`MarksStore`, `LayersStore`, `DrawingsStore`, `TracksStore` and `GeofenceStore` each gain ref-counted
`activate()` / `release()`, modelled on `EventsStore#activate`'s existing shape. **The initial `GET`
moved under `activate()` too, not just the poll** — a constructor `void refresh()` still fires once per
first-ever construction whether or not anything is mounted to show it, and would hand a consumer that
activates much later an arbitrarily stale list instead of a fresh one. Folding live `map`-topic deltas
stays unconditional: it is an in-memory fold with no network cost, and keeping the cursor advancing
means a reactivating consumer does not replay deltas its own fresh `GET` already supersedes.

Every direct injector now activates in its constructor and releases from `DestroyRef.onDestroy` —
the four routed facades (`command`, `live`, `fly/cockpit`, `asset-detail`) *and* the non-routed
presentational children under `shared/map/map-controls/**` (`MarksPanel`, `MarkPalette`,
`DrawingToolbar`, `LayerManager`, `VerifyControls`) plus `command/zones-panel.ts`.

### Left undone, named honestly

- **C2's `/fly` half is not built.** `cockpit.html` still mounts one player per secondary device
  thumbnail, uncapped. Unlike the wall this is bounded by one aircraft's own camera count, and the
  thumbnails exist precisely so an operator can *see* which camera to switch to — making them static
  would remove their reason to exist. That is a UX decision, not a mechanical cap, and it was left for
  the owner rather than taken unilaterally.
- **No bundle-size measurement.** The production-build delta was never captured (the baseline attempt
  needed a tree-wide `git stash`, correctly refused while other waves were uncommitted). The 440 kB
  budget gate is unchanged and still enforced by `ng build`.
- **`MAX_CONCURRENT_WALL_PLAYERS = 6` is reasoned, not measured.** No multi-stream decode load test
  backs the number; it is sized to cover the `Comfortable` density stop. Say so rather than implying
  it is tuned.

### Gotcha this wave introduces

**A store that reads another store's data does not activate it.** `MarksStore` and `DrawingsStore`
both inject `LayersStore` (for `contributable()` / `defaultLayerId()`, i.e. which layer a *new* mark or
drawing targets) but deliberately do not `activate()` it — that stays the consumer's job. Every current
call site activates both, so nothing is broken; a future consumer that activates only `MarksStore` would
get marks that render fine but a contribution target resolved against an empty layer list. Activate both.

### Tests / build

`npm run test:ci` — **192/192 files, 3825/3825 tests green** (up from 190/3793: new `layers-store.spec.ts`
and `tracks-store.spec.ts`, plus ref-count and video-toggle cases added to the existing store and wall
specs). `npx tsc --noEmit` clean on both `tsconfig.app.json` and `tsconfig.spec.json`.

## Status — LIVE-POLL-RETIREMENT waves L1+L2: the safety-net polls stop being unconditional too (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §3 D1, §5 waves L1a/L1b/L1c/L2a/L2b/L2c) — 2026-09-06

ALWAYS-ON-FLOW wave C3 (directly above) made each map-data poll track **demand** — nothing fetches or
polls with zero active consumers. It left a second axis alone: with at least one consumer mounted, the
30s safety-net poll ran unconditionally even while the `map`/`discovery` SSE topics were already
delivering every delta for free over an open live connection. This pair of waves makes the poll also
track **transport** — D1's frozen rule, verbatim: *"poll runs ⟺ `activeConsumers > 0` AND live is not
open."*

### The `applyTransport` idiom, copied five times on purpose

`MarksStore`, `LayersStore`, `DrawingsStore`, `TracksStore` (L1a/L1b/L1c) and `DiscoveryInboxStore`
(L2a) each gained a private `applyTransport(liveAvailable: boolean)` method, called both by a new
reconnect-driven `effect(() => this.applyTransport(isLiveAvailable(this.live.connectionState())))` in
the constructor and by `activate()` itself (which used to schedule the poll directly). Full state table
(identical in all five, matching `core/events/events-store.ts#applyTransport`'s pre-existing shape,
extended with the `liveGated` field below):

| `activeConsumers` | `liveAvailable` | previous (`liveGated`) | Action |
|---|---|---|---|
| `0` | any | any | stop poll; no refresh |
| `>0` | `true` | `false` (was polling) | stop poll; refresh once (the reconcile) |
| `>0` | `true` | `true` (already live) | nothing |
| `>0` | `false` | `true` (was live) | refresh once, then start poll |
| `>0` | `false` | `false` (already polling) | nothing |

Each store carries its own private `liveGated: boolean` field (starts `false`) rather than a new signal
on `LiveStore` — it exists purely to suppress the boot-time double-fetch (constructing while already
`'open'` must not both `activate()`-refresh *and* effect-reconcile). **Deliberately not extracted into
a shared helper** — the plan's own call: copying this ~15-line idiom five times keeps each wave's file
scope disjoint (no store waits on a shared `core/live/*` file another wave might be mid-editing).
`core/live/live-store.ts` itself is untouched by this pair of waves.

### L1c — the one fold `applyTransport` alone can't fix: grant revocation

A layer arriving over the `map` SSE topic never carries `grants` (`LayerResponse.forEvent` strips them,
§4.3) — `layers-logic.ts#applyLayerEvents` compensates by preserving the previously-known grants list on
a fold, correct for every change except a **revocation**, which a "grants shrank" delta cannot express
at all. Before this wave the unconditional 30s poll was the only thing that ever repaired that gap;
retiring it outright would have left a revoked grant invisible for as long as live stayed open.
`LayersStore` now schedules a short, debounced reconcile off `layer`-entity live deltas themselves
(`scheduleGrantsReconcile`, `GRANTS_RECONCILE_DEBOUNCE_MS = 1_000`, raw `setTimeout`/`clearTimeout` —
not `PollScheduler`, mirroring `toast.service.ts`'s own precedent for a one-shot debounce), independent
of `activeConsumers`: a burst of edits coalesces into one `GET`, a solitary revocation still converges
in about a second, and this runs even on a page that never itself calls `LayersStore.activate()` (other
stores read its access decisions unconditionally).

### L2b — `DiscoveryInboxStore.sources` has no SSE channel at all

The `discovery` SSE topic (`DiscoveryEventPayload`) carries only candidate deltas — no `sources` field
exists on it. Once live is up and the 30s poll has stopped, `sources` can *only* ever change on the one
reconcile `GET` a genuine reconnect (`poll → live` transition) triggers; that reconcile is not a nicety
here the way it is for the other four stores, it is the *only* remaining path. `discovery-inbox-store.ts`
gained this D1 gate with the same `applyTransport`/`liveGated` shape as the map stores, deliberately
keeping its own pre-existing `=== 1`/`stopPollingFn` naming (vs. the map stores' `> 1`/`stopPollFn`) —
left as found, not churned. `discovery-inbox-store.spec.ts` is a **new file** (this store had no spec at
all before this wave) covering construction, `activate()`/`release()` ref-counting, the `discovery`
topic's candidate-only fold, the D1 reconnect acceptance test, and — explicitly — a test that drives a
poll→live reconcile then floods the store with live candidate deltas while asserting `sources` never
moves again except on that one reconcile call.

### The frozen acceptance test, on all five stores

Per store: with the store active and live already `'open'`, drive the stubbed `connectionState` through
`open → closed → open` and assert **exactly one** REST refresh on (re-)entering `'open'`, and **zero**
REST requests for as long as `'open'` persists (a second concurrent `activate()` while live holds proves
the latter). Each spec's `stubLiveStore()` now also returns a writable `connectionState` signal, seeded
`'closed'` in every case — reproducing pre-D1 behaviour exactly, so every pre-existing assertion in the
four L1 spec files kept passing untouched. One easy mistake worth naming: the `closed` leg of that
sequence *also* legitimately fires its own refresh (the table's own `>0 | false | was-live → refresh
once, then start poll` row) — a test that clears the mock before driving `closed` and asserts exactly
one call across the whole `closed → open` round-trip will flake with "expected 1, got 2". The fix is to
clear the mock *after* the `closed` leg's own refresh has fired, isolating only the `entering 'open'`
transition.

### Docs updated alongside the code

Every touched store's own class doc and its `*_POLL_INTERVAL_MS` constant's doc comment were rewritten
— the old "safety-net poll, runs unconditionally once a consumer is active" framing was no longer true
and would have misled the next reader. `MARKS_POLL_INTERVAL_MS`, `LAYERS_POLL_INTERVAL_MS`,
`DRAWINGS_POLL_INTERVAL_MS`, `TRACKS_POLL_INTERVAL_MS` and `DiscoveryInboxStore`'s `POLL_INTERVAL_MS`
all now state the D1 gate explicitly instead of describing an unconditional cadence.

### Tests / build

`npm run test:ci` — **193/193 files, 3845/3845 tests green** (up from 192/3825: this pair of waves adds
20 new cases across the four `core/map-data/**` spec files — 2 live-gate cases each on `MarksStore`/
`DrawingsStore`/`TracksStore`, 2 live-gate + 3 grants-revocation cases on `LayersStore` — plus a brand
new `discovery-inbox-store.spec.ts`, 9 cases, the first spec this store has ever had). `npx tsc --noEmit`
clean on both `tsconfig.app.json` and `tsconfig.spec.json`. `ng build --configuration production` green,
same two pre-existing budget warnings only (the 390 kB initial-bundle warning and `tactical-map.css`'s
own, both long-standing and unrelated to this pair of waves — see the "Initial-bundle budget" note
under the main `## Status` section above before treating either as this wave's own). Bundle delta
measured via `git stash push -u` on this wave's own 10 files (9 tracked + the new, then-untracked
`discovery-inbox-store.spec.ts`; package.json/lock untouched, so a re-run of `npm ci` was not needed to
reproduce the baseline): initial bundle **435.22 kB → 435.22 kB raw (flat,
0.00 kB)**, transfer 122.03 kB → 122.02 kB (a 0.01 kB rounding difference, not a real change) — expected,
since this pair of waves adds a handful of private methods/fields to five already-shipped stores and no
new imports.

### Left undone, named honestly

- **No shared `applyTransport` helper** — by design, see above; a future wave (L3+) touching a sixth
  store repeats the idiom a sixth time rather than reaching for an extraction that would re-couple these
  waves' file scopes.
- **D2 (the `zones` SSE topic) and D3 (the system sampler)** are out of this pair of waves' scope — see
  the plan's own §3 for their own rules, untouched here.
- **`core/live/live-store.ts` is untouched**, per the plan's own instruction — `applyTransport`'s
  `isLiveAvailable(this.live.connectionState())` read is the only new coupling to it, and that read
  already existed in `EventsStore`'s pre-existing implementation this pair of waves mirrors.

## Status — INVENTORY-REWORK web wave W3: authority-aware verbs, details on selection, names and cause on rows (docs/plans/active/INVENTORY-REWORK-PLAN.md §5.2/§6, wave W3) — 2026-09-06

The Inventory page stops guessing. Before this wave it rendered the same kebab to everyone and found
out what the session could actually do by watching the server say 403; it opened `GET /api/assets/{id}`
once per row to fill two columns; it printed raw 36-character UUIDs where a custodian's name belongs;
and it told an operator a vehicle was "Not ready" without saying why. Five defects from
`INVENTORY-REWORK-CONTEXT.md` §3 (A, C, D, E, F) and the §6 row-4 wizard double-call (B).

### A — one authority-aware verb matrix

`vehicleRowActions(row)` moved out of `features/inventory/vehicles-logic.ts` (where it was a
capability-blind boolean set) into `core/fleet/inventory-logic.ts` as
`vehicleRowActions(row: InventoryActionRow, actor: InventoryActor)`. The actor is read once, in
`InventoryFacade#actor`, from `AuthStore`:

```
InventoryActor { canManageFleet, canManageOrg, canCommandFlight, userId? }
```

`userId` is unused by the two columns shipped here — it is present so wave W2's web half can add §5.2
columns 2/3 (a pilot's *Report issue*, a custodian's *Return*) by extending this record and the switch,
without touching a single call site; the interface carries a one-line TSDoc pointer saying exactly that.

The rule, uniform across the row kebab and the detail pane: **a verb this session's capabilities would
have the server refuse is not rendered at all**; **a verb that exists but is momentarily impossible is
rendered disabled with its reason** (`.kebab-item` + `.kebab-reason`, `.disabled-reason` in the pane).
The matrix as implemented — every cell has its own `it` in `core/fleet/inventory-logic.spec.ts`:

| Effective state | `canManageFleet` (col. 1) | `canCommandFlight` (col. 2's Fly) | anyone in scope (col. 4) |
|---|---|---|---|
| In stock | Issue to… · Ground… · Retire… · Archive | Fly | Open · Watch live¹ |
| Issued | Return to stock · Ground… · **Retire disabled** — "Return it to stock first" | Fly | Open · Watch live¹ |
| In field | Ground… only (a usage is open: neither Return nor Retire) | Fly | Open · Watch live¹ |
| Maintenance | Release · Retire… · Archive | **Fly disabled** — "Grounded for maintenance — release it first" | Open |
| Retired | Archive | — | Open |
| Archived / Deactivated | Restore | — | Open |
| `inventoryState` never fetched | — | — | Open |

¹ only while `AssetSummary.status === 'STREAMING'`.

Two deliberate divergences from the printed §5.2 table, both spec-asserted: **DEACTIVATED has no row of
its own** in the plan and is treated exactly like ARCHIVED (Restore only, since the lifecycle write is
the same one); **an unfetched `inventoryState`** (the honest `unknown` chip) offers no mutating verb at
all rather than defaulting to the In-stock row. Effective state is derived by calling
`effectiveInventoryStateChip` — the chip and the verbs read the same function, so they cannot disagree.

Gates outside the matrix, each named against the endpoint it mirrors: the pane's maintenance **Open
record**/**Close** and its new-record form need `MANAGE_FLEET`; the page-bar **+ Add vehicle** needs
`MANAGE_ORG` (`AssetController#register`, and `/add-source` carries `orgGuard` — a pilot who clicked it
used to be bounced by that guard one navigation later); **Found devices** activates
`DiscoveryInboxStore` only for `MANAGE_ORG` and only after `/api/auth/me` has resolved (an `effect` with
a once-only `activated` flag, released symmetrically) — `GET /api/discovery/inbox` 403s everyone else,
so a pilot or viewer used to take that error path every 30 s for the life of the page.

### B — the N+1 is gone

`InventoryFacade#loadAll` is **five requests flat, whatever the fleet size** (it was `5 + N`):

```
Promise.all([ listAssets() | listAssetsIncludingArchived(),  listCategories(),
              listUsers()*,  fleetReadiness(),  fleetSummary() ])          (* see C)
```

Rows are built from `AssetSummary` alone (`buildVehicleRows` now takes summaries + readiness +
`detailsByAssetId` + users). `AssetDetails` is fetched **on row selection** by
`InventoryFacade#ensureDetails(assetId)` — cached per id in `detailsByAssetId`, returning `undefined`
on failure without blocking the pane, and cleared on every `loadAll` (the selected row is re-fetched
immediately). **This is the path W4's drawer should call**: `facade.selectedDetails()` for the data,
`facade.loadingDetails()` for the spinner; never `api.getAsset` from a component.

The Links column is `summary.deviceCount ?? details?.devices.length ?? '—'` (`linksLabel`) — a
pre-W1 backend that does not send `deviceCount` renders `—` for an unselected row, never a fabricated
`0`, which would read as the assertion "this vehicle has no devices linked". `patchAsset` now **merges**
`{...asset, ...updated}` into both the list and the details cache: custody/inventory responses omit
`firmware`/`totalFlightSeconds`/`deviceCount`, and a wholesale replace blanked those columns after an
Issue. `Watch live` no longer needs a device id on the row — `InventoryFacade#watchLive` resolves
details through the same cache on click, then `findVideoDevice`, and toasts honestly when there is none.

### C, D, E — the row says something

- **Names come off the wire.** `AssetCustody` gained optional `custodianName`, `AssignedPilot` optional
  `username`/`displayName` (additive, plan §6). `custodianLabel` resolves
  `custody.custodianName ?? nameById.get(id) ?? shortIdLabel(id)` — the last rung is a truncated
  `3f2a91c4…` carrying the full id in `title`, never a bare UUID in the cell. `listUsers()` stays as the
  middle rung only, and is **not called at all** when `auth.scopeKind() === 'ASSIGNED_ASSETS'` (the
  server answers `[]` for that scope anyway — one less request and one less 403-shaped empty).
- **Readiness says why.** The cell is dot + verdict + first blocker as muted truncated text, reusing
  `core/readiness/readiness-logic.ts#fleetRowAttention(row, 1)` rather than duplicating a second
  phrasing of the same sentence. Still one chip per row (the state chip) per frontend-style §5.
- **Location** (`custody.location`) is a new column after Custodian, `—` when unset.

### Left undone, named honestly

- **The "~5 requests, not 25" check was made statically, not against a running app.** The backend on
  :8080 has auth enabled and answers `/api/auth/me` with 401; no credentials were available to this
  agent. What was verified: `loadAll` awaits exactly five API calls, and the only `api.getAsset` in the
  facade is inside `ensureDetails`. **Both-theme screenshots are pending W6** for the same reason.
- **`streaming` reads `AssetSummary.status === 'STREAMING'`**, not a join against `FleetSummary.assets`
  as the wave brief phrased it. The summary already carries the field; joining a second source for the
  same fact would be the thing this wave is deleting elsewhere.
- **§5.2 columns 2/3's own verbs are not shipped** (a pilot's *Report issue*, a custodian's *Return* —
  defects D4/D5): they need W2's backend half. There is an explicit regression spec asserting a pilot
  holding a vehicle gets neither, so the gap is a tested fact rather than an oversight.
- **The wizard's dropped `assignPilot` (defect B) depends on W1 being deployed.** Until
  `AssetHandoverService` composes custody + PILOT assignment server-side, a wizard hand-over on an old
  backend sets custody without the assignment — which is exactly what the Inventory kebab's Issue has
  always done, so the two paths are consistent either way.

### Tests / build

`npm run test:ci` — **192/192 files, 3860/3860 tests green** (up from 3825: the verb matrix cell by cell
in `core/fleet/inventory-logic.spec.ts`, plus `linksLabel`/`custodianLabel`/`buildVehicleRows` cases in
`features/inventory/vehicles-logic.spec.ts`). `npx tsc --noEmit` clean on both `tsconfig.app.json` and
`tsconfig.spec.json`. `ng build --configuration production` green with only the two pre-existing budget
warnings; initial bundle unchanged at 435.22 kB raw (transfer 122.03 → 122.01 kB), the lazy `inventory`
chunk 102.99 → 109.11 kB raw (+6.12), 20.37 → 21.65 kB transfer (+1.28).

## Status — INVENTORY-REWORK web wave W4: the stats become the view, the drawer becomes a triage sheet, Issue becomes pilot-first (docs/plans/active/INVENTORY-REWORK-PLAN.md §2 D7/§5.1/§5.3/§5.5, wave W4) — 2026-09-07

W3 made the Inventory page tell the truth about what a session may do. W4 makes it worth standing in
front of. Before this wave the widest band on the page was five numbers nobody could act on; the page
bar carried five side-by-side selects that wrapped onto three lines and pushed the table below the
fold; the detail pane printed a raw ISO timestamp for "since", a full maintenance history, and a
second form for opening a record that duplicated Ground; and issuing a vehicle meant scrolling a flat
36-row directory of every enabled user to find the one pilot who can fly it.

### D7 — the KPI strip is now the view switcher

`inventory-page-logic.ts` was rewritten: `inventoryKpis`/`InventoryKpis` are gone, and with them the
fifth request (`GET /api/fleet/summary`) `loadAll` used to make — the page is now **four flat requests**.
In their place, five toggleable views:

| View | Predicate (`rowMatchesInventoryView`) | Tone (non-zero) |
|---|---|---|
| Needs attention | `stateChip.kind === 'maintenance'` **or** `readinessVerdict === 'NO_GO'` **or** `readinessCause !== undefined` | `danger` |
| In field | `stateChip.kind === 'in-field'` | `ok` + live dot |
| Issued | `stateChip.kind === 'issued'` | — |
| In stock | `stateChip.kind === 'in-stock'` | — |
| Maintenance | `stateChip.kind === 'maintenance'` | `warn` |

Four of the five read the row's own effective-state chip, so a row's chip and the view it lands under
can never disagree. **Needs attention** is §3.1's union — *grounded · open maintenance · stale/never
probed* — expressed as the two facts a row actually carries; a row whose readiness was never fetched
carries neither and is deliberately not counted, because absence of evidence is not a blocker.
An archived or retired row matches no view at all (spec-asserted for all five).

**Counts are of the rows a click would show, not of the fleet.** `InventoryFacade#viewTiles` counts
the tab's rows *after* search/Category/More-filters/retired/archived and *before* the view filter, so
a tile reading `3` always yields exactly three rows and can never disagree with the table under it.
Selection is the codebase's own convention — a 2px inset `--color-info` rule plus a `--color-info-soft`
tint, reaching the shared `vision-stat` through `::ng-deep` (the tactic `tactical-map.css` and
`flight-plan-dialog.css` already use) rather than forking the primitive into a selectable variant.
Clicking the selected tile deselects to All (`toggleInventoryView`).

**Where the view lives.** `features/inventory/inventory-view-store.ts` — `InventoryViewStore`, one key
(`vision.inventory.view`), `read()`/`write()` both wrapped in `try`/`catch`. It does **not** go through
`core/panel-state.ts#readPersistedString`: that helper touches `localStorage` bare, which is safe for a
value re-derived every boot but not for one read during this page's own construction — a Safari private
window or a browser configured to block site data would take the page down with it. A blocked browser
simply gets no memory. The stored value is three-valued (`InventoryViewSelection`): a view, `'all'` for
an explicit All (so deselecting survives a reload instead of being undone by the default), and absent
for never-chosen. The default — Needs attention while anything is in it, else All — is latched **once**,
in `#latchDefaultView` on the first fleet read, deliberately not as a live `computed`: a vehicle going
NO_GO must never move the table under whoever is reading it.

**Export** cannot be filtered honestly. `InventoryExportController` takes exactly one parameter,
`format`, and exports the caller's whole scope; there is no filter to pass. So the file stays whole and
the button says so: **`Export all (CSV)`**.

### The page bar folds

Search (now matching **display name, serial and registration** — `searchVehicleRows`, renamed from
`searchVehicleRowsByName` because a function that also matches serials must not keep a name saying
otherwise) and **Category** stay on the bar. State, Custodian and Readiness moved behind a **More
filters** `<details>`/`<summary>` popover with a count badge (`#moreFilterCount`, counting only the
hidden three — a badge exists to say "something you can't see is hiding rows") and `Clear filters`
inside. `<details>` rather than a signal-backed dropdown because `core/ui/architecture.spec.ts` forbids
a routed page holding an `*Open` signal, and because it closes on Escape with no keyboard handler —
the same idiom `shared/ui/kebab-menu.ts` already uses. **Show retired / Show archived** moved to the
right end of the view row; `+ Add vehicle` and `Refresh` gating is untouched.

### §5.3 — the drawer is a triage sheet

| Section | Reads |
|---|---|
| Title row: name; `category · Simulated` muted | `VehicleRow#asset.displayName`/`categoryName`/`simulated` (new, `core/fleet/triage-logic.ts#isSimulated`) |
| Chips row: **one** state chip + readiness dot/verdict | `#stateChip`, `#readinessVerdict` (`verdictLabel`/`verdictTone`) |
| One fact grid, fixed-width muted labels | `#custodianName`/`#custodianTitle`, `#location`, **`#sinceLabel`** (new — `custodySinceLabel`, `3h ago`, `—` for in-stock/unparsable, replacing a raw ISO timestamp), `asset.identity?.*`, `#registration`, `#firmware`, `#hours`, `#lastFlownLabel`, `#links` (last group Vehicles-only) |
| `h3 Why not ready` | **`#readinessBlockers`** (new — `core/readiness/readiness-logic.ts#fleetRowBlockers`, the uncapped list behind `fleetRowAttention`'s `+N more`), first bold; `GO — no blockers`; or `Not evaluated yet` |
| `h3 Maintenance` | `InventoryFacade#openMaintenance` → `vehicles-logic.ts#openMaintenanceSummary` — the **oldest still-open** record as kind · summary · *opened by name* · age, else `No open records` |
| `h3 Pilots` | `#selectedPilots`/`#loadingPilots`/`#selectedPilotsUnavailable`, named by `core/org/pilot-logic.ts#assignedPilotName` + `assignmentRoleLabel` |
| Actions row | `core/fleet/inventory-logic.ts#primaryVehicleVerb(actions)` — **exactly one** `.btn`, everything else `.btn.secondary`, `Open full ›` last |

`fleetRowBlockers` was exported (and `fleetRowAttention` made to delegate to it) precisely so the cell's
one-line summary and the drawer's full list can never word or order the same blockers differently.
The manager-only **Open record** form is deleted, not moved: grounding a vehicle already *is* opening a
record, with a kind and a reason, and it moves the state with it — a second near-identical form offered
a `NOTE` record nothing on this page could read. **Close record** stays (it is the only way to clear a
non-blocking `NOTE`/`REPAIR` record on a vehicle that is not grounded, where Release is not offered).
`+ Assign…` **links to `/assets/:id`** rather than lifting `pilots-card.ts`: that card injects
`VisionApi`/`AuthStore` and owns its own pilots + users fetch, so mounting it here would put a second
API consumer under a routed page and duplicate two reads this facade already caches. It is gated on
`canManageOrg`, not `canManageFleet` — that is what the destination card itself requires, and a link to
a drawer that would refuse to render is the same broken promise as a button that 403s.

### §5.5 — Issue is pilot-first

The grouping rule's final home is **`core/org/pilot-logic.ts`**. `creatorOwnershipGroup`,
`pilotsInGroup` and `defaultPilotSelection` moved there **verbatim** from
`features/onboarding/onboarding-logic.ts` (which now carries a pointer comment, and whose store imports
them from the new home) — the standing "a second consumer moves shared logic to `core/`" precedent, not
a copy. Added beside them: `pilotsAnywhere`, `assignedPilotName`, `custodianPickerGroups`,
`custodianCandidateName`.

`custodianPickerGroups` returns three disjoint, name-sorted lists rendered as `<optgroup>`s:
**Assigned pilots** (this asset's own `PILOT`-seat holders; a `CREW` seat-holder is deliberately
excluded — W1's `HandoverService` skips the `PILOT` grant whenever any seat exists, so issuing to them
would leave them holding something they still may not fly, and they stay reachable under Show everyone
where nothing about them is claimed) → **Other pilots** → **Show everyone** (a checkbox revealing the
third `<optgroup>`, kept a native `<select>` because the OS picker is the right control on a phone).

*Other pilots* is **two-rung, and the second rung is the honest half**: the asset's own group's pilots,
and — only when that finds nobody — every enabled pilot in the already-scoped user list. The reason is
a wire limitation: `AssetSummaryResponse.owner` is the owning **user** id and no endpoint returns
`Ownership#groupId`, so `InventoryFacade#ownershipGroupId` passes the session's own ownership group as a
stand-in. That misses a child-group asset, and matches nothing at all in dev-parity mode, where the
fixed dev-admin principal's synthetic group id differs from the seeded users' own "Root" group id (two
unrelated ids sharing a display name). Rung 1 alone would hand a live manager an empty *Other pilots*
and leave defect E exactly where it was. Neither rung hides anybody — Show everyone always holds the
remainder — so this is a sorting aid that degrades to *less sorted*, never a visibility decision.

The primary button reads **`Issue to <name>`** once somebody is picked, disabled until then. Toasts:
`Issued to <name>` with **Undo** = `returnAsset`, appending `· assigned as pilot` **only when a `PILOT`
seat genuinely appeared** — the facade compares the asset's pilot list either side of the write, because
the server skips the grant when any seat already exists and the plan's literal wording would otherwise
lie. `Returned to stock` with **Undo** = re-issue to the same custodian at the same location, both
captured before the write; an asset with no recorded custodian gets a plain toast with nothing to undo
rather than an Undo that would re-issue to nobody. Ground/Release/Retire keep their plain toasts.

### The W1 wire, honoured

`POST /api/assets/{id}/custody` and `/inventory` answer with a `CustodyResponse` built without the name
join, so the custody object that replaces the old one carries a `custodianId` and **no `custodianName`**
— `patchAsset` alone would blank the Custodian column the instant somebody pressed Issue on a station
that had just rendered the real name. Every custody/inventory mutation is therefore followed by exactly
one `GET /api/assets/{id}` for that asset (`InventoryFacade#refreshAsset`), silently degrading to the
mutation response's own picture if the re-read fails. After a successful Issue the asset's pilot list is
invalidated and re-fetched, so the new pilot appears in `h3 Pilots` without a reload.

### W5's slot

`inventory.html` wraps everything from the view row down in `@if (facade.showsManagerView())`
(`actor.canManageFleet || auth.scopeKind() !== 'ASSIGNED_ASSETS'` — a wider-scoped viewer reads the same
table, read-only). The `@else` branch is W5's, and carries the literal marker
`<!-- W5: My vehicles cards render here -->` plus a temporary `<vision-empty>` pointing at Fly.

### Honest degradation, in one list

- A failed `getAsset` re-read after a mutation → the row keeps the shorter custodian label. Degraded, never wrong.
- A failed pilots read is stored as `null`, distinct from `[]`: the drawer prints `—`, never the *claim* "Nobody is assigned to fly this".
- Blocked `localStorage` → no remembered view; the page behaves exactly like a first-time visitor.
- An empty view (rather than an empty fleet) gets its own "Nothing in this view" state with a **Show all** button, not the "No vehicles yet" state that would imply the fleet is gone.
- Every absent fact in the drawer grid is an em dash.

### Dev parity (`vision.auth.enabled=false`)

The dev admin holds every capability, so `showsManagerView()` is true, every verb the matrix can grant
is granted, and the page behaves exactly as before. The one place dev parity is *visibly* different is
the Issue picker's *Other pilots* group — the dev principal's synthetic group id matches no seeded
user's membership — and that is precisely the case rung 2 exists to cover: the group falls back to
"every pilot you can already see" rather than to nothing.

### Left undone, named honestly

- **Verified statically and by spec only.** The app on :8080 has auth enabled and this agent had no credentials; both-theme screenshots remain W6's job, as W3's own entry already recorded.
- **`+ Assign…` is a link, not an inline flow.** Assigning still happens on `/assets/:id`. Lifting `pilots-card.ts` would need it refactored to take its data as inputs first — real work, outside this wave's scope, and the link is honest in the meantime.
- **Export is still whole-scope.** A filtered export needs `InventoryExportController` to accept a filter; that is a backend change this wave did not invent client-side.
- **The view row renders on Equipment too.** Deliberate: the persisted view would otherwise filter that tab invisibly. In-field/Needs attention will often read 0 there, which is honest rather than hidden.

### Tests / build

`npm run test:ci` — **194/194 files, 3906/3906 tests green** (192/3860 before this wave: new
`features/inventory/inventory-page-logic.spec.ts` rewritten around the five views, new
`features/inventory/inventory-view-store.spec.ts` (5 cases incl. `localStorage` throwing on both read
and write), new `core/org/pilot-logic.spec.ts`, plus `fleetRowBlockers`, `primaryVehicleVerb`,
`custodySinceLabel`, `searchVehicleRows`, `openMaintenanceSummary` and a `buildVehicleRows` case for the
three new derived row facts). `npx tsc --noEmit` clean on both `tsconfig.app.json` and
`tsconfig.spec.json`. `ng build --configuration production` **green, exit 0**, only the two pre-existing
budget warnings; initial bundle **unchanged at 435.22 kB raw** (transfer 122.01 → 121.99 kB), the lazy
`inventory` chunk **109.11 → 121.00 kB raw (+11.89)**, 21.65 → 23.96 kB transfer (+2.31) — the drawer's
new sections, the grouped Issue dialog, the view row and `core/org/pilot-logic.ts`, all behind the
`/assets` lazy route.

## Status — INVENTORY-REWORK web wave W5: My vehicles — the pilot/crew card view fills W4's own slot (docs/plans/active/INVENTORY-REWORK-PLAN.md §5.4, wave W5) — 2026-09-07

W4 left `inventory.html`'s `@else` branch carrying a literal marker comment and a temporary
`<vision-empty>` pointing at Fly. A genuinely `ASSIGNED_ASSETS`-scoped session — a pilot or crew
member, never `MANAGE_FLEET` — landed on `/assets` and got told the real view "is not built yet."
This wave builds it: three new files (`my-vehicles.ts`/`.html`/`.css`) plus a pure
`my-vehicles-logic.ts`/`.spec.ts`, consuming exactly what W3/W4 already built without changing
manager-view behavior at all.

### The component

`<vision-my-vehicles>` is a non-routed presentational child — the same `core/ui/architecture.spec.ts`
carve-out `vehicles-table.ts`/`found-devices.ts`/`pilots-card.ts` already use — injecting
`InventoryFacade` directly (DI-shared from `InventoryPage`'s own `providers: [InventoryFacade]`), never
`VisionApi`/an `*Store`. It makes no network call of its own. `inventory.html`'s `@else` branch is now
just `<vision-my-vehicles />`; `inventory.ts` dropped the now-dead `RouterLink`/`EmptyState` imports the
old placeholder alone had used.

Two stacked card grids — Vehicles, then a muted uppercase `h3 Equipment` heading, then Equipment's own
grid — rather than a second tab switch: a pilot's whole fleet is small enough that a second click to see
the handful of batteries they also hold is friction the manager's dense table doesn't have to justify.

### Row sourcing — a correctness fix, not just a read

Cards read two new facade computeds, `myVehicleRows`/`myEquipmentRows`, added to `InventoryFacade`
alongside the pre-existing `vehicleRows`/`equipmentRows`. They wrap the facade's own *private*
pre-view computeds (`preViewVehicleRows`/`preViewEquipmentRows`) rather than the public post-view ones
— deliberately. The view row (Needs attention/In field/Issued/In stock/Maintenance, D7, wave W4) is a
manager-only control, rendered only inside `showsManagerView()`, with a default-view latch that runs
unconditionally in `loadAll()` and a pick persisted **per browser** (`InventoryViewStore`'s
`localStorage` key), not per session. A naive implementation reading `vehicleRows()`/`equipmentRows()`
directly would have silently hidden a pilot's own perfectly fine vehicles the moment any one of them
needed attention — the tile that would explain why is a control that persona never sees — or shown
whatever a manager last picked on a shared station. `myVehicleRows`/`myEquipmentRows` still run the
same search/category filter pipeline (so the page bar's search box narrows them exactly as it narrows
the manager's table), just stopping one step before the view split. This is additive: the private
pre-view computeds already existed unchanged, so manager-view rendering is untouched.

### Card anatomy (§5.4)

Per card: title = asset name linking to `/assets/:id` (`routerLink`, never a button — `open` is this
link, not a verb in the actions row); category + `Simulated` muted top-right, one line, never a second
chip; a readiness line (dot + verdict + first blocker, reusing `verdictLabel`/`verdictTone` and the
row's own `readinessBlockers`/`readinessCause` — the identical facts the table/drawer already read, so
this card can't phrase the same verdict differently); the state chip (the card's only chip); a prose
custody line (`myVehicleCustodyLine` — `With you · since 3h ago` when `custodianId === facade.actor()
.userId`, `With <name> · since …` otherwise, `In stock` / `In stock at <location>`, `In the field`,
falling back to the chip's own label for any other state — maintenance/retired/archived/unknown); a
meta line (`myVehicleMetaLine`, `Last flown 7h ago · 15h 04m total`, riding the row's own `'—'`
unchanged for a never-flown vehicle); an actions row. Equipment cards drop the readiness and meta lines
(neither concept applies to a battery or a gimbal) but share title/origin/chip/custody/actions.

### Verbs — generic rendering, one authority matrix

Every verb comes from `facade.actionsFor(row)` (`core/fleet/inventory-logic.ts#vehicleRowActions`, the
one matrix every surface reads), through `my-vehicles-logic.ts#myVehicleActions`, which layers exactly
one more rule: a `CREW`-assigned row never offers Fly (§5.4's own rule — a CREW seat is about working
the camera, not flying, and the shared matrix has no way to know a session's per-asset
`AssignmentRole`, only its capabilities, which a CREW-assigned *pilot-role account* may still hold).
`MY_VEHICLE_VERB_ORDER = ['fly', 'watchLive', 'return']` + `MY_VEHICLE_VERB_LABELS` +
`myVehicleVerbList` render every shown verb as a button generically — the template never names a verb
— a disabled one still renders, as a ghost with its `title`/`.disabled-reason`. `myVehiclePrimaryVerb`
picks **exactly one** primary: Fly when shown and enabled, else Watch live when shown, else no primary
at all — a deliberate divergence from the table/drawer's own `primaryVehicleVerb` (which ranks Return
above Fly: right for a manager weighing a take-back, wrong for a pilot's own launch-pad card, where
flying it is always the headline action when it's possible at all). **This is also the whole hook W2**
(D4/D5, a pilot's own *Report issue*, a custodian's self-*Return*) **needs**: extend
`vehicleRowActions` with the new verb, append it to `MY_VEHICLE_VERB_ORDER` and one label to
`MY_VEHICLE_VERB_LABELS`, and wire its case in `MyVehicles#runVerb` — the template's `@for` over
`verbList(row)` never changes.

### The crew seat, and the flag that doesn't exist

`InventoryFacade#myRoleFor(assetId)` answers this session's own `AssignmentRole` for one asset, backed
by a new signal (`myAssignmentRoleByAssetId`) populated by a new private `loadMyAssignments()` — a
fifth request, `GET /api/me/assignments` via `VisionApi#myAssignments` (pre-existing), fired
fire-and-forget from `loadAll()` right after `latchDefaultView()`, deliberately **outside** the
existing four-request `Promise.all` (`INVENTORY-REWORK-CONTEXT.md`'s own "5 requests flat" precedent)
so a slow/failed assignments read can never hold up or fail the manager table's own load. Gated on
`auth.scopeKind() === 'ASSIGNED_ASSETS'` — the only persona "My vehicles" ever renders for — and
degrades to an empty map on any failure, so every card then simply falls through to the plain verb set.

The task brief asked for "Open crew seat → `/crew/:assetId`, only if the crew feature is exposed to the
web; else fall back to Watch live." **No client-readable `vision.crew.enabled` (or equivalent) signal
exists anywhere in vision-web** — checked `MeResponse`, `core/seat/seat-logic.ts`, `core/seat/
seat-store.ts`, and `features/hubs/nav-entries.ts` (whose "Crew seat" nav entry is deliberately
ungated, and whose bare `/crew` route redirects to `/wall` for lack of a real landing page). The
fallback is therefore not a special case at all: `myVehicleActions` simply removes `fly` for a
CREW-assigned row, and `myVehiclePrimaryVerb`'s existing Fly→Watch-live rule falls through to Watch
live exactly the way it would for any other Fly-less row — no separate "Open crew seat" verb, label, or
route needed. If a client-readable crew flag is added later, wiring the real crew-seat link is a small,
localized change to `myVehicleActions`/`runVerb` alone.

### Empty state, loading, page bar

`vision-empty`, `No vehicles assigned to you yet` / `Your fleet manager assigns vehicles from Roster.`,
no CTA — reusing `facade.hasActiveFilters()` to distinguish a genuinely empty assignment (`§5.4`'s
literal copy) from a search/filter that matched nothing (`No matches` / `Clear filters`, the same
distinction W4's own "empty view vs. empty fleet" precedent established for the manager table). Loading
reads the page's existing `Loading…` treatment gated on the same `facade.loading()` signal the manager
branch uses — the empty state can never flash ahead of it, since both are `@if`/`@else if` siblings off
one signal.

**The page bar narrows by persona, not duplicates.** `inventory.html`'s filter block changed from
`@if (facade.showsManagerView() && (tab is vehicles/equipment))` to `@if (!facade.showsManagerView() ||
tab is vehicles/equipment)` — algebraically identical to the old condition whenever
`showsManagerView()` is true (`!true = false`), so manager rendering is unchanged; it additionally opens
the block for a non-manager session. Search is unconditional inside (`myVehicleRows`/`myEquipmentRows`
run the same filter pipeline as the table); Category and the "More filters" `<details>` are now nested
under their own inner `@if (facade.showsManagerView())` — a pilot's handful of assigned assets is not a
directory that needs slicing. `Export all (CSV)` is now wrapped in `@if (facade.showsManagerView())`
too (it rendered unconditionally before this wave) — the endpoint's own scope already limits it to the
caller's own assets regardless, but "export the fleet" has no meaning over two assigned vehicles.
`[count]` on `<vision-page-bar>` now reads `facade.showsManagerView() ? facade.activeRows().length :
facade.myRowsCount()` (`myRowsCount` = vehicles + equipment together, since both render as one stacked
list here). `+ Add vehicle` and `Refresh` gating is untouched.

### Grid (frontend-style §5.4/§9)

`.card-grid` — `grid-template-columns: repeat(auto-fill, minmax(16rem, 1fr))`, 1–3 columns by content
width rather than a breakpoint list, `--space-16` gaps. Cards reuse the global `.card` primitive
(`--panel`/`--border`/`--radius`, no shadow); `.card-actions` gets its own hairline `--border` top rule
separating it from the prose lines above. Fully theme-blind — no raw hex, no off-grid px, checked
against both themes' token definitions in `styles.css` (a live screenshot check remains W6's job, same
as every prior wave — the app on :8080 has auth on with no credentials available to this agent).

### Dev parity (`vision.auth.enabled=false`)

The dev admin resolves `MANAGE_FLEET` unbounded, so `showsManagerView()` is `true` and `<vision-my-
vehicles>` never mounts — the app behaves exactly as before. `loadMyAssignments()` also never fires for
that session (`auth.scopeKind() !== 'ASSIGNED_ASSETS'`), so this wave adds zero requests to the dev-
parity path.

### Left undone, named honestly

- **Verified statically and by spec only** — the app on :8080 has auth enabled and this agent had no
  credentials; both-theme screenshots remain W6's job, as W3's and W4's own entries already recorded.
- **No "Open crew seat" verb/route** — see above; there is nothing in vision-web to gate it on, so the
  plan's own named fallback (Watch live) is what ships.
- **§5.4 under-specified the empty-vs-no-matches split** for this view specifically; this wave resolved
  it the same way W4's own "empty view vs. empty fleet" precedent did, reusing `hasActiveFilters()`
  rather than inventing a second empty-state rule.
- **§5.4's card diagram doesn't show Equipment cards at all** — this wave trims readiness/meta off them
  (chosen, not specified) since neither concept exists for non-connected categories; everything else
  (title/origin/chip/custody/actions) is shared with vehicle cards.

### Tests / build

`npm run test:ci` — **195/195 files, 3925/3925 tests green** (194/3906 before this wave: new
`features/inventory/my-vehicles-logic.spec.ts` covering `myVehicleCustodyLine`/`myVehicleMetaLine`/
`myVehicleActions`/`myVehicleVerbList`/`myVehiclePrimaryVerb`). `npx tsc --noEmit` clean on both
`tsconfig.app.json` and `tsconfig.spec.json`. `ng build --configuration production` **green, exit 0**,
only the same three pre-existing warnings (the `cockpit.html` NG8107 and the two budget warnings);
initial bundle **unchanged at 435.22 kB raw** (transfer ~122.00 kB, matching W4's own number within
rounding), the lazy `inventory` chunk **121.00 → 129.68 kB raw (+8.68)**, 23.96 → 25.43 kB transfer
(+1.47) — the new component, its logic module, and the `GET /api/me/assignments` wiring, all behind the
`/assets` lazy route.

## Status — CV-ORCHESTRATION wave W-pre (web): DetectionState's 4th value stops falling through every switch (docs/plans/active/CV-ORCHESTRATION-PLAN.md §7 D1, §4.6, §6 W-pre row) — 2026-09-12

Standalone defect fix, disjoint from the rest of CV-ORCHESTRATION's waves (W-pre runs parallel to
W0, ahead of W1-W6). Scope was `station/vision-web/**` only — no Java, no proto, no Python.

**What changed:**

- `core/api/models.ts` — `DetectionState` now has all four values Java's enum has always declared
  post-ALWAYS-ON-FLOW D2 (`OFF | IDLE_NO_VIEWERS | RUNNING_UNWATCHED | RUNNING`), derived from a new
  exported `DETECTION_STATES` `as const` tuple so the type and the runtime value list share one
  source and cannot drift apart independently again. Doc comment rewritten to explain
  `RUNNING_UNWATCHED` from the Java javadoc: an asset opted into `DetectionPolicy.ALWAYS` keeps
  inferring with no current viewer; durable persistence/events proceed exactly as `RUNNING`, only
  the *live* read models a viewer would see go stale.
- New `core/api/detection-state.contract.spec.ts` — asserts `DETECTION_STATES` equals
  `['OFF','IDLE_NO_VIEWERS','RUNNING_UNWATCHED','RUNNING']`, with a comment pointing at
  `DetectionState.java` as the fixture's source of truth (a generated cross-language fixture is
  wave W1's job per the plan, not this one's).
- `features/fly/cv-control-panel-logic.ts#detectionStatus` — new `'running-unwatched'`
  `DetectionStatusKind` and an explicit branch between the `IDLE_NO_VIEWERS` and `RUNNING` checks,
  ordered to match the Java enum's declaration order. Renders `"On — inferring without a viewer
  (asset policy: always)."`, and deliberately never touches `rate`/`classesOnScreen` — those are
  exactly the *live* read models the enum's own javadoc says are not being kept warm in this state,
  so echoing a possibly-stale number would be a fabricated health claim, not an honest one.
- `core/camera-geo/camera-geo-logic.ts` — `detectionStateLabel`/`detectionStateTone`/
  `detectionStateExplanation` each gained an explicit `RUNNING_UNWATCHED` case rather than falling
  into `default`. Decided per-case (rationale in the file's own new comment and the commit message):
  the pose panel's question is narrower than the Fly hero's — "is the detector producing tracks for
  this map to project" — and the answer is yes regardless of viewer presence, so tone matches
  `RUNNING` (`'ok'`), while the label (`'Detecting (no viewer)'`) and explanation still name the
  "no viewer" fact plainly instead of silently reusing `RUNNING`'s wording verbatim.
- Unit tests added next to each changed function in `cv-control-panel-logic.spec.ts` and
  `camera-geo-logic.spec.ts`, matching each file's existing spec style (no new component specs —
  all four are pure functions).

**Not touched (out of this wave's scope, named honestly):** the static "Runs only while this
stream is watched — zero cost otherwise." caption under the Detect toggle in `cv-control-panel.html`
is now imprecise for an `ALWAYS`-policy asset; fixing it needs the asset's own policy plumbed into
this component, which is D7/wave W3's job (no UI sets `cv.detection-policy=ALWAYS` yet, so the two
defects cancel out today per the plan's own §7 D7 note). The honest per-stream status line beneath
it already carries the truth for `RUNNING_UNWATCHED` specifically.

### Tests / build

`npm run test:ci` — **193/193 files, 3829/3829 tests green** (up from 192/3825: one new contract
spec file plus cases added to the two existing logic specs). `npx tsc --noEmit` clean on both
`tsconfig.app.json` and `tsconfig.spec.json`.

## Status — CV-ORCHESTRATION wave W1 (web): the `ObjectState` type mirror, no rendering (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.5, §6 W1 row) — 2026-09-12

W1 is a sequential, disjoint-file wave (domain-modeler → adapter-builder → spring-integrator →
web-ui); this entry is only the last, web-ui step. Scope was `station/vision-web/**` only — no
component, store, service call, or route. **No endpoint serves `ObjectState` in this worktree yet**
(no DTO exists under `station/vision-api/src/main/java/.../dto/` for it) — this step mirrors only
the domain record family the plan's §6 W1 row names, ahead of the wire actually carrying it.

**What changed, all in `core/api/models.ts`:**

- New interfaces mirroring `contexts/vision-perception/.../domain/model/ObjectState.java` 1:1 by
  JSON key: `ObjectState` (`id`, `lifecycle`, `streamId`, `identity?`, `kinematics?`, `belief?`,
  `provenance?`, `memory?`, `lock?`, `timing?`) and its nested groups `ObjectLabelCandidate`,
  `ObjectIdentity`, `ObjectKinematics`, `ObjectBelief`, `ObjectProvenance`, `ObjectMemoryFacts`
  (JSON key `memory`), `ObjectLockFacts` (JSON key `lock`), `ObjectTiming`. `Object*` prefixing is a
  deliberate deviation from the Java records' own bare names (`Identity`, `Belief`, …): TS has no
  per-record nesting scope, so reusing the bare names in this one 4000-line file would collide with
  or shadow unrelated concepts. `Kinematics.box` reuses the existing `BoundingBox` interface rather
  than declaring a second one.
- Every nested group on `ObjectState` is **optional (`?`), not `T | null`** — the DTO layer
  serializes with Jackson `NON_NULL` (confirmed against `StreamTracksResponse.java`'s own use of the
  same annotation), so a `null` group on the Java side is a **missing key** on the wire, not an
  explicit `null`. The same rule applies to `ObjectKinematics.detectorBox`/`trackerBox`/
  `predictedBox` (`box` itself is never null/absent). Each doc comment says why this distinction
  matters: an absent group means "this configuration does not compute these facts," which is a
  different statement from a present-but-zeroed group, and collapsing the two back into one is the
  exact honesty defect this whole plan exists to remove (`ObjectState.java`'s own class doc makes
  the same point about the Java side).
- `streamId` is `string` (the wire's `StreamId` mirror, matching every other `streamId` field in
  this file).
- New `OBJECT_LIFECYCLES`/`ObjectLifecycle` and `EVIDENCE_SOURCES`/`EvidenceSource` — `as const`
  tuple + derived string union, the exact idiom `DETECTION_STATES`/`DetectionState` established in
  wave W-pre (immediately above), each with its own hand-pinned contract spec so a Java enum
  addition or reorder fails a TS test instead of falling through a switch silently, the way
  `DetectionState` already did once (§7 D1). `EvidenceSource` is a new, separate type from the
  existing `DetectionSource` union (`'DETECTOR' | 'TRACKER'`) — no collision, but note both exist:
  `DetectionSource` is `domain.model.DetectionSource` (two values, mirrored on `Detection`/
  `StreamTrack`/`DetectionTrack`); `EvidenceSource` is `perception.domain.model.EvidenceSource` (a
  five-value superset used only inside `ObjectProvenance`).
- New `core/api/object-lifecycle.contract.spec.ts` and `core/api/evidence-source.contract.spec.ts`,
  in the `detection-state.contract.spec.ts` pattern exactly: a hand-pinned tuple of the Java enum's
  declaration order, a comment naming the Java file path it's pinned to, and a comment naming the
  silent-fallthrough failure it prevents.

**Not built (by this brief's own design, not an omission):** no component reads `ObjectState`, no
store fetches it, `StreamTracksResponse` gained no `objects` field — there is nothing to wire it to
yet on the server side of this worktree. Rendering is wave W3's job per the plan.

**Superseded in part by wave W2.8** (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.6): `models.ts`
now does declare `StreamTracksResponse.objects` — typed `readonly WorldObject[]`, not `ObjectState[]`
— since the server-side `WorldObject` fold (`WorldObjectResponse`) reaches this endpoint (and
`CvTraceResponse.world`, still type-mirror-only, and the `tracks:` SSE topic) as of that wave. New
`WorldObject`/`WorldObjectOperator`/`WorldObjectEventLink`/`WorldObjectRender` interfaces and
`RENDER_TIERS`/`RenderTier` (same `as const` tuple idiom as `OBJECT_LIFECYCLES`) sit right after
`ObjectState` in `models.ts`; `ObjectState` itself is unchanged and is `WorldObject.state`'s type
verbatim. New `core/api/world-object.wire.contract.spec.ts`, same `Record<keyof T, true>` key-map
technique as `object-state.wire.contract.spec.ts`, against a new `__fixtures__/world-object.wire.json`
generated by `station/vision-api`'s `WorldObjectResponseWireContractTest`. Still no UI reads any of
this — rendering stays wave W3's job; this wave's only web-side work is the type mirror plus fixing
the one call site (`core/detections/detections-store.spec.ts`'s `tracksResponse()` test helper) that
built a `StreamTracksResponse` literal predating the now-required `objects` field.

**Nothing in this brief was found wrong or unimplementable.** One judgment call worth flagging: the
brief named `Identity`/`Belief`/`Timing`/etc. as the Java accessor names to mirror for JSON *keys*,
which this entry followed exactly (`identity`, `belief`, `timing`, `memory`, `lock` — all lowercase,
matching the record accessors); the `Object*` prefix applies only to the TS *interface* names, never
to a field name or JSON key.

### Tests / build

`npm run test:ci` — **198/198 files, 3892/3892 tests green** (this worktree's branch already carried
193/3829 from prior waves before this step; +2 files/+2 tests are this step's own two contract
specs — the remaining +3 files/+61 tests already existed on this branch from unrelated,
already-committed work ahead of this task, per `git status` showing only `models.ts` plus the two
new spec files touched). `npx tsc --noEmit` clean on both `tsconfig.app.json` and
`tsconfig.spec.json`.

## Status — CV-ORCHESTRATION wave W5.1 (web): the engineer inspector's TS mirrors + the `cv-trace` live topic (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.4/§4.8, §6 W5 row) — 2026-09-13

Sequential wave (Java W5.0 → this web step W5.1 → W5.2 store → W5.3 page). Scope this step:
`core/api/models.ts`, `core/live/live-store.ts`, `core/live/live-fallback-logic.ts`, plus two new
spec files — no component, no route yet (that's W5.3).

**New types, all appended at the end of `models.ts` under a `// CV-ORCHESTRATION W5 — trace
mirrors` banner** (this wave's own file-scope convention, so a concurrent wave editing the
W1/W2.8 sections above never conflicts here): `GATE_OUTCOMES`/`GateOutcome` and `GATE_REASONS`/
`GateReason` (`as const` tuple + derived union, `OBJECT_LIFECYCLES`'s own idiom, pinned to
`application.pipeline.GateOutcome`/`GateReason`'s declaration order), `DemandSnapshot`,
`GateDecision` (`reason?` — absent when `outcome` isn't `'SKIPPED'`, the same "absence of a
relation" idiom as every other `@JsonInclude(NON_NULL)` field in this file), `LEDGER_OUTCOMES`/
`LedgerOutcome`, `LedgerEntry`, `ObjectEvidence` (`claim` is `Readonly<Record<string, string>>` —
genuinely free-form, not part of the frozen §5 contract), `FrameLedger`, and `CvTrace`. `models.ts`
grew from 4280 to 4485 lines.

**Live wiring — ONLY the `'cv-trace'` member/case, per this wave's exclusive-scope constraint**
(wave W3, running in parallel on a disjoint worktree, owns the `'tracks'` member and every
`features/fly/**`/`shared/player/**`/`core/detections/**` file): `LiveEnvelope` gained one more
discriminated-union member, `{ seq, assetId, type: 'cv-trace', payload: FrameLedger }` (12th topic,
opt-in per-asset like `telemetry`/`detections`/`geo`). `live-fallback-logic.ts` gained
`cvTraceTopic(assetId)` (`` `cv-trace:${assetId}` ``, same shape as `detectionsTopic`/`geoTopic`).
`live-store.ts` gained `cvTraceSignals` (a `Map<assetId, Signal<FrameLedger | undefined>>`),
`cvTraceFor(assetId)`, `trackCvTrace`/`untrackCvTrace`, and one `applyEnvelope` switch case —
**latest-wins**, the same posture as `detections`/`geo`, not an accumulating log: the capped
client-side ring an inspector actually reads from is `core/cv-trace/cv-trace-store.ts`'s own job
(wave W5.2), matching the server's own `last` cap, not this store's concern. The class doc's
topic count/enumeration and its `<h2>` heading were updated from eleven to twelve.

**New tests:** `core/api/cv-trace.wire.contract.spec.ts` (the `Record<keyof T, true>` key-mapping
technique `world-object.wire.contract.spec.ts` established) against the fixture W5.0 committed at
`__fixtures__/cv-trace.wire.json` — asserts the full example's `gate` covers all seven `GateReason`
values plus a `SENT`/`PROBE` pair (each missing the `reason` key), the `frame` ledger's entries
cover all three `LedgerOutcome` values, and the `predict.cv` evidence row carries a `held` box
string (this fixture's own shape, not a general `ObjectEvidence` guarantee — the type stays
free-form). One assertion needed an `as unknown as FrameLedger` cast: the JSON-imported fixture's
inferred literal type has no string index signature, so indexing `ledger.objects[trackId]` needs
the real `FrameLedger` type in scope. Also added one case to `live-fallback-logic.spec.ts`'s
existing `telemetryTopic`/`detectionsTopic` test, covering `cvTraceTopic`.

**Environment note, not a code defect:** this worktree's `station/vision-web/node_modules` did not
exist at the start of this wave (a fresh worktree checkout, never `npm install`ed) — running
`test:ci` failed instantly with `panic: aborting due to terminal initialize failure` before any
Angular/Vitest code ran at all. `npm install` (477 packages, from the committed `package-lock.json`)
resolved it; this is a worktree-provisioning gap, not anything wrong with this app's own tooling.

### Tests / build

`npm run test:ci` — **201 files / 3919 tests, all green** (up from 198/3892 at wave W1's own count;
+3 files/+27 tests is this step's own two new spec files plus growth already on this branch from
other concurrent waves, not solely this step's addition).

## Status — CV-ORCHESTRATION wave W5.2 (web): `CvTraceStore` + `VisionApi.getCvTrace` (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.4/§4.8, §6 W5 row) — 2026-09-13

New `core/cv-trace/` (`cv-trace-store.ts`, `cv-trace-logic.ts`, plus their specs) — the `/manage/cv`
engineer inspector's (wave W5.3) one data source. Also: `VisionApi.getCvTrace(streamId, last?)`
(`GET /api/streams/{streamId}/cv/trace`), and the `cv-trace` row added to this file's `core/**`
stores table.

**Why poll and live run *concurrently*, not exclusively (unlike every other dual-transport store
in this file):** `gate` and `world` have no live topic at all — `cv-trace:<assetId>` carries only
`FrameLedger` (`frame`), per `station/vision-api/MODULE.md`'s own "Live updates" section — so a
recurring `GET .../cv/trace` poll (3s, `POLL_INTERVAL_MS`) is their *only* freshness source
regardless of whether live is available. `frame` itself is treated as a ring the inspector renders
as a timeline: the poll's own `frame` array — the server's own `FrameLedgerRing`, already capped
at `last` — **replaces** this store's ring wholesale on every tick (authoritative resync, never
drifts); a live arrival between ticks is merged in immediately via `cv-trace-logic.ts#appendFrameLedger`
(dedup-and-replace by `sequence`, ascending order, capped at the session's own `last`) purely to
shave latency off what the next poll would show anyway.

**This store IS the plan's own demand rule** (§6 W5 row: "opening the inspector flips `trace` on
and closing flips it off, asserted via `/cv/trace`") — `TraceDemandPort` fails **closed** (the
opposite of `DetectionDemandPort`), so for as long as `track()` is in effect, either the live
`cv-trace:<assetId>` subscription or the recurring poll (usually both) keeps a stream traced;
`reset()` releases both. `assetId` is optional on `track(streamId, assetId?, last?)` — omitting it
stays poll-only (the poll alone is still real trace demand) rather than blocking on a caller that
doesn't yet know the asset id, mirroring `DetectionsStore.track`'s own optional-`assetId` shape.

**Not built this step (W5.3's job):** no component, no route, no facade — `CvTraceStore` has no
consumer yet in this worktree.

### Tests / build

`npm run test:ci` — **203 files / 3935 tests, all green** (+2 files/+16 tests over W5.1's own
201/3919 — exactly this step's two new spec files, `cv-trace-logic.spec.ts` and
`cv-trace-store.spec.ts`).

## Status — CV-ORCHESTRATION wave W5.3 (web): the engineer inspector page, `/manage/cv` (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.8, §9 decision 3, §6 W5 row) — 2026-09-13

New `features/cv-inspector/` — `CvInspectorPage`/`CvInspectorFacade`/`cv-inspector-logic.ts` (+specs),
`cv-inspector.routes.ts` — the first consumer of wave W5.2's `CvTraceStore`. See this file's own
`features/**` `cv-inspector/` bullet above for the full component/panel account; this section covers
the wave-level record: what was decided, what stayed out, and the build totals.

**Stream picker, not an asset picker.** The plan's own §4.8 names "one picked stream" — `FleetStore
.streams()` (already polling app-wide) fills a plain `<select>` with running streams only; picking one
calls `CvTraceStore.track(streamId)` (poll-only, see the `cv-inspector/` bullet's own "disclosed scope
cut" for why no `assetId` is ever resolved and passed).

**Process facts are rendered unconditionally**, before any stream is picked — `SubsystemStatus`'s
`cv-service` row (`SystemStatusStore`, already polling app-wide) is a whole-process fact, not scoped
to any one stream (§4.8's own audience table lists it under "Ops", not per-asset); this page is a
second reader of the exact row `/manage/system` already renders, never a competing derivation of it —
see `CvStatusProvider`'s own javadoc (`cv/grpc/**`) for why capacity/occupancy/queue facts are folded
into that row's free-text `detail` sentence server-side rather than structured fields, which is why
this panel renders one health chip + one sentence, not a KPI grid.

**§1.5 acceptance table, transcribed onto the page itself.** `CvInspectorPage`'s own doc comment
carries the plan's §4.4 "what the why table looks like afterwards" table verbatim, each question
mapped to the exact panel and field that answers it — the brief's own instruction ("every §1.5 'why'
question must be answerable from one lookup") is satisfied by making that table impossible to read
without also reading where it points.

**Route + nav.** `/manage/cv`, `orgGuard`-gated (`cv-inspector.routes.ts`, spread into `app.routes.ts`
beside `VISION_PROFILES_ROUTES`) — a new fifth `vision` nav entry ("CV inspector", icon `chip`,
`MANAGE_ORG`), landing the manager-visible nav count at 21 (`nav-entries.spec.ts`'s own regression
guard updated in the same commit, +1 over W3's 20). `core/ui/architecture.spec.ts`'s `ROUTED_PAGES`
gained `cv-inspector/cv-inspector` — the new page injects only its facade, declares no bare-signal
overlay flag, and has a matching `cv-inspector-facade.ts`, guarded from day one like every other
routed page in that list.

**Save trace was deferred to W5.4** (below), landed in the same working session — the facade already
held every ledger a download would need (`gate`/`frame`/`world`, plus `selectedStreamId`), so that
step was purely additive, not a rework of anything built here.

### Tests / build

`npm run test:ci` — **204 files / 3955 tests, all green** (+1 file/+20 tests over W5.2's own
203/3935 — `cv-inspector-logic.spec.ts`'s 15 cases, `nav-entries.spec.ts` gained one new case and two
existing counts moved, `architecture.spec.ts`'s own `it.each` grew by one page × 3 invariants).

**Worktree-provisioning gap, disclosed once more for this step's own record**: this worktree's
`station/vision-web/node_modules` did not exist at all when W5.1 started this wave (a fresh `git
worktree add` checkout, never `npm install`ed) — `npm run test:ci` failed instantly with an unrelated-
looking `panic: aborting due to terminal initialize failure` (exit 134) before any code ever ran; fixed
once, in W5.1, with a plain `npm install` from the committed `package-lock.json`. Carried here only so
a reader who opens this file at W5.3 without having read W5.1's own section doesn't waste time on the
same red herring.

## Status — CV-ORCHESTRATION wave W5.4 (web): Save trace (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.8, §6 W5 row) — 2026-09-13

`CvInspectorPage`'s page bar gained a "Save trace" action (right beside the stream picker) that
downloads the currently-picked stream's whole in-memory `CvTrace` — `{streamId, gate, frame, world}`,
exactly the shape `GET .../cv/trace` itself returns — as one pretty-printed JSON file. This is wave
W5.5's own replay fixture: the plan's §4.8 names "Save trace" and "replay through trackeval" as one
continuous engineer workflow, so this file's shape is not incidental.

**Built from live signals, never a fresh fetch.** `CvInspectorFacade#saveTrace` reads `this.gate()`/
`this.frame()`/`this.world()` — the same signals the panels above it are already rendering — rather
than issuing a second `GET`. A second request could race the first and capture a different moment
than what the engineer is actually looking at when they click the button; reusing the live signals
means "what you see is what you get" is exact, not approximate.

**`canSaveTrace`/`saveTrace()` on the facade**, not the page. `canSaveTrace` is `false` until a stream
is picked (`selectedStreamId() !== undefined`) — the button binds `[disabled]` to `!canSaveTrace()`
rather than being hidden outright, so an engineer who picked a stream but sees an empty ring yet
still gets an honest, if empty, file rather than a puzzling missing button. `saveTrace()` itself
no-ops (never throws) when called with nothing picked, staying defensive against a future caller that
skips the `canSaveTrace` check.

**Filename + serialization are pure, in `cv-inspector-logic.ts`, not inline in the facade.**
`traceFileName(streamId, nowMs)` → `cv-trace-<streamId>-<timestamp>.json`, timestamped to the second
(`:`/`.` stripped from the ISO instant — `:` is a Windows path separator, `.` would read as a second,
spurious extension) so saving several traces across one session never silently overwrites an earlier
one. `serializeTrace(trace)` → `JSON.stringify(trace, null, 2)`, pretty-printed so the fixture stays
human-diffable. Both are unit-tested with no DOM/`Blob` involved (4 new cases: filename shape,
collision-freedom a second apart, a round-trip-through-`JSON.parse` equality check, and a
not-a-single-line pretty-print check).

**Download mechanics reuse the app's one existing convention**, not a new one: `URL.createObjectURL(new
Blob([...], {type: 'application/json'}))` → a synthetic `<a>` with `.href`/`.download` set → `.click()`
→ `URL.revokeObjectURL(url)` — the same idiom `features/onboarding/onboarding-facade.ts#downloadBlock`
already established, called directly rather than through that helper since it is typed around a
different (`ConfigBlock`) shape, not a generic "download this string" utility worth extracting for a
single second caller.

### Tests / build

`npm run test:ci` — **204 files / 3959 tests, all green** (+4 tests over W5.3's own 204/3955 — exactly
`cv-inspector-logic.spec.ts`'s new `traceFileName`/`serializeTrace` describe blocks; no new spec file,
so the file count is unchanged).

## Status — CV-ORCHESTRATION wave W5.7 (web): Live subscription — 2026-09-13

Follow-up requested on review of W5.0–W5.4: W5.3's stream picker tracked by `streamId` alone, so
`CvTraceStore.track()`'s optional `assetId` (its live-merge opt-in) was never passed and the
inspector ran poll-only — dead code on the server's own `cv-trace:<assetId>` topic, which exists
specifically so this page does not have to be poll-only. This wave fixes that properly rather than
re-disclosing it as a permanent scope cut.

**No already-loaded structure maps a stream to its owning asset.** Verified by reading, not assumed:
`ActiveStream` (`FleetStore.streams`) carries a `deviceId`, never an `assetId`; `FleetStore` itself
says so directly — `fleet-store.ts`'s own comment: "assets aren't tracked by a `FleetStore` [...]
signal"; `WallFacade`/`CommandFacade` (the only pages that already join a stream to an asset, via
`GET /api/fleet/summary`'s `AssetAttention.streamId`) are page-provided, not root-provided, so their
data is not loaded at all while `/manage/cv` is open. One `GET` per pick is therefore the documented
fallback the plan's own W5.7 review explicitly sanctioned — an engineer picks a stream rarely.

**`CvInspectorFacade#selectStream` is now asynchronous internally.** It still updates
`selectedStreamId`/clears the frame+track selection synchronously, but defers the actual
`CvTraceStore.track()` call to `trackWithResolvedAsset()`: one `VisionApi#fleetSummary()` fetch,
joined by the new pure `cv-inspector-logic.ts#assetIdForStream(summary, streamId)` — the identical
`AssetAttention.streamId` join `features/wall/wall-logic.ts` already performs to attribute a stream
tile to an asset, reused rather than re-derived so the inspector never disagrees with Wall/Command
about which asset a stream belongs to. Once resolved, `track(streamId, assetId)` runs — a single
call that starts the poll AND (when `assetId` resolved) the live topic together, never a second,
retargeting call that would flash the ring empty right after the first. A private
`assetResolutionToken` counter (bumped on every `selectStream()` call, including the `''` deselect)
drops a resolution that a later pick has since superseded — the same "compare a captured generation
before applying" guard `CvTraceStore.pollOnce` itself already uses for the identical race. A failed
`fleetSummary()` fetch (or a hit with no matching `AssetAttention` row) degrades to poll-only
(`assetId` left `undefined`) rather than blocking the inspector — `CvTraceStore`'s own 3s poll
(`POLL_INTERVAL_MS`, `core/cv-trace/cv-trace-store.ts`) is unconditionally authoritative either way
(W5.2/W5.3's deviation (2), unaffected by this wave).

**New `cv-inspector-facade.spec.ts` — the first facade-level spec in this app.** Every other facade
in `vision-web` is exercised only indirectly (through a `*-logic.ts` spec plus, at most, structural
checks in `core/ui/architecture.spec.ts`); proving "picking a stream subscribes to
`cv-trace:<assetId>`, switching releases the old one, leaving the page releases it" requires
observing a real side effect on `LiveStore`, which no pure logic function can do. The spec follows
`core/map/map-store.spec.ts`'s established "real store, stub only its own leaf deps" convention one
layer up: `CvTraceStore` and `CvInspectorFacade` are both the genuine classes; `VisionApi`,
`LiveStore`, `PollScheduler`, `FleetStore`, `SystemStatusStore` are the only stubbed leaves. Seven
cases: subscribe-on-pick (`live.trackCvTrace` called with the resolved `assetId`); degrade-on-miss
(no `AssetAttention` row names the stream); degrade-on-fetch-failure; switching releases the old
subscription before the new one starts (`untrackCvTrace` then `trackCvTrace`); a slow, superseded
resolution never tracks a stream the engineer already left; deselecting drops an in-flight
resolution; and leaving the page (`TestBed.resetTestingModule()`, which destroys `CvTraceStore` and
runs its pre-existing `DestroyRef.onDestroy` teardown — unmodified by this wave) releases the
subscription.

### Tests / build

`npm run test:ci` — **205 files / 3970 tests, all green** (+1 file/+11 tests over W5.4's own
204/3959 — `cv-inspector-facade.spec.ts` new, 7 cases; `cv-inspector-logic.spec.ts` gained
`assetIdForStream`'s own 4 cases).

## Status — CV-ORCHESTRATION wave W3.1 (web): `tracks:<assetId>` live topic — 2026-09-13

Small, disjoint-file wave inside the larger W3 web effort. Scope was exactly `core/api/models.ts`'s
`LiveEnvelope` union, `core/live/live-fallback-logic.ts`, `core/live/live-store.ts`,
`core/detections/detections-store.ts`, and their `.spec.ts` files — no component, no rendering. The
backend (already merged) added a live SSE topic `tracks:<assetId>` carrying a raw JSON array of
`WorldObjectResponse` (→ TS {@link WorldObject}, already mirrored in `models.ts` since wave W2.8) —
the world model's frame-cadence snapshot of every object it currently knows about for that asset,
richer than the flat `DetectionResult` the existing `detections:<assetId>` topic carries. **This
step only wires the transport — topic string, per-asset signal, and subscription lifecycle. Nothing
renders `WorldObject`/`RenderTier` yet; that's wave W3.2, a later, different step.**

**What changed:**

- `core/api/models.ts`: `LiveEnvelope` gained a 12th member, `{ seq, assetId, type: 'tracks',
  payload: readonly WorldObject[] }` — a **raw array**, not wrapped in an object, straight from
  `LiveUpdateRegistry`'s Java-side `List<WorldObjectResponse>`. Latest-wins, ring capacity 1
  server-side, the exact same pattern `detections`/`geo` already establish — no new semantics
  invented. The class doc comment gained a matching bullet stating plainly that this is
  **additive to, not a replacement for**, `DetectionsStore`'s existing poll of `GET
  /api/streams/{id}/tracks` (`trackTracks`/`tracks`) — that poll's other fields (`stats`, `latency`,
  `rate`, `follow`, `lockedTrackId`) have no live-topic equivalent yet, only `objects` does.
- `core/live/live-fallback-logic.ts`: new `tracksTopic(assetId)` → `` `tracks:${assetId}` ``,
  sitting right next to `detectionsTopic`/`geoTopic`, same opt-in ref-counted contract. One new test
  in `live-fallback-logic.spec.ts` (folded into the existing `telemetryTopic / detectionsTopic`
  describe block, renamed to include `tracksTopic`).
- `core/live/live-store.ts`: mirrors the `geo`/`detections` per-asset-signal machinery, but
  array-typed (mirrors `telemetrySignals`' array-default pattern, since `WorldObject[]` is a list,
  not a scalar-or-undefined). New private `worldObjectSignals` map (named to avoid any conceptual
  collision with `DetectionsStore`'s own, unrelated `tracks` poll signal) + private
  `worldObjectSignalFor(assetId)` helper, defaulting to `signal<readonly WorldObject[]>([])` — `[]`,
  not `undefined`, since "no objects known yet" and "confirmed zero objects" both correctly render
  as nothing to draw. Public surface: **`trackWorldObjects(assetId)`**, **`untrackWorldObjects(assetId)`**,
  **`worldObjectsFor(assetId): Signal<readonly WorldObject[]>`** — deliberately distinct names from
  `DetectionsStore.trackTracks`/`untrackTracks`/`tracks` (a different, pre-existing poll of the same
  endpoint's other fields) so nobody confuses "the poll" with "the new live SSE snapshot." New
  `applyEnvelope` case `'tracks'`: latest-wins `set()`, same one-line comment style as `'detections'`/
  `'geo'`. Class doc comment gained a 12th bullet in the topic list, same prose pattern as the
  existing `geo:<assetId>` bullet, citing this wave.
- `core/detections/detections-store.ts`: piggybacks the new subscription on `DetectionsStore`'s
  **existing detections-feed lifecycle** (`track()`/`teardownTracking()`), matching
  LIVE-POLL-RETIREMENT-PLAN's D1 demand rule ("only while a viewer is mounted") — not the separate
  tracks-poll lifecycle (`trackTracks`/`untrackTracks`), which stays untouched and independent.
  `track(streamId, assetId?)` now also calls `live.trackWorldObjects(assetId)` when an assetId is
  given; `teardownTracking()` now also calls `live.untrackWorldObjects(assetId)`. New public
  computed **`worldObjects: Signal<readonly WorldObject[]>`** — `[]` when no asset id is in scope or
  nothing has arrived yet; frame-cadence, live-only, no poll fallback (unlike `results` above) — an
  honest empty array is this store's answer for a viewer with no live connection, same posture as
  every other live-only signal in this app. Class doc comment gained a new, clearly separated
  paragraph describing this as a **third, independent thing** this store now exposes (distinct from
  both the detections-feed `results` and the tracks-poll `tracks`), consumed by wave W3.2 (not this
  one) for server-assigned render tiers.
- Tests: `detections-store.spec.ts`'s `stubLiveStore()` gained `trackWorldObjects`/
  `untrackWorldObjects`/`worldObjectsFor` stubs and a `pushWorldObjects(assetId, objects)` helper,
  mirroring the existing `detectionsFor`/`trackDetections`/`untrackDetections`/`pushResult` pattern
  exactly. Five new tests prove: (1) `track(streamId, assetId)` subscribes and a pushed array
  reflects on `worldObjects()`; (2) `track(streamId)` with no assetId never subscribes and
  `worldObjects()` stays `[]`; (3) `reset()` releases the world-objects subscription; (4) re-tracking
  a different assetId releases the old subscription and subscribes to the new one; (5) `worldObjects()`
  is fully independent of the tracks-**poll** lifecycle — `trackTracks()`/`untrackTracks()` never
  call `trackWorldObjects`/`untrackWorldObjects` and vice versa (mirrors the pre-existing "the tracks
  poll is fully independent of the detections feed" test's structure).

**Not built (by this wave's own design):** no component reads `worldObjects`, no renderer, no
`RenderTier`-driven overlay. This step is wiring only — the topic, the signal, and the subscription
lifecycle — exactly as scoped. `core/api/models.ts`'s `'cv-trace'` `LiveEnvelope` member (a different
wave's territory) does not exist yet on this branch, so there was nothing to avoid touching there in
practice.

**Nothing in this brief was found wrong or unimplementable.**

**Superseded in part by wave W9** (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.9, §8 decision E25):
the `tracks:<assetId>` payload described above as "a raw JSON array of `WorldObjectResponse`" is, as
of wave W9, the whole `StreamTracksResponse` snapshot (`stats`/`latency`/`rate`/`detectionState`/
`follow`/`lockedTrackId` alongside `tracks`/`objects`) — the exact fields this section's own text
said "have no live-topic equivalent yet" now do. `LiveStore.worldObjectsFor(assetId)` still returns
exactly the array this section describes (now derived from `payload.objects` via a cached
`computed()`, same signature/behavior) — it is no longer the payload itself; the new
`LiveStore.tracksFor(assetId): Signal<StreamTracksResponse | null>` is. `DetectionsStore.tracks` (the
poll this section says stays "additive to, not a replacement for" the topic) is now transport-aware
exactly like `results` and only polls as a fallback. See wave W9's own dated section near the end of
this file for the full change.

### Tests / build

`npm run test:ci` — **200/200 files, 3917/3917 tests green** (this worktree's branch already carried
200/3912 before this step, measured directly by reverting this wave's own six touched files to `HEAD`
and re-running — the true local baseline, since `MODULE.md`'s last recorded wave-W1 entry above
(198/3892) predates this branch's already-merged wave-W2.8 work, as that entry's own "Superseded in
part by wave W2.8" paragraph already discloses; +5 tests are this step's own five new
`detections-store.spec.ts` cases, 0 new files — the `tracksTopic` coverage was folded into an
existing `it()` in `live-fallback-logic.spec.ts` rather than adding a new one). `npx tsc --noEmit`
clean on both `tsconfig.app.json` and `tsconfig.spec.json`. `ng build --configuration production` not
run this wave (out of this brief's required verify chain — pure core/-layer wiring, no template or
route change to bundle-measure).

## Status — CV-ORCHESTRATION wave W3.2 (web): render tier + tracked-object motion/label from the wire — 2026-09-13

Follows directly on wave W3.1 above — that wave wired the `tracks:<assetId>` live topic and
`DetectionsStore.worldObjects()` but rendered nothing; this wave is where the canvas overlay, the
detections strip, and the crop-follow HUD stop re-deriving render tier, elected label, and
tracked-object motion client-side and read them off the `WorldObject`/`RenderTier` wire data instead
(docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.6/§6 W3 row: "delete client extrapolation-for-tracked
and `electStickyLabels`... render tier from server... no client re-derivation of velocity or label for
tracked objects (grep test)"). Scope was exactly `shared/player/**` plus the five `<vision-player>`
host templates that bind `[detections]` — no `core/api/models.ts`, no `core/live/**`, no
`core/detections/**`, no Java, matching the plan's own disjoint-wave file split.

**What changed:**

- `shared/player/detection-overlay-logic.ts`:
  - New `worldObjectsByTrackId(worldObjects): ReadonlyMap<number, WorldObject>`, keyed by
    `state.id` — the one lookup every consumer below shares, so a box, its tier, and its strip chip
    can never disagree about which `WorldObject` a track id resolves to.
  - New `resolveDisplayDetections(selected, previous, targetMs, worldObjectsById, maxExtrapolationMs?,
    matchGate?)` replaces the old `extrapolateDetections` + `electStickyLabels` + `applyStickyLabels`
    trio as `player.ts`'s call site. Partitions `selected.detections` into three groups per detection:
    **matched + visible** (`box = state.kinematics?.predictedBox ?? state.kinematics?.box ??
    detection.box`, `label = state.identity?.label ?? detection.label` — reads the wire directly, no
    client motion or label logic at all), **matched + `render.tier === 'HIDDEN'`** (dropped from the
    returned array entirely — not merely left off a tier map, so nothing downstream can accidentally
    draw a suppressed box), and **unmatched** (no track id, or a track id with no `WorldObject` yet —
    the transient pre-`tracks:`-arrival gap, see disclosure below) — the unmatched subset alone still
    runs through the unchanged `matchDetections`/{@link projectUnmatchedOne} projection machinery
    (still fed the *full* `previous`/`targetMs`, just a smaller `detections` array), then all three
    groups are reassembled in original order.
  - New `resolveDetectionTiers(displayDetections, worldObjectsById, context): ReadonlyMap<Detection,
    DetectionTier>` replaces the direct `detectionTiers(...)` call. A matched detection's tier is
    `'T0'` on hover-override (unchanged client concern — momentary UI feedback, not a wire fact) else
    `worldObject.render.tier` cast to `DetectionTier` (server already resolves FOLLOW-lock T0 itself,
    per W2.8 — only hover still needs a client override); unmatched detections still run through the
    unchanged `detectionTiers` priority-tier function (T0 hover/lock > T3 sub-scale > T1 moving/top-K/
    hovered-class > T2 everything else, research §3.2 — untouched this wave).
  - **Renamed** (report's required exact final names): `extrapolateOne` → **`projectUnmatchedOne`**;
    `EXTRAPOLATION_MATCH_GATE` → **`UNMATCHED_MATCH_GATE_DISTANCE`** (value `0.15`, unchanged).
    `EXTRAPOLATION_MAX_MS` keeps its name (still the right name for what it now exclusively bounds:
    the unmatched subset's own forward-projection horizon). `extrapolateDetections` keeps its name and
    signature, now called only from inside `resolveDisplayDetections` on the unmatched subset.
  - **Fully deleted**: `electStickyLabels`, `electFromObservations`, `applyStickyLabels`,
    `STICKY_LABEL_VOTE_WINDOW`, `STICKY_LABEL_SWITCH_MARGIN`, `STICKY_LABEL_SWITCH_STREAK`, the
    `TrackObservation` interface, and the "Sticky labels per track" section's own essay-length header
    comment — replaced by a short note (in the new section's own header comment) citing this wave and
    `WorldModel`/`ObjectState.identity` as the label's new source of truth, not restating the deleted
    election algorithm's mechanics.
- `shared/player/player.ts`: new `readonly worldObjects = input<readonly WorldObject[]>([]);`
  (doc-commented like `lockedTrackId`/`hoveredClass` — a host that never binds it degrades honestly to
  every detection running the unmatched/local-projection path, exactly today's pre-W3.2 behavior, never
  a blocked page). `drawDetections` now computes `worldObjectsById = worldObjectsByTrackId(this.
  worldObjects())` once per frame and calls the two new resolve functions in place of the deleted
  four-call sequence. Every downstream use of `displayed`/`tiers` (the `t0TrackIds` loop, `trailResults`,
  `drawOrder`, the `tiers.get(detection) ?? 'T2'` fallbacks) is untouched — it already assumed a
  HIDDEN-filtered, fully-tier-covered list by construction, which now holds by construction one layer
  earlier instead of by a second explicit check at each site.
- `shared/player/detections-strip-logic.ts` — **not named in the original wave brief, found and
  migrated in this same wave because it also imported `electStickyLabels`** (its own label-grouping
  step) and would otherwise have kept a second, independent re-implementation of server-side label
  election alive, defeating the point of the new grep spec below. `displayLabel(detection,
  worldObjectsById)` replaces the old sticky-label call: a tracked detection groups under
  `worldObjectsById.get(trackId)?.state.identity?.label ?? detection.label` — the identical lookup the
  canvas overlay now uses, so a strip chip's label and the box it corresponds to can never quietly
  disagree. `stripChips` gained `worldObjectsById: ReadonlyMap<number, WorldObject> = new Map()` as its
  new 2nd parameter (before `labelDenyFilter`/`cap`/`windowSeconds`) — defaults to empty, the honest
  choice for a caller with no per-asset `worldObjects()` to thread in (or an asset that hasn't produced
  any yet): every label used raw, unchanged from before this wave. Documented as a **per-current-track**
  lookup, not a historical replay: a track's label within the strip's aggregation window is whatever the
  wire says *right now*, not what it was when each historical batch in the window was actually captured
  — there is no retained per-batch world-object snapshot to do better than that.
- `shared/player/detections-strip.ts`: `chips` computed now threads `worldObjectsByTrackId(this.store.
  worldObjects())` into `stripChips`. Class doc comment's "Click" bullet reworded: a PATCH now sends the
  wire-elected label an operator is looking at, not cv-service's raw per-frame label; a track with no
  world object yet still shows (and can be denied by) its raw label for that one transient beat.
- `shared/player/no-client-rederivation.spec.ts` (**new**) — a plain vitest test, no `TestBed`, per the
  plan's own "no client re-derivation of velocity or label for tracked objects (grep test)" requirement.
  Recursively scans every non-`.spec.ts` `.ts` file under `shared/player/` (`follow-hud/` included) and
  fails if `extrapolateOne`, `electStickyLabels`, `STICKY_LABEL_`, or `EXTRAPOLATION_MATCH_GATE`
  reappears anywhere — `.spec.ts` files are excluded so this file's own doc comment (and any other
  spec's historical prose) can still name the retired identifiers without self-tripping. **Sanity-
  checked**: a forbidden string was temporarily appended to `player.ts`, confirmed the spec fails
  (`AssertionError: expected [ Array(1) ] to deeply equal []`, naming the exact file/substring), then
  removed before this commit — the diff this wave ships contains no such marker.
  - **Node-builtins gotcha**: this project has no `@types/node` (not a dependency, and this wave's own
    build instructions say not to add one), so `fs`/`path`/`url` have no ambient types under
    `tsconfig.spec.json`'s `types: ["vitest/globals"]`. New `shared/player/node-builtins.d.ts` supplies
    minimal ambient declarations for just the handful of members this one spec calls. It has to be a
    standalone global-script `.d.ts` (no top-level `import`/`export` of its own) rather than declared
    inline in the spec file — TypeScript treats `declare module '...'` written inside a file that
    already has its own imports (a "module" file, which the spec is, importing from `vitest`) as an
    *augmentation* of an existing module, which fails with `TS2664` for `fs`/`path`/`url` (bare or
    `node:`-prefixed) since nothing declares them without `@types/node`; a global script `.d.ts` has no
    such restriction. Also found: `import.meta.url` is a genuine `file://` URL at runtime under this
    builder, but passing `new URL('.', import.meta.url)` into `fileURLToPath` threw `TypeError: The URL
    must be of scheme file` (a realm/identity mismatch between the WHATWG `URL` global available here and
    the one Node's real `url` module expects) — fixed by passing the raw URL string straight to
    `fileURLToPath` and taking `dirname()` of the result instead of constructing a new `URL`.
- Host wiring — `[worldObjects]="..."` added immediately after each host's existing `[detections]`
  binding, matching that file's own binding style exactly:
  - `features/fly/cockpit.html` — `[worldObjects]="facade.detections.worldObjects()"`
  - `features/live/live.html` — `[worldObjects]="facade.detections.worldObjects()"`
  - `features/crew/crew.html` — `[worldObjects]="facade.detections.worldObjects()"`
  - `features/wall/wall-focus.html` — `[worldObjects]="detections.worldObjects()"`
  - `features/wall/wall-tile.html` — `[worldObjects]="detections.worldObjects()"`
  - `features/command/asset-panel.html` — **left untouched**: it has no `[detections]` binding at all
    (no overlay on that surface), so there is nothing to pair a `[worldObjects]` binding with.

**Disclosure — the sticky-label transient-gap regression (my own finding, not in the wave brief):** a
genuinely-tracked detection (`detection.track.id` set) that has no matching `WorldObject` yet — the
narrow window between a track first appearing in a `detections:<assetId>` batch and its first
`tracks:<assetId>` snapshot arriving — used to get the old `electStickyLabels` confidence-weighted,
switch-margin/streak-gated smoothing across historical batches. After this wave it falls into the
"unmatched" bucket and shows whatever raw label that one batch's detector produced, with no
cross-batch smoothing, until the world object arrives (typically within one `tracks:` cadence tick).
**Judged acceptable**: the gap is bounded and self-resolving (it closes the moment the world object
shows up, at which point every downstream reader — overlay box, tier, and strip chip alike — converges
on the server's own elected label simultaneously); the alternative of keeping a second, client-side
election algorithm running *only* for this narrow window would resurrect exactly the duplicated-logic
risk this wave exists to remove, for a cosmetic flicker lasting at most one tracking cadence tick on a
detection that, by definition, has no confirmed track history to smooth from yet.

**Not built / not touched:** `core/api/models.ts`, `core/live/**`, `core/detections/**`, any Java,
`features/cv-inspector/`, `core/cv-trace/`, and the `'cv-trace'` `LiveEnvelope` member — all other
waves' territory, per this wave's own file-scope allowlist. `features/command/asset-panel.html` — see
above. No dev-parity concern arises: `worldObjects()` is fed purely by the live SSE topic wired in
W3.1, which has no dependency on `vision.auth.enabled`; a dev-admin session with auth disabled sees the
exact same wire-driven boxes/labels/tiers as any other session once its `tracks:` subscription is live,
and degrades to the pre-W3.2 unmatched/local-projection path identically to any other session if it
never arrives.

### Tests / build

`npm run test:ci` — **201/201 files, 3940/3940 tests green**. This wave's own isolated contribution
(measured file-by-file against this wave's own `HEAD` versions, since a concurrent, still-in-progress
sibling wave shares this worktree and also added tests elsewhere): `detection-overlay-logic.spec.ts`
131 → 133 `it()`s (+2 net — the sticky-label describe blocks removed, `worldObjectsByTrackId`/
`resolveDisplayDetections`/`resolveDetectionTiers` describe blocks added), `detections-strip-logic.
spec.ts` 12 → 16 (+4 net), `no-client-rederivation.spec.ts` new (+1 file, +1 test) — **this wave's own
delta is +1 file / +7 tests** against wave W3.1's recorded 200/3917 baseline above; the remaining +23
tests/+0 files beyond that (3917 → 3940, 200 → 201 once this wave's own +1/+7 is subtracted) come from
the concurrently-running, unrelated `vision-profiles`/`cv-control-panel-logic` wave sharing this same
worktree, not from this wave's diff. All 10 spec files under `shared/player/` pass in isolation (350
tests) as well as inside the full run. `npx tsc --noEmit` clean on both `tsconfig.app.json` and
`tsconfig.spec.json`.

`ng build --configuration production` — green, same two pre-existing warnings only (initial bundle
over its 390 kB budget; `tactical-map.css` over its 11 kB component-style budget — both present before
this branch). Raw measured total at this wave's own tree state: initial **436.41 kB raw / 122.32 kB
transfer**. **No isolated before/after bundle delta for this wave alone was captured** — the standard
method (a `git stash` scoped to this wave's own touched files, rebuild, restore, rebuild again) was
judged too risky to run safely while a sibling agent has active uncommitted edits in this same shared
worktree (the exact same call this file's own ALWAYS-ON-FLOW wave-C entry above made in an identical
situation: "needed a tree-wide `git stash`, correctly refused while other waves were uncommitted").
This wave's own real cost is expected to be small and template/logic-only (one new `input()`, two
renamed/added pure functions, no new dependency, no new component) inside the already-lazy `cockpit`/
`live`/`crew`/`wall` chunks, never the initial bundle.

**Nothing in this wave's brief was found wrong or unimplementable.**

- **2026-09-13, docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.7/§6, wave W3.6 (intent picker + resolved sources on `/vision/profiles`, `cv.detection-policy=ALWAYS` control):** built entirely inside `features/vision-profiles/**` plus one additive `core/api/models.ts` edit, on a shared tree with a concurrent unrelated agent editing `shared/player/**` and five `<vision-player>` host templates at the same time (confirmed disjoint throughout via `git status` — never touched).
  - **Closes a real, previously-undocumented TS-mirror gap**: `CvProfile` (`core/api/models.ts`) had no `sources` field at all, even though `dto.CvProfileResponse` has carried one since wave W3.0 (`fieldSources()`/`Sources` record) — W3's own plan row never called this out. Added `readonly sources: CvProfileSources` as **required**, not optional, because the wire always sends it (see next bullet) and an optional field would let a caller silently forget to read it.
  - **New types** (`core/api/models.ts`, ~line 400-535): `CvProfileIntent = 'PEOPLE'|'VEHICLES'|'EVERYTHING'|'CUSTOM'` (mirrors `domain.model.Intent`); `CvProfileSources { readonly model?: 'INTENT'; readonly labelFilter?: 'INTENT'; }`. `CvProfileRequest` gained optional `intent?: CvProfileIntent` (absent-means-skip-resolution, the identical convention W3.0 established for `UpdateStreamConfigRequest#intent`).
  - **`sources` JSON-shape finding — verified, not assumed, and not a new independent claim**: read `CvProfileResponse.java` directly — the class carries `@JsonInclude(NON_NULL)` and every `Sources` field/record is populated via `Sources.none()`/`Sources.from(...)`, never left as a bare `null` reference; `Jackson NON_NULL` suppresses a `null`-valued field but **not** a non-null nested object whose own fields are null, so an all-absent `Sources{model:null,labelFilter:null}` still serializes as a present `"sources":{}`, never an omitted key. Cross-checked all 5 `CvProfileController` call sites (list/get/create/update/effective) via `grep -n "CvProfileResponse.from\|Sources.none\|fieldSources"` — every one passes a non-null `Sources`. This is static-code verification (reading the Java source and its call sites), not a written/run test, and it is **the same finding W3.0 already made** for the sibling `UpdateStreamConfigResponse.sources` — `CvProfileSources` reuses that exact `{model?, labelFilter?}` shape rather than asserting anything new.
  - **Pure logic** (`vision-profiles-logic.ts` + spec): `ProfileDraft` gained `intent: CvProfileIntent | ''` — defaulted to `''` in `emptyProfileDraft`/`draftFromProfile`/`forkDraftFromProfile` and **never** inferred from a loaded profile's `sources` (a saved profile's `sources` describes what the *last save* resolved, not an editable intent the operator is now choosing — re-inferring it would silently re-apply a stale resolution on every open). New `applyIntentToDraft(draft, intent)`: blanks `model` only on the `'' → <intent>` transition; changing between two different intents, or an intent an operator has since typed a model over, never re-blanks it — the model stays whatever the operator's last explicit choice was, checked by 4 new tests covering exactly the blank/sticky/revert transitions. `labelFilter` needed no equivalent special-casing: `parseLabelList('') === []` already round-trips as the "leave to platform" sentinel the server checks for. `validateDraft` relaxed the model-required check to skip when an intent is chosen, and gained a friendly pre-submit error — `'Custom intent needs at least one class in the label filter.'` — when `intent === 'CUSTOM'` and the label filter parses empty, mirroring `IntentPolicyResolver.resolve`'s Java exception instead of letting the operator hit a raw 400. `draftToRequest` maps `''` → `undefined` for `intent`. New `saveOutcomeMessage(saved, intent, isUpdate)`/`describeIntent(intent)`: the post-save toast names exactly which knob(s) (`model`/`label filter`) were resolved from intent by reading `saved.sources` off the real response — it never claims a knob was intent-resolved when its `sources` entry is absent, and reads as a plain `"X" saved.`/`"X" created.` when no intent was chosen at all.
  - **UI** (`vision-profiles.html`/`vision-profiles-facade.ts`): a new Intent `<select>` (none/People/Vehicles/Everything/Custom) ahead of the Model field, same `<select>` styling as every other form control on this page, wired through `facade.setIntent(intent)` → `applyIntentToDraft`. The Model select's blank option is now genuinely selectable and relabeled **"Resolved from intent"** only while an intent is chosen; with no intent chosen it reverts to exactly today's behavior — disabled, required, labeled "Choose a model…" — so an operator who never touches Intent sees no change at all. `saveDraft()` now captures the create/update response and feeds it to `saveOutcomeMessage` for the enriched toast.
  - **D7 (`cv.detection-policy=ALWAYS`)**: placed inside the Bindings panel's existing `@case ('ASSET')` scope branch, gated on an asset actually being selected (`facade.bindingScopeId()`) — the only per-asset-detail spot already on this page, so no new page section was invented for one checkbox. Storage fact confirmed by reading `DetectionPolicy.java`: `Asset#attributes['cv.detection-policy']`, parsed case-insensitive/trimmed but written canonically **lowercase** `"always"`/`"on-view"` (uppercase silently fails to parse) — `withDetectionPolicy`/`isDetectionAlways` (new, `vision-profiles-logic.ts`) enforce the lowercase write and case-insensitive read. Because `AssetEdit.attributes` (`VisionApi#updateAsset`, `PATCH /api/assets/{id}`) **replaces the whole attributes map wholesale**, the facade does a read-merge-write: `setBindingScopeId` triggers a fresh `getAsset(assetId)` (never the page's already-loaded `AssetSummary.attributes` off the roster — that could be stale from page-load time, and this checkbox must reflect the asset's *live* value), and `toggleDetectionAlways()` merges the new key into that fetched map before PATCHing. The checkbox never shows an invented/optimistic state before the fetch resolves: `facade.detectionAlwaysOn()` is `true`/`false`/`null` (loading/unknown), the checkbox is `[disabled]` while `null` and shows "(loading…)", and a stale response is dropped by re-checking the scope kind/id still matches after the `await` before applying it.
  - **One disclosed, minimal, out-of-strict-scope fix**: `features/fly/cv-control-panel-logic.spec.ts`'s `cvProfile()` test fixture built a full `CvProfile` object literal with no `sources` field; making `CvProfile.sources` required (this wave's own deliberate design, not an accident) broke `tsc --noEmit -p tsconfig.spec.json` there with `TS2322`. Fixed by adding one line, `sources: {},` (with a comment explaining a plain read with no originating create/update request reports none) — chosen over making `sources` optional, which would have avoided touching the file but contradicted this wave's explicit instruction to model the always-present shape honestly. No other line in that file was touched.
  - **Role-gating**: unchanged — write actions (including the new intent field and the D7 checkbox) still gate on the same `canManageOrg` the rest of this page's mutations already use; `/vision/profiles` stays `orgGuard`-routed and rail-gated `managerOnly`, so a pilot never sees any of this wave's new affordances.
  - **Dev parity**: unaffected — `vision.auth.enabled=false`'s dev admin resolves ADMIN/unbounded, so every gate above passes exactly as it does for a real manager; no new auth-conditional branch was added.
  - **Degrades honestly**: a failed `getAsset` read for D7 leaves `detectionAlwaysOn()` at `null` (rendered as "(loading…)", disabled) rather than guessing off/on; a failed `saveDraft()` still surfaces through the existing `describeHttpError` → toast path unchanged; the save-outcome toast never fabricates an intent-resolution claim the response's own `sources` didn't make.
  - **Foreign/pre-existing, not mine**: `shared/player/no-client-rederivation.spec.ts` (untracked, actively being authored by the concurrent unrelated agent this wave was told to ignore) fails both `tsc --noEmit -p tsconfig.spec.json` (no `@types/node` configured for its `node:fs`/`node:path`/`node:url` imports) and at runtime ("The URL must be of scheme file") — confirmed live/in-progress (content changed between checks), confirmed unrelated to any file this wave touched, left exactly as found.
  - **Verify chain**: `npx tsc --noEmit -p tsconfig.app.json` — 0 errors. `npx tsc --noEmit -p tsconfig.spec.json` — 0 errors, including the foreign `shared/player/no-client-rederivation.spec.ts` (its 3 errors, present mid-wave while that concurrent agent's work was still in flight, were gone by this wave's final verify pass — resolved by that other agent, not by any file this wave touched). `npm run test:ci` — **201/201 files, 3940/3940 tests passing** (this wave's own contribution: +16 new tests / 0 new files, all in `vision-profiles-logic.spec.ts`: intent-draft defaults/never-inferred, `applyIntentToDraft`'s 4 blank/sticky/revert cases, `validateDraft`'s blank-model-with-intent + CUSTOM-needs-label-filter cases, `draftToRequest`'s intent-omitted/intent-carried cases, `saveOutcomeMessage`'s 4 cases, `describeIntent`, and the D7 `isDetectionAlways`/`withDetectionPolicy` describe block including case-insensitivity and merge-not-replace; confirmed via a pathspec-scoped `git stash push -u -- <this wave's 6 files>` baseline/restore, never a bare `git stash`, that reverting only this wave's files dropped the count by exactly 16 with no other file's tests affected). A mid-wave snapshot briefly saw 200/201 files with a foreign `shared/player` failure — gone by this final pass, confirming it was never this wave's own. `ng build --configuration production` — green, same one pre-existing budget warning only (initial bundle 46.41 kB over its 390 kB budget, pre-existing, not from this wave); **bundle delta** (same stash-based before/after method): initial bundle unchanged at 436.41 kB raw / 122.32 kB transfer (`vision-profiles` is lazy-loaded, so none of this wave's additions are eager); lazy chunk **`vision-profiles` 32.43 kB → 36.35 kB raw (+3.92 kB), 7.47 kB → 8.37 kB transfer (+0.90 kB)**.
  - **Commit**: `feat(cv-orchestration W3.6): intent picker + resolved sources on /vision/profiles, detection-policy control` — this wave's launching task explicitly required this one commit (unlike every prior wave's own "not committed, staged-ready" note above), with an exact trailer overriding this session's own default attribution; see the commit itself for the final trailer text used.

## Status — CV-ORCHESTRATION wave W3.4 (web): "Tuning" modal — drop the tracking-mode picker, resolved-source lines (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.7, §9 decision E19/#4, §6 W3 row) — 2026-09-13

Pure copy/removal/addition inside `CvSetupModal` (the same component, selector, and file names —
this is a UX rename, not a refactor). Built on a shared tree with a concurrent sibling agent (W3.3)
actively editing `core/api/models.ts`, `cockpit.html`, `cockpit-facade.ts`, `fly-logic.ts`,
`cv-control-panel-logic.ts`/`.spec.ts`, and `shared/player/**` throughout this wave — confirmed
disjoint the whole time via `git status`/`git diff --stat`, never touched.

- **Part A — renamed to "Tuning"**: `cv-setup-modal.html`'s `aria-label`, `<h2>`, and close-button
  `aria-label` all say "Tuning" now (`aria-label="Close Tuning"`); `cv-control-panel.html`'s door
  button reads `Tuning…`. Every doc-comment in `cv-setup-modal.ts`/`cv-control-panel.ts`/
  `cv-control-panel.html` that named "Detection setup" prose was updated to "Tuning" alongside a
  pointer to this wave — component class name (`CvSetupModal`), selector (`vision-cv-setup-modal`),
  file names, method names (`requestCvSetup`-shaped ones), and every CSS class encoding "setup"
  (`.cv-setup-button`, `.cv-setup-footer`, `dialog.open('cv-setup')`) are all **untouched**, exactly
  as scoped, so `cockpit.ts`'s import and `cockpit.html`'s usage site needed zero changes.
  `cockpit.html` itself still says "Detection setup…" in its own comment prose (lines ~402, ~670) —
  out of this wave's file scope (a sibling agent owns that file concurrently this run); left exactly
  as found, disclosed here rather than risk-edited.
- **Part B — E19, tracking-mode picker removed**: deleted the whole Off/Associate/Follow segmented
  control section from `cv-setup-modal.html` (markup + its description switch), `onTrackingMode`
  from `cv-setup-modal.ts`, and the `buildTrackingModePatch` import (the function itself is
  untouched in `cv-control-panel-logic.ts` — out of this wave's file scope, and nothing else in this
  component tree called it, confirmed by grep before deleting the import).
  - **The now-possibly-dangling Expert gate**: `trackingMode` used to be a `signal<TrackingMode>`
    written only by the deleted picker and read by three things — the Expert disclosure's capability-
    ceiling/engine gate (`trackingMode() !== 'OFF'`), the Follow-only verify/follow-fps gate
    (`trackingMode() === 'FOLLOW'`), and `engineOptionsForMode`'s own mode argument. Rather than
    leaving a dangling write-only signal or inventing a fake mode, `trackingMode` became a
    `computed<TrackingMode>(() => this.detections.tracks()?.stats?.mode ?? 'OFF')` — reading the
    **actual running mode already available on this component** via the tracks poll's own `stats`
    (the exact fact the pre-existing constructor `effect` was already syncing the old signal *from*,
    every poll tick — so this is strictly less code, not new plumbing). All three read sites keep
    working unchanged, now driven by server truth instead of a client write. `'OFF'` before the
    first poll ever reports `stats` is the honest default (nothing known to be running yet). Server-
    side confirmation (read-only, no Java touched): `TrackingConfig.java` — `PipelineConfig#defaults()`
    ships `TrackingConfig.associate()`, i.e. ASSOCIATE is already the running default whenever
    detection is on, matching E19's own text.
  - The `trackingEngineId` sync effect keeps running (Engine stays an Expert-tier control per §4.7's
    table — only the mode picker is removed) — split out of the effect that used to set both fields
    together, since `trackingMode` no longer needs an effect to write it at all.
- **Memory/cached-tracking toggle — confirmed no-op, not found anywhere**: grepped
  case-insensitively for `memory`/`cached`/`remember` across all of `features/fly/` before touching
  anything. Every hit is the unrelated "remembered streaming asset" navigation concept
  (`fly-redirect-guard.ts`, `fly-logic.ts#rememberedStreamingAssetId`, `cockpit-facade.ts`'s "last
  flown" bookmark) — a different feature entirely, not a CV tracking-memory switch. No such toggle
  exists in this component or any sibling in this tree to remove; per this wave's own instructions,
  nothing was invented to then delete. This is the same finding prior CV-ORCHESTRATION research
  already made — confirmed again here, independently, before acting.
- **Part C — resolved-source lines**: new `cv-setup-modal-logic.ts` (+ `.spec.ts`, 8 tests) — this
  component previously delegated all its pure logic to the shared `cv-control-panel-logic.ts` (out
  of this wave's file scope, shared with the sibling `cv-control-panel.ts`); a new sibling `-logic.ts`
  file keeps this Tuning-modal-only concept out of that shared file rather than risk-editing it.
  `resolvedSourceLine(sources: CvProfileSources | undefined, profileSource: EffectiveCvProfile['source']
  | undefined, knob: 'model' | 'labelFilter' | 'confidenceThreshold'): string | null` — precedence:
  (1) `sources[knob] === 'INTENT'` (only representable for `model`/`labelFilter`; `confidenceThreshold`
  never checks — `CvProfileSources` has no such field, since request-time intent resolution never
  seeds confidence, §4.9's "As built in W2" table) → `"Resolved from your intent pick"`; (2) else
  `profileSource` present → `"From your asset/category/organization profile"` or `"Platform default"`
  for `'PLATFORM'`; (3) else `null` (never a fabricated source). New input
  `readonly lastConfigSources = input<CvProfileSources | undefined>(undefined);` — **always
  `undefined` today**: no host template binds it yet (wave W3.3's own concern, landing concurrently
  in this exact worktree); every resolved-source line below therefore falls through to the
  profile-tier fact or renders nothing, never a blocked page. Wired as two new computeds,
  `confidenceSourceLine`/`classesSourceLine`, rendered as an extra `<p class="muted hint">` line —
  next to the Confidence slider, and alongside (never replacing) Classes' existing "Applied: N of the
  classes…" line.
  - **Ambiguous intent-name decision**: `CvProfileSources` only reports the tag `'INTENT'`, never
    *which* intent resolved a knob, and this component has no chosen-intent value of its own to name
    one with. Chose **option (b)** from the brief — phrased the sentence without naming the specific
    intent (`"Resolved from your intent pick"`) — over adding a second speculative input this wave
    has nothing yet to wire (it would sit dead until a sibling wave lands a value into it, exactly
    the kind of premature plumbing CLAUDE.md rule 10 warns against).
  - Deliberately **did not** add a resolved-source line to the "Looking for" model/intent-card
    section, even though the brief allowed it optionally — see next bullet.
- **Disclosed, not fixed — the "Looking for" overlap**: `cv-setup-modal.html`'s pre-existing "Looking
  for" intent-card grid (`models()`-roster-driven, `onModelChange`) is a different, older mechanism
  from the newer server-resolved `CvProfileIntent` enum (wave W3.0/W3.6's `/vision/profiles` picker).
  Neither reads nor writes the other; this card grid never shows a resolved-source line, even though
  §4.7's table describes an eventual "resolved from intent People (platform)" reading exactly there.
  Left exactly as it was — noted in the template's own comment block and here — a known UX
  inconsistency for a future wave to merge or reconcile, not this one's brief.
- **Role-gating / dev parity**: unaffected by this wave — no new gate, no auth-conditional branch;
  the existing `canManage` input (already threaded from `CockpitFacade#canManage`) is untouched, and
  `vision.auth.enabled=false`'s dev admin sees identical behavior to any other ADMIN session, exactly
  as before this wave.
- **Degrades honestly**: `lastConfigSources` absent → every resolved-source line either falls to the
  profile-tier fact or renders nothing, never a guess; the removed tracking-mode picker's read side
  (`trackingMode`) defaults to `'OFF'` before any poll ever reports `stats`, never a fabricated
  "running" state.
- **Foreign/pre-existing, not mine**: at this wave's final verify pass, `npm run test:ci` showed
  **1 failing test** in `shared/player/detection-overlay-logic.spec.ts` — confirmed via
  `git diff --stat` to be a brand-new `it()` (`'an untracked-box hit resolves to "point"…'`) the
  concurrent sibling agent (W3.3, working on click-to-follow per its own new untracked
  `click-to-follow.spec.ts` in this same tree) was actively adding to a file this wave never touched;
  present both times `test:ci` was run a minute apart. Left exactly as found — same "confirmed
  unrelated, not this wave's" call as W3.6's own entry above made for a different foreign file.
- **Verify chain**: `npx tsc --noEmit -p tsconfig.app.json` — 0 errors. `npx tsc --noEmit -p
  tsconfig.spec.json` — 0 errors. `npm run test:ci` — **202 files / 3948 tests, all green**, measured
  immediately after this wave's own 6 files were the *only* uncommitted change in the tree (before
  the sibling's concurrent edits resumed); this wave's own isolated contribution is exactly **+1 file
  / +8 tests** (`cv-setup-modal-logic.spec.ts`) against wave W3.6's recorded 201/3940 baseline above.
  A later, final-state run (with the sibling's own further concurrent edits back in the tree)
  reported 203 files / 3960 tests with the 1 foreign failure noted above — that delta (203−202 files,
  3960−3948 tests, 1 failing) is entirely the sibling's own in-flight work, not this wave's.
  `ng build --configuration production` — green, same two pre-existing budget warnings only (initial
  bundle over its 390 kB budget; `tactical-map.css` over its 11 kB component budget). **Bundle
  delta** (pathspec-scoped `git stash push -u -- <this wave's 6 files>`, confirmed via
  `git stash list --format='%H %gs'` and restored via `git stash apply <sha>` + `git stash drop`,
  never a bare `stash`/`pop`, exactly the precedent this file's own W3.6 entry set): measured in a
  tight ~90-second window, lazy chunk **`cockpit` 138.93 kB → 138.69 kB raw (−0.24 kB), 29.31 kB →
  29.28 kB transfer (−0.03 kB)** — a net *decrease*, expected since this wave deletes a whole picker
  section/method/import and adds only two small computeds plus one new logic file. The eager
  **initial bundle wobbled by ~0.3 kB raw across builds taken minutes apart** (436.41–436.72 kB) —
  attributable to the concurrently-editing sibling's own in-flight changes to `cockpit.html`/
  `cockpit-facade.ts`/`models.ts` (none of which this wave touched), not to anything in this diff;
  `cv-setup-modal`/`cv-control-panel` are both inside the lazy `cockpit` chunk and were never eager
  before or after.
- **Commit**: `feat(cv-orchestration W3.4): Tuning modal — drop the tracking-mode picker,
  resolved-source lines`.

## Status — CV-ORCHESTRATION wave W3.3 (web): intent chips + one honest status line on the Fly hero (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.7/§4.8, §6 W3 row) — 2026-09-13

Built on the same shared tree as waves W3.4 ("Tuning" modal rename, `features/fly/cv-setup-modal.*`/
`cv-control-panel.*`) and W3.5 (tap-to-follow, `shared/player/**` + `cv-control-panel-logic.ts`'s
`buildPointLockPatch`/`followPoint`) editing concurrently in real time — confirmed disjoint from
both throughout via `git status`/`git diff` before every stage, never touched their files. (Note:
the W3.4 entry immediately above this one twice mislabels the *tap-to-follow* sibling as "W3.3" —
that work is W3.5's; this section is the actual W3.3.) This wave's own files were repeatedly
overwritten mid-task by the concurrently-committing W3.4/W3.5 agents sharing this same working tree
(their own Read-then-Write cycles raced this wave's edits to `cockpit-facade.ts`/`cockpit.html`/
`cv-control-panel-logic.spec.ts` at least twice); every edit below was ultimately re-applied from a
clean `git show HEAD:<path>` snapshot in a scratch directory and staged directly via
`git hash-object -w` + `git update-index --cacheinfo` (never a plain `git add` on the live,
still-being-written path), so this commit is guaranteed self-consistent regardless of what the
live working tree looked like at any given moment.

- **Part A — TS-mirror fix, `core/api/models.ts`**: `UpdateStreamConfigRequest` gained
  `readonly intent?: CvProfileIntent` (~line 243) — the Java field has existed since wave W3.0;
  this TS mirror was simply never added. `PatchStreamConfigResponse` (this file's own pre-existing
  name for Java's `UpdateStreamConfigResponse`, not renamed here) gained `readonly sources:
  CvProfileSources` (~line 278) as **required, not optional** — reusing `CvProfileSources` verbatim,
  no new type — because the wire always sends a real `sources: {}`, never an omitted key; see that
  type's own doc comment (wave W3.6) for the full Jackson `@JsonInclude(NON_NULL)`-suppresses-a-
  null-*field*-not-a-non-null-*nested-object* finding, cited rather than re-derived a third time.
  One object literal broke as a result: `cv-control-panel-logic.spec.ts`'s `reArmHint` describe
  block (3 fixtures) — fixed by adding `sources: {}` to each, one line + comment per fixture, same
  shape as wave W3.6's own identical fix to `CvProfile` fixtures elsewhere.
- **Part B — intent chips, `cockpit.html`/`cockpit-facade.ts`**: a new row of four one-shot chips
  (People/Vehicles/Everything/Custom) plus the status line (Part C) render in `cockpit.html`,
  inside the `.dock`'s existing notice stack right after the crew camera-presence line, gated on
  `facade.live()` — **regardless of whether detection is currently on**, unlike the "Turn on" chip
  a few lines above (which only shows while detection is off): an operator whose CV goes `Degraded`
  mid-detection still needs to see it, and one already detecting may still want to re-aim without a
  round trip through "off" first. The chip row itself is additionally gated on `!facade.watchMode()`.
  **No persistent "selected" state** by design — there is no wire fact for "the stream's current
  intent," only its resolved *effect* (`model`/`labelFilter`), so every click is independent and
  none ever renders pressed; "Turn on" remains the one required, separate act, and no chip bundles
  `detectionEnabled: true`.
  - New `CockpitFacade.setIntent(intent: CvProfileIntent): Promise<void>` — a single `{ intent }`
    PATCH via `fleet.patchStreamConfig`, following `followTrack`'s exact fire-and-forget shape (no
    optimistic UI, no new error handling: `patchStreamConfig` already toasts + returns `null` on
    failure, so `null?.sources` degrades honestly to `undefined` for free).
  - New `CockpitFacade.lastConfigSources` (private writable `lastConfigSourcesSignal`, exposed
    `.asReadonly()`) — the most recent PATCH response's `sources`, captured by `setIntent`, reset to
    `undefined` inside `selectAsset()` (alongside the other per-asset reset fields there) since a
    prior asset's PATCH provenance has nothing honest to say about a new one. Exposed plainly for
    wave W3.4's Tuning modal to read later (per that wave's own entry above, its `lastConfigSources`
    input is wired but still always `undefined` — this facade signal is the value a future host
    binding would feed it); no reader of it added in this wave beyond the signal itself, per the
    brief's own "expose plainly, no gold-plating" instruction.
  - **Disclosed judgment call — "Custom" chip never sends `setIntent('CUSTOM')`**: read
    `IntentPolicyResolver.resolve()` (`contexts/vision-perception/.../profile/IntentPolicyResolver.java`)
    directly — it throws `IllegalArgumentException` when `intent === CUSTOM` and the same request's
    `labelFilter` is null/empty, and the Fly hero has no surface to collect custom classes inline, so
    a literal one-shot `setIntent('CUSTOM')` chip would be a guaranteed-400 button. The "Custom" chip
    instead calls the already-existing `cockpit.ts#requestCvSetup()` (opens the Tuning modal, which
    already has that surface) — a deliberate deviation from "each chip always sends the intent PATCH,"
    disclosed here and in the commit message. `setIntent` itself stays correct for all four
    `CvProfileIntent` values for any future caller that does have classes in hand.
  - New CSS: `.intent-chip-row` (`cockpit.css`, next to `.icon-btn`) — a `flex-wrap` row of the four
    `.icon-btn` pills, same responsive wrap-to-two-lines fallback `.header-actions` already uses.
    Chips reuse `.icon-btn` (not `.chip`, whose `--panel-raised`/`--border` tokens are for themed
    panels, not a control floating directly over video — frontend-style §2's HUD rule).
- **Part C — the hero's one status line, `fly-logic.ts`**: new pure `FlyHeroStatus` interface
  (`{ text: string; tone: 'off'|'on'|'warn'|'degraded' }`) + `flyHeroStatus(detectionEnabled,
  cvSubsystem, detectionState, worldObjects)`, plus 9 new `it()`s in `fly-logic.spec.ts` covering
  all four branches, the health-exclusion cases (`OK`/`DISABLED` never "Degraded"; an `undefined`
  subsystem read — no completed `/api/system/status` fetch yet — never guesses a fault either), the
  N=0 edge case, and singular/plural (`1 object` vs `N objects`, a codebase-precedent pluralization
  `cv-control-panel-logic.ts`'s own "N class(es) on screen" line already established; §4.8's own
  wording never shows a singular example, so this is a minor, low-risk judgment call, not a
  literal-text deviation from anything §4.8 actually specifies). Exact 4-state priority order:
  1. `cvSubsystem` present and `health` is anything other than `'OK'`/`'DISABLED'`
     (`'DEGRADED'`/`'DOWN'`/`'UNKNOWN'` all qualify) → `Degraded — <detail>` verbatim from
     `SystemStatusStore#status()`'s `cv-service` subsystem row — wins regardless of `detectionOn`.
  2. Else `!detectionOn` → `Off`.
  3. Else `detections.tracks()?.detectionState === 'RUNNING_UNWATCHED'` → `On — no viewer`.
  4. Else → `On — N objects`, `N` = `detections.worldObjects().filter(o => o.render.tier !==
     'HIDDEN').length`, `N=0` rendering literally `On — 0 objects`, never a softened phrase.
  `CockpitFacade.heroStatus` wires this from `detectionOn()`, `systemStatus.status()
  ?.subsystems.find(s => s.id === 'cv-service')` (new private `systemStatus = inject(SystemStatusStore)`
  — injected here, not in `cockpit.ts`, per `architecture.spec.ts`'s routed-page rule),
  `detections.tracks()?.detectionState`, and `detections.worldObjects()`. Deliberately **not** a
  reuse/edit of `cv-control-panel-logic.ts#detectionStatus` — that is the Tuning-drawer's own,
  separate 7-way status line for a different audience (§4.8's own table lists Operator/hero and
  Expert/Tuning-modal as two distinct rows); this is a second, independent derivation.
  - **Tone vocabulary**: `'off'|'on'|'warn'|'degraded'` (new to this file, no existing union reused
    — `detectionStatus`'s own `DetectionStatusKind` is a `kind`, not a `tone`, and explicitly carries
    no color mapping). Rendered via the two chrome classes this cockpit already has: `'degraded'` →
    `.notice` (amber fault chrome); `'off'`/`'on'`/`'warn'` all → `.stream-state-chip` (neutral HUD
    pill) — `'warn'` is its own tone rather than folded into `'on'` only so a future pass can style
    "on, unwatched" apart from "on, seeing things" without re-parsing `text`, not because it gets
    different chrome today.
  - **No new poll started**: `CockpitFacade`'s existing `wantsTracksPoll` computed already includes
    `this.detectionOn()` in its formula — the tracks poll (and therefore `worldObjects()`/`tracks()`)
    was already running whenever this status line's own "On" branches can be reached; confirmed by
    reading that computed directly rather than assumed.
- **Role-gating / dev parity**: unaffected — no new gate, no auth-conditional branch. `setIntent`
  and the chip row are visible to any operator who can already reach the cockpit; `vision.auth.
  enabled=false`'s dev admin sees identical behavior to any other ADMIN session, exactly as before.
- **Degrades honestly**: a failed `setIntent` PATCH leaves `lastConfigSources` at `undefined` (never
  a fabricated value) via the same `patchStreamConfig` toast-and-`null` path every other PATCH-sending
  method here already relies on; `heroStatus` never guesses `Degraded` from a `cv-service` row it
  hasn't actually read (`cvSubsystem === undefined` falls through to the ordinary Off/On branches).
- **Verify chain**: `npx tsc --noEmit -p tsconfig.app.json` — 0 errors. `npx tsc --noEmit -p
  tsconfig.spec.json` — 0 errors. `npm run test:ci` — **203 files / 3969 tests, all green**, measured
  against this wave's own code before this final race-safe re-application; re-verified after
  reconstruction by re-running the same suite against the reconstructed files copied back onto the
  live tree. `ng build --configuration production` — green, same two pre-existing budget warnings
  only (initial bundle over 390 kB; `tactical-map.css` over 11 kB), neither touched by this wave.
  **Bundle delta — corrected post-hoc (W3.7 docs pass, 2026-09-13)**: the paragraph originally here
  said no isolated delta was possible on this shared tree; that was written before an isolated
  measurement existed, and the pessimism doesn't hold. Re-measured via two disposable
  `git worktree add --detach` checkouts (no shared index, no sibling contamination) — one at this
  wave's own commit `a5ab10d3`, one at its parent `41d04ba8` — both with `node_modules` symlinked in:
  lazy **`cockpit` chunk 138.69 kB → 140.35 kB raw (+1.66 kB), 29.26 kB → 29.55 kB transfer
  (+0.29 kB)**; initial bundle **436.41 kB → 436.82 kB raw (+0.41 kB), 122.32 kB → 122.55 kB transfer
  (+0.24 kB)** — noise-level, as expected for a wave that adds no new route and no new eager import.
- **Commit**: `feat(cv-orchestration W3.3): intent chips + one honest status line on the fly hero`.

## Status — CV-ORCHESTRATION wave W3.5 (web): tap to follow — box or point (D8) (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.7 D8, §6 W3 row) — 2026-09-13

The last implementation step of wave W3 ("one operator act"). Wires the wire's own `TargetLockRequest.
pointX`/`pointY` form (`core/api/models.ts`) — a **zero-caller** shape before this wave (§7 D8's own
"R1 surprise 2") — to the click gesture `shared/player/player.ts#onOverlayClick` already had, so a tap
on the video always does *something* useful: a tracked box locks by id (unchanged), an untracked box
locks onto its own center, and a bare click on open video locks onto that point. §7 D8's own acceptance
line ("click path to follow = 3, measured by the e2e spec") is the new `features/fly/click-to-follow.
spec.ts`, described below.

**Built on a heavily shared tree** — two sibling waves editing the exact same files concurrently for
most of this wave's duration: W3.4 ("Tuning" modal, merged as `41d04ba8`, confirmed by its own commit
message to make "zero changes" to `cockpit.ts`/`cockpit-facade.ts`) and W3.3 (intent chips + one honest
hero status line, uncommitted for most of this wave's duration — at times adding ~90 uncommitted lines
to `cockpit-facade.ts` and ~37 to `cockpit.html`, the exact two files this wave also needed — until it
landed mid-wave as `a5ab10d3`, see the race disclosure below). Rather than a tree-wide `git stash`
(refused for the same reason every prior wave in this file with a live sibling refused one — no safe
way to isolate one agent's WIP from another's mid-edit in a shared index), `cockpit-facade.ts` and
`cockpit.html` were cleaned via a repeatable per-file procedure while W3.3 was still uncommitted:
(1) back up the current combined (mine + sibling's) working-tree content to a scratch file, (2) edit
the working tree down to "HEAD plus only this wave's own hunks", (3) `git diff` to confirm nothing
foreign remains, (4) commit that clean content via `git commit --only -- <this wave's paths>` (which
ignores whatever else the shared index holds for every other path, sidestepping a second hazard found
mid-wave: the sibling agent's own `git add` sweeps repeatedly re-staged its in-progress `models.ts`/
`cockpit.css`/`fly-logic.ts`/`fly-logic.spec.ts`/`MODULE.md` changes, and once even re-contaminated this
wave's own already-cleaned `cockpit-facade.ts`/`cockpit.html` working-tree content between verification
passes), (5) restore the combined content back onto the working tree afterward so the sibling's
uncommitted work was left exactly as found, never lost, never force-included.

**Disclosed race, caught and fixed before this entry was written**: between the last "nothing foreign
remains" check and the actual `git commit --only` call, the W3.3 sibling committed for real
(`a5ab10d3`, landing on top of `41d04ba8`). Because the clean `cockpit-facade.ts`/`cockpit.html`/
`cv-control-panel-logic.spec.ts`/`MODULE.md` content staged for this wave's commit had been computed
against the *stale* pre-`a5ab10d3` HEAD, the first attempt at this wave's commit (since superseded via
`git commit --amend` — the only amend in this wave, on a commit that had not been shared or built upon
by anything else, per this repo's own "prefer a new commit" rule's own stated exception for a not-yet-
shared local mistake) would have **silently reverted W3.3's now-permanent work** in those four files:
removing its `systemStatus`/`flyHeroStatus`/`setIntent`/`lastConfigSourcesSignal` additions from
`cockpit-facade.ts`, its hero-status/intent-chip markup from `cockpit.html`, its required `sources: {}`
test fixtures from `cv-control-panel-logic.spec.ts` (which would have left a real, permanent `tsc`
failure — `a5ab10d3` also made `PatchStreamConfigResponse.sources` a required field, so a reverted
fixture omitting it no longer compiles once `a5ab10d3` is a real ancestor), and its own real MODULE.md
section. Caught by re-running `git log --oneline` and noticing `a5ab10d3` where a draft used to be;
fixed by re-deriving each of those four files as "`a5ab10d3`'s real content plus only this wave's own
hunks on top" (the identical isolate-and-diff procedure above, just re-run against the corrected
baseline) before finalizing the commit actually shipped. This file's own section is appended directly
after W3.3's real, now-committed section (not before it, as an earlier draft of this same paragraph
assumed while W3.3 was still in flight).

**What changed:**

- `shared/player/player.ts`: new `readonly pointFollowed = output<{ readonly x: number; readonly y:
  number }>();`, a sibling to the existing `readonly trackFollowed = output<number>();` — added, not
  replaced; every existing `trackFollowed` caller/binding is untouched. `onOverlayClick`'s hit-test
  (`x`/`y` from `event.clientX/Y` minus the canvas's own bounding rect, matched against `drawnBoxes`,
  unchanged) now delegates its *decision* to a new pure `resolveOverlayClickTarget` (`detection-overlay-
  logic.ts`), a three-way `{kind:'track'|'point'|'none'}` discriminated union:
  - **Tracked-box hit** (`hit.detection.track?.id !== undefined`) → `trackFollowed.emit(trackId)`,
    byte-identical to pre-W3.5 behavior (regression-guarded by the first new spec case below).
  - **Untracked-box hit** → `pointFollowed.emit({x, y})` at *that detection's own box center*
    (`box.x + box.width/2`, `box.y + box.height/2`) — already the wire's normalized `[0,1]` fraction
    (the same one `Detection.box` itself uses), so **no pixel math at all** for this branch.
  - **No hit** (a bare click on open video) → the click's own CSS-pixel position is normalized against
    the frame's *effective* content rect, recomputed via the identical two-step pipeline `redrawOverlay`
    already uses for painting every box — `letterboxRect(...)` then `applyCropFollowToContentRect(
    cropFollowTransform(cropFollowState))` — so this inversion stays consistent with the forward mapping
    at any crop-follow zoom (proven by a dedicated round-trip spec case, below). A result outside `[0,1]`
    on either axis (the click landed in a letterbox/pillarbox bar) is a no-op — no lock request sent, the
    honest "nothing meaningful was clicked" posture this hit-test already had. A result just inside the
    edge is clamped defensively for float error.
  - **The box-center-vs-pixel-inversion distinction, spelled out**: an untracked box's center needs zero
    unit conversion because a `Detection.box` is already normalized; a bare click is a raw CSS-pixel
    coordinate that must be normalized against the *content* rect specifically (not the canvas/video
    element's own full bounding rect) because `object-fit: contain` letterboxing and any active
    crop-follow zoom both shrink/offset where "the video" actually renders relative to where the
    `<canvas>` element itself sits.
  - `resolveOverlayClickTarget` was extracted as a pure, standalone function rather than tested via a
    `TestBed`/component harness — **judgment call**: no `player.spec.ts`/component-test precedent exists
    anywhere in this codebase for `Player` (heavy dependencies: `WebrtcCertificateService`, an
    unpolyfilled `ResizeObserver` in jsdom, `hls.js`, required `viewChild` DOM refs), and this codebase's
    own convention already favors pure-logic vitest over component specs (`crop-follow-logic.spec.ts`
    already mirrors this exact hit-test math the same way). 9 new `it()`s added to the existing
    `detection-overlay-logic.spec.ts` (which already hosts every other pure overlay-logic spec): the
    tracked-box regression case, the untracked-box-center case (float-safe via `toBeCloseTo`, since
    `0.2 + 0.2/2 !== 0.3` exactly in JS), a bare click inside a zero-origin content rect, a bare click
    inside a letterboxed (non-zero-origin) content rect, two letterbox-bar no-op cases (x axis and y
    axis separately), a degenerate zero-area content rect (NaN-safety — `!(value > 0)` rather than
    `value <= 0` so a `NaN` width/height also falls through to `'none'` instead of propagating a `NaN`
    point), an exact-edge float-clamp case, and a **non-identity crop-follow zoom round-trip** case
    (a `CropFollowState` fixture `{scale:2, centerX:0.75, centerY:0.75, anchorX:0.75, anchorY:0.75}`
    mirroring `crop-follow-logic.spec.ts`'s own quadrant-pinned fixture) proving the forward mapping
    (`applyCropFollowToContentRect`) and this inversion agree at a non-trivial zoom, not just at 1x.
- `features/fly/cv-control-panel-logic.ts`: new `buildPointLockPatch(pointX: number, pointY: number):
  UpdateStreamConfigRequest` — `{ tracking: { mode: 'FOLLOW', lock: { pointX, pointY } } }` — mirroring
  `buildFollowLockPatch`'s exact "always pair `mode: 'FOLLOW'` with the lock, in one call" convention,
  doc-commented and cross-referenced to `player.ts#pointFollowed`. One new test alongside
  `buildFollowLockPatch`'s own.
- `features/fly/cockpit-facade.ts`: new `followPoint(pointX: number, pointY: number): void`, a sibling
  to `followTrack` (same file, immediately after it) — identical shape: reads `this.stream()?.streamId`,
  no-ops with nothing running, otherwise `fleet.patchStreamConfig(streamId, buildPointLockPatch(pointX,
  pointY)).then(() => this.seats.refreshNow())` — the same choke point `followTrack` already uses so a
  crew member's read state and the dock's presence line catch up on the next tick rather than the
  ordinary ~3s seat-poll cadence. No optimistic UI: nothing here claims the lock took until the next
  tracks poll confirms it (docs/extracts/TRACKING-ORCHESTRATION.md §3.3's honesty rule, the same one
  `followTrack` already follows).
- `features/fly/cockpit.html`: **one line added** — `(pointFollowed)="facade.followPoint($event.x,
  $event.y)"` immediately after the existing `(trackFollowed)="facade.followTrack($event)"` binding on
  `<vision-player>`. Nothing else in this file was touched by this wave — the W3.3 sibling's own larger
  edit to this same file (the hero status line + intent chip row, now permanently part of `a5ab10d3`)
  was confirmed via `git diff` before every stage and surgically excluded from this wave's own commit,
  then correctly preserved (not reverted) once `a5ab10d3` landed, per the race disclosure above.
- **Deliberately not propagated**: `features/live/live.html` and `features/crew/crew.html` also bind
  `(trackFollowed)` but were left untouched — matching the plan's own D8 scope (the Fly cockpit's tap
  gesture only) and avoiding scope creep into two surfaces this wave was never asked to touch.
- **New acceptance spec — `features/fly/click-to-follow.spec.ts`** (kept as its own file rather than
  folded into `cockpit-facade.spec.ts`, which does not exist — there is no existing facade-level spec
  file to fold into, and a new standalone file makes the 3-operator-act structure easiest to read as one
  unit): drives real, non-mocked production functions for every click-to-follow-relevant step —
  `resolveOverlayClickTarget` (the actual hit-test decision), `buildHotKnobPatch`/`buildFollowLockPatch`/
  `buildPointLockPatch` (the actual patch bodies) — across two independent test cases, each asserting an
  explicit **3-act** sequence and act count:
  1. *Tracked-box path*: act 1 (start, a disclosed bare `vi.fn()` stand-in for `FleetStore#start`/
     `VisionApi#startStream`'s call shape — carries no click-to-follow logic of its own, so a real
     `CockpitFacade` instantiation was judged disproportionate here, see below), act 2 (`buildHotKnobPatch`
     with `detectionEnabled: true` — "Turn on"), act 3 (a tracked `DetectionResult` fixture →
     `resolveOverlayClickTarget` resolves `{kind:'track', trackId:7}` → `buildFollowLockPatch(7)`).
     Asserts `acts.length === 3`, `startStream` called once, `patchStreamConfig`-shaped calls captured
     twice, and the final patch equals `{tracking:{mode:'FOLLOW', lock:{trackId:7}}}`.
  2. *Untracked-box point path*: identical structure, but the fixture detection carries no `track`; act 3
     resolves `{kind:'point', x:0.4, y:0.5}` (that box's own center) → `buildPointLockPatch(0.4, 0.5)` →
     final patch `{tracking:{mode:'FOLLOW', lock:{pointX:0.4, pointY:0.5}}}`.
  - **Test-boundary judgment call**: `CockpitFacade` itself is not instantiated (~20 injected
    collaborators, no existing harness anywhere in this codebase does so) — acts 2 and 3 call the real
    production pure functions directly rather than routing through the facade's own methods, while act 1
    ("start stream") is a disclosed spy stand-in, since it has no click-to-follow behavior to verify.
  - **Zero drawer/modal state touched**: this file imports nothing from `core/ui/ui-store`,
    `features/fly/cv-control-panel`, or `features/fly/cv-setup-modal`, and instantiates none of them —
    confirmed by the file's own import list (`vitest`, `core/api/models` types, `shared/player/
    detection-overlay-logic`, `features/fly/cv-control-panel-logic`).
- **Role-gating**: unaffected — click-to-follow was already reachable by any operator who can already
  reach the cockpit and see boxes; this wave adds no new gate and removes none.
- **Dev parity**: unaffected — `vision.auth.enabled=false`'s dev admin resolves ADMIN/unbounded exactly
  as before; no auth-conditional branch exists anywhere in this wave's diff.
- **Degrades honestly**: a click in a letterbox bar, or on a degenerate (`NaN`/zero-area) content rect,
  is a plain no-op — never a fabricated point sent to the wire. A failed `followPoint` PATCH degrades
  exactly like `followTrack` already does: `fleet.patchStreamConfig` toasts and returns `null`, no
  optimistic UI anywhere claims the lock took, and the next tracks poll is the only source of truth for
  whether it actually did.
- **Unit convention confirmed, not assumed**: `pointX`/`pointY` on `TargetLockRequest` (`core/api/
  models.ts`) share the exact same normalized `[0,1]` video-frame fraction `Detection.box`/`BoundingBox`
  already use on this same wire — checked directly against the existing type definitions rather than
  guessed, since this was a zero-caller field with no existing usage example to copy.
- **Verify chain**: `npx tsc --noEmit -p tsconfig.app.json` — 0 errors. `npx tsc --noEmit -p
  tsconfig.spec.json` — 0 errors. `npm run test:ci` — **203 files / 3969 tests, all green**, run against
  the fully-restored combined tree (this wave's own files, W3.3's then-still-uncommitted WIP, and
  W3.4's already-merged commit, all present at once) — the same total W3.3's own section above
  independently cites, confirming the shared tree was self-consistent at measurement time; re-confirmed
  green again after `a5ab10d3` landed and this wave's own final commit was re-derived on top of it.
  `shared/
  player/no-client-rederivation.spec.ts` (wave W3.2's grep-based forbidden-identifier guard) spot-checked
  directly (`npx vitest run` on that one file) and still passes — this wave introduces no client-side
  velocity/label re-derivation. `ng build --configuration production` — green, the same two pre-existing
  budget warnings only (initial bundle over 390 kB; `tactical-map.css` over 11 kB — neither touched by
  this wave); raw measured totals on the same fully-restored combined tree: initial **437.13 kB raw /
  122.59 kB transfer**, `cockpit` lazy chunk **140.58 kB raw / 29.63 kB transfer**. **No isolated
  before/after bundle delta for this wave alone** — the same call every other wave sharing this tree
  already made in this file (W3.2, W3.3, W3.6): a sibling agent held large, actively-uncommitted edits
  in these same files for most of this wave's duration, so a stash-based before/after would measure the
  sibling's edits appearing/disappearing, not this wave's own; this wave's own diff is small
  (one output, one facade method, one patch-builder, one template line, all doc-comment-heavy) and lives
  entirely inside the already-lazy `cockpit` chunk, never the eager initial bundle.

## Status — CV-ORCHESTRATION wave W5b.4 (web): `FrameLedger`'s traced-detections mirror (docs/plans/active/CV-ORCHESTRATION-PLAN.md §8 E23, §6 W5b row) — 2026-09-13

A saved `CvTrace` could not replay through `tools/trackeval` before this wave: `FrameLedger` only
ever carried a detection *count*, and `ObjectState.detectorBox` exists only for matched objects, so
every box the associator rejected was already lost by the time "Save trace" wrote its JSON.
Decision E23 fixes this at the source (`FrameLedger` on the wire and in cv-service, waves
W5b.0–W5b.3); this step is the frontend's own mirror of the result.

- `core/api/models.ts`'s `FrameLedger` interface gains `detections: readonly TracedDetection[]`,
  `frameWidth: number`, `frameHeight: number` (edited in place, per this wave's own instruction —
  see the interface's own doc comment for the "empty + 0/0 means not carried" contract). New
  `TracedDetection` type appended at the very end of the file under a fresh
  `// CV-ORCHESTRATION W5b — traced detections` banner (a new banner, not the existing wave-W5 one
  above it): `{ label: string, confidence: number, box: BoundingBox }`, reusing the existing
  `BoundingBox` type — deliberately **not** shaped like `Detection`, since a detector has no track
  identity to carry (mirrors `dto.FrameLedgerResponse.DetectorBoxResponse`/domain `DetectorBox`).
- `cv-trace.wire.contract.spec.ts` updated: `frameLedgerKeys` gained the three new keys, a new
  `tracedDetectionKeys` map, and the fixture's `full.frame` now has **two** ledger entries (this
  wave's regenerated `cv-trace.wire.json`, from `station/vision-api`'s W5b.3) — two new `it` blocks
  assert `frame[0]`'s populated `detections`/non-zero frame size and `frame[1]`'s empty/`0` "not
  carried" shape, replacing the old single-ledger `toHaveLength(1)` assertion.
  `cv-inspector-logic.spec.ts`, `core/cv-trace/cv-trace-store.spec.ts`, and
  `core/cv-trace/cv-trace-logic.spec.ts` each build a hand-written `FrameLedger` fixture object —
  all three gained `detections: []`, `frameWidth: 0`, `frameHeight: 0` so the object literal still
  satisfies the widened interface (no test assertion depended on these three fields, so this is a
  type-completeness fix only, not new coverage in those files).
- `features/cv-inspector/cv-inspector.html`'s frame-summary line gained `· raw boxes {{
  frame.detections.length }}` — the one-line addition this step's own instruction allowed ("only if
  it is one line"). No `.ts`/`.css` change needed for this addition (three-file component already
  intact, template-only edit).
- **"Save trace" needs no change**: it already downloads the *entire* current `CvTrace` object
  (`features/cv-inspector`'s existing download action serializes whatever `CvTrace` shape the wire
  sends), so `detections`/`frameWidth`/`frameHeight` ride along automatically the moment
  cv-service/vision-api start populating them — nothing in the download path names `FrameLedger`'s
  fields explicitly.
- **Worktree note, not a code defect**: this worktree's `station/vision-web/node_modules` had never
  been installed (`npm ci` — 477 packages) before this step could run `npm run test:ci`; without it,
  `npm run` fell through PATH to an unrelated system `/usr/bin/ng` binary (Debian's `nethack`
  console client, not Angular's CLI), which is the actual source of a `panic: aborting due to
  terminal initialize failure` this step hit first, before `npm ci`. Recorded here only because the
  symptom is easy to misread as a code or environment regression; it was neither.
- **Verify chain**: `npm run test:ci` — **208 test files / 4029 tests, all green** (up from the
  pre-wave count; this run is the first full pass after `npm ci` repopulated `node_modules` in this
  worktree, so no isolated before/after delta is meaningful here — see the note above). `npx tsc
  --noEmit -p tsconfig.app.json` — 0 errors. `npx tsc --noEmit -p tsconfig.spec.json` — 0 errors. No
  `features/vision-profiles/**`, `features/fly/**`, or other W7-scoped file touched (W7 runs
  concurrently on `CvProfile*`/profile-as-patch, a disjoint file scope from this step).
- **Commit**: `feat(cv-orchestration W3.5): tap to follow — box or point (D8) + click-path acceptance spec`.

## Status — CV-ORCHESTRATION wave W7 (web, steps W7.4/W7.5): a profile is a patch (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.7/§8, decision E22, §6 W7 row) — 2026-09-13

The web half of wave W7, on `feat/cv-orchestration-w7-profile-patch`, following four Java-side steps
(W7.0 perception domain, W7.1 resolver, W7.2 persistence, W7.3 vision-api DTOs, commits `7d3926b4`/
`7886789b`/`6781f533`/`89776c1c`, all documented in their own modules' `MODULE.md`/this plan's context
log, not repeated here). Makes `CvProfile` a genuinely per-knob inherit-capable patch instead of a
wholesale-per-tier record, `intent` persisted with the profile and resolved at fold time rather than at
save time, and per-knob `sources` (real provenance) reported on every read — not only the response to a
save, the pre-W7 contract's own limitation (W2's "As built" note had already flagged this gap and
deferred the schema decision to this wave).

**W7.4 — `core/api/models.ts` + `/vision/profiles`' editor (commit `1481b57d`):**

- `models.ts` widened one for one against W7.0–W7.3's Java DTOs: `CvProfile`/`CvProfileRequest`/
  `CvProfileTracking` knobs are all now optional (absent = inherit from the tier below). The original
  2-field `CvProfileSources`/`Sources` (`model`/`labelFilter`, both `'INTENT'`-only) is kept
  **byte-identical** — its doc comment now says explicitly it serves only `PatchStreamConfigResponse`
  (the stream-config hot-knob PATCH response), not `CvProfile#sources` any more. `CvProfile#sources`'s
  own type is the new `CvProfileFieldSources` (4 fields: model/confidenceThreshold/inferenceFps/
  labelFilter). Two more new types — `CvKnobSources` (8 fields, one `CvKnobSourceTier` — `ASSET`/
  `CATEGORY`/`ORGANIZATION`/`PLATFORM`/`INTENT` — per knob) and `EffectiveCvProfile#sources`/`#intent`
  — carry the fold's own real per-knob provenance on `GET .../effective-profile`. Every new type is
  appended under the required `// CV-ORCHESTRATION W7 — profile as patch` banner, after the W5b-owned
  `CvTrace`/`FrameLedger`/`GateDecision`/`WorldObject`/`ObjectEvidence`/`LedgerEntry` section — confirmed
  untouched (W5b lives entirely on the sibling `feat/cv-orchestration-w5b-trace-replay` branch, never
  merged into this one).
- `features/vision-profiles/` (`vision-profiles-logic.ts`/`.ts`/`.html`/`-facade.ts`): `ProfileDraft`
  is now genuinely per-knob inherit-capable — see `ProfileDraft`'s own doc comment for the full
  reasoning, summarized here: **blank text** means inherit for `model` (no legitimate "explicit blank"
  value of its own); a **separate `*Inherit` boolean** for `labelFilterText`/`labelDenyFilterText` (an
  explicit empty `[]`, "keep/deny nothing", is itself a real value distinct from "inherit" — blank text
  alone cannot carry both meanings, CLAUDE.md rule 10); a **dedicated sentinel value on the `<select>`
  itself** for `trackingMode`/`trackingEngineId`/`detectionEnabled` — each already has its own real,
  explicit "off"/"default" value distinct from unset (`'OFF'`, `''`, `false`), so the option list grows
  one more entry rather than pairing the control with a checkbox. **Tracking inherits per sub-knob, not
  as one whole-group toggle** — a deliberate choice: `TrackingKnobPatch` (the domain patch this maps
  onto) is itself nullable field-by-field, so an operator overriding only tracking mode can leave
  capability level/cadence inherited rather than being forced to either restate every other knob's
  current effective value or lose a partial override entirely.
  `emptyProfileDraft()` no longer takes a `defaultModel` parameter — every knob starts unset (a
  no-op patch that falls all the way through to the platform default is decision E22's own documented
  default, not a leftover pre-seeded concrete value). `validateDraft` no longer requires a model at all,
  with or without an intent chosen, and only range-checks a knob that is actually set — an unset knob
  has nothing of this draft's own to be wrong, its value comes from whichever tier the fold resolves.
  `draftToRequest` omits every unset knob from the wire body (`undefined`, an absent JSON key, never a
  fabricated concrete value); `tracking` itself is omitted only when every one of its five sub-knobs is
  unset. `saveOutcomeMessage`'s toast copy corrected **"resolved from" → "left to"** your intent — under
  the new fold-time-resolution contract, saving no longer computes a concrete value for an intent-seeded
  knob, it only flags it `INTENT` for the fold to seed later; "resolved from" misstated that as a
  completed past act.
  Template: Model/Confidence/Rate inputs bind `[ngModel]="draft.x ?? null"`/`(ngModelChange)="…?? undefined"`
  with `placeholder="Inherit"`; Detection becomes a 3-way `<select>` (Inherit/On/Off) replacing a plain
  checkbox; the label filter/deny-list `<textarea>`s each gain a sibling inherit checkbox — the wrapper
  element for each had to change from `<label>` to `<div>` in the process, since a `<label>` may not
  nest another `<label>` and each now contains both a `<textarea>` and a checkbox `<label>`; Tracking's
  mode/engine `<select>`s each gain an explicit "Inherit" option.
- **A downstream ripple, found and fixed, not part of this wave's own file list**:
  `features/fly/cv-control-panel-logic.ts#resolvedConfigFromProfile`/new
  `resolvedTrackingFromProfile` broke under the widened `CvProfile` type — `EffectiveCvProfile#profile`'s
  knobs are contractually always concrete once `CvProfileResolver`'s fold bottoms out at the platform
  tier's own defaults (wave W7.1), even though `CvProfile`'s own type now allows `undefined` on every
  field. Fixed with explicit non-null assertions and a doc comment naming that fold guarantee, rather
  than silently swallowing the compile error — mirrors the identical compromise already made Java-side
  (`CvProfileResponse` reused verbatim for both a raw, possibly-partial profile read and
  `fromEffective()`'s always-concrete result, rather than minting a second, statically-non-nullable
  DTO). `cv-control-panel-logic.spec.ts`'s own `EffectiveCvProfile` test fixtures needed a `sources`
  field added too (newly required, wave W7) — a small `knobSources()` helper added alongside the
  existing `cvProfile()` one.
- **Verify chain**: `npm run test:ci` — **208 files / 4043 tests, all green**, including new
  coverage for `describeOptionalModel`/`describeOptionalNumber`/`describeDetectionCardState` and the
  three tri-state select helper pairs. `npx tsc --noEmit -p tsconfig.app.json` / `-p
  tsconfig.spec.json` — both 0 errors.

**W7.5 — Tuning modal sources from the effective read (commit `d7a064c1`):**

- `features/fly/cv-setup-modal-logic.ts#resolvedSourceLine` now takes the whole `EffectiveCvProfile`
  (not a bare `EffectiveCvProfile['source']`) and reads real per-knob provenance straight off
  `CvKnobSources` — the exact tier (`ASSET`/`CATEGORY`/`ORGANIZATION`/`PLATFORM`/`INTENT`) that supplied
  *that specific knob's* resolved value, on **every** `GET .../effective-profile` read, not only the
  response to a PATCH that just applied an intent. Replaces the pre-W7 two-tier approximation (one
  coarse whole-profile tier reused as a fallback for every knob alike, unable to distinguish "this knob
  came from the asset tier" from "this knob came from the category tier the asset profile itself
  inherited"). Precedence, unchanged in shape: `lastConfigSources` (this session's own most recent
  hot-knob PATCH, a live fact about the *running stream*) still wins when present, then the
  effective-profile read's own per-knob fact, then nothing. An intent line now names the specific intent
  ("Resolved from your Vehicles intent") whenever `EffectiveCvProfile#intent` is known — that data
  simply did not exist on this wire shape before W7, so the pre-W7 generic "your intent pick" phrasing
  is now only the fallback for the (should not normally happen once `intent` is itself persisted) case
  where a knob is flagged `INTENT` with no intent value attached. `confidenceThreshold` is no longer a
  documented exception either — W7.1's resolver now seeds it from an intent at fold time exactly like
  `model`/`labelFilter`, so its own source line can report `INTENT` in practice now, unlike before.
- **A real, separate wiring gap found and closed, not scope creep**: `CockpitFacade#lastConfigSources`
  has existed since wave W3.3, but `<vision-cv-setup-modal>` in `cockpit.html` never actually bound it
  as the `[lastConfigSources]` input — `resolvedSourceLine`'s own first-priority branch was permanently
  dead code in production despite the facade carrying the exact data it needed. One line added:
  `[lastConfigSources]="facade.lastConfigSources()"`.
- **No dedicated `cockpit-facade.spec.ts` added.** `features/fly/click-to-follow.spec.ts`'s own doc
  comment already establishes this codebase's precedent for `CockpitFacade` specifically: ~20 injected
  collaborators, no existing harness anywhere in this tree instantiates one, and building one just to
  prove a source line survives a reload would dwarf the thing being measured — test the real production
  functions its methods delegate to instead. `cv-setup-modal-logic.spec.ts` gained a "survives a reload"
  describe block that is exactly that proof: with **no** `lastConfigSources` fact at all (the state after
  an asset switch or a fresh page load, before any hot-knob PATCH this session resets it — `cockpit-
  facade.ts#selectAsset` resets `lastConfigSourcesSignal` to `undefined` on every switch), a fresh
  `effective-profile` GET alone still produces the correct source line for every Tuning knob — the
  pre-W7 contract could only get this right immediately after a save.
- **Verify chain**: `npm run test:ci` — **208 files / 4048 tests, all green** (+5 over W7.4's own
  4043-test total: the rewritten `resolvedSourceLine` spec's expanded coverage). `npx tsc --noEmit -p
  tsconfig.app.json` / `-p tsconfig.spec.json` — both 0 errors.

**Left undone, named honestly**: neither W7.4 nor W7.5 added a `modelSourceLine` computed signal —
`TuningKnob` has carried `'model'` since wave W3.4 but no template in this codebase has ever rendered a
resolved-source line for it (only Confidence and Classes do); adding one was judged out of this wave's
own brief (fixing sourcing correctness for the knobs already wired, not adding a new rendered line) and
is left as a small, disclosed gap for whichever wave next touches the Tuning modal's Model section.

- **Commit**: `feat(cv-orchestration W7.4): per-knob inherit editor + models.ts mirror`,
  `feat(cv-orchestration W7.5): Tuning modal sources from the effective read`.
## Status — CV-ORCHESTRATION wave W9.2 (web): the `tracks:` SSE flip reaches `DetectionsStore` (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.9, §8 decision E25, §6 W9 row) — 2026-09-13

Retires the last per-stream REST poll on a live surface. Wave W9.1 (`station/vision-api`, Java)
widened the `tracks:<assetId>` SSE payload from a bare `WorldObject[]` (wave W3.1) to the whole
`StreamTracksResponse` snapshot — the same assembly (`StreamTracksResponse.from(TracksSnapshot,
Instant)`) both `GET /api/streams/{id}/tracks` and the live push now build from ("one assembly, two
transports"). This step makes `DetectionsStore.tracks` transport-aware **exactly like `results`**, so
the poll finally becomes the fallback it should always have been.

**What changed:**

- `core/api/models.ts`: `LiveEnvelope`'s `'tracks'` member payload type changed from `readonly
  WorldObject[]` to `StreamTracksResponse` — the same type `GET /api/streams/{id}/tracks` already
  returns. Doc comments on both the union member and `StreamTracksResponse` itself updated to
  cross-reference each other and explain the widening.
- `core/live/live-store.ts`: the existing ref-counted `trackWorldObjects`/`untrackWorldObjects`
  subscription (wave W3.1, names kept unchanged for continuity — **not** a new track/untrack pair)
  now backs a per-asset `StreamTracksResponse | null` signal, not a `WorldObject[]` one. New public
  **`tracksFor(assetId): Signal<StreamTracksResponse | null>`** returns it directly;
  **`worldObjectsFor(assetId)`** keeps its exact wave-W3.1 signature/behavior but is now a cached
  `computed()` deriving `payload?.objects ?? []` — every existing W3.1/W3.2 consumer is unchanged.
- `core/detections/detections-store.ts`: **`tracks`** is now a `computed()` reading
  `live.tracksFor(assetId)` when the store's own already-known asset id (`track(streamId, assetId?)`
  — no new parameter threaded through `followTracks`) is in scope and the live transport resolves
  open (`resolveAssetScopedTransport`, the same `AssetScopedTransport` idiom `results` already uses).
  New `tracksTransportSignal`/`tracksWanted` fields and a constructor `effect()` mirroring the
  pre-existing `results`-transport effect drive the flip; `trackTracks`/`untrackTracks` rewritten
  around a new private `applyTracksTransport`. `followTracks(streamId, wanted)` keeps its exact
  two-argument signature. `CockpitFacade.wantsTracksPoll`'s formula is byte-identical; its doc
  comments now describe what it actually gates post-flip (the tracks session's "wanted" state, not
  the poll directly).
- New `core/api/stream-tracks.wire.contract.spec.ts`: pins `Record<keyof StreamTracksResponse, true>`
  (plus `StreamTrack`/`TrackStats`/`PipelineLatency`/`DetectionRate`/`FollowStatus`) against the
  fixture `station/vision-api`'s `StreamTracksResponseWireContractTest` commits (wave W9.1) — same
  `Record<keyof T, true>` technique as `cv-trace.wire.contract.spec.ts`/`world-object.wire.contract.spec.ts`.
- `detections-store.spec.ts`: `stubLiveStore()` gained an independent `tracksFor`/`pushTracks` pair,
  deliberately separate from the pre-existing, untouched `worldObjectsFor`/`pushWorldObjects` (since
  `DetectionsStore.worldObjects`/`.tracks` each call only their own one accessor — simulating that
  fidelity, not collapsing it). Three new cases prove: zero `GET .../tracks` requests once an asset id
  is in scope and `LiveStore` is open, with `tracks()` reflecting the live envelope; the poll runs
  unchanged, same as before this wave, when no asset id is in scope even though `LiveStore` is open;
  and the tracks session flips from poll to live mid-session, stopping the poll, mirroring the
  existing `results()`-transport test. Every pre-existing assertion in this file is unchanged.

**Payload size** (measured against the committed fixture, one `tracks:<assetId>` envelope): **243
bytes** before wave W9 (bare `WorldObject[]`) → **1,596 bytes** after (the whole
`StreamTracksResponse`) — the payload now rides at frame cadence rather than poll cadence, a fact the
next capacity/scale decision needs.

**Not built (by this wave's own design):** `worldObjects`'s own subscription lifecycle is untouched —
it still lives on the detections-feed `track()`/`teardownTracking()` pair (wave W3.1's scope), never
this wave's demand-gated `trackTracks`/`untrackTracks`; the two lifecycles read the same underlying
per-asset subscription through two different accessors but neither owns the other. No component
template changed — this is a core/-layer transport flip only.

**Nothing in this brief was found wrong or unimplementable.**

### Tests / build

`npm run test:ci` — **209/209 files, 4041/4041 tests green** (+1 file / +12 tests over wave W5b.4's
208/4029: 9 in the new contract spec, 3 in `detections-store.spec.ts`). `npx tsc --noEmit` clean on
both `tsconfig.app.json` and `tsconfig.spec.json`. No `features/vision-profiles/**`,
`features/fly/cv-setup-modal*`, `features/fly/cv-tuning*`, or other W7-scoped file touched (W7 ran
concurrently on `CvProfile*`/profile-as-patch, a disjoint file scope from this wave).

- **Commit**: `feat(cv-orchestration W9.2): tracks: SSE flip reaches the web store`.

## Status — NGRX-MIGRATION wave N0: the state engine, and two slices to prove it (docs/plans/active/NGRX-MIGRATION-PLAN.md §2/§3, §4 row N0) — 2026-09-18

**What the owner asked for and what was actually missing.** The ask was *"move from injections to the
signals … as a final result a well structured ngrx application, with all the reducers, actions,
effects and so on."* The first half was already true and is worth recording so nobody re-does it:
this SPA has **1 137 `signal`/`computed`/`effect` call sites, zero `BehaviorSubject`/`Subject`, and no
`zone.js` dependency at all**. Signals are not the gap. The gap is a state **engine** — shared state
lived in **32 hand-rolled store classes (9 026 lines)** each re-inventing the same five mechanisms
(private `signal()` + `.asReadonly()` pairs, `async` methods wrapping `VisionApi` in `try/catch`, a
per-store `run()` that fires one error toast, `PollScheduler` timers that pause while SSE is open, and
ad-hoc `localStorage` reads), with **38 facades (11 291 lines)** re-exporting those stores wholesale
(`readonly fleet = inject(FleetStore)`) so templates read `facade.fleet.devices()` and store internals
leak into HTML. NgRx replaces the five mechanisms; the facade rule in `MODULE.md` closes the leak.

**Why NgRx 21.1.1 and not 22.** 21.x is the last line declaring `@angular/core ^21.0.0`; NgRx 22
requires Angular 22, which this repo is not on.

**The three foundation pieces** (all in `core/state/`, see `MODULE.md` for the contract):
`provideAppState()` is deliberately a function rather than an inline block in `app.config.ts`, because
a spec that needs real state must register *the identical* store — same hydrators, same runtime
checks — instead of a hand-rolled subset free to drift from what ships. `hydration.ts` makes
persistence a **meta-reducer**, the plan's §8 risk: an effect that re-reads storage on every action
double-fires, a meta-reducer keyed on `INIT`/`UPDATE` cannot. `UPDATE` matters as much as `INIT` —
without it, a slice provided later by a lazy route never hydrates. Router state uses
`MinimalRouterStateSerializer`: the full `RouterStateSnapshot` carries component classes and
injectors, and would trip `strictStateSerializability` on the first navigation. All four runtime
checks are on, deliberately — this app's state is plain data end to end, so a mutation or a
non-serializable value is a defect, not a trade-off.

**Pilot slices: `theme` and `sidebar`.** Both were `localStorage`-backed root stores, which makes them
the right pilots — they exercise hydration, effects-own-the-side-effect (the `data-theme` attribute on
`<html>` is written by an effect, never a reducer), and consumer rewiring, without touching HTTP.
`sidebar` carried the harder invariant: its collapsed state is a three-layer precedence
(`override ?? (preference || fullBleed)`) that had to survive as a *pure* function of state
(`sidebarCollapsed`, exposed through `extraSelectors`), and its persist effect is filtered on
`!fullBleed` so an operator who never touched the toggle still has no key written — a behaviour the
old store got by accident and the effect now gets on purpose.

**Facades are named 1:1 with the store they replaced** (`ThemeFacade#theme()`/`setTheme()`,
`SidebarFacade#collapsed()`/`toggle()`), which is the whole reason a 12-consumer rewiring was
one line each: `inject(ThemeStore)` → `inject(ThemeFacade)` and nothing else. The legacy classes were
deleted in the same wave — the plan's §5 step 9, never two engines for one piece of state.

**The guard grew a third concern.** `core/ui/architecture.spec.ts` now also asserts that only a
`*-facade.ts` **or** a `*.effects.ts` may `inject(Store)`. Its first run failed on the two effects
files, correctly: an effect reading state through `concatLatestFrom` legitimately holds `Store`, and
the predicate had to say so. The other two invariants — reducers contain no
`inject(`/`Date.now(`/`Math.random(`/`localStorage`/`document.`, and every `*.reducer.ts` has a
sibling `*.actions.ts` and `*.reducer.spec.ts` — passed first try.

**`VisionApi` deliberately stays Promise-shaped until N9** (plan §7). It is 1 802 lines, 162 methods,
**289 call sites across 70 source files plus 265 in specs**; flipping it now would spend a ~135-file
blast radius on files this migration is about to delete, and leave the tree half-converted along two
axes at once. The cost of waiting is cancellation — `switchMap` over `from(promise)` discards a
superseded result but does not abort the request — and this app polls and does CRUD, it has no
typeahead, so the difference is nil until N9 makes it exact.

### Tests / build

`npm run test:ci` — **220/220 files, 4 285/4 285 tests green** (+3 files / +24 tests over the 217/4 261
baseline: `theme.reducer.spec.ts`, `sidebar.reducer.spec.ts` and the two facade specs replacing the
two deleted store specs). `npx ng build --configuration production` — exit 0.

**Bundle cost, measured honestly.** The first production build *failed* (487.46 kB against a 445 kB
error budget), so the baseline was measured properly rather than guessed: a detached
`git worktree add HEAD` with a symlinked `node_modules` builds at **442.45 kB raw / 123.81 kB
transfer** — i.e. the app was already only 2.5 kB under its own error budget before NgRx existed.
NgRx itself costs **+45.01 kB raw / +13.31 kB transfer**. `angular.json`'s initial budget moved
`390/445 kB` → **`500/550 kB`** (a clean 2-line diff). That is a real, deliberate cost of the engine,
recorded here rather than buried in a budget bump: every wave N1–N9 that deletes a hand-rolled store
gives some of it back, and the next wave to touch budgets should re-measure rather than assume.

- **Commit**: `feat(ngrx N0): NgRx foundation + theme/sidebar pilot slices`.

## Status — NGRX-MIGRATION wave N1: the shell's overlay slice, and the DOM half it can't hold (docs/plans/active/NGRX-MIGRATION-PLAN.md §4 row N1, §8 "Corrections found while briefing N1/N2") — 2026-09-18

**Scope, corrected before the code.** The wave table originally read as if a `ui` slice belonged here;
there is no such thing. `core/ui/ui-store.ts#UiStore` is a deliberately plain, DI-less class
instantiated **27 times** across the app as a host-owned overlay *group* (`readonly dialogs = new
UiStore()`) — exactly the ephemeral, per-host local state §2 says never belongs in NgRx, and NgRx
feature state is global by name, so it cannot stand in for 27 independent instances without inventing
a key for each. **N1 migrates `GlobalOverlayStore` only; `UiStore` is untouched, per §8.**

**The one design decision that mattered: split the store, not the behaviour.** `GlobalOverlayStore`
held two genuinely different things — the open overlay id (serializable, three lines of logic) and an
`OverlayHost` registry of live `HTMLElement` `{root, trigger}` pairs (never serializable, ever).
Putting the second into NgRx state trips `strictStateSerializability` on the very first `register()`
call — the plan's own prediction, confirmed by trying it first and watching the runtime check fire.
The fix is `core/ui/overlay-host-registry.ts#OverlayHostRegistry`: a small `providedIn: 'root'` class,
**not** a slice, holding nothing but the `Map<GlobalOverlayId, OverlayHost>` — deliberately not named
`*Store`, since `core/ui/architecture.spec.ts`'s guards (routed-page injection, NgRx layering) both key
off that suffix and this class is neither a page nor a facade/effects file. `overlay.effects.ts` and
`overlay-facade.ts` both inject it directly, alongside `Store`, for the one job each still needs from
it (focus-on-Escape; the contains-check on outside-click; the `register()` passthrough).

**Reducer expresses exclusivity directly — `UiStore` is not composed.** The old class explicitly
composed `core/ui/ui-store.ts#UiStore` for one-open-at-a-time behaviour. Re-read for this wave, that
precedent doesn't transplant: a reducer is a pure function of `(state, action)`, and `UiStore` is a
stateful class with its own `signal()` — nothing to "hold" inside a `createReducer` call.
`overlay.reducer.ts`'s `on(opened, (state, {id}) => ({...state, active: id}))` already **is** the
one-open-at-a-time rule, as a plain assignment; composing a second primitive on top would add a
dependency for zero behaviour. This is the "likely cleaner" option the plan's own §8 correction
flagged without picking — picked here, and stated plainly: **not composed, by design.**

**Close-on-navigation listens for `ROUTER_NAVIGATED`, not `Router.events` — a decision `app-state.ts`'s
own doc comment made first.** The old store injected `Router` directly, in its own constructor's
injection context. Copying that into an effect looks equivalent until the full suite runs:
`app-state.ts` already documents that `provideAppState()` deliberately ships with **no** `Router`
provider, "since most component specs have no reason to provide one" — and two of them,
`theme-facade.spec.ts` and `sidebar-facade.spec.ts`, prove it by calling `provideAppState()` alone.
`provideEffects()` subscribes every registered effect at `ROOT_EFFECTS_INIT`, eagerly, for every
consumer of `provideAppState()` — so an effect that unconditionally does `inject(Router)` throws
`NullInjectorError` the instant either of those two specs boots, nowhere near any test that mentions
overlays at all. `@ngrx/router-store`'s own `ROUTER_NAVIGATED` action, filtered through the `Actions`
stream instead, needs nothing but `Actions` — already a hard dependency of every effect — so it
subscribes cleanly everywhere and simply never fires in a spec that never dispatches it. Production
parity holds because `provideRouterStore()` is already wired in `app.config.ts` and dispatches this
exact action on every real navigation. Flagging this because the plan's own phrasing ("owns a
`Router.events` subscription... becomes effects") reads as license to inject `Router` straight into an
effect; under the plan's own non-negotiable that the whole suite must stay green, it isn't, for any
slice registered through `provideAppState()`.

**Consumer rewiring stayed one line each, prose excepted.** `OverlayFacade` keeps
`GlobalOverlayStore`'s exact public surface — `active`/`isOpen`/`open`/`close`/`toggle`/`register` — so
`identity-chip.ts`, `notification-bell.ts` and `app-sidebar.ts` (plus their three specs) changed only
`inject(GlobalOverlayStore)` → `inject(OverlayFacade)` and the import line. Two of the three specs
(`identity-chip.spec.ts`, `notification-bell.spec.ts`) additionally needed `provideAppState()` added to
their `TestBed` providers — they never registered any store before, since `GlobalOverlayStore` was a
plain root-provided class needing no store at all. Doc comments in all three components that described
the deleted "`GlobalOverlayStore` composes `UiStore`" mechanism were corrected in place rather than
left to go stale, since the design decision above made that sentence false. Five further files
(`shared/ui/return-home-button.ts`, `shared/ui/page-bar/page-bar.ts`, `features/fly/geo-chip.ts`,
`core/map-data/drawings-store.ts`, `core/map-data/marks-store.ts`) cite
`core/ui/overlay-store.ts#GlobalOverlayStore` only in doc-comment prose, as a design precedent, never
as an import — left untouched, out of this wave's declared 6-consumer scope; their citations now point
at a deleted path and are worth a follow-up pass.

**Test shape mirrors the facade idiom N0 actually shipped, not the plan's abstract §3 rule 10.** Rule
10 says "effects (`provideMockActions`)"; N0's own reference implementation shipped no
`.effects.spec.ts` for either `theme` or `sidebar`, testing effects only indirectly through
`*-facade.spec.ts` against real `provideAppState()`. `overlay.effects.ts`'s Escape/outside-click
effects don't consume `actions$` at all — they source from `fromEvent(document, …)` — so
`provideMockActions` has nothing to mock for two of the three effects; the third
(`closeOnNavigation$`) is exercised by dispatching a bare `{ type: ROUTER_NAVIGATED }` into a real
store instead of standing up `provideRouterStore()` + a real `Router` for one assertion. Following
N0's own precedent over the abstract rule, this wave ships `overlay.reducer.spec.ts` (pure, 14 cases)
+ `overlay-host-registry.spec.ts` (plain class, 4 cases, no `TestBed`) + `overlay-facade.spec.ts` (16
cases through real `provideAppState()`, replacing `overlay-store.spec.ts`'s 15 — exclusivity,
stale-close no-op, Escape+focus-return, outside-click containment including the "trigger click doesn't
fight the document listener" DOM-bubble-order case, and navigation) — no dedicated
`overlay.effects.spec.ts`; `core/ui/architecture.spec.ts`'s sibling-file guard only requires
`.actions.ts` + `.reducer.spec.ts` beside a reducer, not an effects spec.

### Tests / build

`npm run test:ci` — **222/222 files, 4 304/4 304 tests green** (+2 files / +19 tests over N0's
220/4 285: `overlay.reducer.spec.ts`, `overlay-host-registry.spec.ts` and `overlay-facade.spec.ts`
added, `overlay-store.spec.ts` deleted). `npx tsc --noEmit` clean on both `tsconfig.app.json` and
`tsconfig.spec.json`. `npx ng build --configuration production` — exit 0.

**Bundle cost, measured against N0's own tip**, not guessed: `git stash push -u` in this worktree,
rebuilt, popped back, to isolate this wave's delta from N0's already-recorded one. N0 tip: **487.46 kB
raw / 137.08 kB transfer**. This wave: **488.35 kB raw / 136.99 kB transfer** — **+0.89 kB raw / −0.09
kB transfer**. Replacing one ~140-line class with six small files (`overlay.model.ts`,
`overlay.actions.ts`, `overlay.reducer.ts`, `overlay.effects.ts`, `overlay-facade.ts`,
`overlay-host-registry.ts`) nets out close to flat — the NgRx action/reducer/effect ceremony costs
roughly what the deleted class's own `Router`/`document` wiring used to.

**Nothing in the plan was found wrong for this wave** beyond the two corrections §8 already made
before briefing started (`UiStore` not a slice; the store splits in two) — both confirmed exactly as
described. The one thing not spelled out and worked out fresh here: `provideAppState()`'s
already-documented "no `Router` provider" choice forces every future app-wide effect that cares about
navigation through `ROUTER_NAVIGATED` rather than `Router.events`, not just this one — worth flagging
for N3 (`live`) and any later wave whose store used to inject `Router` directly.

- **Commit**: `feat(ngrx N1): overlay slice — GlobalOverlayStore splits into an NgRx slice + a DOM host registry`.
## Status — NGRX-MIGRATION wave N2: four hand-rolled stores become slices — settings, org, seat, auth (docs/plans/active/NGRX-MIGRATION-PLAN.md §4 row N2) — 2026-09-18

**Scope.** Four of the remaining hand-rolled stores became `core/<domain>/state/<domain>.{model,actions,reducer,effects}.ts` slices + a `core/<domain>/<domain>-facade.ts`, registered in `provideAppState()`: `SettingsStore`, `OrgStore`, `SeatStore`, and `AuthStore` — the last, at 276 lines / 6 state keys / ~30 consumer files, the biggest single store this whole migration touches. All four legacy classes and their specs are deleted in this same wave; every real consumer was rewired (`inject(XStore)` → `inject(XFacade)`, import path swap only — nothing else changed at any call site). **This is not the end of the migration.** 26 `*-store.ts` files remain — `live`, `fleet`, `telemetry`, `detections`, the four map-data stores, `map`, `geofence`, `geo`, `events`, `system-events`, `system-status`, `weather`, `ops`, `rc`, `training`, `discovery`, `pairing`, `cv-trace`, plus the three feature-local ones and `ui-store.ts` (which stays, by decision) — waves N3–N8.

**`SeatFacade` decision: page-scoped facade over an app-wide slice, not injector scoping.** `SeatStore` was page-provided (`CrewPage`/`CockpitPage` each got their own instance) specifically so two hosts tracking two different assets could never see each other's seat data. NgRx slices are registered once, app-wide, so that isolation had to move into the *data shape* instead of the DI tree: the slice's state is `Record<assetId, SeatsResponse | undefined>`, and its poll/renewal effects run through `groupBy(assetId) + mergeMap`, giving each asset id its own independent, non-cancelling inner pipeline — one host's poll or renewal can never cancel or clobber another asset's in-flight one, which a naive `switchMap` keyed only on the action stream would. `SeatFacade` itself stays `@Injectable()` and page-provided, exactly as before (`CrewPage`'s/`CockpitPage`'s own `providers:`) — only the storage moved app-wide, not the facade's lifetime, so a consumer's construction/teardown story is unchanged.

**`AuthFacade`'s `ready: Promise<void>` — a corrected design from the plan's own tentative sketch.** The plan's working assumption going in was a boot effect gated on `ROOT_EFFECTS_INIT`. That was dropped: `AuthFacade`'s constructor now dispatches `AuthPageActions.bootRequested()` itself and builds `ready` via the existing `dispatchAndAwait` bridge (`core/state/dispatch-bridge.ts`, unmodified, reused verbatim), listening for `AuthApiActions.meLoaded`/`meLoadFailed`. The triggering effect, `bootMe$`, is an ordinary `ofType(AuthPageActions.bootRequested)` effect — nothing `ROOT_EFFECTS_INIT`-special about it. This is race-free without that hook because Angular's `ENVIRONMENT_INITIALIZER` ordering guarantees `provideEffects(...)`'s own initializer (which subscribes every registered effect, `bootMe$` included) runs before any consumer can be constructed and reach the facade's own constructor — by the time `AuthFacade`'s constructor dispatches, something is already listening. Flagging this here because it is a deviation from the plan's own sketch, reasoned through and empirically checked (see the Router-DI finding below for the verification method), not a shortcut.

**`login`/`logout`/`changePassword`/`bootstrap`/`bootstrapRequired` — two shapes, not one.** `login`, `changePassword` and `bootstrap` each have a natural success/failure action pair (`loginSucceeded`/`loginFailed`, etc.) and return their `Promise<T>` through `dispatchAndAwait` exactly like org's five mutations already do. `logout` and `bootstrapRequired` do not — both mirror the original `AuthStore`'s own contract of never throwing/rejecting, always resolving with a safe default — so both instead await one specific action directly: `firstValueFrom(actions$.pipe(ofType(X), take(1)))`, dispatched immediately after. `logout` in particular is a two-stage effect chain: `logout$` (reads `wasAuthEnabled` via `concatLatestFrom` *before* the reducer clears it, best-effort calls `POST /api/auth/logout`, always emits `logoutCompleted`) feeds `logoutSideEffects$` (conditionally stops `LiveStore`, navigates, emits `logoutFinished`) — split in two so the state read that must happen before the clear, and the side effects that must happen after it, can't be reordered by accident. `changePassword$` emits *two* actions from one dispatch, in order — `meLoaded` (reusing the exact reducer branch boot already uses, since the original `changePassword()` called into the same session-refresh path) then `changePasswordSucceeded` — via an async IIFE returning a short array flattened with `mergeMap(actions => from(actions))`; a dedicated effects-spec test asserts that exact order, since `dispatchAndAwait`'s listener depends on `changePasswordSucceeded` never overtaking the session refresh it's paired with.

**Router-DI finding (empirically checked, not assumed).** `logoutSideEffects$` injects a plain `Router`, and NgRx eagerly runs every registered effect factory once, synchronously, on first use of the environment injector — so the worry going in was that every existing `provideAppState()`-based spec lacking an explicit `provideRouter(...)` would now break. It doesn't: Angular's own `@angular/build:unit-test` environment supplies a real default `Router` in every spec, confirmed by temporarily probing `bootMe$`/`logoutSideEffects$`'s factory bodies with `console.error` and running an unrelated spec (`seat-facade.spec.ts`) with `--reporters=verbose` — both effects' factories ran regardless, and the injected `Router` was a genuine instance, not a stub. No spec needed a `provideRouter([])` addition anywhere in this wave.

**Two spec files needed a structural change, not just a token swap**, because they provided the old store as a bare class provider rather than through `provideAppState()`: `core/auth/session-interceptor.spec.ts` (the cold-boot circular-DI regression test — `AuthStore` needed only `VisionApi`/`Router`/`LiveStore`, `AuthFacade` additionally needs the NgRx `Store`/`Actions` machinery) and `shared/ui/identity-chip.spec.ts`. Both now provide `provideAppState()` alongside their existing stubs; the circular-DI fix itself in `session-interceptor.ts` (lazy `Injector.get(AuthFacade)`, only inside the 401 branch) was left untouched — the same eager-constructor-time `GET /api/auth/me` race the original comment describes still exists structurally under `AuthFacade`, so the same fix is still required and still correct, confirmed by re-reading the call chain rather than by assumption.

**Consumer rewiring** covered 25 real `inject(AuthStore)` sites plus 9 doc-comment-only files, all via one mechanical `AuthStore`→`AuthFacade` / `auth-store`→`auth-facade` pass — safe because every real consumer used the identical `inject(AuthStore)` pattern. Two doc comments were hand-edited rather than blanket-renamed: `core/state/dispatch-bridge.ts`'s (its "the old `AuthStore#login`/etc." mention is deliberately historical) and `core/auth/auth-logic.ts`'s header (which pointed at a since-removed `AuthStore.loadMe` method, now pointed at `auth.effects.ts`'s `bootMe$` instead).

### Tests / build

`npm run test:ci` — **229/229 files, 4 368/4 368 tests green** (+9 files / +83 tests over the N0 baseline of 220 files / 4 285 tests: 12 new slice/facade spec files across the four domains, offset by the 4 deleted legacy store specs; net files were already at 227/4 335 after settings+org+seat, auth alone added the final +2 files / +33 tests). `npx tsc --noEmit` clean on both `tsconfig.app.json` and `tsconfig.spec.json`. `npx ng build --configuration production` — exit 0 (only pre-existing, unrelated warnings: an `NG8107` optional-chain hint in `cockpit.html` and a CSS budget warning on `tactical-map.css`, both present in the pre-N2 baseline too).

**Bundle cost, measured honestly.** Built the full wave-N2 tree: **499.93 kB raw / 142.82 kB transfer** initial total. Then built the exact commit this wave branched from (`cda2429b` — N0 plus plan docs, **not** including N1, which landed on the migration branch in parallel) in a throwaway `git worktree` with a symlinked `node_modules` to skip a slow reinstall: **487.46 kB raw / 137.10 kB transfer**. Wave N2's four slices combined cost **+12.47 kB raw (+2.56%) / +5.72 kB transfer (+4.17%)** over that N0 baseline. Because N1 was measured against the same baseline rather than against N2's tip, **these two waves' deltas must not be added together** — the merged tree was re-measured by the orchestrator after both landed, and that number is the one to trust. The scratch worktree was removed after measuring.

- **Commit** (suggested; this agent does not commit per its task — the orchestrator commits each wave): `feat(ngrx N2): the session slices — auth, org, settings, seat`.

### Merge note — N1 + N2 on `feat/ngrx-migration`, 2026-09-18

The two waves were built in parallel worktrees off the same N0 tip, so their separately-measured
bundle deltas do not compose. Merged and re-verified by the orchestrator on the combined tree:
**231/231 files, 4 387/4 387 tests green**, `tsc --noEmit` clean on both configs, production build
exit 0 at **500.83 kB raw / 142.55 kB transfer**.

That is **835 bytes over `angular.json`'s 500 kB warning budget** (the 550 kB error budget is what
keeps the build green). Left as a warning on purpose: raising it now would silence the only signal
that tracks this migration's cost, and every wave from N3 on deletes a hand-rolled store.

Six files conflicted, all of them predictable and none of them subtle: `core/state/app-state.ts`
(both waves registered a slice — kept both), `shared/ui/identity-chip.ts`/`.spec.ts` (each wave
swapped a different `inject()` in the same two lines — kept both), `core/ui/overlay-store.ts` (N1
deleted it, N2 had only renamed a doc comment inside it — stayed deleted), and the two module docs
(both appended their own section — kept both, N1 then N2).

Three claims in N2's own entry were corrected while merging rather than left standing: it said every
hand-rolled store the plan named was now a slice (26 remain), it described its bundle baseline as
"after N0+N1" when that commit predated N1, and its suggested commit subject repeated the first
error. Also repointed seven doc comments in `return-home-button.ts`/`page-bar.ts` that still named
`GlobalOverlayStore` methods as if the class existed.

## Status — NGRX-MIGRATION wave N3: the live slice — one SSE connection becomes a full NgRx slice + a `LiveGateway` seam (docs/plans/active/NGRX-MIGRATION-PLAN.md §4 row N3) — 2026-09-18

**Scope.** `core/live/live-store.ts` (735 lines, the app's one `GET /api/live` SSE connection, 25
real consumer files — 26 counting `core/cv-trace/cv-trace-store.ts`, missed by an initial `grep`
survey and caught by `tsc`, see the tooling note below) became `core/live/state/live.{model,actions,
reducer,effects}.ts` + `core/live/live-facade.ts` + `core/live/live-gateway.ts`, registered in
`provideAppState()`. `LiveStore` and its behaviour (ref-counted topic subscribe/unsubscribe,
append-only per-topic logs, the manual retry loop on a fatal SSE close, the accepted "ref-count
change during a native auto-retry" gap) carry over unchanged; every consumer's own `inject(LiveStore)`
became `inject(LiveFacade)`, nothing else, because `LiveFacade` kept every one of the old class's
signal and method names on purpose. Left untouched, correctly: nine files whose only `LiveStore`
mention is historical doc-comment prose (`attention-logic.ts`, `geofence-logic.ts`,
`map-event-logic.ts`, `pairing-logic.ts`, `system-events-logic.ts`, `notification-logic.ts`,
`wall-tile.ts`, `auth.actions.ts`, `auth.reducer.ts`) plus `core/api/vision-api.ts` and
`core/rc/manual-control-client.ts`; `core/api/models.ts`'s own stale `LiveEnvelope` doc comment
(same missing-`links` defect category as the heading fixed below) is outside `core/live/`'s file
scope and was left alone.

**`LiveGateway` is this migration's first seam built specifically to keep a non-serializable browser
object out of state**, following `OverlayHostRegistry`'s N1 precedent rather than inventing a new
shape: a plain `providedIn: 'root'` class, `isAvailable()` reads `typeof EventSource !== 'undefined'`
(false under jsdom — every spec's own confirmation of "no `EventSource` at all" rather than an
assumption), `open(topics)` returns a cold `Observable<LiveGatewayEvent>` that owns exactly one real
`EventSource` for its subscription's lifetime and closes it on teardown. `live.effects.ts#connection$`
is the only subscriber, mapping each `LiveGatewayEvent` (`open`/`connected`/`message`/`retrying`/
`fatal`) to a `LiveSocketActions` dispatch and owning the `SSE_RETRY_INTERVAL_MS` manual retry loop
after a fatal close — a *transient* drop is the browser's own native reconnect, reported as
`'retrying'` with no action from this code, which is exactly where the inherited gap lives: a topic
tracked or untracked while a native auto-retry is in flight is missed until the next full reconnect,
since the browser silently re-fetches the previous URL. **Preserved, not fixed** — the task's own
instruction, and the honest thing to do given the alternative (canceling and reopening on every
tracker change) would defeat the ref-counting's whole purpose of coalescing rapid mount/unmount
churn into one PATCH.

**Ref-counting split cleanly across the reducer/effects boundary using one NgRx guarantee**: a
dispatched action's reducer always runs before any effect observes that same action. `topicRefs:
Record<string, number>` lives in the reducer; `patchOnTrack$`/`patchOnUntrack$` read
`topicRefs[topic]` immediately after via `concatLatestFrom`, so `=== 1` reliably means "I am the
first subscriber, PATCH add" and an absent key after decrementing reliably means "I was the last,
PATCH remove" — no separate counter or lock needed on the effects side.

**Topic count doc defect, verified rather than assumed**: the old class's doc comment (and this
file's own stores table, now fixed) said "Twelve topics now, twelve projected stores." Counting
`LiveEnvelope`'s actual discriminated union in `core/api/models.ts` gives **fourteen** — `fleet`,
`event`, `telemetry`, `detections`, `devices`, `detection-events`, `map`, `geo`, `discovery`,
`zones`, `system`, `tracks`, `cv-trace`, `links` — and thirteen store classes project them (`tracks`
and `geo` each get their own dedicated projection *and* feed a second facade reader —
`worldObjectsFor`/`geoFor` — off the same topic, which is also why there are **seven** `xFor` reader
methods but only **six** independent `trackX`/`untrackX` ref-count pairs: `worldObjectsFor` has no
pair of its own, it piggybacks on `trackWorldObjects`/`untrackWorldObjects`'s `tracks:<assetId>`
subscription, same as `tracksFor` does). `LiveFacade`'s class doc now reads "Fourteen topics now."

**A production defect this wave found and fixed, not a test artifact — flagging for N4–N8 the same
way N1 flagged the Router-DI finding for this one.** `auth.effects.ts`'s `reconnectLiveOnSession$`/
`logoutSideEffects$` injected `LiveFacade` as an eager `createEffect` factory default parameter —
the same idiom every other effect in the file uses for `Actions`/`VisionApi`/`Router`/`Store`, all of
which are side-effect-free to construct. `LiveFacade` is not: its constructor dispatches
`LivePageActions.reconnectRequested()` once, on construction, mirroring `AuthFacade`'s own
constructor-dispatch precedent from N2. Read `@ngrx/effects`' own source
(`node_modules/@ngrx/effects/fesm2022/ngrx-effects.mjs`, `EffectsRootModule`'s constructor): it calls
`runner.start()` — subscribing the merged effect stream — **before** looping over every registered
effects group and calling `sources.addEffects(group)` for each, in `provideEffects(...)`'s own array
order. `app-state.ts` registers `authEffects` ahead of `liveEffects`. Resolving `authEffects`'s
functional-effect factories therefore eagerly constructs `LiveFacade` — running its constructor's own
`reconnect()` dispatch — **before** `liveEffects.connection$` has even been created to receive it,
silently dropping that boot-time reconnect on every cold load. This was masked in practice, not
absent: `AuthFacade`'s own later `bootstrapSucceeded`/`loginSucceeded` dispatch re-triggers
`reconnectLiveOnSession$` well after every effect is live, so the connection still opens — just never
from the constructor path the class's own doc comment claims, and never at all if that later dispatch
were ever skipped. Caught by `live-facade.spec.ts`'s "degrades to closed shortly after construction"
case, confirmed deterministic (not flaky, reproduced across repeated runs) and root-caused by
bisection against a byte-for-byte copy of `live-facade.ts` under a different class name in a
different file (which passed, isolating the cause to *which* class token `auth.effects.ts` itself
eagerly resolves, not to `LiveFacade`'s own shape or field count). **Fixed** by replacing the eager
`liveStore = inject(LiveFacade)` parameter with `injector = inject(Injector)` and resolving
`injector.get(LiveFacade)` **inside** each effect's own `tap`/`switchMap` callback instead — deferring
construction until the action genuinely fires, by which point every effect (including `connection$`)
is already subscribed. **The general lesson for later waves**: any effects file that eagerly injects
(as a factory default parameter, not inside the pipeline) a facade whose constructor has a dispatch or
other side effect is order-dependent on `provideEffects(...)`'s array position in a way that is easy
to get right by accident and easy to break silently — resolve such a facade lazily via `Injector.get`
inside the operator chain instead, every time.

**Tooling note: the interactive shell's `grep` is aliased to `ugrep` with flags that produce false
negatives.** `grep -rn 'LiveStore' src/` missed the real `import { LiveStore } from '../live/
live-store'` in `core/cv-trace/cv-trace-store.ts` (caught only once `tsc --noEmit` reported the now-
dangling import) and separately misreported a "binary file" match on a file containing a legitimate
raw NUL byte (`cv-trace-store.ts`'s own `` `${streamId}\x00${assetId ?? ''}\x00${last}` `` cache-key
delimiter — confirmed pre-existing via `git show HEAD:...`, not introduced by this wave, left
untouched). **Use `command grep` to bypass the shell alias for any search whose completeness matters.**

### Tests / build

`npm run test:ci` — **234/234 files, 4443/4443 tests green** (+3 files / +56 tests over the N1+N2
merged baseline of 231/4387: `live.reducer.spec.ts` (33 tests), `live.effects.spec.ts` (13 tests),
`live-facade.spec.ts` (10 tests) — no legacy spec file existed for `live-store.ts` to delete, unlike
every prior wave). `npx tsc --noEmit` clean on both `tsconfig.app.json` and `tsconfig.spec.json`.
`npx ng build --configuration production` — exit 0 (same two pre-existing, unrelated warnings as
every prior wave: the `NG8107` optional-chain hint in `cockpit.html` and the CSS budget warning on
`tactical-map.css`).

**Bundle cost, measured honestly.** This wave's tree: **505.87 kB raw / 143.74 kB transfer** initial
total. Rebuilt the exact commit this wave branched from (`e67ae16a`, N1+N2 merged) via `git stash
push -u` in this same worktree rather than a separate throwaway one (no reinstall needed either way —
`node_modules` is symlinked): **500.83 kB raw / 142.62 kB transfer**. This wave's own cost: **+5.04 kB
raw (+1.0%) / +1.12 kB transfer (+0.8%)**. `angular.json`'s initial-bundle budget is 500 kB warn /
550 kB error — the build stays green, now **5.87 kB over the warning line** (835 bytes of that already
present before this wave, per N1+N2's own entry above). Left as a warning on purpose, same rule as
every prior wave: raising it would silence the only signal tracking this migration's cost, and N4–N8
each delete a hand-rolled store in turn.

**Nothing in the plan itself was found wrong** beyond the stale "Twelve topics" doc-comment heading
(now "Fourteen topics", verified by counting `LiveEnvelope`'s actual union rather than trusting the
old prose) and the 6-pairs-vs-7-readers detail above, which the plan's own recipe didn't need to spell
out but is worth knowing before touching this facade again. The `auth.effects.ts` eager-injection
defect was not a plan defect — it was a pre-existing correctness bug in code the plan's brief never
asked to change, found only because the migration made `LiveFacade`'s construction observable through
NgRx's own effects machinery for the first time (`LiveStore` had no such ordering hazard, being a
plain, self-contained, eagerly-constructed class with no dependency on effects registration order at
all).

- **Commit** (suggested; this agent does not commit per its task — the orchestrator commits each wave): `feat(ngrx N3): the live slice — SSE connection as an NgRx slice + LiveGateway seam, and an auth.effects.ts eager-injection fix`.

## Status — NGRX-MIGRATION wave N7: the ops slices — events, discovery, pairing, geo, training, rc, weather, thresholds (docs/plans/active/NGRX-MIGRATION-PLAN.md §4 row N7) — 2026-09-19

Eight stores, ~1 420 lines, converted with the idioms N2/N3/N5 established. Three things are worth
recording beyond the mechanical conversion.

**Two demand-gate shapes, both now named.** A *root-singleton ref-count* (`discovery`, `events`)
keeps `activeConsumers: number` in state, moved by plain reducer handlers, while the poll-vs-live
phase is computed inside the effects file from `combineLatest([selectActiveConsumers,
selectConnectionState])` — never by injecting `LiveFacade` into an effect (plan §9). A
*`Record`-keyed slice* (`weather`, `geo`, `pairing`) gives two hosts or assets state that cannot
collide. `weather-facade.spec.ts` proves the second the only way that means anything: it builds two
independent `WeatherFacade` instances off one shared parent injector with `Injector.create`, tracks
each host's own signal, and asserts a poll resolving for host A never moves host B's reading.

**`EventsStore#applyIncoming` mixed a pure merge with impure browser reads** — `Notification.permission`
and `document.hidden` — which a reducer may not do. The merge stayed in `events.reducer.ts`; the
notify decision became its own `notify$` effect (`dispatch: false`) holding a closure-scoped
`seenIds: Set<string>` created once when the effect factory runs at bootstrap, which is exactly the
lifetime the original class field had. This is the general shape for any "store method that was
half data and half browser".

**Two live phases that look inconsistent and are not.** `events`' `'live'` phase is `EMPTY` — no
sidecar poll — while `discovery`'s still fires one reconcile `GET`. That asymmetry is deliberate and
predates the migration: the `discovery` SSE topic carries only candidate deltas and never `sources`,
so the reconcile `GET` is the only channel `sources` has left once the poll stops. Confirmed against
the original sources rather than regularised; a reducer-level test now pins it.

### Tests / build

`npm run test:ci` — **251/251 files, 4 604/4 604 tests green**. `npx tsc --noEmit` clean on both
configs. `npx ng build --configuration production` — exit 0. Bundle measured against this wave's own
base (`93af4a4d`) on a disposable worktree: **505.87 → 530.49 kB raw, 143.69 → 152.40 kB transfer
(+24.62 kB raw / +8.71 kB transfer)** for eight stores' worth of slice scaffolding — the largest
single-wave delta so far, and consistent with the trend the N5 entry records: a slice ships more
code than the class it replaces.
