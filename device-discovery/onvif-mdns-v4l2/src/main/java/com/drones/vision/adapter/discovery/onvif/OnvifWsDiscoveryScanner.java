package com.drones.vision.adapter.discovery.onvif;

import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.warehouse.domain.model.DiscoveredDevice;
import com.drones.vision.warehouse.domain.model.SourceStatus;
import com.drones.vision.warehouse.domain.port.DeviceDiscoveryPort;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link DeviceDiscoveryPort} implementation for ONVIF WS-Discovery.
 *
 * <p>Sends a WS-Discovery SOAP {@code Probe} for {@code
 * NetworkVideoTransmitter} over UDP multicast ({@code 239.255.255.250:3702}
 * by default) and collects {@code ProbeMatch} datagrams sent back to the
 * probing socket until {@link #scan(Duration)}'s timeout elapses.
 *
 * <p>No SOAP/XML stack is used — deliberately, per the discovery plan's KISS
 * decision (hand-rolled UDP + minimal parsing). {@code XAddrs}, {@code
 * Scopes}, {@code Types} and the endpoint reference are extracted from the
 * raw response text with lenient regex/string matching rather than a real
 * XML parser. A datagram that does not look like a usable {@code
 * ProbeMatch} (missing/blank {@code XAddrs}, truncated XML, an unparsable
 * address, ...) is skipped — it never fails or aborts the scan.
 *
 * <p>After WS-Discovery collection, every ProbeMatch candidate is followed
 * up with an anonymous ONVIF {@code GetCapabilities}/{@code GetProfiles}/
 * {@code GetStreamUri} SOAP-over-HTTP chain ({@link OnvifDeviceClient}) to
 * try to turn its ONVIF device-service address into a playable {@code
 * suggestedStream}. A camera that requires credentials (HTTP 401 or a SOAP
 * auth fault) keeps {@code suggestedStream = null} but gets an honest
 * {@code details["note"]} instead of a guessed URL; a camera that times out,
 * refuses the connection, or answers with something unparsable is returned
 * exactly as before this chain existed — {@code suggestedStream = null},
 * no note. Either way the candidate is still returned so the user can
 * register it manually (docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md
 * &sect;3 P3, wave Z4).
 *
 * <p>{@link #scan(Duration)}'s {@code timeout} bounds <em>both</em> phases
 * together, not the probe collection alone: {@link #PROBE_COLLECTION_SHARE}
 * of the budget goes to WS-Discovery collection, the rest to the follow-up
 * chain (run concurrently across every candidate, one virtual thread each,
 * bounded by the same overall deadline — mirroring {@code
 * DefaultDiscoveryService}'s own per-port fan-out/bounded-wait pattern) —
 * see {@link #completeStreams(Collection, long)}. A single slow or
 * unresponsive camera's follow-up calls are bounded by {@link
 * OnvifDeviceClient}'s own per-request {@code HttpClient} timeout, so it
 * cannot delay any other candidate's follow-up, let alone extend the scan
 * past its budget.
 *
 * <p>Plain class, no framework dependency — instantiated directly by {@code
 * vision-app}'s wiring configuration.
 */
public final class OnvifWsDiscoveryScanner implements DeviceDiscoveryPort {

    private static final System.Logger LOG = System.getLogger(OnvifWsDiscoveryScanner.class.getName());

    private static final String METHOD = "onvif";

    private static final String STANDARD_MULTICAST_HOST = "239.255.255.250";
    private static final int STANDARD_MULTICAST_PORT = 3702;

    /** Larger than any realistic ONVIF ProbeMatch response; max theoretical UDP/IPv4 payload. */
    private static final int RECEIVE_BUFFER_SIZE = 65_507;

    /**
     * Share of {@link #scan(Duration)}'s {@code timeout} spent on WS-Discovery {@code ProbeMatch}
     * collection; the remainder is left for the {@code GetCapabilities}/{@code GetProfiles}/
     * {@code GetStreamUri} follow-up chain. An even split is deliberately simple (docs/plans/
     * active/LAYERING-REFACTOR-PLAN.md &sect;1.3's out-of-scope-constant precedent, same as this
     * class's multicast address/buffer size) rather than a configurable {@code ScanBudget}-style
     * record — there is exactly one tunable here, below the module's two-tunable settings-record
     * threshold.
     */
    private static final double PROBE_COLLECTION_SHARE = 0.5;

    private static final String CREDENTIALS_REQUIRED_NOTE = "credentials required — stream URL cannot be suggested";

    private static final Pattern XADDRS_PATTERN =
            Pattern.compile("<[^:>]*:?XAddrs[^>]*>(.*?)</[^:>]*:?XAddrs>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern SCOPES_PATTERN =
            Pattern.compile("<[^:>]*:?Scopes[^>]*>(.*?)</[^:>]*:?Scopes>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern TYPES_PATTERN =
            Pattern.compile("<[^:>]*:?Types[^>]*>(.*?)</[^:>]*:?Types>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern EPR_ADDRESS_PATTERN = Pattern.compile(
            "<[^:>]*:?Address[^>]*>\\s*(urn:uuid:[^<\\s]+)\\s*</[^:>]*:?Address>", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME_SCOPE_PATTERN =
            Pattern.compile("onvif://www\\.onvif\\.org/name/(\\S+)", Pattern.CASE_INSENSITIVE);

    private final InetSocketAddress probeTarget;
    private final OnvifDeviceClient deviceClient;

    /**
     * {@link SourceStatus#OK} until a scan fails to even open/send its probe socket, {@link
     * SourceStatus#UNREACHABLE} after; the next successful scan resets it. Without this override
     * the port's {@link DeviceDiscoveryPort#lastStatus()} default answers {@link
     * SourceStatus#NEVER_SCANNED} forever, which {@code DefaultDiscoveryService#health()} may pair
     * with a real last-scan time — a combination {@code SourceHealth}'s compact constructor
     * rightly rejects (the C2-wave default change from OK to NEVER_SCANNED missed this scanner).
     */
    private volatile SourceStatus lastStatus = SourceStatus.OK;

    /** Uses the standard WS-Discovery multicast address, {@code 239.255.255.250:3702}. */
    public OnvifWsDiscoveryScanner() {
        this(new InetSocketAddress(STANDARD_MULTICAST_HOST, STANDARD_MULTICAST_PORT));
    }

    /**
     * Test seam: probes an arbitrary target instead of the standard
     * multicast address — e.g. a loopback fake UDP responder in tests.
     *
     * @param probeTarget where to send the {@code Probe} datagram
     */
    public OnvifWsDiscoveryScanner(InetSocketAddress probeTarget) {
        this.probeTarget = Objects.requireNonNull(probeTarget, "probeTarget must not be null");
        this.deviceClient = new OnvifDeviceClient();
    }

    @Override
    public String method() {
        return METHOD;
    }

    @Override
    public SourceStatus lastStatus() {
        return lastStatus;
    }

    @Override
    public List<DiscoveredDevice> scan(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout must not be null");
        long budgetNanos = timeout.isNegative() ? 0L : timeout.toNanos();
        long startNanos = System.nanoTime();
        long deadlineNanos = startNanos + budgetNanos;
        long probeDeadlineNanos = startNanos + (long) (budgetNanos * PROBE_COLLECTION_SHARE);

        Map<URI, DiscoveredDevice> found = new LinkedHashMap<>();
        try (DatagramSocket socket = new DatagramSocket()) {
            sendProbe(socket);
            collectResponses(socket, probeDeadlineNanos, found);
        } catch (IOException e) {
            // Socket creation or the initial send failed outright -- a genuine
            // failure per the port contract; the caller (DiscoveryService)
            // isolates it. Mid-scan receive errors are handled inside
            // collectResponses and never reach here.
            lastStatus = SourceStatus.UNREACHABLE;
            throw new UncheckedIOException("ONVIF WS-Discovery scan failed", e);
        }
        lastStatus = SourceStatus.OK;
        return List.copyOf(completeStreams(found.values(), deadlineNanos));
    }

    /**
     * Runs the {@code GetCapabilities}/{@code GetProfiles}/{@code GetStreamUri} follow-up chain
     * for every probe candidate concurrently (one virtual thread each, mirroring {@code
     * DefaultDiscoveryService}'s own fan-out), then waits for each — up to its remaining share of
     * {@code deadlineNanos} — falling back to the original, unmodified candidate if that wait
     * itself times out or is interrupted (defensive: {@link OnvifDeviceClient#probeStream} already
     * self-bounds via its per-request HTTP timeout and never throws, so this fallback should not
     * normally trigger).
     */
    private List<DiscoveredDevice> completeStreams(Collection<DiscoveredDevice> candidates, long deadlineNanos) {
        List<DiscoveredDevice> ordered = List.copyOf(candidates);
        if (ordered.isEmpty()) {
            return ordered;
        }
        List<CompletableFuture<DiscoveredDevice>> futures = new ArrayList<>(ordered.size());
        for (DiscoveredDevice candidate : ordered) {
            futures.add(completeOneAsync(candidate, deadlineNanos));
        }

        List<DiscoveredDevice> result = new ArrayList<>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            DiscoveredDevice fallback = ordered.get(i);
            long remainingNanos = Math.max(0L, deadlineNanos - System.nanoTime());
            try {
                result.add(futures.get(i).get(remainingNanos, TimeUnit.NANOSECONDS));
            } catch (TimeoutException | ExecutionException e) {
                LOG.log(System.Logger.Level.DEBUG,
                        () -> "ONVIF stream follow-up for " + fallback.address() + " abandoned: " + e);
                result.add(fallback);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                result.add(fallback);
            }
        }
        return result;
    }

    private CompletableFuture<DiscoveredDevice> completeOneAsync(DiscoveredDevice candidate, long deadlineNanos) {
        CompletableFuture<DiscoveredDevice> future = new CompletableFuture<>();
        Thread.ofVirtual().name("onvif-streamuri-" + candidate.address()).start(() -> {
            try {
                future.complete(completeOne(candidate, deadlineNanos));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    private DiscoveredDevice completeOne(DiscoveredDevice candidate, long deadlineNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            return candidate;
        }
        StreamProbeOutcome outcome = deviceClient.probeStream(candidate.address(), Duration.ofNanos(remainingNanos));
        return switch (outcome) {
            case StreamProbeOutcome.Found found -> withStream(candidate, found.uri());
            case StreamProbeOutcome.AuthRequired ignored -> withCredentialsNote(candidate);
            case StreamProbeOutcome.Unavailable ignored -> candidate;
        };
    }

    private static DiscoveredDevice withStream(DiscoveredDevice candidate, URI streamUri) {
        StreamDescriptor stream = new StreamDescriptor("rtsp", streamUri, Map.of());
        return new DiscoveredDevice(candidate.method(), candidate.name(), candidate.address(),
                candidate.suggestedCategory(), stream, candidate.details());
    }

    private static DiscoveredDevice withCredentialsNote(DiscoveredDevice candidate) {
        Map<String, String> details = new LinkedHashMap<>(candidate.details());
        details.put("note", CREDENTIALS_REQUIRED_NOTE);
        return new DiscoveredDevice(candidate.method(), candidate.name(), candidate.address(),
                candidate.suggestedCategory(), null, details);
    }

    private void sendProbe(DatagramSocket socket) throws IOException {
        byte[] payload = buildProbeEnvelope().getBytes(StandardCharsets.UTF_8);
        socket.send(new DatagramPacket(payload, payload.length, probeTarget));
    }

    private static String buildProbeEnvelope() {
        String messageId = "urn:uuid:" + UUID.randomUUID();
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope"
                            xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing"
                            xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery"
                            xmlns:dn="http://www.onvif.org/ver10/network/wsdl">
                    <e:Header>
                        <w:MessageID>%s</w:MessageID>
                        <w:To e:mustUnderstand="1">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>
                        <w:Action e:mustUnderstand="1">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action>
                    </e:Header>
                    <e:Body>
                        <d:Probe>
                            <d:Types>dn:NetworkVideoTransmitter</d:Types>
                        </d:Probe>
                    </e:Body>
                </e:Envelope>
                """.formatted(messageId);
    }

    /**
     * Receives datagrams on {@code socket} and parses each as a {@code
     * ProbeMatch} until {@code deadlineNanos} is reached. A timeout or any
     * other receive-time error simply ends collection -- whatever was
     * gathered so far is kept; the scan never throws from here.
     */
    private void collectResponses(DatagramSocket socket, long deadlineNanos, Map<URI, DiscoveredDevice> found) {
        byte[] buffer = new byte[RECEIVE_BUFFER_SIZE];
        while (true) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                return;
            }
            try {
                int remainingMillis = (int) Math.max(1L, Math.min(Integer.MAX_VALUE, remainingNanos / 1_000_000L));
                socket.setSoTimeout(remainingMillis);
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String xml = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
                parseProbeMatch(xml).ifPresent(device -> found.putIfAbsent(device.address(), device));
            } catch (IOException e) {
                LOG.log(System.Logger.Level.DEBUG, () -> "ONVIF WS-Discovery receive ended: " + e);
                return;
            }
        }
    }

    /**
     * Parses one {@code ProbeMatch} datagram body into a candidate, or
     * returns empty if the text does not contain a usable {@code XAddrs}
     * entry or otherwise fails to parse. Package-private so parser tests can
     * drive it directly on canned XML without a socket.
     *
     * @param xml raw datagram payload, decoded as UTF-8
     * @return the parsed candidate, or empty if {@code xml} is not a usable ProbeMatch
     */
    static Optional<DiscoveredDevice> parseProbeMatch(String xml) {
        if (xml == null || xml.isBlank()) {
            return Optional.empty();
        }
        try {
            String xAddrsBlock = firstGroup(XADDRS_PATTERN, xml);
            String firstXAddr = xAddrsBlock == null ? null : firstToken(xAddrsBlock);
            if (firstXAddr == null) {
                return Optional.empty();
            }
            URI address = URI.create(firstXAddr);

            String scopesBlock = firstGroup(SCOPES_PATTERN, xml);
            String name = extractName(scopesBlock, address);

            Map<String, String> details = new LinkedHashMap<>();
            if (scopesBlock != null && !scopesBlock.isBlank()) {
                details.put("scopes", collapseWhitespace(scopesBlock));
            }
            String typesBlock = firstGroup(TYPES_PATTERN, xml);
            if (typesBlock != null && !typesBlock.isBlank()) {
                details.put("types", collapseWhitespace(typesBlock));
            }
            String epr = firstGroup(EPR_ADDRESS_PATTERN, xml);
            if (epr != null) {
                details.put("epr", epr.trim());
            }

            return Optional.of(new DiscoveredDevice(METHOD, name, address, new CategoryId("ip-camera"), null, details));
        } catch (RuntimeException e) {
            // Malformed/unexpected XML (bad URI syntax, missing groups, ...):
            // skip this datagram rather than crashing the scan.
            return Optional.empty();
        }
    }

    private static String extractName(String scopesBlock, URI fallbackAddress) {
        if (scopesBlock != null) {
            Matcher matcher = NAME_SCOPE_PATTERN.matcher(scopesBlock);
            if (matcher.find()) {
                String encoded = matcher.group(1);
                try {
                    return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
                } catch (RuntimeException e) {
                    return encoded;
                }
            }
        }
        String host = fallbackAddress.getHost();
        return host != null && !host.isBlank() ? host : fallbackAddress.toString();
    }

    private static String firstGroup(Pattern pattern, String xml) {
        Matcher matcher = pattern.matcher(xml);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String firstToken(String block) {
        String trimmed = block.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String[] tokens = trimmed.split("\\s+");
        return tokens[0].isBlank() ? null : tokens[0];
    }

    private static String collapseWhitespace(String s) {
        return s.trim().replaceAll("\\s+", " ");
    }
}
