package com.drones.vision.adapter.discovery.onvif;

import com.drones.vision.kernel.CategoryId;
import com.drones.vision.warehouse.domain.model.DiscoveredDevice;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
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
 * <p>Every candidate's {@link DiscoveredDevice#suggestedStream()} is {@code
 * null}: producing a playable RTSP URI requires an authenticated {@code
 * GetStreamUri} SOAP call, which needs device credentials the discovery flow
 * does not have. That call is added by {@code adapter-onvif} in Phase 4; for
 * now the candidate carries the device's ONVIF service address ({@code
 * XAddrs}) and best-effort name/scopes so the user can still register it
 * manually.
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
    }

    @Override
    public String method() {
        return METHOD;
    }

    @Override
    public List<DiscoveredDevice> scan(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout must not be null");
        long budgetNanos = timeout.isNegative() ? 0L : timeout.toNanos();
        long deadlineNanos = System.nanoTime() + budgetNanos;

        Map<URI, DiscoveredDevice> found = new LinkedHashMap<>();
        try (DatagramSocket socket = new DatagramSocket()) {
            sendProbe(socket);
            collectResponses(socket, deadlineNanos, found);
        } catch (IOException e) {
            // Socket creation or the initial send failed outright -- a genuine
            // failure per the port contract; the caller (DiscoveryService)
            // isolates it. Mid-scan receive errors are handled inside
            // collectResponses and never reach here.
            throw new UncheckedIOException("ONVIF WS-Discovery scan failed", e);
        }
        return List.copyOf(found.values());
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
