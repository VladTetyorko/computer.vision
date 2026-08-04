# UI-STATE-PLAN

Authoritative spec for view-state consistency in `vision-web`. Grounded in reproduced defects on the
running app (localhost:4200, admin session) on 2026-08-04, after the NAV-IA-REDESIGN merge.

Companion to [`UI-ARCHITECTURE-PLAN.md`](UI-ARCHITECTURE-PLAN.md), which fixed the *layering*
(Component → Facade → Store → Service). This file fixes the *lifecycle*: what may be open, when it
must close, and who decides.

---

## 1. Reproduced defects

Driven in the browser, not inferred:

```
1. open the notification bell   → bell.open = true
2. open the identity menu       → identity.open = true, bell STILL true   ← two at once
3. select an asset (side panel) → both STILL true alongside the panel     ← three at once
4. navigate /assets → /devices  → both STILL true on the new page         ← survives navigation
```

**D1 — Two global overlays open simultaneously.** The identity menu and the notification dropdown
render over each other, visually colliding. Nothing coordinates them.

**D2 — Global overlays survive navigation.** Both stayed open across a route change to an unrelated
page. The user's words: *"not to have opened tabs when user is on another page."*

**D3 — Global overlays coexist with page overlays.** A bell dropdown open on top of an open two-pane
detail panel, neither aware of the other.

**D4 — The root cause is uncontrolled DOM state.** `identity-chip.html`, `notification-bell.html` and
the sidebar's disclosures are native `<details>`. Their `open` state lives in the DOM, not in a
signal — Angular cannot see it, no store can coordinate it, and nothing resets it. `UiStore` exists
and is good, but a `<details>` structurally cannot participate in it.

**D5 — The shell is always mounted, so nothing cleans up.** Page-scoped overlays are fine by
accident: their component is destroyed on navigation, taking their `UiStore` with it. The identity
chip, notification bell and sidebar live in `app.html` and are never destroyed, so "destroyed on
navigation" — the app's only cleanup mechanism — does not apply to exactly the overlays that leak.

---

## 2. The model

### 2.1 Three tiers, one rule each

| Tier | Examples | Rule |
|---|---|---|
| **URL state** | two-pane selection (`?sel=`), roster pivot (`?by=`), replay deep link | Lives in the query string. Survives refresh, Back and sharing. Already correct — the model to copy. |
| **Page overlays** | cockpit tool rail, asset-detail drawers/editors, Command zones/marks, confirms | One `UiStore` per exclusive group, owned by the page. Dies with the page. Already correct. |
| **Global overlays** | identity menu, notification bell, mobile sidebar sheet | **Missing.** Owned by the shell, which never unmounts — so they need explicit lifecycle. |

### 2.2 `GlobalOverlayStore` — the missing piece

One root-provided coordinator for every overlay that outlives a page:

```ts
type GlobalOverlayId = 'identity-menu' | 'notification-bell' | 'sidebar-mobile';
```

Backed by a `UiStore` (exclusivity is already solved there — do not reimplement it), plus the three
lifecycle rules the shell needs and no page does:

1. **Exclusive** — opening one closes the others. Fixes D1.
2. **Closes on `NavigationEnd`** — every global overlay closes on any route change. Fixes D2.
   This is the single behaviour that makes "no stale overlay on another page" true by construction
   rather than by discipline.
3. **Closes on `Escape` and on a click outside the open overlay** — one document-level listener pair
   in the store, not one per component. Consistency here is what stops each overlay inventing its own
   half of the contract.

### 2.3 `<details>` is for disclosure, not for overlays

| Use | Verdict |
|---|---|
| Inline disclosure that pushes content (sidebar `Advanced`/`Upcoming`, `/debug` raw JSON, detection `Advanced`) | **Keep `<details>`.** Native, accessible, and its state is genuinely local and harmless. |
| Floating overlay that covers other content (identity menu, notification dropdown) | **Convert to signal-backed state** in `GlobalOverlayStore`. An overlay must be closable by something other than itself. |

The distinguishing question is not "is it a dropdown" but *"can something else need to close this?"*
If yes, its state cannot live in the DOM.

### 2.4 Page overlays keep their own store — with one addition

Page `UiStore`s are already correct, but two page-level overlays close only by clicking their own
trigger: `page-bar`'s `?` hint and `return-home-button`'s confirm. Both should honour `Escape` and
outside-click for the same reason as §2.2 rule 3 — an operator who hits Escape expects *whatever is
floating* to go away, not to have to remember which component owns it.

---

## 3. Guardrail

A source-scanning spec, in the style of `core/ui/architecture.spec.ts` (which already enforces the
layering with `import.meta.glob(..., '?raw')` and no `TestBed`):

- No component under `shared/ui/**` or `app.*` may declare a floating-overlay `<details>`. Inline
  disclosures are allowed; the rule is keyed on the component being part of the always-mounted shell.
- Any new `GlobalOverlayId` must be registered in the store's union type — the compiler already
  enforces this, which is the point of a union over free strings.

Without a guardrail this regresses the first time someone adds a header menu, exactly as the
`managerOnly` filter drifted between two nav renderers before NAV-IA-REDESIGN collapsed them into one.

---

## 4. Scope

**In:** `core/ui/overlay-store.ts` (new) + spec; `shared/ui/identity-chip.*`;
`shared/ui/notification-bell.*`; `shared/ui/app-sidebar/**` (mobile sheet only — the two disclosures
stay `<details>`); `shared/ui/page-bar/**` and `shared/ui/return-home-button.*` (Escape/outside-click);
the guardrail spec.

**Out:** any change to `UiStore` itself (it is correct — this builds on it), page-level overlay
groups, URL-state pages, and anything under `features/**` that is already destroyed on navigation.

**Acceptance** — re-run the reproduction in §1 and get:
- opening the identity menu closes the bell, and vice versa;
- navigating anywhere closes both;
- `Escape` closes whatever is open;
- clicking outside closes it;
- the sidebar's `Advanced`/`Upcoming` disclosures are unaffected by all of the above.
