# R1 — API console design analogs (for `/debug` redesign)

Research only. No product code. Grounded in the current page (`station/vision-web/src/app/features/debug/`)
and `.claude/skills/frontend-style/SKILL.md` (the "daylight chart" law: tokens only, calm, one blue
accent + status hues, two type registers, no glassmorphism/gradients/glow, headless component
sourcing per §11).

## 0. What we have today

One card, top to bottom: endpoint `<select>` + method `<select>` + path `<input>` in a 3-column
grid → conditional JSON body `<textarea>` → Send button → response block (status chip, ms, header
chips, raw `<pre>` JSON) → history table (method/path/status/ms, row click = refill-only, "replay"
never auto-sends). Below that, two more cards: Health (system-status chip + collapsed raw
`/actuator/health` probe) and Last scan (one button, raw scan JSON). 13 known endpoints total —
this is a small, fixed, internal surface, not an open API a third party integrates against. That
fact should anchor every recommendation below: we are not building Postman.

## 1. Analogs surveyed

### Request builders (Postman, Insomnia, Bruno, Hoppscotch, HTTPie Desktop)

**Postman.** Three-pane: collection tree (left) → request builder with a tabbed strip
(Params/Auth/Headers/Body/Scripts) under one URL bar (center) → response pane with its own tab
strip (Body/Cookies/Headers/Test Results) stacked below the builder, not beside it. The
load-bearing decision is *tabs*, not accordions or a wall of always-visible fields — headers,
auth, and body all compete for the same vertical space, and only one is usually relevant per
request. Right for us in spirit (we don't need most of the tabs — no auth manager, no
pre-request scripts), wrong for us in weight — a whole tab strip for a 13-endpoint console is
over-built; a headers/body toggle inside one row is enough.

**Insomnia.** Request (left) and response (right) as two resizable columns, both always visible,
one URL bar with method as a segmented prefix. Good for iterating fast (change a param, see the
new response, without losing sight of what you sent) but it buys that by halving the width
available to both — a wide JSON response (a device list, a telemetry frame) gets cramped into
half the viewport and wraps hard. Our response bodies are exactly the wide, list-shaped JSON this
layout punishes; a top/bottom stack (what we already have) reads better for us.

**Bruno.** Git-native, filesystem-backed collections (`.bru` files you commit) — the whole product
thesis is "no cloud sync forced." Load-bearing decision: response pane is a strict bottom half
(pretty body / headers / cookies / timeline as sub-tabs), never a side column, precisely because a
git-tracked collection is meant to be read top-to-bottom like a document. That vertical-stack
instinct is the one to copy; the git-native storage model is irrelevant to us (our "collection" is
a hardcoded 13-row TS array, correctly — see §5).

**Hoppscotch.** Web-based, the cleanest of the four: one URL bar (method as a dropdown prefix,
address as one field) at the top, a tab strip for Params/Headers/Body/Auth beneath it, response
docked to the right only on wide screens. Its real contribution is the *single combined URL bar*
— method and path read as one sentence ("GET /api/devices") rather than two adjacent form fields
you have to visually knit together, which is closer to how an operator actually thinks about a
request. Our current method-select + path-input pair is the two-field version of the same idea;
merging them into one bar is a cheap, high-value steal.

**HTTPie Desktop.** The starkest of the bunch — one address bar, a minimal method chip, response
as pretty-printed/syntax-highlighted JSON with almost no chrome around it. It proves a raw console
doesn't need Postman's density to feel complete; restraint reads as confidence. This is the
closest existing product to our actual ambition (a *small* console, not an IDE), and its bias
toward "fewer visible controls, more legible output" is the one to hold onto hardest.

### Browser DevTools Network panel

List (left, one row per request: name/method, status, type, size, time, colored by status family)
+ detail (right, tabbed: Headers/Preview/Response/Timing) + a waterfall bar drawn inline per row.
Three ideas worth isolating from the whole: (1) status-family color coding applied consistently to
every row, not just the open one — ours already does this via `.chip.ok/.danger` but only in the
open response and the history table, never as a persistent glance-scannable column the way DevTools
treats it as *the* primary scan axis; (2) **Copy as cURL** on any past request — a context action,
not a mode; (3) clicking a past request opens its *detail*, it does not resend it — resending is a
deliberate, separately-labeled action ("Replay XHR"). That view/replay separation is exactly the
safety property our history-row click already has (refill, not resend) — DevTools is independent
validation that this is the correct default, not overcaution.

