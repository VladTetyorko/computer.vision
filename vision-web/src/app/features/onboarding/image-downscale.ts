/**
 * Client-side photo downscale for the onboarding wizard's Profile step (docs/plans/done/UX-REWORK-PLAN.md §U-d
 * item 1 — "photo upload with preview … client-side downscale to ≤2MB jpeg — canvas"), backing
 * `PUT /api/assets/{id}/image`'s pinned ≤2MB limit.
 *
 * Split into a pure half ({@link computeTargetDimensions}, {@link isAcceptableImageType},
 * {@link JPEG_QUALITY_STEPS}) and a DOM-dependent half ({@link downscaleImageToJpeg}, which draws
 * into a real `<canvas>`) — jsdom (this app's test environment, vision-web/MODULE.md's own Build/test
 * note) has no canvas implementation, so only the pure half is unit-tested; the DOM half is exercised
 * by hand against the running app (see this cycle's MODULE.md entry's own live-verification list).
 */

/** The pinned `PUT /api/assets/{id}/image` size limit (docs/plans/done/UX-REWORK-PLAN.md §U-d item 3). */
export const MAX_IMAGE_BYTES = 2 * 1024 * 1024;

/** The longest side a photo is scaled down to before quality is ever reduced. Generous for a thumbnail-ish use. */
export const MAX_IMAGE_DIMENSION = 1600;

/**
 * Successive JPEG qualities tried, most-faithful first, until the encoded size clears
 * {@link MAX_IMAGE_BYTES} — a photo that still doesn't fit at the lowest step is sent at that step
 * anyway (best effort; `PUT /api/assets/{id}/image` is the final, authoritative size check).
 */
export const JPEG_QUALITY_STEPS: readonly number[] = [0.92, 0.85, 0.75, 0.65, 0.55, 0.45, 0.35];

/** File types the file input accepts and this module will actually attempt to decode. */
export function isAcceptableImageType(type: string): boolean {
  return type === 'image/jpeg' || type === 'image/png' || type === 'image/webp';
}

/**
 * Scales `width`x`height` down so its longest side is at most `maxDimension`, preserving aspect
 * ratio; an image already within budget is returned unchanged (rounded to whole pixels — a canvas
 * cannot have a fractional size). Degenerate input (`0`/negative) never produces a `0`x`0` canvas,
 * which some engines reject.
 */
export function computeTargetDimensions(
  width: number,
  height: number,
  maxDimension: number = MAX_IMAGE_DIMENSION,
): { readonly width: number; readonly height: number } {
  const safeWidth = Math.max(1, width);
  const safeHeight = Math.max(1, height);
  const longest = Math.max(safeWidth, safeHeight);
  if (longest <= maxDimension) {
    return { width: Math.round(safeWidth), height: Math.round(safeHeight) };
  }
  const scale = maxDimension / longest;
  return {
    width: Math.max(1, Math.round(safeWidth * scale)),
    height: Math.max(1, Math.round(safeHeight * scale)),
  };
}

/** Loads `file` into whatever this browser can draw from a `<canvas>` — `ImageBitmap` when available. */
async function loadDrawable(file: File): Promise<ImageBitmap | HTMLImageElement> {
  if (typeof createImageBitmap === 'function') {
    return createImageBitmap(file);
  }
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => {
      URL.revokeObjectURL(url);
      resolve(img);
    };
    img.onerror = () => {
      URL.revokeObjectURL(url);
      reject(new Error('Could not decode the selected image.'));
    };
    img.src = url;
  });
}

function drawableSize(drawable: ImageBitmap | HTMLImageElement): { width: number; height: number } {
  return drawable instanceof HTMLImageElement
    ? { width: drawable.naturalWidth, height: drawable.naturalHeight }
    : { width: drawable.width, height: drawable.height };
}

function canvasToJpegBlob(canvas: HTMLCanvasElement, quality: number): Promise<Blob> {
  return new Promise((resolve, reject) => {
    canvas.toBlob(
      (blob) => (blob ? resolve(blob) : reject(new Error('Could not encode the downscaled image.'))),
      'image/jpeg',
      quality,
    );
  });
}

/**
 * Decodes `file`, scales it to at most `maxDimension` on its longest side, and re-encodes it as JPEG
 * at successively lower quality until it clears `maxBytes` (or the lowest step is reached, in which
 * case that attempt is returned anyway — see this module's own doc comment).
 */
export async function downscaleImageToJpeg(
  file: File,
  options: { readonly maxBytes?: number; readonly maxDimension?: number } = {},
): Promise<Blob> {
  const maxBytes = options.maxBytes ?? MAX_IMAGE_BYTES;
  const maxDimension = options.maxDimension ?? MAX_IMAGE_DIMENSION;

  const drawable = await loadDrawable(file);
  const { width: sourceWidth, height: sourceHeight } = drawableSize(drawable);
  const { width, height } = computeTargetDimensions(sourceWidth, sourceHeight, maxDimension);

  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext('2d');
  if (!ctx) {
    throw new Error('This browser has no 2D canvas support.');
  }
  ctx.drawImage(drawable, 0, 0, width, height);
  if (typeof (drawable as ImageBitmap).close === 'function') {
    (drawable as ImageBitmap).close();
  }

  let attempt: Blob | undefined;
  for (const quality of JPEG_QUALITY_STEPS) {
    attempt = await canvasToJpegBlob(canvas, quality);
    if (attempt.size <= maxBytes) {
      return attempt;
    }
  }
  // Best effort at the lowest quality step tried — PUT /api/assets/{id}/image is the final check.
  return attempt!;
}
