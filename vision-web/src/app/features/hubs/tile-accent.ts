/**
 * Decorative categorical accent hues for hub tiles (docs/UI-REDESIGN-PLAN.md — colour polish).
 *
 * Chosen in the app's existing vivid/legible band (the same saturation/lightness register as the
 * detection-overlay label colours and `--accent`) BUT deliberately confined to the cool, non-semantic
 * arc — cyan → sky → blue → indigo → violet → purple, ~184–296° — so a purely decorative tile accent
 * can never be misread as one of the four RESERVED status hues:
 *   --danger coral-red ~0°, --warn amber ~40°, --ok green ~145°, --live rose ~340°.
 * That reservation is why the palette never crosses into red/amber/green/rose: those hues carry
 * meaning elsewhere (stop, caution, ok, live) and must stay unambiguous.
 *
 * Assigned by tile POSITION (not by name/hash) so each hub reads as an intentional left-to-right
 * colour sequence, and the same grid position shows the same hue across all three hubs — a stable,
 * scannable rhythm rather than a random scatter. Used only as a border/icon accent (never a fill).
 */
const TILE_HUES = [184, 206, 228, 250, 272, 296] as const;

/** The vivid accent colour for a tile at `index` (cycles the cool-arc palette). */
export function tileAccent(index: number): string {
  const n = TILE_HUES.length;
  const hue = TILE_HUES[((index % n) + n) % n];
  return `hsl(${hue} 80% 62%)`;
}