### Swagger UI / Redoc / Scalar

**Swagger UI.** Endpoint catalog grouped by tag (left/top, collapsed operations) → expand one →
"Try it out" toggles the operation from *read* mode (docs: params, schemas, example) into *write*
mode (real inputs, an Execute button) → curl + live response appended below the docs, not
replacing them. The load-bearing decision is that Try It Out is opt-in per-operation — the default
state of the page is documentation, not a loaded gun. That matters for a page whose catalog
includes `DELETE /api/streams/{streamId}` and `POST /api/discovery/scan`: browsing the catalog
should not itself be an action.

**Redoc.** Pure three-pane docs (nav / prose+schema / code samples), deliberately *no* try-it —
proof that a known, finite endpoint list is legible as a browsable catalog even with zero
execution affordance, which is useful evidence for how much of "catalog UI" is independent of
"console UI."

**Scalar.** The modern take on the same shape: sidebar endpoints grouped by tag, center docs,
right-hand panel that is simultaneously live try-it *and* code-snippet generator (40+ HTTP clients,
21 languages), with request history and env vars built into that same right panel. Scalar is the
strongest single analog for "a known, closed API surface browsed and executed in the same view" —
but its code-gen breadth (21 languages) is exactly the kind of feature-surface creep we should not
match; one target format (curl) covers our actual use case (handing a repro to a teammate or a bug
report).

### Grafana Explore / terminal tools (k6, httpie, curlie)

**Grafana Explore.** Query editor pinned at the top, one big result panel below with a
data-shape toggle (table/logs/graph) rather than separate pages per shape, plus a query-history
drawer. The toggle-not-separate-pages instinct maps directly to "pretty JSON vs raw text" for our
response body — one control, not two views to navigate between.

**httpie/curlie (terminal).** Syntax-highlighted, auto-indented JSON by default, headers only when
asked (`-h`/`-v`), body search left to the terminal's own scrollback/search (`/` in a pager) rather
than a bespoke in-app search box. This is the "don't build search-in-response" data point: at the
body sizes a 13-endpoint internal console actually returns, the browser's native page-find is
sufficient, and building our own is solving a problem we don't have yet.

### Embedded-in-a-bigger-product exemplars (the actual shape of our problem)

**Stripe Workbench.** Shell (raw request console with tab-completion) and API Explorer live as
*tabs inside one Workbench*, alongside health/event monitoring for the same integration — not
three unrelated cards bolted together, but siblings under one shared frame with one shared sense
of "this is the developer-facing corner of the product." This is the strongest justification for
tightening the relationship between our Console/Health/Last-scan cards (§4) even though a deeper
retab is out of scope for this research wave.

