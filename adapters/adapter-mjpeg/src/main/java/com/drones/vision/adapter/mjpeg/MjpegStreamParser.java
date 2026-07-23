package com.drones.vision.adapter.mjpeg;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Pure, dependency-free {@code multipart/x-mixed-replace} parser: reads
 * successive JPEG parts off an {@link InputStream} one at a time.
 *
 * <p>This is exactly the wire format an MJPEG camera (e.g. an ESP32-CAM)
 * serves: a boundary line, a small set of part headers (notably an optional
 * {@code Content-Length}), a blank line, the part body, then the next
 * boundary. This class knows nothing about HTTP or sockets — it operates
 * purely on whatever bytes {@link #nextPart(InputStream)} is handed, which
 * makes it unit-testable with a plain {@link java.io.ByteArrayInputStream}
 * and no network at all. Callers driving this from a live connection should
 * wrap the raw response stream in a {@link java.io.BufferedInputStream} for
 * I/O efficiency — this class deliberately does no internal buffering of its
 * own to stay a simple, single-purpose state machine.
 *
 * <h2>Boundary handling</h2>
 * The boundary token is taken from the response's {@code Content-Type}
 * header (see {@link #forContentType(String)}), tolerating a quoted or
 * unquoted value and a token that a non-conformant server may already have
 * prefixed with {@code --} (both forms normalize to the same bare token; see
 * {@link #normalizeBoundaryToken(String)}). Bytes preceding the very first
 * boundary marker (or between an unmeasured part's end and the next marker)
 * are treated as ignorable framing noise and discarded.
 *
 * <h2>Part length</h2>
 * When a part declares {@code Content-Length}, exactly that many bytes are
 * read as the body — the fast, unambiguous path. When it is absent, the body
 * is instead determined by scanning forward for the next boundary marker
 * (preceded by its {@code CRLF}, which is excluded from the returned bytes)
 * — slower, but correct for servers that omit the header.
 *
 * <h2>Threading</h2>
 * Not thread-safe: an instance carries the byte-level scan position for one
 * connection and must only ever be driven by one caller at a time, exactly
 * like the {@link InputStream} it reads from.
 */
final class MjpegStreamParser {

    private static final String BOUNDARY_PARAMETER = "boundary=";
    private static final byte[] CRLF = {'\r', '\n'};

    private final byte[] bareDelimiter; // "--" + token
    private final byte[] delimiterAfterCrlf; // "\r\n--" + token

    /**
     * Tracks whether the stream position is already parked right after a
     * boundary token (true once a boundary-scanned body's trailing
     * {@code CRLF--token} has already been consumed while searching for the
     * body's end) so the next {@link #nextPart(InputStream)} call skips the
     * usual leading boundary search and goes straight to reading the
     * boundary's trailer/headers.
     */
    private boolean positionedAtBoundaryToken;

    /**
     * @param boundaryToken the bare boundary token (no leading {@code --}, no quotes); must not be blank
     */
    MjpegStreamParser(String boundaryToken) {
        if (boundaryToken == null || boundaryToken.isBlank()) {
            throw new IllegalArgumentException("boundaryToken must not be blank");
        }
        byte[] tokenBytes = boundaryToken.getBytes(StandardCharsets.ISO_8859_1);
        this.bareDelimiter = concat("--".getBytes(StandardCharsets.ISO_8859_1), tokenBytes);
        this.delimiterAfterCrlf = concat(CRLF, bareDelimiter);
    }

    /**
     * Builds a parser from a raw {@code Content-Type} header value, e.g.
     * {@code multipart/x-mixed-replace; boundary=frame} or
     * {@code multipart/x-mixed-replace;boundary="frame"}.
     *
     * @param contentTypeHeaderValue the header value; must contain a {@code boundary} parameter
     * @return a parser for that boundary
     * @throws IllegalArgumentException if the header is missing or has no {@code boundary} parameter
     */
    static MjpegStreamParser forContentType(String contentTypeHeaderValue) {
        return new MjpegStreamParser(extractBoundaryToken(contentTypeHeaderValue));
    }

    /**
     * Extracts and normalizes the boundary token from a {@code Content-Type} header value.
     *
     * @param contentTypeHeaderValue header value, e.g. {@code multipart/x-mixed-replace; boundary=frame}
     * @return the bare boundary token (no quotes, no leading {@code --})
     * @throws IllegalArgumentException if the header is missing or has no {@code boundary} parameter
     */
    static String extractBoundaryToken(String contentTypeHeaderValue) {
        if (contentTypeHeaderValue == null || contentTypeHeaderValue.isBlank()) {
            throw new IllegalArgumentException("Missing Content-Type header for an MJPEG multipart stream");
        }
        for (String parameter : contentTypeHeaderValue.split(";")) {
            String trimmed = parameter.trim();
            if (trimmed.regionMatches(true, 0, BOUNDARY_PARAMETER, 0, BOUNDARY_PARAMETER.length())) {
                return normalizeBoundaryToken(trimmed.substring(BOUNDARY_PARAMETER.length()));
            }
        }
        throw new IllegalArgumentException(
                "Content-Type has no boundary parameter for an MJPEG multipart stream: " + contentTypeHeaderValue);
    }

    /**
     * Strips optional surrounding quotes and any leading {@code --} a
     * non-conformant server may already have embedded in the header value
     * (the standard form has none — the {@code --} is added only in the
     * body's delimiter lines — but tolerating it costs nothing).
     */
    static String normalizeBoundaryToken(String raw) {
        String value = raw.trim();
        if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
            value = value.substring(1, value.length() - 1).trim();
        }
        while (value.startsWith("--")) {
            value = value.substring(2);
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException("boundary parameter is blank once normalized: " + raw);
        }
        return value;
    }

    /**
     * Reads and returns the next part's raw body bytes (the JPEG payload).
     *
     * @param in the stream to read from; the same instance must be passed on every call for one connection
     * @return the part's body bytes, or {@code null} at a clean end of the multipart stream (EOF, or an
     *         explicit {@code --boundary--} terminal marker)
     * @throws IOException on the underlying stream's own I/O failures
     */
    byte[] nextPart(InputStream in) throws IOException {
        if (!positionedAtBoundaryToken) {
            if (scanUntil(in, bareDelimiter) == null) {
                return null; // EOF: no more boundaries, nothing left to parse
            }
        }
        positionedAtBoundaryToken = false;

        String trailer = readLine(in);
        if (trailer == null) {
            return null; // EOF right after the boundary token
        }
        if (trailer.startsWith("--")) {
            return null; // "--boundary--": explicit end of the multipart stream
        }

        Integer contentLength = null;
        String headerLine;
        while ((headerLine = readLine(in)) != null && !headerLine.isEmpty()) {
            int colon = headerLine.indexOf(':');
            if (colon > 0) {
                String name = headerLine.substring(0, colon).trim();
                if (name.equalsIgnoreCase("Content-Length")) {
                    try {
                        contentLength = Integer.parseInt(headerLine.substring(colon + 1).trim());
                    } catch (NumberFormatException ignored) {
                        // malformed Content-Length: fall back to the boundary-scan path below
                    }
                }
            }
        }
        if (headerLine == null) {
            return null; // EOF while reading part headers
        }

        if (contentLength != null && contentLength >= 0) {
            byte[] body = new byte[contentLength];
            int read = in.readNBytes(body, 0, contentLength);
            return read == contentLength ? body : null; // short read: EOF mid-body
        }

        byte[] body = scanUntil(in, delimiterAfterCrlf);
        if (body == null) {
            return null; // EOF while scanning for the body's end
        }
        positionedAtBoundaryToken = true; // delimiterAfterCrlf already consumed the next "--token"
        return body;
    }

    /**
     * Reads bytes up to (and consuming, but not returning) the first
     * occurrence of {@code delimiter}, via a small sliding window so
     * fragmented delivery (the underlying stream returning as little as one
     * byte per {@code read} call) never affects correctness.
     *
     * @return bytes preceding the delimiter, or {@code null} if the stream ended before it was found
     */
    private static byte[] scanUntil(InputStream in, byte[] delimiter) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] window = new byte[delimiter.length];
        int windowSize = 0;
        int b;
        while ((b = in.read()) != -1) {
            if (windowSize == delimiter.length) {
                out.write(window[0]);
                System.arraycopy(window, 1, window, 0, windowSize - 1);
                windowSize--;
            }
            window[windowSize++] = (byte) b;
            if (windowSize == delimiter.length && matches(window, delimiter)) {
                return out.toByteArray();
            }
        }
        return null; // EOF before a full delimiter match
    }

    private static boolean matches(byte[] window, byte[] delimiter) {
        for (int i = 0; i < delimiter.length; i++) {
            if (window[i] != delimiter[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reads one line terminated by {@code \n} (a preceding {@code \r} is
     * stripped), one byte at a time so arbitrarily small underlying reads
     * never break line framing.
     *
     * @return the line without its terminator, or {@code null} at EOF before any terminator was seen
     */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                byte[] bytes = line.toByteArray();
                int length = bytes.length;
                if (length > 0 && bytes[length - 1] == '\r') {
                    length--;
                }
                return new String(bytes, 0, length, StandardCharsets.ISO_8859_1);
            }
            line.write(b);
        }
        return null; // EOF without a terminating newline
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
