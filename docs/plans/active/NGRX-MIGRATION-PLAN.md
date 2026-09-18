# NGRX-MIGRATION-PLAN — one state engine for `vision-web`

**Owner's ask, 2026-09-18:** *"move from injections to the signals in angular application … as a
final result I need a well structured ngrx application … with all the reducers, actions, effects and
so on."*

**State:** N0 foundation landing. Waves N1–N9 open.

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
| N1 shell/UI | `core/ui/` | `ui`, `overlay` |
| N2 session | `core/auth/`, `core/org/`, `core/seat/`, `core/settings/` | 4 |
| N3 live backbone | `core/live/` | `live` (+ `LiveGateway` seam) |
| N4 fleet | `core/fleet/`, `core/system-status/`, `core/system-events/` | 3 (fleet = entity) |
| N5 perception | `core/telemetry/`, `core/detections/`, `core/cv-trace/` | 3 |
| N6 map | `core/map/`, `core/map-data/`, `core/geofence/` | 7 |
| N7 ops | `core/ops/`, `core/weather/`, `core/training/`, `core/rc/`, `core/discovery/`, `core/pairing/`, `core/geo/`, `core/events/` | 8 |
| N8 feature slices | `features/onboarding/`, `features/fly/grounding-store.ts` | 2 (+ facade rewiring) |
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

## 9. Status

| Wave | State |
|---|---|
| N0 | in progress |
| N1–N9 | open |
