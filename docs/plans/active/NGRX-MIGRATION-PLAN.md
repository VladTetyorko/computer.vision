# NGRX-MIGRATION-PLAN — one state engine for `vision-web`

**Owner's ask, 2026-09-18:** *"move from injections to the signals in angular application … as a
final result I need a well structured ngrx application … with all the reducers, actions, effects and
so on."*

**State:** N0–N3 + N5 **BUILT** 2026-09-18 on `feat/ngrx-migration` (`91143666`), not yet on `master`. 11 slices done, **22 hand-rolled stores remain**. N6/N7 in flight; N4, N8, N9 open.

---

## 1. What is actually being replaced

`vision-web` is **already signal-based**: 1 137 `signal`/`computed`/`effect` call sites, 508
`inject()` call sites, **zero** `BehaviorSubject`/`Subject`, and no `zone.js` dependency at all
(zoneless). "Moving from injections to signals" is therefore not the work — the signals are there.
What is missing is a state **engine**. Shared state lives in **32 hand-rolled store classes
(9 026 lines)**, each re-inventing the same five mechanisms:

| Re-invented in every store | Replaced by |
|---|---|
| private `signal()` + public `.asReadonly()` pairs | reducer + `createFeature` selectors |
| `async` methods wrapping `VisionApi` in `try/catch` | effects |
| a per-store `run()` helper firing one error toast | one `notify$` effect per slice, on `*Failure` |
| `PollScheduler` timers + "pause while SSE is open" | one polling effect gated by a selector |
| ad-hoc `localStorage` reads/writes | one hydration meta-reducer |

**38 facades (11 291 lines)** then inject those stores and re-export them wholesale
(`readonly fleet = inject(FleetStore)`), so templates read `facade.fleet.devices()` — store
internals leak all the way into HTML. This migration closes that too.

Baseline at the start of the work: **217 spec files / 4 261 tests green**.

## 2. Target architecture

```
Component ──reads Signal / calls method──▶ Facade ──dispatch(action)──▶ Store
    ▲                                        │                            │
    └────────── selectSignal(selector) ───────┘                     Effects ──▶ VisionApi / gateways
```

- **Component** — template plus *ephemeral local* signals only (hover, a form draft, a toggle
  nothing else can observe). Never injects `Store`, `VisionApi`, or a slice.
- **Facade** — the only layer that knows an action exists. Two kinds, same rules:
  - *page facade* — `@Injectable()`, listed in the page's `providers`, one per routed feature (today's 38).
  - *core facade* — `@Injectable({providedIn:'root'})`, one per cross-cutting core slice, replacing
    today's root store class 1:1 so a consumer's change is `inject(ThemeStore)` → `inject(ThemeFacade)`
    and nothing else.
- **Store** — actions / reducer / selectors. Pure, synchronous, framework-free.
- **Effects** — every async thing: HTTP, SSE, timers, navigation, toasts, storage writes, DOM writes.
- **Service** — `VisionApi` (unchanged) and the gateways (`LiveGateway`, `WebSerialGateway`, …).
  Dumb I/O, no state.

**No component injects `Store` directly.** `core/ui/architecture.spec.ts` already fails any routed
page that injects something ending in `Store`, which catches NgRx's own `Store` for free; the guard
is extended in N0 to cover non-page components too.

### Version pin
`@ngrx/{store,effects,entity,router-store,operators} ^21.1.1`, `@ngrx/store-devtools ^21.1.1` (dev).
21.x is the last line declaring `@angular/core ^21.0.0` — **NgRx 22 is wrong for this repo**, it
requires Angular 22.

### Where a slice lives — by responsibility, never in one global `state/` dump
```
core/<domain>/state/<domain>.actions.ts     # createActionGroup, one group per event source
core/<domain>/state/<domain>.reducer.ts     # createFeature({name, reducer, extraSelectors})
core/<domain>/state/<domain>.effects.ts     # functional effects
core/<domain>/state/<domain>.model.ts       # State interface + initialState
core/<domain>/<domain>-facade.ts            # the signal/dispatch boundary
features/<feature>/state/…                  # page-scoped slices, provided on the route
```