**Supabase auto-generated API docs.** Per-table CRUD reference embedded directly in the project
dashboard, generated from the live schema, output as ready-to-paste client-library snippets rather
than raw HTTP. The lesson isn't "generate client code" (out of scope for us) — it's that an
embedded console should assume the *user already trusts the surrounding product's auth/session* and
never ask them to re-authenticate or configure a base URL, because they're already inside it. Ours
already gets this right (`DebugApiService` rides the app's own session); worth stating explicitly
so nobody "fixes" it later by adding a token field.

## 2. Patterns worth stealing

1. **One combined method+path bar**, not two adjacent fields (Hoppscotch). Method as a small
   segmented/chip prefix inside the same field group as the path input; reads as one sentence,
   costs less width, and the endpoint picker becomes the thing that *fills* this one bar rather
   than a parallel third field. Maps directly onto `.console-form`'s existing 3-column grid — drop
   to two visual groups (picker, bar) using `--space-8` gaps and the existing `.field`/`.mono`
   tokens; no new colors needed.

2. **Try It Out is opt-in per catalog pick, not the default page state** (Swagger UI). Selecting a
   destructive-looking endpoint (`DELETE`, `POST …/scan`) from the catalog should load it into the
   form, never queue a send. We already do this (`selectEndpoint` only prefills) — keep it, and
   extend the same discipline to history recall (below).

3. **View vs. replay are different actions on a history row** (DevTools' "open detail" vs. "Replay
   XHR"). Current `replay()` already refills-only; make the *distinction* visible instead of
   implicit — e.g. a small secondary "send again" affordance appears on row hover, while the row
   click itself stays a silent refill. Prevents an operator from fat-fingering a repeat POST while
   just trying to look at what they sent last time.

4. **Status-family color as a persistent scan column, not just the open response** (DevTools).
   The history table already has a status chip per row (`ok`/`danger` per §5's "one chip per row"
   rule) — keep it there and resist adding a second color signal (e.g. coloring the whole row);
   one chip is the rule and DevTools' own list view actually over-colors by comparison.

5. **Copy as cURL on the current response and on every history row** (DevTools, Scalar). Highest
   practical value item in this whole survey for an *internal* console: it's how an operator hands
   a repro to another agent, a teammate, or a bug report without hand-transcribing method/path/body.
   One format only (curl) — see §5 on why not "generate in N languages."

6. **Data-shape toggle instead of parallel views** (Grafana Explore) — applied to *pretty vs. raw*
   response text, one small control (a segmented pair, `.segmented` token already exists) rather
   than two different panels or a modal.

7. **Restraint as the default read** (HTTPie Desktop) — fewer visible chrome elements around the
   response than Postman/Insomnia carry, more weight given to the body itself. This is not a
   feature to add so much as a ceiling to hold the whole redesign under: every new control earns
   its permanent visibility, or it lives behind a disclosure (`<details>`, already the pattern used
   for the actuator probe).

8. **The console assumes the surrounding product's session — never its own auth affordance**
   (Supabase). Explicit non-goal to preserve, called out so a later wave doesn't "helpfully" add a
   bearer-token field.

## 3. Anti-patterns to avoid

- **Tab strips for a 13-endpoint surface** (Postman's Params/Auth/Headers/Body/Scripts). Violates
  the "restraint" reading of our own law by importing a component built for hundreds of endpoints
  and dozens of auth schemes. We have neither.
- **Side-by-side request/response columns** (Insomnia). Halves the width available to exactly the
  wide, list-shaped JSON our endpoints return; fights `.response-body`'s existing full-width
  `<pre>`. Keep the vertical stack.
- **A pre-skinned "API client" visual language** (any of Postman/Insomnia/Hoppscotch's own chrome —
  rounded pill buttons, colored method badges as brand identity, a dedicated dark-mode-only
  aesthetic). §9 of the style law bans exactly this kind of borrowed SaaS identity; method should
  render as `.mono` text or a plain chip using existing tokens, never a bespoke colored badge per
  HTTP verb (that would be a second status-color vocabulary competing with the one the law already
  reserves for ok/warn/danger/live).
- **Environment/variable systems** (Postman/Insomnia/Hoppscotch/Scalar all have one). We have one
  environment: this deployment. Adding `{{baseUrl}}`-style variable substitution solves a problem
  (multiple backends) we don't have, and the `{deviceId}`-style path placeholders already in
  `DEBUG_ENDPOINTS` are today resolved by hand-editing the path string, which is fine at this
  scale (see §5).
- **A JSON tree/twisty view built before it's needed** (Scalar/Postman's collapsible response
  tree). Real engineering for a device-list/telemetry payload size that a flat `<pre>` already
  handles; defer until an actual body-size complaint exists, per HTTPie Desktop's evidence that
  flat pretty-printed text is enough at our scale.

## 4. Recommended information architecture

Single page stays single page — no new route, no modal takeover. The console keeps top-to-bottom
flow (matches the calm/checklist reading model the style law wants elsewhere), but the catalog and
history move to a persistent narrow rail so switching endpoints or reviewing a past call doesn't
require scrolling past a live response. Health and Last scan stay separate cards below, but framed
explicitly (via a shared section label, not a merged component) as siblings of the console under
one "developer surface" heading — a light nod to Stripe Workbench's framing without the cost of an
actual re-tab.

```
┌─ Debug ────────────────────────────────────────────────────────────────────┐
│                                                                            │
│ ┌─ catalog + history rail ──┐  ┌─ console ────────────────────────────────┐│
│ │ Endpoints            [/]  │  │ [GET ▾] /api/devices/{deviceId}   [Send] ││
│ │  GET  /api/devices        │  │ (headers/body toggle, collapsed by       ││
│ │  POST /api/devices        │  │  default — only shown for POST/PUT/PATCH)││
│ │  GET  /api/streams        │  │                                          ││
│ │  ...                      │  │ ── response ─────────────────────────────││
│ │  ▸ Custom…                │  │ 200 OK   42 ms      [pretty|raw] [curl]  ││
│ │──────────────────────────  │  │ { …pretty JSON, full width… }            ││
│ │ History                   │  │                                          ││
│ │  GET  /api/devices  200   │  └──────────────────────────────────────────┘│
│ │  POST /api/devices  201  ⋮│                                              │
│ │  ...                      │                                              │
│ └────────────────────────────┘                                              │
│                                                                            │
│ ┌─ Health ───────────────────┐  ┌─ Last scan ─────────────────────────────┐│
│ │ Overall: OK   [Refresh]    │  │ [Run scan]                              ││
│ │ ▸ Actuator probe (details) │  │ { …scan JSON… }                          ││
│ └────────────────────────────┘  └──────────────────────────────────────────┘│
└────────────────────────────────────────────────────────────────────────────┘
```

Rail collapses to a top strip (picker + a "History ›" disclosure) under the 900px breakpoint the
page already uses (`.console-form`'s existing `@media (max-width: 900px)` rule) — same pattern as
today, just relocating what currently lives as a `<select>` and a below-the-fold table into a
narrower always-visible column at desk width. The rail is a list, not a tree (13 flat entries, no
folders) — source its keyboard/selection behavior from Angular Aria Listbox per §11, skinned with
the existing selection language (§4 of the style law: 2px left inset bar in `--color-info`), which
also finally gives the endpoint picker the same selection idiom the rest of the app uses instead of
a bare native `<select>`.

## 5. Keyboard / efficiency idioms worth having

- **Ctrl/Cmd+Enter sends** from the path or body field — universal across Postman/Insomnia/
  Bruno/Hoppscotch/HTTPie; cheap, expected, no new UI surface.
- **Up/Down arrow recalls history** when the path field has focus and is otherwise empty of a
  pending edit — shell-history idiom, cheap because the data's already in the `history` signal.
- **Copy as cURL** — one button next to the response's status chip, and one small icon per history
  row on hover (§2.5, §2.3). This is the single highest-leverage addition in the whole survey.
- **Row click = load (safe), an explicit small "send again" = resend** (§2.3) — codify the
  distinction DevTools already proves is right, rather than leaving `replay()`'s safety implicit
  in the doc comment only.
- **A lightweight `/`-to-focus on the endpoint picker** (command-palette convention, cheap given
  Angular Aria Listbox already provides typeahead) — not a full command palette, just "jump to the
  filter field."

## 6. What NOT to build (scope traps)

- **Collections / saved requests beyond the fixed catalog.** `DEBUG_ENDPOINTS` is deliberately a
  hardcoded array kept in lockstep with `vision-api`'s actual controllers (its own doc comment says
  so) — a user-editable "save this as a favorite" system duplicates that catalog with a second,
  driftable source of truth. Don't.
- **Environments / multiple base URLs / variable substitution.** One deployment, one origin,
  session-authenticated already. Not our problem.
- **Auth/token managers.** The console rides the app's own session (Supabase lesson, §1). A
  bearer-token or API-key field would be actively wrong here — it would imply a second, weaker auth
  path exists.
- **Mock servers / request chaining / pre-request scripts / tests.** All Postman-tier features for
  building *integrations against* an API. We are debugging a console's *own* backend, live, as one
  person. No scripting surface earns its keep at 13 endpoints.
- **Multi-language code generation (Scalar's 21, Postman's dozen).** One target format — curl —
  covers "hand a repro to a teammate." Generating Python/JS/Go client snippets is solving a
  developer-onboarding problem this page doesn't have.
- **A full JSON tree/collapse widget, in-app response search, resizable split panes.** All real UI
  engineering with no demonstrated need yet at our response sizes (§3, §1's HTTPie/curlie note);
  build only if an actual body genuinely becomes hard to read in a flat `<pre>`.
