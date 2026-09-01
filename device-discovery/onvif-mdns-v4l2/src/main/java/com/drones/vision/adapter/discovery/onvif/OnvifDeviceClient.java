package com.drones.vision.adapter.discovery.onvif;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Negotiates a playable RTSP stream URI from an ONVIF device's Media service, over plain
 * (anonymous/unauthenticated) SOAP-over-HTTP.
 *
 * <p>Chain, mirroring the ONVIF Profile S media flow: {@code GetCapabilities} against the device
 * service address (the {@code XAddrs} from a WS-Discovery {@code ProbeMatch}) to find the Media
 * service's own address, {@code GetProfiles} against that address for the first configured
 * profile's token, then {@code GetStreamUri} for that profile's RTP-Unicast/RTSP transport.
 * {@code GetServices} is not implemented as a fallback — {@code GetCapabilities} alone covers
 * every Profile-S-conformant camera this scanner targets; a device that answers neither simply
 * yields {@link StreamProbeOutcome.Unavailable}.
 *
 * <p>No SOAP/XML stack is used, consistent with {@link OnvifWsDiscoveryScanner}'s own {@code
 * Probe}/{@code ProbeMatch} handling: hand-built SOAP 1.2 envelopes as text blocks, hand-rolled
 * regex extraction of the response (no credentials, no WS-Security header — every request is
 * anonymous by construction).
 *
 * <p>{@link #probeStream(URI, Duration)} never throws: a connection failure, a timeout, a
 * malformed/unexpected response, a missing capability, or the budget running out mid-chain all
 * fold into {@link StreamProbeOutcome.Unavailable} — the same "never fails the scan" contract
 * {@link OnvifWsDiscoveryScanner#parseProbeMatch} already keeps for the {@code Probe} side. A
 * device that refuses with HTTP 401 or a SOAP auth fault at <em>any</em> step is reported as
 * {@link StreamProbeOutcome.AuthRequired} instead — the two are deliberately not conflated, since
 * one means "try again with credentials" and the other means "this candidate cannot be probed
 * further right now".
 *
 * <p>{@code budget} bounds the <em>whole</em> chain, not each call individually: the deadline is
 * computed once and each of the (up to three) sequential POSTs is given only its remaining share,
 * so a camera that is merely slow on the first call cannot also claim a full budget's worth of
 * time on the second and third.
 */
final class OnvifDeviceClient {

    private static final System.Logger LOG = System.getLogger(OnvifDeviceClient.class.getName());

    private static final int HTTP_UNAUTHORIZED = 401;

    private static final String SOAP_CONTENT_TYPE = "application/soap+xml; charset=utf-8";

    private static final Pattern SOAP_FAULT_PATTERN =
            Pattern.compile("<[^:>]*:?Fault\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern AUTH_FAULT_HINT_PATTERN =
            Pattern.compile("not\\s*authorized|unauthorized|authentication", Pattern.CASE_INSENSITIVE);

    private static final Pattern MEDIA_BLOCK_PATTERN = elementPattern("Media");
    private static final Pattern XADDR_PATTERN = elementPattern("XAddr");
    private static final Pattern URI_PATTERN = elementPattern("Uri");
    private static final Pattern PROFILE_TOKEN_PATTERN =
            Pattern.compile("<(?:[A-Za-z0-9]+:)?Profiles\\b[^>]*\\btoken=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);

    private static final String GET_CAPABILITIES_BODY = """
            <?xml version="1.0" encoding="UTF-8"?>
            <e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope"
                        xmlns:tds="http://www.onvif.org/ver10/device/wsdl">
                <e:Body>
                    <tds:GetCapabilities>
                        <tds:Category>Media</tds:Category>
                    </tds:GetCapabilities>
                </e:Body>
            </e:Envelope>
            """;

    private static final String GET_PROFILES_BODY = """
            <?xml version="1.0" encoding="UTF-8"?>
            <e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope"
                        xmlns:trt="http://www.onvif.org/ver10/media/wsdl">
                <e:Body>
                    <trt:GetProfiles/>
                </e:Body>
            </e:Envelope>
            """;

    private final HttpClient httpClient;

    /** Production constructor: builds its own general-purpose {@link HttpClient}. */
    OnvifDeviceClient() {
        this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build());
    }

    /** Test seam: an explicit {@link HttpClient} (e.g. one pointed at a loopback test server). */
    OnvifDeviceClient(HttpClient httpClient) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
    }

    /**
     * Runs the {@code GetCapabilities} → {@code GetProfiles} → {@code GetStreamUri} chain against
     * {@code deviceServiceAddress}, bounded by {@code budget} as a whole (see class javadoc).
     *
     * @param deviceServiceAddress the ONVIF device service address (WS-Discovery {@code XAddrs})
     * @param budget               total time allowed for every remaining call in the chain
     * @return the outcome; never {@code null}, never throws
     */
    StreamProbeOutcome probeStream(URI deviceServiceAddress, Duration budget) {
        Objects.requireNonNull(deviceServiceAddress, "deviceServiceAddress must not be null");
        Objects.requireNonNull(budget, "budget must not be null");
        if (budget.isZero() || budget.isNegative()) {
            return new StreamProbeOutcome.Unavailable();
        }
        long deadlineNanos = System.nanoTime() + budget.toNanos();
        try {
            Optional<HttpResponse<String>> capabilities =
                    postWithinBudget(deviceServiceAddress, GET_CAPABILITIES_BODY, deadlineNanos);
            if (capabilities.isEmpty()) {
                return new StreamProbeOutcome.Unavailable();
            }
            if (isAuthRefused(capabilities.get().statusCode(), capabilities.get().body())) {
                return new StreamProbeOutcome.AuthRequired();
            }
            Optional<URI> mediaAddress = parseMediaXAddr(capabilities.get().body());
            if (mediaAddress.isEmpty()) {
                return new StreamProbeOutcome.Unavailable();
            }

            Optional<HttpResponse<String>> profiles =
                    postWithinBudget(mediaAddress.get(), GET_PROFILES_BODY, deadlineNanos);
            if (profiles.isEmpty()) {
                return new StreamProbeOutcome.Unavailable();
            }
            if (isAuthRefused(profiles.get().statusCode(), profiles.get().body())) {
                return new StreamProbeOutcome.AuthRequired();
            }
            Optional<String> profileToken = parseFirstProfileToken(profiles.get().body());
            if (profileToken.isEmpty()) {
                return new StreamProbeOutcome.Unavailable();
            }

            Optional<HttpResponse<String>> streamUri = postWithinBudget(
                    mediaAddress.get(), getStreamUriBody(profileToken.get()), deadlineNanos);
            if (streamUri.isEmpty()) {
                return new StreamProbeOutcome.Unavailable();
            }
            if (isAuthRefused(streamUri.get().statusCode(), streamUri.get().body())) {
                return new StreamProbeOutcome.AuthRequired();
            }
            return parseStreamUri(streamUri.get().body())
                    .<StreamProbeOutcome>map(StreamProbeOutcome.Found::new)
                    .orElseGet(StreamProbeOutcome.Unavailable::new);
        } catch (RuntimeException e) {
            // A URI.create() failure on an unexpected address, or any other parsing hiccup: never
            // let a malformed/unusual response fail the scan, same contract as parseProbeMatch.
            LOG.log(System.Logger.Level.DEBUG,
                    () -> "ONVIF stream negotiation abandoned for " + deviceServiceAddress + ": " + e);
            return new StreamProbeOutcome.Unavailable();
        }
    }

    /**
     * POSTs {@code soapBody} to {@code target} with a request timeout equal to whatever remains
     * until {@code deadlineNanos}. Returns empty (never throws) if the budget is already
     * exhausted, the call times out, or any network-level failure occurs — every one of those is
     * an honest "cannot suggest a stream", not a scan failure.
     */
    private Optional<HttpResponse<String>> postWithinBudget(URI target, String soapBody, long deadlineNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            return Optional.empty();
        }
        HttpRequest request = HttpRequest.newBuilder(target)
                .timeout(Duration.ofNanos(remainingNanos))
                .header("Content-Type", SOAP_CONTENT_TYPE)
                .POST(HttpRequest.BodyPublishers.ofString(soapBody, StandardCharsets.UTF_8))
                .build();
        try {
            return Optional.of(httpClient.send(request, HttpResponse.BodyHandlers.ofString()));
        } catch (HttpTimeoutException e) {
            LOG.log(System.Logger.Level.DEBUG, () -> "ONVIF SOAP call to " + target + " timed out");
            return Optional.empty();
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, () -> "ONVIF SOAP call to " + target + " failed: " + e);
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /**
     * @return {@code true} if the response is an outright HTTP 401, or a SOAP {@code Fault} whose
     *         text hints at authorization/authentication — both signals are required together for
     *         the fault-body path so an unrelated fault (e.g. {@code ActionNotSupported}) is never
     *         misreported as a credentials problem.
     */
    static boolean isAuthRefused(int statusCode, String body) {
        if (statusCode == HTTP_UNAUTHORIZED) {
            return true;
        }
        if (body == null || body.isBlank()) {
            return false;
        }
        return SOAP_FAULT_PATTERN.matcher(body).find() && AUTH_FAULT_HINT_PATTERN.matcher(body).find();
    }

    /**
     * Extracts the Media capability's {@code XAddr} from a {@code GetCapabilitiesResponse} —
     * scoped to the {@code Media} block specifically (not just the first {@code XAddr} in the
     * document, which would usually be the Device service's own address).
     */
    static Optional<URI> parseMediaXAddr(String xml) {
        String mediaBlock = firstGroup(MEDIA_BLOCK_PATTERN, xml);
        if (mediaBlock == null) {
            return Optional.empty();
        }
        return toUri(firstGroup(XADDR_PATTERN, mediaBlock));
    }

    /** Extracts the first profile's {@code token} attribute from a {@code GetProfilesResponse}. */
    static Optional<String> parseFirstProfileToken(String xml) {
        if (xml == null) {
            return Optional.empty();
        }
        Matcher matcher = PROFILE_TOKEN_PATTERN.matcher(xml);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String token = matcher.group(1);
        return token.isBlank() ? Optional.empty() : Optional.of(token);
    }

    /**
     * Extracts the stream URI from a {@code GetStreamUriResponse}'s {@code MediaUri/Uri}. Matches
     * the {@code Uri} element specifically, not its {@code MediaUri} wrapper — {@link
     * #elementPattern(String)}'s namespace-prefix group can only consume up to a literal colon, so
     * it never partially matches a longer local name like {@code MediaUri} as if it were {@code
     * Uri}.
     */
    static Optional<URI> parseStreamUri(String xml) {
        return toUri(firstGroup(URI_PATTERN, xml));
    }

    private static Optional<URI> toUri(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(URI.create(trimmed));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String firstGroup(Pattern pattern, String xml) {
        if (xml == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(xml);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * Builds a pattern matching {@code <[prefix:]localName ...>capture</[prefix:]localName>},
     * requiring the local name to be an exact, unqualified match. E.g. {@code
     * elementPattern("Uri")} matches {@code <tt:Uri>} but not {@code <tt:MediaUri>}: the optional
     * namespace-prefix group ({@code (?:[A-Za-z0-9]+:)?}) is all-or-nothing on a trailing literal
     * colon, so for an unprefixed tag like {@code <MediaUri>} it can only match zero characters —
     * and the literal {@code localName} that follows then has to match starting right after
     * {@code <}, which {@code "MediaUri"} does not do ({@code "Media..."} != {@code "Uri..."}).
     * Optional attributes are allowed only behind a leading whitespace, so {@code
     * elementPattern("Media")} likewise does not match a sibling capability like {@code
     * <tt:Media2>}.
     */
    private static Pattern elementPattern(String localName) {
        String tag = "(?:[A-Za-z0-9]+:)?" + localName;
        return Pattern.compile("<" + tag + "(?:\\s[^>]*)?>(.*?)</" + tag + ">",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    }

    private static String getStreamUriBody(String profileToken) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope"
                            xmlns:trt="http://www.onvif.org/ver10/media/wsdl"
                            xmlns:tt="http://www.onvif.org/ver10/schema">
                    <e:Body>
                        <trt:GetStreamUri>
                            <trt:StreamSetup>
                                <tt:Stream>RTP-Unicast</tt:Stream>
                                <tt:Transport>
                                    <tt:Protocol>RTSP</tt:Protocol>
                                </tt:Transport>
                            </trt:StreamSetup>
                            <trt:ProfileToken>%s</trt:ProfileToken>
                        </trt:GetStreamUri>
                    </e:Body>
                </e:Envelope>
                """.formatted(escapeXml(profileToken));
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