### Registration
- `app.config.ts`: `provideStore({}, {metaReducers, runtimeChecks})`, `provideEffects()`,
  `provideRouterStore()`, and `provideStoreDevtools()` in dev builds only.
- App-wide slices (everything `providedIn:'root'` today): `provideState(feature)` +
  `provideEffects(...)` in `app.config.ts`.
- Page-scoped slices: in that feature's `*.routes.ts` `providers:` — lazy, torn down with the page.

## 3. Frozen conventions — the law every wave follows

1. **Actions are events, not commands.** `createActionGroup({source, events})`, at most three sources
   per slice: `'<X> Page'` (user intent), `'<X> API'` (the server answered), `'<X> Socket'` (live
   push). Never reuse one action across two sources.
2. **Event names read as facts** — `'Devices Loaded Success'`, `'Stream Start Failed'`, not
   `'loadDevices'`.
3. **`createFeature` is mandatory.** It generates `selectXState` plus one selector per state key;
   hand-write only derived selectors, inside `extraSelectors`.
4. **Collections use `@ngrx/entity`** (devices, streams, assets, marks, layers, drawings, tracks,
   candidates, links). Ordering comes from a `sortComparer` + `selectAll`, never from a template.
5. **Reducers are pure.** No `inject()`, no `Date.now()`, no `Math.random()`, no `localStorage`, no
   DOM. A timestamp is carried *in* the action — effects read the clock.
6. **Effects own every side effect, including the toast.** One `notify$` per slice maps `*Failure`
   to `ToastService.error(describeHttpError(...))`, preserving today's rule: reported once, by the
   layer that knows what the user was trying to do.
7. **Failure is an action, never a swallowed `catch`.** Today's deliberately silent degrades
   (`loadModels`, `loadTrackers`, `getStreamTracks`) stay silent — as a `*Failed` action the reducer
   handles, not as a `console.warn` nothing can select on.
8. **Facades expose `Signal<T>` only.** No store re-export, no `Observable` in a template, no `| async`.
9. **Cross-slice reads go through selectors.** A slice never imports another slice's reducer.
10. **Every slice ships its specs in the same wave**: reducer (pure), selectors (projector), effects
    (`provideMockActions`), facade (`provideMockStore`).
11. **`runtimeChecks` stay on** (`strictStateImmutability`, `strictActionImmutability`,
    `strictStateSerializability`, `strictActionSerializability`) — the app's state is plain data.

## 4. Waves — each ends green on `npm run test:ci`

| Wave | Folder scope (disjoint) | Slices |
|---|---|---|
| **N0** foundation | `app.config.ts`, `core/state/`, `core/shell/`, `core/ui/architecture.spec.ts` | pilot: `theme`, `sidebar` |
| N1 shell/UI | `core/ui/` | `overlay` **only** — see §8, `UiStore` is not a slice |
| N2 session | `core/auth/`, `core/org/`, `core/seat/`, `core/settings/` | 4 — `seat` is page-provided, see §8 |
| N3 live backbone | `core/live/` | `live` (+ `LiveGateway` seam) |
| N4 fleet | `core/fleet/`, `core/system-status/`, `core/system-events/` | **2** (fleet = entity) — `system-events` shipped as a renamed facade with no slice at all, not a third slice; see §9's N4 row and `station/vision-web/MODULE.md`'s `core/state/` section for the reasoning |
| N5 perception | `core/telemetry/`, `core/detections/`, `core/cv-trace/` | 3 |
| N6 map | `core/map/`, `core/map-data/`, `core/geofence/` | 7 |
| N7 ops | `core/ops/`, `core/weather/`, `core/training/`, `core/rc/`, `core/discovery/`, `core/pairing/`, `core/geo/`, `core/events/` | 8 |
| N8 feature slices | `features/onboarding/`, `features/fly/grounding-store.ts` | 2 (+ facade rewiring) — held exactly; `features/inventory/inventory-view-store.ts` was **not** a third, see §9's N8a row |
| N9 close-out | `core/api/`, guards, `MODULE.md` | flip `VisionApi` to Observables, delete dead helpers |

N1…N7 are disjoint by folder and run three agents at a time.

## 5. Per-store migration recipe

1. Read the store's public surface. Every `readonly x = …asReadonly()` is a state key; every `async`
   method is one action triple (`… Requested` / `… Success` / `… Failure`).
