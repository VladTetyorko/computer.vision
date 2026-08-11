# UI-ARCHITECTURE-PLAN — layered state for vision-web (facades + UiStore)

Status: **in progress** (started 2026-07-31). Owner: FE architecture.

Goal: make `vision-web`'s state **solid and consistent** — no two mutually-exclusive overlays open at
once, no drifting button/disabled state, no domain state stranded in components. Achieve it by
**formalizing the existing zoneless-signals architecture** (chosen over NgRx: the app already has ~11
tested signal stores; classic NgRx would fight the signals grain and rewrite working code for no
gain). No new dependency.

## The layering (frozen)

```
Component  — dumb. OnPush. Renders facade/UiStore signals in the template and calls their methods.
   │         Holds NO domain state and NO cross-cutting UI state. May hold only truly-ephemeral,
   │         self-contained local view state (a draft input string, a hover flag) that no other
   │         component or route transition could ever need to stay consistent with.
   ▼
Facade     — one per routed feature (`<feature>-facade.ts`, `@Injectable`, provided by the route/
   │         page component). Injects the stores/services/UiStore it needs. Exposes:
   │           • read-models — `computed()` signals the template binds to (incl. every `canX`/
   │             `disabled` predicate, derived from a SINGLE source, never duplicated per component)
   │           • commands — methods the template calls (`arm()`, `openAssign()`, `save()`).
   │         The facade is the ONLY thing a feature component injects (plus `UiStore` for template
   │         open-checks). It is pure orchestration — no HTTP, no framework-less domain logic
   │         (that stays in `*-logic.ts`).
   ▼
Store      — the single source of truth for a domain concern (existing `core/**/*-store.ts`,
   │         unchanged in shape): signal state + `computed` + methods. Side effects via `effect()`/
   │         `PollScheduler`. Stores never import components or facades.
   ▼
Service / API client / (domain port) — `VisionApi`, `PollScheduler`, `Toast*`, etc.
```

Dependency rule (convention, enforced by review + the guard spec below): **component → facade →
store → service**. A component that injects a store/`VisionApi` directly, or holds an overlay
boolean, is a violation.

## UiStore — the consistency linchpin (frozen contract)

Generalizes the existing `core/panel-state.ts#PanelState` (one-open-at-a-time, already exactly this
shape and well-tested) into the single coordinator for **every mutually-exclusive overlay** — tool-
rail drawers, confirm dialogs, inline editors, row menus. Two overlays in the same group can never be
open at once *by construction*, which is the stated bug class ("a menu stayed open, a button drifted").

- `core/ui/ui-store.ts#UiStore` (evolution of `PanelState`; `PanelState` is absorbed/renamed, its
  tests carried over):
  - `active: Signal<string | null>` — the one open overlay id in this group (null = none).
  - `isOpen(id): boolean`, `open(id)` (closes any other in the group), `close(id?)`, `toggle(id)`.
  - optional constructor `storageKey` round-trips `active` (existing persisted-drawer behavior).
  - **Scoped by instance**: a feature provides one `UiStore` per independent overlay *group* — e.g.
    Fly provides a `tool-rail` group (drawers) and, separately, a `dialog` group (arm/disarm/mode/
    stop confirms) so a confirm can open *over* a drawer but two confirms can't coexist. Groups that
    must be mutually exclusive share one instance; groups that may overlap get separate instances.
  - Transient overlays (confirms/editors) pass `{ persist: false }` so they never survive reload.
- **Migration target for the scattered flags** (survey, all become `UiStore` overlay ids):
  `flight-command-panel`'s `modeConfirmOpen`/`armConfirmOpen`/`disarmConfirmOpen`, `fly`'s
  `stopConfirmOpen`, `asset-detail`'s `editingAsset`/`attributesEditorOpen`/`assignOpen`/
  `editingRegistrationNumber`, `command`'s `zonesPanelOpen`, `onboarding-store`'s
  `flightPlanDialogOpen`. Toggle-style state that is NOT mutually exclusive (a persisted `railOpen`,
  a `showArchived` filter, an `expanded` card, an `activeTab`) stays a plain boolean/enum **but moves
  into its feature store/facade**, out of the component.

## Guard (make violations fail, not rot)

`core/ui/architecture.spec.ts` — a pure Vitest test over the source tree (read files, regex) asserting
the invariants cheaply, mirroring the `app.routes.spec.ts` precedent:
1. No `features/**/*.ts` component injects `VisionApi` or a `*Store` directly (only its `*Facade`,
   plus `UiStore`).
2. No `features/**` component declares an `open`/`menu`/`confirm`/`editing` overlay `signal(...)`
   (those belong to `UiStore`).
3. Every routed feature component has a matching `*-facade.ts`.
(A CI lint rule can replace this later; the spec is the zero-dependency version now.)

## Waves (disjoint file scopes — whole-app sweep)

- **W0 — foundation (this session, direct):** `UiStore` (from `PanelState`) + tests; the guard spec
  (initially allowlisting un-migrated features so it goes green incrementally); this doc. One
  **exemplar facade** — `features/fly/flight-command-panel` (its three confirm flags → one `dialog`
  `UiStore` group) — as the pattern every wave copies.
- **W1 — Fly cockpit:** `fly.ts` + fly panels → `FlyFacade`; tool-rail drawers + stop-confirm on
  `UiStore` groups.
- **W2 — Command + maps:** `command.ts`, `asset-panel`, zones → `CommandFacade`.
- **W3 — Asset-detail + assets + devices + warehouse:** the four editor/assign overlays → `UiStore`;
  `AssetDetailFacade`/`AssetsFacade`/`DevicesFacade`.
- **W4 — Live + wall + replay + onboarding + org-settings + activity + settings:** facades +
  overlay/toggle consolidation (`onboarding`'s flight-plan dialog, the `railOpen`/`showArchived`
  toggles into stores/facades).
- **W5 — cleanup:** remove the guard spec's allowlist (all features now compliant); retire
  `PanelState` name; update `MODULE.md`.

## Definition of done (per wave)
- Wave's components inject only their facade (+ `UiStore`); no overlay signals left in components.
- The guard spec passes for the wave's features (removed from its allowlist).
- `npm run test:ci` + `npx tsc --noEmit` + prod build green; `MODULE.md` updated.
