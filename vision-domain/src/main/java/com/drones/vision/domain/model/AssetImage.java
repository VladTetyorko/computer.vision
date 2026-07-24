package com.drones.vision.domain.model;

/**
 * A stored image attached to an {@link Asset} — its user-facing photo (docs/UX-REWORK-PLAN.md
 * §U-d item 3, UX-DESIGN.md §5.1's onboarding wizard "Profile" step). One per asset, replacing
 * wholesale on every store — there is no history/versioning.
 *
 * @param data        the raw image bytes; defensively copied both in and out (see {@link #data()}),
 *                    mirroring {@link VideoFrame}'s buffer-ownership discipline for the same
 *                    reason — a caller must never be able to mutate storage through a reference it
 *                    holds
 * @param contentType the image's MIME type (e.g. {@code "image/jpeg"}, {@code "image/png"}); must
 *                    not be blank. Which content types are actually accepted (and the max size) is
 *                    a wire-boundary concern enforced by the API edge, not this record — the same
 *                    division of labor as {@code StreamDescriptor#protocol}'s lower-case check
 *                    (a shape rule) versus adapter-specific option validation (a caller concern).
 */
public record AssetImage(byte[] data, String contentType) {

    public AssetImage {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("AssetImage data must not be empty");
        }
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("AssetImage contentType must not be blank");
        }
        data = data.clone();
    }

    /**
     * Returns a fresh copy of this image's bytes — never the array stored on this record — so no
     * caller can mutate storage through a returned reference. Mirrors {@link VideoFrame#data()}'s
     * defensive-copy-on-every-access discipline, adapted for a plain {@code byte[]} instead of a
     * {@link java.nio.ByteBuffer} (there is no read-only-view equivalent for arrays, so cloning is
     * the mechanism here instead).
     */
    @Override
    public byte[] data() {
        return data.clone();
    }
}