2. `<x>.model.ts` — `interface XState` + `initialState`. Collections become `EntityState`.
3. `<x>.actions.ts` — one group per source.
4. `<x>.reducer.ts` — `createFeature`; today's `computed()` become `extraSelectors`.
5. `<x>.effects.ts` — HTTP, polling, live routing, toasts, storage/DOM writes.
6. `<x>-facade.ts` — `selectSignal` projections named **exactly** as the old store's signals, plus
   dispatch methods named exactly as the old store's methods. That is what keeps step 7 mechanical.
7. Rewire consumers: `inject(XStore)` → `inject(XFacade)`. Nothing else changes at the call site.
8. Port the store's spec into reducer/effect/selector specs.
9. **Delete the legacy store class in the same wave.** Never two engines for one piece of state.

## 6. Guards (`core/ui/architecture.spec.ts`, extended in N0)

- routed pages inject neither `VisionApi` nor anything ending in `Store` — *already enforced*, and it
  catches NgRx's `Store` for free;
- **new:** no component (routed or not) injects NgRx `Store` — only a `*Facade` may;
- **new:** `*.reducer.ts` contains no `inject(`, `Date.now(`, `Math.random(`, `localStorage`, `document`;
- **new:** every `*.reducer.ts` has a sibling `*.actions.ts` and a spec.

## 7. `VisionApi` stays Promise-shaped until N9 — deliberately

`core/api/vision-api.ts` is 1 802 lines, 162 methods, every one `firstValueFrom(this.http.…)` →
`Promise<T>`, with **289 call sites across 70 source files and 265 more in specs**. Effects want
Observables. Two routes exist: flip the service in N0 and touch ~135 files up front, or let effects
wrap the promise and flip in N9, once the legacy stores holding most of those call sites are gone.

**N9 wins.** The up-front flip spends a 135-file blast radius on files this migration is about to
delete, and leaves the tree half-converted in two dimensions at once. The cost of waiting is
cancellation: `switchMap` over `from(promise)` discards a superseded *result* but does not abort the
request. This app polls and does CRUD — it has no typeahead — so the difference is nil until N9 makes
it exact.

**Rule while this holds:** an effect calls a Promise-returning `VisionApi` method **only** through
`from(...)`/`defer(...)` inside a flattening operator. No `async` effect bodies. No new
Promise-returning method is added to `VisionApi` after N0 — new endpoints land Observable-shaped.

## 8. Risks

- **`core/live/live-store.ts` (735 lines)** is the one slice with real protocol behaviour: twelve SSE
  topics, ref-counted `trackX`/`untrackX` pairs, and a PATCH that renegotiates the topic list. It
  gets its own wave and a `LiveGateway` seam so `EventSource` stays testable — jsdom has none.
- **`features/onboarding/onboarding-store.ts` is 1 652 lines** — its own wave, late, after the idiom
  is proven everywhere else.
- **`WeatherStore` is deliberately page-provided, one instance per host** (Command centres on the
  fleet centroid, Fly on the flown asset). NgRx feature state is global by name, so this slice must
  key its readings by host rather than collapse into one shared `reading` — N7 owns that.
- Hydration double-firing: persistence is a meta-reducer, never an effect.

**Corrections found while briefing N1/N2 (2026-09-18) — the wave table above is amended, not the code.**

- **`core/ui/ui-store.ts#UiStore` is not a slice and must not become one.** The wave table originally
  listed a `ui` slice; there is no such thing to migrate. `UiStore` is a deliberately plain class with
  no DI token, instantiated **27 times across the app** as a host-owned field
  (`readonly dialogs = new UiStore()`, `new UiStore(ACTIVE_PANEL_KEY)`) — one instance per independent
  overlay *group*, which is the entire point: overlays that must be exclusive share an instance,
  overlays that may overlap get separate ones. NgRx feature state is global by name and cannot express
  27 independent instances without inventing a key for each. It is also exactly what §2 calls
  *ephemeral local* state. **N1 migrates `GlobalOverlayStore` only; `UiStore` stays as it is.**
