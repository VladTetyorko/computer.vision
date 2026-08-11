# 12 — Organization

**Files:** `features/org-settings/**`
**Wave:** 2 (page bar + form), 3 (two-pane)

## Current state

`page-head` ("Organization" + "Manage the people and groups in your organization. Grants are limited
to your own scope.") with a `Users | Groups` segmented toggle at the top-right. Then an
**Invite a user** panel — `USERNAME`, `DISPLAY NAME`, `EMAIL`, `TEMPORARY PASSWORD`, `GROUP` select on
one row, `ROLE IN GROUP` on the next, a `Can sign in immediately` checkbox and `Create user`. Below,
a **Users / 3 total** list: name, `username · email`, `Root · Role`, a role chip, `Enabled`, `Disable`.

## Problems

- **A create form permanently occupies the top of a management page.** Inviting is occasional;
  reviewing users is the common task. The form pushes the list — the actual content — below 380px.
- **`TEMPORARY PASSWORD` is a plain text input in a persistent form.** It is on screen at all times,
  it is not masked, and it invites an admin to type a credential into a page that may be screen-shared
  during a review. This should be masked at minimum, and ideally replaced by an emailed invite link.
- The 5-field row stretches each input to ~200px regardless of content, and `GROUP` (a select with
  "No group") gets the same width as `EMAIL`.
- `Users | Groups` is a top-right toggle that looks like a view-density control, not a section switch.
- Each user row repeats `Root · Role` in grey *and* the role as a chip — the same fact twice.
- `Disable` is a bare button on every row with no confirmation and no distinction from `Enabled`
  (which is a status, not an action) sitting immediately to its left. Easy mis-click.
- No search, no sort, no pagination on a list that grows with the org.

## Suggested design

```
┌────┬──────────────────────────────────────────┬────────────────────┐
│ ▎🏢│ 🏢 Organization  ( Users │ Groups )       │  Administrator     │
│    │     3 users   [search]      [+ Invite]   │  admin@vision.local│
│    ├──────────────────────────────────────────┤  ──────────────    │
│    │ NAME           USERNAME    ROLE    STATE │  Root · Admin      │
│    │ ▎Administrator admin       Admin   ●On   │  Group: Root       │
│    │  Manager       manager     Manager ●On   │  Last seen: —      │
│    │  Pilot         pilot       Pilot   ●On   │  ──────────────    │
│    │                                          │  [Change role ⌄]   │
│    │                                          │  [Reset password]  │
│    │                                          │  [Disable user]    │
└────┴──────────────────────────────────────────┴────────────────────┘
```

- **Invite moves into a modal** behind `+ Invite` in the page bar. The list gets the page.
- **`Users | Groups` becomes a tab strip** under the title (a section switch, styled as one), with
  routes `/org/users` and `/org/groups` so each is addressable.
- **Credential handling**: `TEMPORARY PASSWORD` becomes `type="password"` with a
  `Generate` button and a copy affordance, inside the modal — not standing on the page. Where the
  backend supports it, prefer "send an invite link" and drop the field entirely; scope that check in
  the wave-2 task before implementing.
- **Right panel** (`?sel=<userId>`) holds identity, group, role and the per-user actions — which
  removes per-row action buttons entirely.
- **`Disable user` is destructive-styled and confirmed** (`shared/ui/confirm-dialog.ts` already
  exists); the `Enabled` status becomes a dot in the `STATE` column, not a button-lookalike.
- **Search + sort** in the bar.

## Refactor list

- **Move** the invite form into a modal component; trigger from `vision-page-bar`.
- **Split** `/org` into `/org/users` + `/org/groups` child routes with a tab strip.
- **Mask** the temporary-password field; add generate + copy; investigate an invite-link flow.
- **Adopt** `shared/ui/two-pane` + `side-panel`, `?sel=`.
- **Wrap** `Disable` in `confirm-dialog`; restyle status vs action.
- **De-duplicate** the role display.
- **Add** search/sort/pagination.

## Acceptance

- The user list starts within 120px of the top of the content area.
- No credential field is visible without an explicit user action.
- Disabling a user requires confirmation.
