/**
 * The `IconName` union + inner-SVG-markup registry behind `<vision-icon>` (docs/plans/done/UI-REDESIGN-PLAN.md
 * Frozen contract F2). Every current "icon" in this app is a Unicode glyph (`‹ › × ? ▾ ⋯`) or a
 * one-off inline `<svg>` (`shared/ui/notification-bell.html`'s bell) — this is the greenfield,
 * CSP-safe (no external font, no CDN, no data-URI) replacement: a name resolves to a small string of
 * inner SVG markup (`<path>`/`<circle>`/`<line>`/`<polyline>`/`<rect>`/`<polygon>` elements only, no
 * `<svg>` wrapper — `Icon` supplies that), rendered into a 24x24 viewBox, `fill="none"`,
 * `stroke="currentColor"`, `stroke-width="2"`, round caps/joins — the Feather/Lucide stroke-icon
 * visual language named in F2, hand-authored here rather than imported from either library (no new
 * dependency, exact frozen name set only).
 *
 * `IconName` is frozen for this wave's launch set (every glyph any Wave 1-4 area names) — adding a
 * name later is additive/non-breaking, per F2's own note; removing or renaming one is not (every
 * later wave's `name="…"` literal would silently stop compiling, which is the intended guard: an
 * `IconName` typo is a compile error, not a blank icon at runtime).
 *
 * Kept intentionally simple/geometric (mostly `<line>`/`<circle>`/`<rect>`/`<polyline>`, few
 * multi-segment `<path>`s) so every glyph stays legible at the 16-24px sizes `<vision-icon>` is
 * actually used at — precise fidelity to any one named icon set was not the goal, a consistent
 * *family* of marks was.
 */
export type IconName =
  // modes + nav
  | 'home'
  | 'cockpit'
  | 'eye'
  | 'eye-off'
  | 'scan'
  | 'layers'
  | 'list'
  | 'help'
  | 'close'
  | 'plus'
  | 'edit'
  | 'archive'
  | 'trash'
  | 'kebab'
  | 'chevron-left'
  | 'chevron-right'
  | 'chevron-down'
  | 'chevron-up'
  | 'grid'
  | 'bell'
  | 'user'
  | 'settings'
  | 'gear'
  | 'operate'
  | 'monitor'
  | 'manage'
  | 'logout'
  | 'building-org'
  // cockpit / telemetry clusters
  | 'drone'
  | 'battery'
  | 'satellite'
  | 'compass'
  | 'gauge'
  | 'signal'
  | 'wind'
  | 'thermometer'
  | 'map-pin'
  | 'ruler'
  | 'power'
  // monitor / manage
  | 'alert'
  | 'replay'
  | 'history'
  | 'wrench'
  | 'chip'
  | 'firmware'
  | 'report'
  | 'category'
  | 'warehouse'
  | 'source'
  | 'pilot'
  | 'map'
  | 'gamepad'
  // tactical marks (docs/plans/done/TACTICAL-MARKS-PLAN.md M5)
  | 'target'
  | 'flag'
  // audit trail (docs/plans/done/OPS-UX-PLAN.md §3 B1)
  | 'shield'
  // setup checklist (docs/plans/done/OPS-UX-PLAN.md §3 B2) — a done row's own tick mark.
  | 'check'
  // links panel (docs/plans/active/LINK-PAIRING-PLAN.md §3.4, wave L4) — a chain-link glyph, distinct
  // from `signal`'s bars so the drill-in row for "Links" doesn't read as a duplicate of "Full telemetry".
  | 'link';

