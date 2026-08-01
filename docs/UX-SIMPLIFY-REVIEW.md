# UX-SIMPLIFY-REVIEW — where the app overwhelms, and how to make it simple

Status: **review for decision** (2026-08-01). Scope: information architecture + the core flows
(add a source, add an asset/user, Wall, Map/Command, Fly cockpit). Grounded in the actual nav
(`features/hubs/nav-entries.ts`), routes, and page templates.

## The principle we're applying

**Simple where it should be simple, complex where it should be complex.** Borrowed from mature
multi-tool systems (CRM, ops consoles):

1. **One object, one home.** In a CRM you don't have *Contacts* **and** *People* **and** *Directory*
   as three nav items — a Contact has one canonical list, and everything else is a *view* or a
   *detail* of it. Peer nav entries for the same concept are the #1 source of "which do I click?".
2. **Progressive disclosure.** Power lives *inside* the object, not on the top bar. The 20% who need
   the plumbing drill in; the 80% never see it.
3. **Name things by what the user controls, not how the system is built** (frontend-design skill:
   "a person manages notifications, not webhook config").
4. **Role-scoped surface.** An operator flying one drone should see a *tiny* app; an admin sees the
   full console. Same product, different density.
5. **One primary action per screen; empty states teach the first step.**

## Findings, most-overwhelming first

### F1 — Duplicate nav destinations inflate the whole app  *(high impact, low effort)*
`/command` is linked **3×** (Operate "map-pin", Monitor "map", Monitor "gauge"), `/wall` **2×**,
`/fly` **2×**. So the ~23 nav entries across Operate/Monitor/Manage are really ~17 *destinations*
wearing extra hats. Every duplicate is a "are these different?" tax.
**Fix:** each destination gets **one** canonical nav home (its most-primary hub). Command → Monitor
only; Wall → Operate only; Fly → Operate only. Net: ~6 entries removed, zero features lost.

### F2 — Assets / Devices / Warehouse: three doors, one concept  *(highest impact, medium effort)*
The single biggest overwhelm. A new user sees **Assets**, **Devices**, **Warehouse**, and **Add
source** as four peer Manage entries and cannot tell what "my cameras/drones" live under. Reality
(the domain model, which the UI is leaking): the user owns **Assets** (a named, categorized thing
with 1..n devices); **Device** is low-level plumbing; **Warehouse** is a *launcher page whose only
job is to link to Assets + Devices* — pure ceremony.
**Fix — collapse to one home:**
- **Assets is the one home** (call it "Sources" or "Fleet" if that reads plainer to the operator —
  a naming call for you). It lists everything the user watches/flies.
- **Devices demotes to a detail** — the device-level plumbing (protocol, URI, firmware) lives
  *inside* an asset's detail page, not as a top-level nav peer. Keep a `/devices` power-view for
  admins, but off the primary nav (or behind "Advanced").
- **Delete Warehouse** (the launcher) — it launches two things already reachable directly.
- **Add source** stays one clear button on the Assets home *and* the wizard door — that flow is
  already well-built (one `/add-source` wizard, several entry points funnel to it; keep it).
Result: "where are my cameras / how do I add one?" has **one** obvious answer.

### F3 — The Manage hub is a wall of 10  *(high impact, low effort)*
Assets, Devices, Warehouse, Add-source, Roster, Categories, Training, Health, Firmware, Reports.
After F2 that's already down to ~6. Then **role-scope + group the rest:** everyday management
(Sources, People/Roster, Add) up top; **configuration** (Categories, Training, Firmware, Reports)
and **diagnostics** (Health, Debug) folded into labelled groups or a Settings-style sub-nav, shown
only to admins. An operator's Manage hub should be nearly empty.

### F4 — The Fly cockpit tool-rail is creeping  *(medium impact, medium effort)*
The right rail is now **7 drawers**: flight · rc · cv · detections · marks · layers · help. Seven
glyph-only buttons is past the "glance and know" limit. **Fix:** group by job — *Control* (flight,
rc), *Vision* (cv, detections, layers), *Situational* (marks), with Help pinned separate — or
collapse the less-used ones behind a "more" affordance. Keep the 2–3 an operator touches every
flight always-visible; everything else one level down.

### F5 — Per-flow notes
- **Add a source** ✅ already good — the 4-step wizard (Identify → Connect → Test → Done) with
  register/discover/simulate/listen methods is the right progressive-disclosure shape. Only polish:
  make the method choice read in user words ("Point at a camera URL" / "Scan my network" / "Try a
  simulated drone") rather than register/discover/simulate.
- **Add an asset / user** — Assets create is fine; the **Roster** (add user) lives under Manage and
  is role-gated — good. Ensure both use the same create-panel pattern so "adding a thing" feels
  identical everywhere (consistency = learnability).
- **Wall** — strong as-is (density control, suspended off-screen players). It should be the
  *operator's* default "watch everything" home; make sure it's one nav entry (F1).
- **Map / Command** — one destination, three nav names today (F1). One name ("Command" or "Map").

## Recommended order (low-risk → structural)
1. **F1** duplicate-nav removal (pure win, ~1 wave, no page changes).
2. **F3** Manage-hub grouping + role-scope (nav-entries only).
3. **F4** cockpit tool-rail grouping.
4. **F2** Assets/Devices/Warehouse collapse (the big one — touches nav + moves device plumbing into
   asset-detail + deletes Warehouse; do it deliberately, likely its own plan).
5. **F5** copy/consistency polish.

Each is independently shippable. F1 + F3 alone remove most of the perceived overwhelm for near-zero
risk. F2 is the one that makes the product *feel* simple — worth its own focused pass.
