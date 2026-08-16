# IA-TRUTH-PLAN — make the navigation tell the truth

Status: **active** (2026-08-16). Branch `feat/system-status` (continues the same cycle).
Companion to `docs/plans/active/SYSTEM-STATUS-PLAN.md` — same theme one level up: that plan made the
*running system* honest about its state; this one makes the *app* honest about itself.

Grounded in a fresh audit of the live tree against `docs/conclusions/UX-SIMPLIFY-REVIEW.md`
(2026-08-01). **That document is substantially out of date and must not be used as a to-do list**:

| Finding | 2026-08-01 | Today |
|---|---|---|
| F1 duplicate nav destinations | `/command` 3×, `/wall` 2×, `/fly` 2× | **fully fixed**, and regression-tested at `nav-entries.spec.ts:52-57` |
| F2 Assets/Devices/Warehouse | three peer doors | **nav fixed** (`/warehouse` deleted, `/devices` demoted to advanced+managerOnly); **link graph not** — see U1.1 |
| F3 Manage wall of 10 | 10 flat entries | **grouping shipped** (`navTiers()`); a pilot now sees 2 primary entries, an admin 6 |
| F4 cockpit tool-rail | 7 flat drawers | **grouped** into Control/Vision/Situational/Help; still 7 |
| F5 wizard vocabulary | `register`/`discover`/`simulate` | **tiles fixed**; downstream labels still leak — see U1.4 |

## 1. The problem

Four places where the app misstates what it is:

1. **A demoted page is still the link target.** `/devices` is manager-only and out of the pilot's
   nav, yet nine in-page links still point there — including the empty-cockpit first-run path, which
   sends a brand-new pilot to a page their role cannot open.
2. **A scaffold advertises a shipped feature as unbuilt.** `/monitor/replay` renders "a library
   listing every finished flight is *coming*". It shipped; `/replay` serves it.
3. **A finished feature has no way in.** `/manage/training/models` — the CV model registry with
   promote-to-production — has no nav entry.
4. **The add-source wizard renames itself mid-flow**, and its nav description states the wrong step
   count.

Plus one label that overpromises: `/operate/preflight` is called "Pre-flight checklist" and contains
no checklist — while a working checklist component already exists in the Fly cockpit.

## 2. Wave U1 — truth in navigation

**Scope:** `station/vision-web/**` only. **Estimate: S** (≤16 h). Every item is small; the value is
in doing them together so the nav stops contradicting itself in several places at once.

### U1.1 — Retarget the nine stray `/devices` links (highest value)

`/devices` is `group: 'advanced'` + `managerOnly` (`nav-entries.ts:317-324`). These still point at it:

| Site | Change |
|---|---|
| `fly/drone-picker.html:31-34` | → `/add-source` directly (drop `[queryParams]="{addSource:1}"`); fix copy. **This is the first-run path — a two-hop bounce through a manager-only page today** |
| `wall/wall.html:38-41` | → `/assets`, fix copy |
| `command/command.html:124-128` | → `/assets`. Its own copy already says "Assets tab" while the button says "Go to Devices" — self-contradicting in one card |
| `onboarding/onboarding.html:3` | Cancel → `/assets` |
| `asset-detail/asset-detail.html:12`, `:33` | "Back to Devices" / "All devices" → Assets |
| `live/live.html:14`, `:162` | same |
| `asset-detail-facade.ts:340`, `live-facade.ts:294` | programmatic `navigate(['/devices'])` → `/assets` |

Keep `/devices` itself — it is a legitimate admin power-view. Only stop routing pilots into it.

### U1.2 — Delete the orphaned `/monitor/replay` scaffold

Remove `hubs.routes.ts:65-78`; correct the stale "**Five** `ComingSoon` scaffold routes" class doc at
`hubs.routes.ts:28-45`; drop `app.routes.spec.ts:89-93` and `'/monitor/replay'` from `:109`. It has no
nav entry, so nothing in the UI loses a link. ~20 lines removed.

### U1.3 — Give the CV model registry a way in

Add a nav entry for `/manage/training/models` after CV training (`nav-entries.ts:267`),
`group: 'configuration'`, `managerOnly: true`. It is the terminal step of the training loop
("promote it live is the very next thing an operator does" — `training-job.ts:19`) and is currently
findable only by opening CV training and noticing a secondary button.

### U1.4 — One vocabulary through the add-source wizard

- `onboarding-facade.ts:34-40` — `CONNECT_METHOD_LABELS` still reads `Register manually` /
  `Discover on network` / `Simulate`. These render in the chosen-method breadcrumb
  (`onboarding.html:160`), so a user clicks *"Enter a stream address"* and the next screen calls it
  *"Register manually"*. Align with the tile wording (`onboarding.html:120-148`).
- `onboarding.html:679`, `:682` — "…or switch to **Register** manually" names a mode the user never saw.
- `nav-entries.ts:241` — "Register, discover, or simulate a new device in **three** steps": wrong
  vocabulary **and** wrong count (the wizard is four steps — `onboarding-facade.ts:27-32` — with five methods).