export const ICONS: Record<IconName, string> = {
  home: '<path d="M4 11.5 12 4l8 7.5"/><path d="M6 10.5V19a1 1 0 0 0 1 1h4v-6h2v6h4a1 1 0 0 0 1-1v-8.5"/>',
  cockpit: '<path d="M4 15a8 8 0 0 1 16 0"/><line x1="2" y1="15" x2="22" y2="15"/><circle cx="12" cy="12" r="1.4"/>',
  eye: '<path d="M2 12S5.6 5 12 5s10 7 10 7-3.6 7-10 7-10-7-10-7Z"/><circle cx="12" cy="12" r="3"/>',
  // Added for `wall-tile.ts`'s video-on-request toggle (ALWAYS-ON-FLOW-PLAN.md §4 Wave C1/C2) — the
  // "hide video" half `eye` alone has no way to express; a slashed eye is the universal visibility-off
  // mark, additive to the frozen launch set per this file's own header note.
  'eye-off':
    '<path d="M9.9 4.24A9.12 9.12 0 0 1 12 4c6.4 0 10 7 10 7a17.24 17.24 0 0 1-2.94 4.06M6.42 6.42C3.5 8.24 2 12 2 12s3.6 7 10 7a9.5 9.5 0 0 0 5.08-1.42M2 2l20 20"/><path d="M9.53 9.53A3 3 0 0 0 12 15a3 3 0 0 0 2.47-1.29"/>',
  scan:
    '<path d="M4 8V5a1 1 0 0 1 1-1h3"/><path d="M16 4h3a1 1 0 0 1 1 1v3"/><path d="M20 16v3a1 1 0 0 1-1 1h-3"/>' +
    '<path d="M8 20H5a1 1 0 0 1-1-1v-3"/><line x1="4" y1="12" x2="20" y2="12"/>',
  layers: '<path d="M12 3 2 9l10 6 10-6-10-6Z"/><path d="M2 15l10 6 10-6"/>',
  list:
    '<line x1="8" y1="6" x2="20" y2="6"/><line x1="8" y1="12" x2="20" y2="12"/><line x1="8" y1="18" x2="20" y2="18"/>' +
    '<circle cx="4" cy="6" r="1" fill="currentColor" stroke="none"/><circle cx="4" cy="12" r="1" fill="currentColor" stroke="none"/>' +
    '<circle cx="4" cy="18" r="1" fill="currentColor" stroke="none"/>',
  help: '<circle cx="12" cy="12" r="9"/><path d="M9.5 9a2.5 2.5 0 0 1 4.9.8c0 1.7-2.4 2-2.4 3.7"/><line x1="12" y1="17" x2="12.01" y2="17"/>',
  close: '<line x1="6" y1="6" x2="18" y2="18"/><line x1="18" y1="6" x2="6" y2="18"/>',
  plus: '<line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>',
  edit: '<path d="M4 20h4l10.5-10.5a2 2 0 0 0 0-2.8l-1.2-1.2a2 2 0 0 0-2.8 0L4 16v4Z"/><line x1="13.5" y1="6.5" x2="17.5" y2="10.5"/>',
  archive:
    '<rect x="3" y="4" width="18" height="4" rx="1"/><path d="M5 8v10a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8"/>' +
    '<line x1="10" y1="13" x2="14" y2="13"/>',
  trash:
    '<line x1="4" y1="7" x2="20" y2="7"/><path d="M6 7l1 13a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-13"/>' +
    '<path d="M9 7V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v3"/><line x1="10" y1="11" x2="10" y2="17"/><line x1="14" y1="11" x2="14" y2="17"/>',
  kebab:
    '<circle cx="12" cy="5" r="1.4" fill="currentColor" stroke="none"/><circle cx="12" cy="12" r="1.4" fill="currentColor" stroke="none"/>' +
    '<circle cx="12" cy="19" r="1.4" fill="currentColor" stroke="none"/>',
  'chevron-left': '<polyline points="15 6 9 12 15 18"/>',
  'chevron-right': '<polyline points="9 6 15 12 9 18"/>',
  'chevron-down': '<polyline points="6 9 12 15 18 9"/>',
  'chevron-up': '<polyline points="6 15 12 9 18 15"/>',
  grid:
    '<rect x="3" y="3" width="8" height="8" rx="1"/><rect x="13" y="3" width="8" height="8" rx="1"/>' +
    '<rect x="3" y="13" width="8" height="8" rx="1"/><rect x="13" y="13" width="8" height="8" rx="1"/>',
  // Same path data as the pre-existing one-off inline `<svg>` in `shared/ui/notification-bell.html`
  // — deliberately identical so the header bell can migrate onto `<vision-icon name="bell">` in a
  // later wave with zero visual change (not done by this wave — that file is untouched).
  bell: '<path d="M6 8a6 6 0 0 1 12 0c0 5 2 6 2 6H4s2-1 2-6"/><path d="M10 20a2 2 0 0 0 4 0"/>',
  user: '<circle cx="12" cy="8" r="3.5"/><path d="M5 20c0-4 3-6 7-6s7 2 7 6"/>',
  // A sliders/adjustment glyph — deliberately distinct from `gear` below (both would otherwise be
  // "a cog", the frozen set's own two overlapping names for what this app splits into two surfaces:
  // an account/flight settings screen vs. a mechanical gear/cog metaphor).
  settings:
    '<line x1="4" y1="6" x2="20" y2="6"/><circle cx="9" cy="6" r="2"/><line x1="4" y1="12" x2="20" y2="12"/>' +
    '<circle cx="16" cy="12" r="2"/><line x1="4" y1="18" x2="20" y2="18"/><circle cx="11" cy="18" r="2"/>',
  gear:
    '<circle cx="12" cy="12" r="3"/><path d="M12 2v3M12 19v3M4.2 4.2l2.1 2.1M17.7 17.7l2.1 2.1' +
    'M2 12h3M19 12h3M4.2 19.8l2.1-2.1M17.7 6.3l2.1-2.1"/>',
  operate: '<rect x="7" y="14" width="10" height="6" rx="1.5"/><line x1="12" y1="14" x2="12" y2="5"/><circle cx="12" cy="5" r="2.2"/>',
  monitor: '<rect x="3" y="4" width="18" height="12" rx="1.5"/><line x1="8" y1="20" x2="16" y2="20"/><line x1="12" y1="16" x2="12" y2="20"/>',
  manage:
    '<rect x="6" y="4" width="12" height="17" rx="1.5"/><path d="M9 4V3a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v1"/>' +
    '<line x1="9" y1="11" x2="15" y2="11"/><line x1="9" y1="15" x2="15" y2="15"/>',
  logout: '<path d="M9 4H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h3"/><line x1="21" y1="12" x2="10" y2="12"/><polyline points="16 7 21 12 16 17"/>',
  'building-org':
    '<rect x="5" y="3" width="14" height="18" rx="1"/><line x1="9" y1="7" x2="9" y2="7.01"/><line x1="15" y1="7" x2="15" y2="7.01"/>' +
    '<line x1="9" y1="11" x2="9" y2="11.01"/><line x1="15" y1="11" x2="15" y2="11.01"/><line x1="9" y1="15" x2="9" y2="15.01"/>' +
    '<line x1="15" y1="15" x2="15" y2="15.01"/><line x1="10" y1="21" x2="10" y2="17"/><line x1="14" y1="17" x2="14" y2="21"/>',
  drone:
    '<circle cx="12" cy="12" r="2.4"/><line x1="12" y1="9.6" x2="6" y2="5"/><line x1="12" y1="9.6" x2="18" y2="5"/>' +
    '<line x1="12" y1="14.4" x2="6" y2="19"/><line x1="12" y1="14.4" x2="18" y2="19"/><circle cx="6" cy="5" r="2"/>' +
    '<circle cx="18" cy="5" r="2"/><circle cx="6" cy="19" r="2"/><circle cx="18" cy="19" r="2"/>',
  battery: '<rect x="2" y="7" width="18" height="10" rx="1.5"/><line x1="22" y1="10" x2="22" y2="14"/><line x1="6" y1="10" x2="6" y2="14"/>',
  gamepad:
    '<rect x="2" y="7" width="20" height="10" rx="4"/><line x1="7" y1="10" x2="7" y2="14"/>' +
    '<line x1="5" y1="12" x2="9" y2="12"/><circle cx="16" cy="11" r="1"/><circle cx="18.5" cy="13.5" r="1"/>',
  satellite:
    '<path d="M15 4a10 10 0 0 1 5 8"/><path d="M13 8a6 6 0 0 1 3 5"/>' +
    '<rect x="8.5" y="8.5" width="5" height="5" rx="1" transform="rotate(-45 11 11)"/>' +
    '<line x1="8.8" y1="13.2" x2="4" y2="18"/><circle cx="4" cy="19" r="1.3"/>',
  compass: '<circle cx="12" cy="12" r="9"/><path d="M15 9l-2 5-5 2 2-5 5-2Z"/>',
  gauge: '<path d="M4 16a8 8 0 0 1 16 0"/><line x1="12" y1="16" x2="16" y2="10"/><circle cx="12" cy="16" r="1.3"/>',
  signal: '<line x1="4" y1="18" x2="4" y2="14"/><line x1="9" y1="18" x2="9" y2="10"/><line x1="14" y1="18" x2="14" y2="6"/><line x1="19" y1="18" x2="19" y2="3"/>',
  wind:
    '<path d="M3 8h10a3 3 0 1 0-3-3"/><path d="M3 12h14a3 3 0 1 1-3 3"/><path d="M3 16h8a2.5 2.5 0 1 1-2.5 2.5"/>',
  thermometer: '<path d="M12 3a2.5 2.5 0 0 0-2.5 2.5v9.6a4 4 0 1 0 5 0V5.5A2.5 2.5 0 0 0 12 3Z"/><line x1="12" y1="8" x2="12" y2="14"/>',
  'map-pin': '<path d="M12 21s7-6.5 7-12a7 7 0 1 0-14 0c0 5.5 7 12 7 12Z"/><circle cx="12" cy="9" r="2.3"/>',
  ruler: '<rect x="3" y="8" width="18" height="8" rx="1"/><line x1="7" y1="8" x2="7" y2="11"/><line x1="11" y1="8" x2="11" y2="11"/><line x1="15" y1="8" x2="15" y2="11"/><line x1="19" y1="8" x2="19" y2="11"/>',
  power: '<line x1="12" y1="3" x2="12" y2="11"/><path d="M7 6a8 8 0 1 0 10 0"/>',
  alert: '<path d="M12 3 2 20h20L12 3Z"/><line x1="12" y1="10" x2="12" y2="14"/><line x1="12" y1="17" x2="12.01" y2="17"/>',
  replay: '<circle cx="12" cy="12" r="9"/><polygon points="10 8 10 16 16 12"/>',
  history: '<circle cx="12" cy="12" r="9"/><polyline points="12 7 12 12 16 14"/>',
  wrench: '<path d="M17 3a4 4 0 0 0-5.4 5.1L4 15.7 8.3 20l7.6-7.6A4 4 0 0 0 21 7l-3 3-2-2 3-3Z"/>',
  chip:
    '<rect x="6" y="6" width="12" height="12" rx="1.5"/><line x1="9" y1="2" x2="9" y2="6"/><line x1="15" y1="2" x2="15" y2="6"/>' +
    '<line x1="9" y1="18" x2="9" y2="22"/><line x1="15" y1="18" x2="15" y2="22"/><line x1="2" y1="9" x2="6" y2="9"/>' +
    '<line x1="2" y1="15" x2="6" y2="15"/><line x1="18" y1="9" x2="22" y2="9"/><line x1="18" y1="15" x2="22" y2="15"/>',
  firmware:
    '<rect x="7" y="3" width="10" height="14" rx="1.5"/><line x1="12" y1="7" x2="12" y2="13"/>' +
    '<polyline points="9.5 10.5 12 13 14.5 10.5"/><line x1="6" y1="20" x2="18" y2="20"/>',
  report:
    '<path d="M6 2h9l3 3v17H6Z"/><polyline points="15 2 15 5 18 5"/><line x1="9" y1="12" x2="9" y2="16"/>' +
    '<line x1="12" y1="10" x2="12" y2="16"/><line x1="15" y1="13" x2="15" y2="16"/>',
  category: '<path d="M11 3H4v7l10 10 7-7L11 3Z"/><circle cx="8" cy="8" r="1.3"/>',
  warehouse: '<path d="M3 21V10l9-6 9 6v11"/><path d="M3 21h18"/><rect x="9" y="13" width="6" height="8"/>',
  source: '<rect x="2" y="6" width="13" height="12" rx="1.5"/><path d="M15 9.5 21 6v12l-6-3.5Z"/>',
  pilot:
    '<circle cx="12" cy="11" r="4"/><path d="M6 11a6 6 0 0 1 12 0"/>' +
    '<path d="M6 11v2a1.5 1.5 0 0 0 1.5 1.5H8v-4h-.5A1.5 1.5 0 0 0 6 11Z"/>' +
    '<path d="M18 11v2a1.5 1.5 0 0 1-1.5 1.5H16v-4h.5A1.5 1.5 0 0 1 18 11Z"/>',
  map: '<polygon points="3 6 9 4 15 6 21 4 21 18 15 20 9 18 3 20 3 6"/><line x1="9" y1="4" x2="9" y2="18"/><line x1="15" y1="6" x2="15" y2="20"/>',
  // Tactical marks (docs/plans/done/TACTICAL-MARKS-PLAN.md M5) — TARGET's crosshair and FRIENDLY's flag.
  target: '<circle cx="12" cy="12" r="8"/><circle cx="12" cy="12" r="3.4"/><line x1="12" y1="1" x2="12" y2="5"/><line x1="12" y1="19" x2="12" y2="23"/><line x1="1" y1="12" x2="5" y2="12"/><line x1="19" y1="12" x2="23" y2="12"/>',
  flag: '<line x1="5" y1="21" x2="5" y2="3"/><path d="M5 4h13l-3.2 4.5L18 13H5"/>',
  // Audit trail (docs/plans/done/OPS-UX-PLAN.md §3 B1) — a shield reads as "accountability record", distinct
  // from `history` (already the personal-activity icon) so the two log surfaces stay visually distinct.
  shield: '<path d="M12 3l7 3.5v5c0 5-3.2 8-7 9.5-3.8-1.5-7-4.5-7-9.5v-5L12 3Z"/><polyline points="9 12 11 14 15 9.5"/>',
  // Setup checklist (docs/plans/done/OPS-UX-PLAN.md §3 B2) — a bare tick, no ring/circle around it
  // (unlike `shield`'s check-in-a-shield): the row's own layout supplies the "done" framing.
  check: '<polyline points="4 12 9.5 17.5 20 6"/>',
  // Links panel (docs/plans/active/LINK-PAIRING-PLAN.md §3.4, wave L4) — two overlapping rounded
  // links, the familiar "chain link" mark (Feather/Lucide's own `link` glyph, hand-drawn to this
  // file's stroke language).
  link:
    '<path d="M9.5 14.5 14.5 9.5"/><path d="M8 16 5.5 18.5a3 3 0 0 1-4.24-4.24L4 11.5a3 3 0 0 1 4.24 0"/>' +
    '<path d="M16 8l2.5-2.5a3 3 0 0 1 4.24 4.24L20 12.24a3 3 0 0 1-4.24 0"/>',
};