- **`GlobalOverlayStore` splits in two, because half of it is not serializable.** Its `OverlayHost`
  registry holds live `HTMLElement` references (`root`, `trigger`) and it owns a `Router.events`
  subscription plus `document`-level `Escape`/outside-click listeners. Only the *open overlay id*
  goes into state (a `GlobalOverlayId | null`); the element registry stays in a small root service the
  effects inject, and the three listeners become effects. Putting an element in state would trip
  `strictStateSerializability` on the first `register()` — the check is doing its job, so keep the
  DOM out of the store rather than weakening the check.
- **`core/seat/seat-store.ts#SeatStore` is `@Injectable()`, not `providedIn:'root'`** — page-provided,
  one instance per host, the same shape §8 already flags for `WeatherStore`. It is therefore **not** an
  app-wide slice: either key its state by `assetId` in one feature slice, or provide it on the route.
  It also has zero `.asReadonly()` pairs, so §5 step 1's "every `asReadonly()` is a state key" does not
  apply — read its actual public surface instead.

## 9. Status

| Wave | State |
|---|---|
| N0 | **BUILT** 2026-09-18, `93c64ad6` on `feat/ngrx-migration` — engine (`core/state/`), pilot slices `theme`+`sidebar`, facades, guard, budgets. 220/220 files · 4 285/4 285 tests green; production build exit 0. Cost measured: **+45.01 kB raw / +13.31 kB transfer**, budget 390/445 → 500/550 kB |
| N1 | **BUILT**, `4cc6b3f4` — `overlay` slice; `GlobalOverlayStore` deleted. Its DOM half (the `HTMLElement` registry) stayed out of state in `core/ui/overlay-host-registry.ts`; the route fence listens for `ROUTER_NAVIGATED`, not `Router` |
| N2 | **BUILT**, `1a3da7f0` — `settings`, `org`, `seat`, `auth`; all four legacy classes deleted. `seat` keys state by `assetId` instead of relying on injector scoping; `core/state/dispatch-bridge.ts#dispatchAndAwait` keeps `Promise`-returning facade commands |
| N1+N2 merged | `f70afb09` — **231/231 files · 4 387/4 387 tests green**, production build exit 0 at **500.83 kB raw / 142.55 kB transfer** (835 B over the 500 kB *warning* budget, under the 550 kB error budget; left as a warning on purpose) |
| N3 | **BUILT**, `b0e0ce19` — the `live` slice + `core/live/live-gateway.ts` (the seam owning the one `EventSource`; specs fake it, jsdom never needs one). 26 consumers rewired, `LiveStore` deleted. **234/234 files · 4 443/4 443 tests green**, build exit 0, bundle 500.83 → 505.87 kB raw |
| N5 | **BUILT**, `91143666` — `telemetry`, `detections`, `cv-trace`; 24 consumers rewired, all three classes deleted. **240/240 files · 4 492/4 492 tests green**, build exit 0, bundle 505.87 → 515.83 kB raw |
| N7 | **BUILT**, `ecc84dd7` — eight slices at once: `training`, `thresholds`, `controlProfile`, `weather`, `geo`, `links`, `discoveryInbox`, `events`; all eight classes deleted. Settled the two demand-gate idioms later waves reuse (per-host page-provided facade with its own live-bridging `effect()`; root-singleton whose ref-count lives in NgRx state, phase computed by cross-`store.select()` inside the effects file) |
| N7 merged | **257/257 files · 4 653/4 653 tests green**, both tsconfigs clean, production build exit 0 at **540.44 kB raw / 155.15 kB transfer** — 40.44 kB over the 500 kB *warning* budget, **9.56 kB under the 550 kB error budget** |
| N6 | **BUILT**, `09fefaca` — `map`, `marks`, `layers`, `drawings`, `tracks`, `route`, `geofence`; all seven classes deleted. `@ngrx/entity` backs the four collections; `FleetMapStore`'s "construction is demand" contract needed an explicit `activeConsumers` ref-count to survive app-wide registration |
| N6 merged | `00d74f12` — **26 slices now NgRx**. 264/264 files · 4 748/4 748 tests green, both tsconfigs clean. **Production build RED**: 587.42 kB raw / 168.30 kB transfer, 37.42 kB over the 550 kB error budget — see the costed options below |
| N-split | **BUILT** 2026-09-19 — owner chose the split over a budget bump. 13 of 26 slices moved to route-level registration; 12 features became `loadChildren` boundaries; 4 facades converted from `providedIn: 'root'` to page-provided. **Production build GREEN**: 587.42 → **541.37 kB raw / 155.34 kB transfer**, exit 0, 8.63 kB under the 550 kB error budget. 264/264 files · 4 754/4 754 tests green, both tsconfigs clean. `angular.json` untouched |
| N4a | **BUILT**, `29aca82f` — the map/org split, paying for N4's own unavoidable root-registration cost (fleet/systemStatus/systemEvents cannot move off the root injector: `app.ts`/`AppSidebar`/`NotificationBell` inject them before any lazy route loads). Six more facades converted from `providedIn: 'root'` to page-provided: `MarksFacade`, `LayersFacade`, `DrawingsFacade`, `TracksFacade`, `GeofenceFacade`, `OrgFacade`. **541.37 → 497.76 kB raw / 155.34 → 143.03 kB transfer**, 52.24 kB headroom under the 550 kB error budget — briefly *under* the 500 kB warning budget too, for the first time since N2. 264/264 files · 4 754/4 754 tests green, both tsconfigs clean |
| N4b | **BUILT** — `FleetStore`→`FleetFacade` (`@ngrx/entity`-backed `devices`/`streams`, 23 consumers rewired), `SystemStatusStore`→`SystemStatusFacade` (5 consumers); both root-registered, neither split-eligible. **`system-events` shipped as `SystemEventsFacade`, a renamed facade with no NgRx slice at all** (2 consumers rewired) — it owns no state, dispatches nothing, and derives entirely from `LiveFacade.liveEvents()` (already NgRx state since N3); giving it its own `createFeature` would be a reducer with no reachable action of its own. **This corrects this table's own N4 row above, which predicted 3 slices** — see `station/vision-web/MODULE.md`'s `core/state/` section for the full reasoning. `getStreamTracks()` stays a direct, undispatched `VisionApi` passthrough on `FleetFacade` (§3 rule 7 named it as needing a `*Failed` action, but its contract is to rethrow the raw error, which cannot be a serializable action payload). 268/268 files · 4 780/4 780 tests green, both tsconfigs clean, production build exit 0 at **512.34 kB raw / 146.06 kB transfer** — 37.66 kB headroom under the 550 kB error budget, back 12.34 kB over the 500 kB warning line (N4's own two new root slices cost +14.58 kB raw / +3.03 kB transfer over N4a's 497.76 kB) |
| N8a | **BUILT** — `GroundingStore`→`GroundingFacade` over `features/fly/state/**`, the **first feature-local slice** in the app (every prior one lives in `core/`). Page-scoped on `fly.page-routes.ts`'s pathless parent, so the picker pays a registration and no fetch — the same posture `thresholds`/`controlProfile` already had. The two derived values are `extraSelectors`, not facade `computed()`s, so the `MAINTENANCE_GROUNDED:` parse lives once beside the state. **`InventoryViewStore` was assessed and deliberately not migrated** — it owns no state at all (two `localStorage` methods, no signals, no fetch, no timer), and the state it guards is one enum among ~20 plain page signals on `InventoryFacade`, so converting exactly that one would be arbitrary while whatever remained would still be the same wrapper a `StateHydrator` would call. Renamed `InventoryViewStorage` instead, with the reasoning in its own class doc — the same "decline the ceremony, write it down" call N4b made for `system-events` |
| N8b | **BUILT** — `OnboardingStore` (1 656 lines, 66 signals, 13 computeds, ~60 methods, 21 `VisionApi` calls) → the `onboardingWizard` slice + `OnboardingWizardFacade`, the **biggest single-file store this migration has converted** and the second (after `overlay`) to need a **non-serializable split**: the photo `File`/`Blob`/object-URL moved to `OnboardingPhotoBuffer`, `preProvenRoles` went `ReadonlySet` → plain array, and the `PollScheduler` handle became `discoveryStatusPoll$`. Page-scoped on `onboarding.page-routes.ts`. **The subtlety worth reusing**: `OnboardingPhotoBuffer` is registered *inside* `provideOnboardingState()`, not in `OnboardingPage`'s component `providers:` — an `@ngrx/effects` class resolves against the **environment** injector, never an element injector, so a service an effect needs must sit at the slice's own level; the wrong placement compiles cleanly and fails only at runtime on the first upload |
| N8 (both) | **273/273 files · 4 847/4 847 tests green**, both tsconfigs clean, production build exit 0 at **512.34 kB raw / 146.04 kB transfer** — raw *unchanged*, transfer 0.02 kB *down*. **The first wave in this migration to cost the initial bundle nothing**, because both slices were page-scoped from the start: the entire wizard ships in the `onboarding` lazy chunk (100.16 kB raw / 21.14 kB transfer). That is the split rule paying off rather than being paid for |
| N9 | open — flip `VisionApi` to Observables; replace the lazy `injector.get(LiveFacade)` in `auth.effects.ts` with a dispatched action. Headroom is 37.66 kB |

