package com.drones.vision.adapter.discovery.mdns;

import com.drones.vision.domain.model.CategoryId;
import com.drones.vision.domain.model.DiscoveredDevice;
import com.drones.vision.domain.model.StreamDescriptor;
import com.drones.vision.domain.port.out.DeviceDiscoveryPort;

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
 *       with an {@code mjpeg} {@code http://host:port/} suggested stream and a
 *       {@code details} note that the camera's actual MJPEG path is
 *       conventionally {@code :81/stream}, not the advertised port.</li>
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

    /**
     * Cushion carved <em>out of</em> the concurrent {@code list()} calls'
     * window -- not added on top of the requested timeout -- so the browsing
     * threads have a realistic chance of having finished by the time {@link
     * #joinQuietly} is asked to wait on them, without the wait itself ever
     * needing to run past the scan's overall deadline. See {@link
     * #listWindowMillis(long, long)}.
     */
    private static final long JOIN_GRACE_MILLIS = 150L;

    /**
     * Further cushion carved out of the {@code list()} window, on top of
     * {@link #JOIN_GRACE_MILLIS}, to absorb scheduling/JIT jitter and any
     * measurement error in the {@link #createJmDns()} setup-time accounting.
     */
    private static final long SAFETY_MARGIN_MILLIS = 50L;

    /**
     * Floor for the {@code list()} window so an already very tight timeout
     * (mostly or entirely consumed by {@link #createJmDns()} setup) still
     * gives the browse a minimal chance to run, rather than a zero/negative
     * one. {@link #scan(Duration)} still returns by its deadline regardless
     * -- see {@link #remainingMillis(long)} -- this floor only affects how
     * long the now-background browsing threads keep running afterward.
     */
    private static final long MIN_LIST_WINDOW_MILLIS = 50L;

    private final InetAddress bindAddress;

    /** Binds jmdns to the platform's default interface/address selection. */
    public MdnsScanner() {
        this(null);
    }

    /**
     * Test seam: binds jmdns to a specific address (e.g. loopback), letting
     * tests exercise real registration/discovery without depending on the
     * host's real network interfaces.
     *
     * @param bindAddress address to bind jmdns to, or {@code null} for the platform default
     */
    public MdnsScanner(InetAddress bindAddress) {
        this.bindAddress = bindAddress;
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
     * this window plus {@link #JOIN_GRACE_MILLIS} plus {@link
     * #SAFETY_MARGIN_MILLIS} fits inside {@code timeoutMillis} as a whole --
     * unlike the pre-fix behaviour, none of that cushion is added <em>on top
     * of</em> the timeout. Clamped to {@link #MIN_LIST_WINDOW_MILLIS}.
     */
    private static long listWindowMillis(long startNanos, long timeoutMillis) {
        long setupElapsedMillis = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
        long remainingAfterSetupMillis = timeoutMillis - setupElapsedMillis;
        long budgetedMillis = remainingAfterSetupMillis - JOIN_GRACE_MILLIS - SAFETY_MARGIN_MILLIS;
        return Math.max(MIN_LIST_WINDOW_MILLIS, budgetedMillis);
    }

    /**
     * Milliseconds left until {@code deadlineNanos}, clamped to a minimum of
     * 1ms: {@link Thread#join(long)} treats {@code 0} as "wait forever",
     * which would defeat the whole point of bounding the wait, and this is
     * what actually keeps {@link #scan(Duration)} inside its overall
     * deadline even in the {@link #MIN_LIST_WINDOW_MILLIS}-floored edge
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
            Map<String, String> details = Map.of("note",
                    "Standard ESP32-CAM MJPEG stream path is :81/stream, not reflected in this URI.");
            StreamDescriptor stream = new StreamDescriptor("mjpeg", uri, Map.of());
            return new DiscoveredDevice(METHOD, name, uri, new CategoryId("esp32-cam"), stream, details);
        }
        return new DiscoveredDevice(METHOD, name, uri, null, null, Map.of());
    }

    private static boolean looksLikeEsp32(String name, String host) {
        return ESP32_HINT.matcher(name).find() || ESP32_HINT.matcher(host).find();
    }

    private static URI buildUri(String scheme, String host, int port) {
        return URI.create(scheme + "://" + host + ":" + port + "/");
    }
}
