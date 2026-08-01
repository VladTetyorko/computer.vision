package com.drones.vision.domain.model;

/**
 * Image bytes for one {@link TrainingSample} (docs/CV-TRAINING-PLAN.md §1/§C) — the captured
 * frame's raw pixels at full resolution, stored via {@code SampleImageStorePort} keyed by {@link
 * TrainingSampleId}.
 *
 * <p>Mirrors {@link AssetImage}'s defensive-copy discipline bit-for-bit (clone in construction,
 * clone out on every {@link #data()} access) — the same bytea-backed, no-history storage
 * precedent, reused deliberately rather than inventing a second binary-payload shape
 * (docs/CV-TRAINING-PLAN.md §C).
 *
 * @param data        the raw image bytes; defensively copied both in and out (see {@link
 *                    #data()}); must not be empty
 * @param contentType the image's MIME type (e.g. {@code "image/jpeg"}); must not be blank
 */
public record SampleImage(byte[] data, String contentType) {

    public SampleImage {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("SampleImage data must not be empty");
        }
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("SampleImage contentType must not be blank");
        }
        data = data.clone();
    }

    /**
     * Returns a fresh copy of this image's bytes — never the array stored on this record — so no
     * caller can mutate storage through a returned reference. Mirrors {@link AssetImage#data()}'s
     * defensive-copy-on-every-access discipline.
     */
    @Override
    public byte[] data() {
        return data.clone();
    }
}