### The bundle expectation was wrong — recorded, not quietly dropped

This section previously assumed waves that *delete* a hand-rolled store would claw back the engine's
45 kB. Four waves in, that is false: **N1 +0.89, N2 +12.47, N3 +5.04, N5 +9.96 kB raw**, every one
after deleting the class it replaced. A slice (model + actions + reducer + effects + facade, plus
`createFeature`/`@ngrx/entity` machinery) ships more code than the class it replaces. The initial
bundle is now 40.44 kB over the 500 kB **warning** budget, still under the 550 kB error budget —
but by only **9.56 kB**, which is less than any single wave has cost. **N4, N6 and N8 will break the
build**, and the decision can no longer wait for N9: raise the 550 kB error budget, or spend a wave
on route-level code-splitting for the slices only one feature needs. It is the owner's call either
way — not a number for a wave to bump on its way past.

### The two ways out of the budget, costed — 2026-09-19; **owner chose Option 2, built the same day**

**The production build was RED as of the N6 merge**: 587.42 kB raw against a 550 kB error budget.
`npm run test:ci` and both `tsc` configs were green; only `-c production` failed. Measured so the
owner was choosing between numbers, not guesses. **Outcome: Option 2 shipped — 541.37 kB, exit 0,
`angular.json` unchanged.** What the wave actually did, and what it cost, is below the two options.