- `assets.html:134` — "(register, scan, or simulate a moving test drone)".

### U1.5 — Order Operate by frequency of use

`nav-entries.ts:165-176` puts **Detection defaults** (set-once configuration) above **Pre-flight
checklist** (touched every flight). Swap the two blocks.

**Deliberately NOT moving Detection defaults into Manage**, which the audit recommended: Manage's
grouped entries are all `managerOnly`, so the move would silently take the capability away from
pilots. That needs evidence about who uses it, not a drive-by. The reorder is the part that is a pure win.

### U1.6 — Correct the stale cockpit rail comment

`cockpit.html:276-293` claims "the rail is down to **6**" and omits the `map` drawer from both its id
list and the Situational group; `cockpit.ts:28-30` correctly says seven. Fix the comment. **Do not**
restructure the rail — that is a design decision, not a comment fix.

## 3. Wave U2 — ~~give the pre-flight page its checklist~~ (PREMISE WAS WRONG — see §3.1)

> **§3.1 Correction (2026-08-16).** Everything below this heading rests on a false claim inherited
> from the audit: that `/operate/preflight` renders no checklist. **It does, and it did.**
> `preflight.html:45` has carried `<vision-preflight-checklist [items]="facade.preflightItems()" />`
> since commit `7ae9943` (2026-07-31) — over two weeks before the audit asserted otherwise. Verified
> directly against `git show HEAD:…/preflight.html`.
>
> The real, much smaller finding: the component lived under `features/fly/` while being consumed by
> two features, violating this codebase's own "no page imports another page's module" rule. U2 became
> a `git mv` to `shared/ui/preflight-checklist.*` (history preserved, imports updated, zero behaviour
> change, covered by its own pre-existing spec).
>
> **Lesson recorded deliberately**: the audit was right about F1–F5 and about the nine stray
> `/devices` links, and wrong here. An audit finding that asserts a *absence* ("there is no X") is
> worth verifying against the file before it becomes a work item — a finding that asserts a presence
> proves itself, a finding that asserts an absence does not.

**Scope:** `station/vision-web/**`. **Estimate: S–M.**

`/operate/preflight` is labelled "Pre-flight checklist" and renders a single read-only status card
(`preflight.html:44-46`) plus a notice admitting templates are "coming" (`:29-31`) — the most
misleading label in the nav for the primary persona.

A working checklist component already exists: `features/fly/preflight-checklist.ts` (five
always-present rows), used in the Fly cockpit. **This is the same complaint that started this cycle —
the feature exists and isn't shown.**

**Assess first, then act.** If the component is cleanly reusable (takes telemetry/capability inputs
rather than reaching into cockpit-local state), lift it to `shared/ui/` and render it per-asset on the
pre-flight page. **If it is welded to cockpit state, stop and report** — do not fork a second copy,
and do not rewrite the cockpit's own usage to suit this page. A duplicated checklist that drifts is
worse than the current honest-but-thin page.

Either way the "templates are coming" notice stays until templates actually exist.

## 4. Status

| Wave | Estimate | Status |
|---|---|---|
| U1 | S | **done** — all six items; 118 files / 1988 tests, tsc clean |
| U2 | S–M | **done, but not as specced** — premise was false (§3.1); delivered as a `git mv` of the checklist to `shared/ui/` |

Test count moved 1989 → 1988 deliberately: U1.2 deleted the `/monitor/replay` scaffold, and with it the
one test asserting that route existed. Bundle unchanged (406.10 kB vs 406.26 kB — a new nav-entry
object offset by the removed scaffold's).

**Verified independently, not taken on report**: all nine `/devices` links retargeted (grep-clean),
`/monitor/replay` gone, `preflight.html:45`'s pre-existing checklist confirmed via `git show HEAD`.
One residual stale comment the wave missed — `replay.routes.ts:20` still described the deleted
scaffold as live — was fixed by hand; it was the same category of untruth U1 exists to remove.

## 5. Still open (deliberately not taken)

- **F2's last mile** — `/devices` remains a manager-only power-view whose only unique column over
  asset-detail is `Owner`. Folding that in would let the page be deleted outright. Not done: deleting
  a working admin surface deserves its own pass.
- **Firmware** — named in F2 as plumbing to move into asset detail; it exists nowhere but the
  `/manage/firmware` scaffold. Nothing to move yet.
- **The four unbadged-but-hollow pages** — `/operate/preflight`, `/monitor/alerts`,
  `/manage/categories`, `/manage/reports` each render a notice admitting a headline capability is
  missing (templates, saved thresholds, category editing, exports). These are *honest* notices, so
  they are not untruths — but they mean the **Upcoming** disclosure understates unbuilt surface by
  four. Each is a real feature build, not an IA fix.
- **F4's numeric target** — the cockpit rail is grouped but still 7 drawers. Folding `map` back
  (the cockpit already has a separate persisted map-inset toggle) would reach the target, but that is
  a design decision about an operator's primary surface, not a cleanup.
