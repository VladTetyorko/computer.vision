package com.drones.vision.adapter.mjpeg;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MjpegStreamParserTest {

    private static final String TOKEN = "frameboundary";

    @Test
    void parsesSuccessivePartsWithContentLength() throws IOException {
        byte[] part1 = "part-one-bytes".getBytes(StandardCharsets.US_ASCII);
        byte[] part2 = "part-two-bytes-longer".getBytes(StandardCharsets.US_ASCII);
        byte[] stream = buildMultipart(TOKEN, true, part1, part2);

        MjpegStreamParser parser = new MjpegStreamParser(TOKEN);
        InputStream in = new ByteArrayInputStream(stream);

        assertArrayEquals(part1, parser.nextPart(in));
        assertArrayEquals(part2, parser.nextPart(in));
        assertNull(parser.nextPart(in), "expected a clean end after the last part's terminal boundary");
    }

    @Test
    void parsesSuccessivePartsWithoutContentLengthViaBoundaryScan() throws IOException {
        byte[] part1 = "no-length-part-one".getBytes(StandardCharsets.US_ASCII);
        byte[] part2 = "no-length-part-two".getBytes(StandardCharsets.US_ASCII);
        byte[] stream = buildMultipart(TOKEN, false, part1, part2);

        MjpegStreamParser parser = new MjpegStreamParser(TOKEN);
        InputStream in = new ByteArrayInputStream(stream);

        assertArrayEquals(part1, parser.nextPart(in));
        assertArrayEquals(part2, parser.nextPart(in));
        assertNull(parser.nextPart(in));
    }

    @Test
    void toleratesGarbageBeforeTheFirstBoundary() throws IOException {
        byte[] part1 = "after-garbage".getBytes(StandardCharsets.US_ASCII);
        byte[] garbage = "some junk preamble a real server should never send but must not crash us"
                .getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(garbage);
        out.write(buildMultipart(TOKEN, true, part1));

        MjpegStreamParser parser = new MjpegStreamParser(TOKEN);
        assertArrayEquals(part1, parser.nextPart(new ByteArrayInputStream(out.toByteArray())));
    }

    @Test
    void toleratesFragmentedDeliveryOfOneToSevenBytesPerRead() throws IOException {
        byte[] part1 = "fragment-delivery-part-one-with-enough-length-to-cross-several-chunks"
                .getBytes(StandardCharsets.US_ASCII);
        byte[] part2 = "fragment-delivery-part-two".getBytes(StandardCharsets.US_ASCII);
        byte[] stream = buildMultipart(TOKEN, true, part1, part2);

        MjpegStreamParser parser = new MjpegStreamParser(TOKEN);
        InputStream in = new ChunkedInputStream(stream, 1, 7);

        assertArrayEquals(part1, parser.nextPart(in));
        assertArrayEquals(part2, parser.nextPart(in));
        assertNull(parser.nextPart(in));
    }

    @Test
    void toleratesFragmentedDeliveryWithoutContentLengthToo() throws IOException {
        byte[] part1 = "fragmented-no-length-part-one-long-enough".getBytes(StandardCharsets.US_ASCII);
        byte[] part2 = "fragmented-no-length-part-two".getBytes(StandardCharsets.US_ASCII);
        byte[] stream = buildMultipart(TOKEN, false, part1, part2);

        MjpegStreamParser parser = new MjpegStreamParser(TOKEN);
        InputStream in = new ChunkedInputStream(stream, 1, 7);

        assertArrayEquals(part1, parser.nextPart(in));
        assertArrayEquals(part2, parser.nextPart(in));
        assertNull(parser.nextPart(in));
    }

    @Test
    void endMarkerTerminatesTheStreamCleanly() throws IOException {
        byte[] part1 = "only-part".getBytes(StandardCharsets.US_ASCII);
        String stream = "--" + TOKEN + "\r\n"
                + "Content-Type: image/jpeg\r\n"
                + "Content-Length: " + part1.length + "\r\n"
                + "\r\n"
                + new String(part1, StandardCharsets.US_ASCII) + "\r\n"
                + "--" + TOKEN + "--\r\n";

        MjpegStreamParser parser = new MjpegStreamParser(TOKEN);
        InputStream in = new ByteArrayInputStream(stream.getBytes(StandardCharsets.US_ASCII));

        assertArrayEquals(part1, parser.nextPart(in));
        assertNull(parser.nextPart(in));
    }

    @Test
    void constructorRejectsABlankBoundaryToken() {
        assertThrows(IllegalArgumentException.class, () -> new MjpegStreamParser(""));
        assertThrows(IllegalArgumentException.class, () -> new MjpegStreamParser(null));
    }

    @Test
    void extractsBoundaryTokenFromContentTypeHeaderUnquoted() {
        assertEquals("frame", MjpegStreamParser.extractBoundaryToken("multipart/x-mixed-replace; boundary=frame"));
    }

    @Test
    void extractsBoundaryTokenFromContentTypeHeaderQuoted() {
        assertEquals("frame", MjpegStreamParser.extractBoundaryToken("multipart/x-mixed-replace; boundary=\"frame\""));
    }

    @Test
    void extractsBoundaryTokenFromContentTypeHeaderWithLeadingDashesAlreadyEmbedded() {
        assertEquals("frame", MjpegStreamParser.extractBoundaryToken("multipart/x-mixed-replace; boundary=--frame"));
    }

    @Test
    void extractsBoundaryTokenCaseInsensitivelyAndAmongMultipleParameters() {
        assertEquals("xyz",
                MjpegStreamParser.extractBoundaryToken("Multipart/x-mixed-replace; charset=utf-8; BOUNDARY=xyz"));
    }

    @Test
    void extractBoundaryTokenRejectsAMissingBoundaryParameter() {
        assertThrows(IllegalArgumentException.class,
                () -> MjpegStreamParser.extractBoundaryToken("multipart/x-mixed-replace"));
    }

    @Test
    void extractBoundaryTokenRejectsANullOrBlankHeader() {
        assertThrows(IllegalArgumentException.class, () -> MjpegStreamParser.extractBoundaryToken(null));
        assertThrows(IllegalArgumentException.class, () -> MjpegStreamParser.extractBoundaryToken("  "));
    }

    @Test
    void forContentTypeBuildsAWorkingParser() throws IOException {
        byte[] part1 = "via-for-content-type".getBytes(StandardCharsets.US_ASCII);
        byte[] stream = buildMultipart(TOKEN, true, part1);

        MjpegStreamParser parser = MjpegStreamParser.forContentType("multipart/x-mixed-replace; boundary=" + TOKEN);
        assertArrayEquals(part1, parser.nextPart(new ByteArrayInputStream(stream)));
    }

    private static byte[] buildMultipart(String token, boolean includeContentLength, byte[]... parts) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(("--" + token + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write("Content-Type: image/jpeg\r\n".getBytes(StandardCharsets.US_ASCII));
            if (includeContentLength) {
                out.write(("Content-Length: " + part.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
            }
            out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            out.write(part);
            out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        out.write(("--" + token + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    /** Test-only {@link InputStream} that never returns more than a small chunk per bulk read call. */
    private static final class ChunkedInputStream extends InputStream {
        private final byte[] data;
        private final int minChunk;
        private final int maxChunk;
        private final Random random = new Random(42);
        private int position = 0;

        ChunkedInputStream(byte[] data, int minChunk, int maxChunk) {
            this.data = data;
            this.minChunk = minChunk;
            this.maxChunk = maxChunk;
        }

        @Override
        public int read() {
            if (position >= data.length) {
                return -1;
            }
            return data[position++] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (position >= data.length) {
                return -1;
            }
            int chunk = minChunk + random.nextInt(maxChunk - minChunk + 1);
            int toCopy = Math.min(len, Math.min(chunk, data.length - position));
            System.arraycopy(data, position, b, off, toCopy);
            position += toCopy;
            return toCopy;
        }
    }
}
