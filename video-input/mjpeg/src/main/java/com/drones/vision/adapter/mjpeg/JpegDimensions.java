package com.drones.vision.adapter.mjpeg;

/**
 * Pure helper: extracts the pixel width/height a JPEG codestream declares in
 * its baseline ({@code SOF0}) or progressive ({@code SOF2}) start-of-frame
 * marker, without decoding any pixel data.
 *
 * <p>A JPEG stream is a sequence of {@code 0xFF} markers; every marker except
 * a handful of fixed-length ones ({@code SOI}/{@code EOI}/{@code RSTn}) is
 * followed by a big-endian 2-byte length (itself included in the count) and
 * that many bytes of payload. The {@code SOFn} payload always begins with a
 * 1-byte sample precision followed by 2-byte height then 2-byte width, so
 * this walks the marker chain until it finds {@code SOF0}({@code 0xC0}) or
 * {@code SOF2}({@code 0xC2}) and reads those two fields directly — cheaper
 * and more robust than decoding the whole image just to learn its size.
 */
final class JpegDimensions {

    private static final int MARKER_PREFIX = 0xFF;
    private static final int MARKER_SOI = 0xD8;
    private static final int MARKER_EOI = 0xD9;
    private static final int MARKER_SOF0 = 0xC0;
    private static final int MARKER_SOF2 = 0xC2;
    private static final int MARKER_SOS = 0xDA;

    private JpegDimensions() {
    }

    /**
     * @param jpeg raw JPEG codestream bytes; may be {@code null}
     * @return {@code {width, height}} in pixels, or {@code null} if {@code jpeg} is not a
     *         well-formed JPEG, is truncated, or has no {@code SOF0}/{@code SOF2} marker
     *         before the entropy-coded scan data begins
     */
    static int[] parse(byte[] jpeg) {
        if (jpeg == null || jpeg.length < 4) {
            return null;
        }
        if (unsigned(jpeg[0]) != MARKER_PREFIX || unsigned(jpeg[1]) != MARKER_SOI) {
            return null; // does not start with the SOI marker: not a JPEG
        }

        int i = 2;
        while (i + 1 < jpeg.length) {
            if (unsigned(jpeg[i]) != MARKER_PREFIX) {
                return null; // lost sync with the marker chain -- not well-formed enough to trust
            }
            int marker = unsigned(jpeg[i + 1]);
            if (marker == MARKER_PREFIX) {
                i++; // 0xFF fill byte before the real marker code; skip and recheck
                continue;
            }
            if (marker == MARKER_SOI || marker == MARKER_EOI || (marker >= 0xD0 && marker <= 0xD7)) {
                i += 2; // fixed-length markers (SOI/EOI/RSTn): no length field follows
                continue;
            }
            if (i + 3 >= jpeg.length) {
                return null; // truncated before the length field
            }
            int length = (unsigned(jpeg[i + 2]) << 8) | unsigned(jpeg[i + 3]);
            if (marker == MARKER_SOF0 || marker == MARKER_SOF2) {
                int payloadStart = i + 4;
                if (payloadStart + 4 >= jpeg.length) {
                    return null; // truncated SOF payload
                }
                int height = (unsigned(jpeg[payloadStart + 1]) << 8) | unsigned(jpeg[payloadStart + 2]);
                int width = (unsigned(jpeg[payloadStart + 3]) << 8) | unsigned(jpeg[payloadStart + 4]);
                if (width <= 0 || height <= 0) {
                    return null;
                }
                return new int[] {width, height};
            }
            if (marker == MARKER_SOS) {
                return null; // entropy-coded data starts here; no SOF0/SOF2 seen before it
            }
            if (length < 2) {
                return null; // malformed length field
            }
            i += 2 + length;
        }
        return null; // ran off the end without finding SOF0/SOF2
    }

    private static int unsigned(byte b) {
        return b & 0xFF;
    }
}
