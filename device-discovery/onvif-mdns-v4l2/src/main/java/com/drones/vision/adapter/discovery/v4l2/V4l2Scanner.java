package com.drones.vision.adapter.discovery.v4l2;

import com.drones.vision.kernel.CategoryId;
import com.drones.vision.warehouse.domain.model.DiscoveredDevice;
import com.drones.vision.warehouse.domain.model.SourceStatus;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.warehouse.domain.port.DeviceDiscoveryPort;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * {@link DeviceDiscoveryPort} implementation for local V4L2 device
 * enumeration: lists {@code /dev/videoN} nodes rather than probing a network.
 *
 * <p>For each {@code videoN} entry found directly under {@code devBase}
 * (default {@code /dev}), a best-effort friendly name is read from {@code
 * <sysBase>/class/video4linux/videoN/name} (default {@code sysBase} is
 * {@code /sys}) when that file exists and is readable; otherwise the node
 * name itself ({@code videoN}) is used. A missing {@code devBase} (including
 * simply not being Linux) yields an empty list rather than an exception --
 * there is nothing to enumerate, and {@link #lastStatus()} stays {@code OK}
 * since this is a normal topology fact, not a failure. A {@code devBase} that
 * exists but genuinely cannot be listed (permission denied, not a directory,
 * ...) also yields an empty list, but {@link #lastStatus()} reports {@code
 * UNREACHABLE} instead (docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md U8).
 *
 * <p>Every candidate is a {@code "usb-camera"} {@link CategoryId} with a {@code "v4l2"}
 * {@link DiscoveredDevice#suggestedStream()} whose URI is always the real
 * {@code file:/dev/videoN} path -- regardless of the {@code devBase} used to
 * enumerate it (that constructor parameter only redirects <em>where this
 * scanner looks</em>, e.g. in tests; the produced candidate always points at
 * the real device node a production host would have). Registering this
 * candidate today is possible but the resulting device fails to start with
 * a 400: there is no video-source adapter that supports the {@code "v4l2"}
 * protocol yet. That lands with {@code adapter-usb} in Phase 4; returning
 * the candidate now (rather than withholding it) is deliberate -- discovery
 * should show what physically exists on the host even before every
 * protocol is wired up.
 *
 * <p>Plain class, no framework dependency -- instantiated directly by {@code
 * vision-app}'s wiring configuration.
 */
public final class V4l2Scanner implements DeviceDiscoveryPort {

    private static final System.Logger LOG = System.getLogger(V4l2Scanner.class.getName());

    private static final String METHOD = "v4l2";

    private static final Path DEFAULT_DEV_BASE = Path.of("/dev");
    private static final Path DEFAULT_SYS_BASE = Path.of("/sys");

    private static final Pattern VIDEO_NODE_PATTERN = Pattern.compile("video(\\d+)");

    private final Path devBase;
    private final Path sysBase;

    /**
     * (docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md U8) Defaults to {@link SourceStatus#OK} per
     * {@link DeviceDiscoveryPort#lastStatus()}'s own contract. Unlike {@code MdnsScanner}/{@code
     * OnvifWsDiscoveryScanner} -- which that contract explicitly exempts, since a genuine setup
     * failure there is thrown, never swallowed -- {@link #scan(Duration)} below genuinely does
     * collapse one real failure into an empty list: {@code devBase} existing but not actually being
     * listable (permission denied, not a directory, ...). A missing {@code devBase} entirely (no
     * V4L2 subsystem at all -- any non-Linux host) is a normal topology fact, not a failure, and is
     * deliberately excluded from flipping this to {@link SourceStatus#UNREACHABLE} -- see {@link
     * #scan(Duration)}'s own comment.
     */
    private volatile SourceStatus lastStatus = SourceStatus.OK;

    /** Enumerates the real {@code /dev} and {@code /sys} trees. */
    public V4l2Scanner() {
        this(DEFAULT_DEV_BASE, DEFAULT_SYS_BASE);
    }

    /**
     * Test seam: enumerates fake {@code devBase}/{@code sysBase} trees
     * (e.g. a JUnit {@code @TempDir}) instead of the real filesystem.
     *
     * @param devBase root under which {@code videoN} nodes are looked for directly
     * @param sysBase root under which {@code class/video4linux/videoN/name} is looked for
     */
    public V4l2Scanner(Path devBase, Path sysBase) {
        this.devBase = Objects.requireNonNull(devBase, "devBase must not be null");
        this.sysBase = Objects.requireNonNull(sysBase, "sysBase must not be null");
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
        // Enumeration is local filesystem I/O and normally finishes almost
        // instantly, but each entry is still checked against a deadline so a
        // pathological filesystem cannot make this scanner stall the
        // parallel scan past its bound.
        long deadlineNanos = System.nanoTime() + (timeout.isNegative() ? 0L : timeout.toNanos());

        List<Path> entries;
        try (Stream<Path> listing = Files.list(devBase)) {
            entries = listing.sorted().toList();
            lastStatus = SourceStatus.OK;
        } catch (NoSuchFileException e) {
            // devBase genuinely does not exist -- no V4L2 subsystem at all (any non-Linux host,
            // or a container with no /dev passthrough). This is a normal topology fact, not a
            // failure (see class javadoc), so lastStatus is left exactly as it already was
            // (default OK) rather than flagging every such host as "unreachable".
            LOG.log(System.Logger.Level.DEBUG, () -> "V4L2 enumeration of " + devBase + " yielded nothing: " + e);
            return List.of();
        } catch (IOException | RuntimeException e) {
            // devBase exists but genuinely could not be listed -- permission denied, not a
            // directory, or another real I/O fault: this is worth surfacing (SOURCE-ONBOARDING-2
            // U8), unlike the "not present at all" case above.
            lastStatus = SourceStatus.UNREACHABLE;
            LOG.log(System.Logger.Level.DEBUG, () -> "V4L2 enumeration of " + devBase + " yielded nothing: " + e);
            return List.of();
        }

        List<DiscoveredDevice> devices = new ArrayList<>();
        for (Path entry : entries) {
            if (System.nanoTime() >= deadlineNanos) {
                break;
            }
            String fileName = entry.getFileName().toString();
            if (!VIDEO_NODE_PATTERN.matcher(fileName).matches()) {
                continue;
            }
            devices.add(toDiscoveredDevice(fileName));
        }
        return List.copyOf(devices);
    }

    private DiscoveredDevice toDiscoveredDevice(String videoNodeName) {
        URI uri = URI.create("file:/dev/" + videoNodeName);
        String name = readFriendlyName(videoNodeName).orElse(videoNodeName);
        StreamDescriptor stream = new StreamDescriptor("v4l2", uri, Map.of());
        Map<String, String> details = Map.of("device", "/dev/" + videoNodeName);
        return new DiscoveredDevice(METHOD, name, uri, new CategoryId("usb-camera"), stream, details);
    }

    private Optional<String> readFriendlyName(String videoNodeName) {
        Path namePath = sysBase.resolve("class").resolve("video4linux").resolve(videoNodeName).resolve("name");
        if (!Files.isReadable(namePath)) {
            return Optional.empty();
        }
        try {
            String content = Files.readString(namePath).trim();
            return content.isBlank() ? Optional.empty() : Optional.of(content);
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