**Option 1 — raise `angular.json`'s error budget.** One line. Instant, reversible, and it spends the
visibility the 500 kB warning currently buys. 620 kB would clear N6 and leave room for N4/N8. **Not
taken.**

**Option 2 — wave N-split: stop registering page-scoped slices at the root injector.** Routes are
*already* lazy (48 chunks); what fills the initial bundle is `provideAppState()` registering all 26
slices at root, so a slice only `/fly` ever reads still ships to a visitor who opens `/settings`.

Which slices can move was measured, not guessed. **The test is the facade's own injectability**, not
its consumer count: a `providedIn: 'root'` facade outlives the route that registered its slice, and
would then read selectors of a feature NgRx has already removed. Only page-provided facades
(`@Injectable()` in a component's `providers:`) can move, because facade and slice then share one
lifetime:

| Feature route | Slices it would register |
|---|---|
| `fly` | `telemetry`, `detections`, `weather`, `geo`, `seat` |
| `command` | `weather`, `map`, `route` |
| `crew` | `telemetry`, `detections`, `seat` |
| `live` | `telemetry`, `detections` |
| `wall` | `detections` |
| `asset-detail` | `telemetry`, `links` |
| `cv-inspector` | `cvTrace` |

Registering one feature from two routes is safe — NgRx keys feature state by name. The seven slices
that must stay root are `theme`, `sidebar`, `overlay`, `auth`, `live`, `events` (the always-on
notification bell) and `settings` (`events.effects.ts` selects it); verified by walking what
`app.ts`, the five shell components, `notification-bell`/`identity-chip`, and every root-`providedIn`
service and guard actually inject. No root slice's effects cross-select a movable feature — checked.

**The constraint that makes this a wave rather than an edit**, found while attempting it: route
`providers` must be statically analysable, and every `features/*/*.routes.ts` is **statically
imported** by `app.routes.ts`. Putting `provideState(...)` there pulls the slice straight back into
the initial chunk. The providers must sit behind a `loadChildren` boundary — which removes those
routes from the static `children` tree that `features/hubs/route-audit-logic.ts#flattenRoutes` walks,
and `app.routes.spec.ts`'s "no dead link" suite plus the in-app route audit both depend on that walk.
So N-split is: features converted to `loadChildren`, **plus** teaching the route audit to
resolve a `loadChildren` boundary. Do not attempt it as a tail-end fix to another wave.

**Not decided by an agent.** Option 1 is a policy change about what the project is willing to ship;
it belongs to the owner, and no wave may take it unilaterally on its way past.

### What N-split actually shipped — 2026-09-19

**587.42 → 541.37 kB raw (−46.05 kB), 168.30 → 155.34 kB transfer, exit 0, 8.63 kB of headroom.**
264/264 files · 4 754/4 754 tests · both tsconfigs clean. `angular.json` untouched.

The table above under-counted, in two ways found while building it.

**Five more slices were in reach than the table listed, and the ceiling was measured before anything
was converted.** Stripping the five largest remaining root slices from `app-state.ts` and building
once (then reverting) put the ceiling at **19.09 kB for five** — arithmetic instead of a guess, and
the reason four facades were converted rather than all five. Do this probe first in N4/N8 too.

**The eligibility rule has a usable form.** "Only a page-provided facade's slice may move" is the
*invariant*; the test that decides a candidate is **"does every class injecting this facade already
sit behind a lazy route?"**. If yes, converting the facade from `providedIn: 'root'` to
`@Injectable()` in its host page's `providers:` is part of the split, not a byte-chase — but it is a
real behaviour change (state loaded per page visit, not per session; two pages that shared a fetch
now each fetch) and belongs in the facade's own doc comment. Four passed: `ThresholdsFacade` (fly),
`ControlProfileFacade` (fly + manage/controller), `TrainingFacade` (manage/training + replay),
`DiscoveryInboxFacade` (assets + add-source). **`OrgFacade` failed and stayed root** —
`shared/map/map-controls/layer-manager.ts` injects it and that control renders on several map
surfaces at once. Final split: **13 root slices, 13 route-registered**.

**Two cascades the plan did not predict, both now guarded by tests:**

- **A `loadChildren` boundary prefix-matches** where a `loadComponent` route only matched with no
  leftovers. `/assets` would swallow `/assets/:assetId`; `/manage/training` would swallow
  `/manage/training/models`. Both families are ordered longest-path-first in `app.routes.ts`
  (`INVENTORY_ROUTES` moved below `ASSET_DETAIL_ROUTES`), and `app.routes.spec.ts` asserts that order
  **by index** — the router is not guaranteed to backtrack out of a child-match failure, so ordering
  is the whole guarantee. Same trap CREW-CONTROL's `pathMatch` finding recorded.
- **`findRouteByPath` in `app.routes.spec.ts` went blind too**, not just `flattenRoutes`. Both now
  resolve `loadChildren` (the former async, the latter via the new `flattenRoutesDeep`). That the
  "no dead link" suite passes unchanged is the proof the split moved no URL.

### N5 found a convention that does not generalise

Poll-vs-live is *exclusive* everywhere except `cv-trace`, where poll and live run **concurrently by
design**: `gate`/`world` have no live topic at all (the 3 s poll is their only freshness source) and
`frame` is a ring the poll authoritatively replaces every tick, into which a live arrival merely
merges between ticks. `CvTraceFacade` therefore has no transport selector. A later wave that
"regularises" this will silently drop `gate`/`world` freshness.

### Two things N3 established that later waves must not undo

**A cross-slice *command* from an effect must be lazy — or better, an action.** N2's
`auth.effects.ts` injected `LiveFacade` as an eager `createEffect` factory default parameter.
`@ngrx/effects`' `EffectsRootModule` calls `runner.start()` **before** iterating `provideEffects()`'s
groups, and `authEffects` registers ahead of `liveEffects`, so resolving the auth factories
constructed `LiveFacade` — running its constructor's `reconnect()` dispatch — before `connection$`
existed to receive it. Production masked it because `bootstrapSucceeded` re-triggers the reconnect
later. N3 fixed it by resolving `LiveFacade` lazily inside each effect callback.

> **Open follow-up, deliberately not done in N3:** the lazy `injector.get(LiveFacade)` fixes the
> ordering hazard but keeps a slice's effect calling another slice's facade. Dispatching a `live`
> action instead would remove the coupling *and* the hazard. Left alone rather than rewritten on a
> hunch under a green suite — pick it up in N9's close-out.

**Registration order in `provideAppState()` is load-bearing.** Anything a wave adds there can change
which singleton constructs first. If a new effect needs another slice, dispatch to it; don't inject
its facade eagerly.

### Wave order amended, 2026-09-18

The table runs N4 → N7; the *execution* order is **N5, N6, N7 in parallel, then N4 alone**. Measured
consumer overlap decides it: N5∩N6 = 3 files, N5∩N7 = 4, N6∩N7 = 3, but N4 overlaps N5 by 9, N7 by 8
and N6 by 5 — N4 touches the most shared feature facades of the four, so it runs on a settled tree
rather than against two moving ones.

**Carry into N1.** Two facts N0 established that every later wave depends on: (a) a spec that needs
real state calls `provideAppState()` — never a hand-rolled `provideStore` — so adding a slice there is
part of the wave, not a follow-up; (b) the guard is now a real gate, and an effect *may* inject
`Store` while a component may not. And one to re-check, not assume: the bundle only grows from here
until a wave **deletes** its legacy store, so re-measure against the previous wave's tip rather than
reading the number above.

---

## 10. Reference implementation — read these before writing a slice

N0 shipped a complete, working slice twice. **Copy its shape; do not invent a second idiom.**

| Read | For |
|---|---|
| `core/shell/state/sidebar.model.ts` | `interface XState` + `initialState`, nothing else in the file |
| `core/shell/state/sidebar.actions.ts` | `createActionGroup` — one group per event source, events named as facts |
| `core/shell/state/sidebar.reducer.ts` | `createFeature` + `extraSelectors` (the old `computed()`s live here, as pure functions of state) |
| `core/shell/state/sidebar.effects.ts` | functional effects, `concatLatestFrom` for state reads, `{dispatch:false}` for pure side effects, exported as one `xEffects` object |
| `core/shell/state/sidebar.hydration.ts` | a `StateHydrator` — returns `undefined` for anything it cannot trust, never a partial guess |
| `core/shell/sidebar-facade.ts` | `selectSignal` reads + dispatch methods, **named exactly as the store class being replaced** |
| `core/state/app-state.ts` | where a new app-wide slice and its effects get registered |
| `core/shell/state/sidebar.reducer.spec.ts`, `core/shell/sidebar-facade.spec.ts` | the two spec shapes: pure reducer cases, and a facade case driven through the real `provideAppState()` |

### Non-negotiables for every wave

1. **`npm run test:ci`** from `station/vision-web` — never a bare `npx vitest run` (it fakes ~536
   failures). The whole suite must be green when you finish, not just your own files.
2. **`npx tsc --noEmit -p tsconfig.app.json` and `-p tsconfig.spec.json`** — 0 errors on both.
3. **Delete the legacy store class and its spec in your own wave.** If a consumer outside your file
   scope still injects it, that consumer is inside your scope for the one-line `inject()` swap — and
   nothing more. Never leave the old class behind "for now".
4. **A spec that needs real state calls `provideAppState()`.** Never hand-roll a `provideStore` in a
   spec; that is exactly the drift `provideAppState()` exists to prevent.
5. **An effect may `inject(Store)`; a component may not.** `core/ui/architecture.spec.ts` enforces it,
   along with reducer purity and the reducer↔actions↔spec sibling rule. Run it; don't weaken it.
6. **No `async` effect bodies.** `VisionApi` is Promise-shaped until N9 — wrap with
   `from(...)`/`defer(...)` inside a flattening operator (§7).
7. **A silent degrade stays silent**, but becomes a `*Failed` action the reducer handles — never a
   `console.warn` nothing can select on (§3 rule 7).
8. **Do not commit.** The orchestrator commits each wave. Report what you changed and the exact
   test/build numbers you saw.
9. **Update `MODULE.md` in place** for what your wave changed (the `core/**` stores table row, the
   `core/state/` section's "converted so far" line). Wave narrative goes to `MODULE-HISTORY.md`,
   never to `MODULE.md`.
