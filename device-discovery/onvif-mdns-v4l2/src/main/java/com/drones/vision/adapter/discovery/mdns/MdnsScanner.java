package com.drones.vision.adapter.discovery.mdns;

import com.drones.vision.kernel.CategoryId;
import com.drones.vision.warehouse.domain.model.DiscoveredDevice;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.warehouse.domain.port.DeviceDiscoveryPort;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * {@link DeviceDiscoveryPort} implementation for mDNS/DNS-SD, backed by
 * jmdns.
 *
 * <p>Browses two service types for the duration of {@link #scan(Duration)}'s
 * timeout window: {@code _rtsp._tcp.local.} and {@code _http._tcp.local.}.
 * <ul>
 *   <li>{@code _rtsp} hits become {@code "ip-camera"} {@link CategoryId}
 *       candidates with a ready-to-use {@code rtsp://host:port/} {@link
 *       DiscoveredDevice#suggestedStream()}.</li>
 *   <li>{@code _http} hits whose service name or host hints at an ESP32-CAM
 *       ({@code esp32}, {@code espressif}, {@code esp-cam} -- case
 *       insensitive) become {@code "esp32-cam"} {@link CategoryId} candidates
 *       whose {@code mjpeg} suggested stream URI is the camera's actual
 *       conventional MJPEG path, {@code http://host:81/stream} (docs/plans/
 *       active/SOURCE-ONBOARDING-2-PLAN.md U9) -- {@code address()} still
 *       carries the advertised {@code _http._tcp} port (the plain control
 *       page), only the suggested stream is corrected.</li>
 *   <li>Every other {@code _http} hit is still returned, as a candidate with
 *       no suggested type or stream -- the user can still see and register
 *       it manually.</li>
 * </ul>
 *
 * <p>Both service types are browsed concurrently (each on its own virtual
 * thread) so the wall-clock cost of scanning two types stays within one
 * timeout window instead of doubling it. The underlying {@link JmDNS}
 * instance's setup ({@link JmDNS#create()}) and teardown ({@link
 * JmDNS#close()}) both cost real wall-clock time too -- close() in
 * particular runs jmdns's own internal cancellation state machine, which is
 * hard-coded to allow itself up to several seconds regardless of this
 * scanner's timeout -- so both are budgeted against {@link #scan(Duration)}'s
 * deadline the same way the browsing itself is: closing is always
 * <em>started</em> before {@link #scan(Duration)} returns (or throws), but
 * on a tight budget the method returns once its deadline is reached rather
 * than blocking for jmdns's teardown to fully finish, leaving that to
 * complete (and release the multicast socket) on its own virtual thread in
 * the background.
 *
 * <p>Plain class, no framework dependency -- instantiated directly by {@code
 * vision-app}'s wiring configuration.
 */
public final class MdnsScanner implements DeviceDiscoveryPort {

    private static final String METHOD = "mdns";

    private static final String RTSP_SERVICE_TYPE = "_rtsp._tcp.local.";
    private static final String HTTP_SERVICE_TYPE = "_http._tcp.local.";

    private static final Pattern ESP32_HINT = Pattern.compile("esp32|espressif|esp-cam", Pattern.CASE_INSENSITIVE);

    /** ESP32-CAM's conventional MJPEG stream port/path (docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md
     *  U9) -- distinct from the advertised {@code _http._tcp} port, which is the camera's plain HTTP
     *  control page, not the video stream. */
    private static final int ESP32_CAM_MJPEG_PORT = 81;
    private static final String ESP32_CAM_MJPEG_PATH = "/stream";

    private final InetAddress bindAddress;
    private final ScanBudget budget;

    /**
     * Binds jmdns to the platform's default interface/address selection,
     * using {@link ScanBudget#defaults()}.
     */
    public MdnsScanner() {
        this(null, ScanBudget.defaults());
    }

    /**
     * Test seam: binds jmdns to a specific address (e.g. loopback), letting
     * tests exercise real registration/discovery without depending on the
     * host's real network interfaces. Uses {@link ScanBudget#defaults()}.
     *
     * @param bindAddress address to bind jmdns to, or {@code null} for the platform default
     */
    public MdnsScanner(InetAddress bindAddress) {
        this(bindAddress, ScanBudget.defaults());
    }

    /**
     * Config seam: binds to the platform's default interface/address
     * selection but with a caller-supplied {@link ScanBudget} — the shape
     * {@code vision-app}'s wiring uses once {@code vision.discovery.mdns.*}
     * is bound (a later wave).
     *
     * @param budget timeout-budget cushions; see {@link ScanBudget}
     */
    public MdnsScanner(ScanBudget budget) {
        this(null, budget);
    }

    /**
     * Full constructor: both the bind-address test seam and a
     * caller-supplied {@link ScanBudget}.
     *
     * @param bindAddress address to bind jmdns to, or {@code null} for the platform default
     * @param budget timeout-budget cushions; see {@link ScanBudget}
     */
    public MdnsScanner(InetAddress bindAddress, ScanBudget budget) {
        this.bindAddress = bindAddress;
        this.budget = Objects.requireNonNull(budget, "budget must not be null");
    }

    @Override
    public String method() {
        return METHOD;
    }

    @Override
    public List<DiscoveredDevice> scan(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout must not be null");
        long timeoutMillis = Math.max(1L, timeout.toMillis());
        long startNanos = System.nanoTime();
        long deadlineNanos = startNanos + timeoutMillis * 1_000_000L;

        AtomicReference<ServiceInfo[]> rtspHits = new AtomicReference<>(new ServiceInfo[0]);
        AtomicReference<ServiceInfo[]> httpHits = new AtomicReference<>(new ServiceInfo[0]);
        JmDNS jmdns;
        try {
            jmdns = createJmDns();
        } catch (IOException e) {
            throw new UncheckedIOException("mDNS scan failed", e);
        }
        try {
            long listWindowMillis = listWindowMillis(startNanos, timeoutMillis);
            Thread rtspThread = startListing(jmdns, RTSP_SERVICE_TYPE, listWindowMillis, rtspHits);
            Thread httpThread = startListing(jmdns, HTTP_SERVICE_TYPE, listWindowMillis, httpHits);
            joinQuietly(rtspThread, remainingMillis(deadlineNanos));
            joinQuietly(httpThread, remainingMillis(deadlineNanos));
        } finally {
            closeWithinBudget(jmdns, deadlineNanos);
        }

        List<DiscoveredDevice> devices = new ArrayList<>();
        for (ServiceInfo info : rtspHits.get()) {
            devices.add(toDiscoveredDevice(info, true));
        }
        for (ServiceInfo info : httpHits.get()) {
            devices.add(toDiscoveredDevice(info, false));
        }
        return List.copyOf(devices);
    }

    private JmDNS createJmDns() throws IOException {
        return bindAddress != null ? JmDNS.create(bindAddress) : JmDNS.create();
    }

    /**
     * Starts {@link JmDNS#close()} on its own virtual thread and waits for it
     * only up to the scan's remaining budget, instead of blocking on it
     * directly. This matters because jmdns's close() runs an internal
     * cancellation state machine hard-coded to allow itself up to {@code
     * DNSConstants.CLOSE_TIMEOUT} (5 seconds in jmdns 3.5.9) -- entirely
     * unaware of and unrelated to this scanner's own timeout -- so a
     * synchronous close can by itself blow through the deadline that the
     * setup + browse phases were carefully budgeted to respect. If the
     * budget runs out first, this method returns anyway and lets the close
     * finish (and release the multicast socket) in the background -- the
     * same "leaked but harmless daemon-ish virtual thread" tradeoff {@code
     * DiscoveryService} already accepts for a hanging adapter.
     */
    private static void closeWithinBudget(JmDNS jmdns, long deadlineNanos) {
        Thread closer = Thread.ofVirtual().name("mdns-scan-close").unstarted(() -> {
            try {
                jmdns.close();
            } catch (IOException e) {
                // Best-effort cleanup; nothing meaningful to do with a close failure here.
            }
        });
        closer.start();
        joinQuietly(closer, remainingMillis(deadlineNanos));
    }

    /**
     * Budgets how long the two concurrent {@code list()} calls should each
     * run for, so that {@code createJmDns()} setup time (already spent by
     * the time this is called, measured against {@code startNanos}) plus
     * this window plus {@link ScanBudget#joinGrace()} plus {@link
     * ScanBudget#safetyMargin()} fits inside {@code timeoutMillis} as a
     * whole -- unlike the pre-fix behaviour, none of that cushion is added
     * <em>on top of</em> the timeout. Clamped to {@link
     * ScanBudget#minListWindow()}.
     */
    private long listWindowMillis(long startNanos, long timeoutMillis) {
        long setupElapsedMillis = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
        long remainingAfterSetupMillis = timeoutMillis - setupElapsedMillis;
        long budgetedMillis = remainingAfterSetupMillis - budget.joinGraceMillis() - budget.safetyMarginMillis();
        return Math.max(budget.minListWindowMillis(), budgetedMillis);
    }

    /**
     * Milliseconds left until {@code deadlineNanos}, clamped to a minimum of
     * 1ms: {@link Thread#join(long)} treats {@code 0} as "wait forever",
     * which would defeat the whole point of bounding the wait, and this is
     * what actually keeps {@link #scan(Duration)} inside its overall
     * deadline even in the {@link ScanBudget#minListWindow()}-floored edge
     * case, since the browsing threads may still be running when this
     * returns.
     */
    private static long remainingMillis(long deadlineNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        return Math.max(1L, remainingNanos / 1_000_000L);
    }

    private static Thread startListing(JmDNS jmdns, String type, long timeoutMillis, AtomicReference<ServiceInfo[]> sink) {
        Thread thread = Thread.ofVirtual().name("mdns-scan-" + type).unstarted(
                () -> sink.set(jmdns.list(type, timeoutMillis)));
        thread.start();
        return thread;
    }

    private static void joinQuietly(Thread thread, long millis) {
        try {
            thread.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static DiscoveredDevice toDiscoveredDevice(ServiceInfo info, boolean rtsp) {
        String host = resolveHost(info);
        String name = resolveName(info, host);
        return rtsp ? mapRtspHit(name, host, info.getPort()) : mapHttpHit(name, host, info.getPort());
    }

    private static String resolveHost(ServiceInfo info) {
        String[] addresses = info.getHostAddresses();
        if (addresses.length > 0 && addresses[0] != null && !addresses[0].isBlank()) {
            return addresses[0];
        }
        String server = info.getServer();
        if (server != null && !server.isBlank()) {
            return server.endsWith(".") ? server.substring(0, server.length() - 1) : server;
        }
        return info.getName();
    }

    private static String resolveName(ServiceInfo info, String fallbackHost) {
        String name = info.getName();
        return (name == null || name.isBlank()) ? fallbackHost : name;
    }

    /**
     * Pure mapping from an already-resolved {@code _rtsp._tcp} hit to a
     * candidate. Package-private and free of any {@link ServiceInfo}
     * dependency so it can be unit tested directly.
     */
    static DiscoveredDevice mapRtspHit(String name, String host, int port) {
        URI uri = buildUri("rtsp", host, port);
        StreamDescriptor stream = new StreamDescriptor("rtsp", uri, Map.of());
        return new DiscoveredDevice(METHOD, name, uri, new CategoryId("ip-camera"), stream, Map.of());
    }

    /**
     * Pure mapping from an already-resolved {@code _http._tcp} hit to a
     * candidate: ESP32-CAM if {@code name}/{@code host} hints at one,
     * otherwise a type-less candidate. Package-private and free of any
     * {@link ServiceInfo} dependency so it can be unit tested directly.
     */
    static DiscoveredDevice mapHttpHit(String name, String host, int port) {
        URI uri = buildUri("http", host, port);
        if (looksLikeEsp32(name, host)) {
            // (SOURCE-ONBOARDING-2 U9) The suggested stream URI itself now carries ESP32-CAM's real
            // MJPEG port/path, rather than the advertised _http._tcp port plus a human-readable note
            // explaining the mismatch -- a candidate an operator (or an auto-add flow) accepts is
            // immediately playable instead of failing to stream until someone reads the note.
            URI streamUri = buildUri("http", host, ESP32_CAM_MJPEG_PORT, ESP32_CAM_MJPEG_PATH);
            StreamDescriptor stream = new StreamDescriptor("mjpeg", streamUri, Map.of());
            return new DiscoveredDevice(METHOD, name, uri, new CategoryId("esp32-cam"), stream, Map.of());
        }
        return new DiscoveredDevice(METHOD, name, uri, null, null, Map.of());
    }

    private static boolean looksLikeEsp32(String name, String host) {
        return ESP32_HINT.matcher(name).find() || ESP32_HINT.matcher(host).find();
    }

    private static URI buildUri(String scheme, String host, int port) {
        return URI.create(scheme + "://" + host + ":" + port + "/");
    }

    private static URI buildUri(String scheme, String host, int port, String path) {
        return URI.create(scheme + "://" + host + ":" + port + path);
    }
}
