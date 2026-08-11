package com.drones.vision.application.discovery;

import com.drones.vision.domain.model.CategoryId;
import com.drones.vision.domain.model.DiscoveredDevice;
import com.drones.vision.domain.model.StreamDescriptor;
import com.drones.vision.domain.port.out.DeviceDiscoveryPort;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Fans a device scan out to every requested
 * {@link DeviceDiscoveryPort} in parallel and aggregates the results.
 *
 * <p>Each selected port's {@link DeviceDiscoveryPort#scan(Duration)} runs on
 * its own virtual thread (cheap enough that one per port per scan is not a
 * concern, consistent with the platform's virtual-thread concurrency
 * choice). The overall wait is <b>hard-bounded</b> by {@code
 * request.timeout()} plus a small fixed {@link #GRACE_PERIOD} — a
 * well-behaved adapter that respects the timeout contract comfortably
 * finishes within the grace window, but a hanging or misbehaving adapter can
 * never stall the scan past that bound: this method simply stops waiting on
 * it and reports its method key in {@link DiscoveryScanResult#failedMethods()}. The
 * bound applies to the scan as a whole (not per-adapter): the deadline is
 * computed once and each pending result is awaited for only its
 * <i>remaining</i> share of that deadline, so N slow/hanging adapters cannot
 * multiply the total wait.
 *
 * <p>Per-adapter failures (a thrown exception) and timeouts are isolated the
 * same way — {@link #scan(DiscoveryScanSpec)} itself never throws for adapter
 * trouble, only for a caller error (an unknown requested method).
 *
 * <p>Results are first deduplicated on the exact {@code (method, address)}
 * pair within one scan (a single mechanism reporting the identical candidate
 * twice), then a second pass merges <b>cross-method</b> duplicates — the same
 * physical device found by two or more different mechanisms — into one
 * {@link DiscoveredDevice}. Candidates are grouped by network identity: the
 * {@link URI#getHost()} of {@link DiscoveredDevice#address()}, case-insensitive,
 * when present; candidates whose address has no host (e.g. {@code v4l2}'s
 * {@code file:} URIs) are grouped by exact address instead, since a bare file
 * path carries no network identity to fuzzy-match on. A group is only merged
 * when it contains candidates from <b>two or more distinct methods</b> — a
 * group where every member came from the same single method (even if they
 * happen to share a host) is left untouched, preserving each candidate as its
 * own entry exactly as the exact-match dedup pass would have. See {@link
 * #mergeGroup(List)} for the field-merge rules.
 *
 * <h2>Threading</h2>
 * Holds no mutable state beyond the immutable port registry built at
 * construction time, so {@link #scan(DiscoveryScanSpec)} is safe to call
 * concurrently for independent scans.
 */
public final class DefaultDiscoveryService implements DiscoveryService {

    /**
     * Default {@link #gracePeriod}: a small fixed allowance added to {@link
     * DiscoveryScanSpec#timeout()} when bounding the overall wait, so a well-behaved adapter that
     * returns right around the requested timeout is not falsely reported as failed. Exposed here
     * purely so same-package tests can assert against it by name (docs/plans/active/LAYERING-REFACTOR-PLAN.md
     * &sect;1.3 config extraction).
     */
    static final Duration GRACE_PERIOD = Duration.ofMillis(200);

    private final Map<String, DeviceDiscoveryPort> portsByMethod;
    private final Duration gracePeriod;

    public DefaultDiscoveryService(List<DeviceDiscoveryPort> ports) {
        this(ports, GRACE_PERIOD);
    }

    /**
     * @param gracePeriod small fixed allowance added to {@link DiscoveryScanSpec#timeout()} when
     *                    bounding the overall wait (docs/plans/active/LAYERING-REFACTOR-PLAN.md &sect;1.3 config
     *                    extraction, {@code vision.application.discovery-grace}); production always
     *                    uses the 1-argument constructor's {@link #GRACE_PERIOD} default
     */
    public DefaultDiscoveryService(List<DeviceDiscoveryPort> ports, Duration gracePeriod) {
        Objects.requireNonNull(ports, "ports must not be null");
        this.gracePeriod = Objects.requireNonNull(gracePeriod, "gracePeriod must not be null");
        Map<String, DeviceDiscoveryPort> byMethod = new LinkedHashMap<>();
        for (DeviceDiscoveryPort port : ports) {
            byMethod.put(port.method(), port);
        }
        this.portsByMethod = Map.copyOf(byMethod);
    }

    @Override
    public DiscoveryScanResult scan(DiscoveryScanSpec request) {
        Objects.requireNonNull(request, "request must not be null");

        Set<String> requestedMethods = request.methods().isEmpty() ? portsByMethod.keySet() : request.methods();
        for (String method : requestedMethods) {
            if (!portsByMethod.containsKey(method)) {
                throw new IllegalArgumentException("Unknown discovery method: " + method);
            }
        }

        Map<String, CompletableFuture<List<DiscoveredDevice>>> pending = new LinkedHashMap<>();
        for (String method : requestedMethods) {
            pending.put(method, scanAsync(portsByMethod.get(method), request.timeout()));
        }

        long deadlineNanos = System.nanoTime() + request.timeout().plus(gracePeriod).toNanos();

        List<DiscoveredDevice> rawDevices = new ArrayList<>();
        Set<String> failedMethods = new LinkedHashSet<>();
        Set<DedupKey> seen = new HashSet<>();

        for (Map.Entry<String, CompletableFuture<List<DiscoveredDevice>>> entry : pending.entrySet()) {
            long remainingNanos = Math.max(0L, deadlineNanos - System.nanoTime());
            try {
                List<DiscoveredDevice> found = entry.getValue().get(remainingNanos, TimeUnit.NANOSECONDS);
                for (DiscoveredDevice device : found) {
                    if (seen.add(new DedupKey(device.method(), device.address()))) {
                        rawDevices.add(device);
                    }
                }
            } catch (TimeoutException | ExecutionException e) {
                failedMethods.add(entry.getKey());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failedMethods.add(entry.getKey());
            }
        }

        return new DiscoveryScanResult(mergeCrossMethodDuplicates(rawDevices), failedMethods);
    }

    /**
     * Groups exact-deduplicated candidates by network identity and merges
     * every group spanning two or more distinct methods into one {@link
     * DiscoveredDevice} (see {@link #mergeGroup(List)}). Groups with a single
     * distinct method — including groups of size one — pass through
     * unchanged, in their original relative order.
     */
    private static List<DiscoveredDevice> mergeCrossMethodDuplicates(List<DiscoveredDevice> rawDevices) {
        Map<String, List<DiscoveredDevice>> groups = new LinkedHashMap<>();
        for (DiscoveredDevice device : rawDevices) {
            groups.computeIfAbsent(networkIdentityKey(device.address()), k -> new ArrayList<>()).add(device);
        }

        List<DiscoveredDevice> merged = new ArrayList<>();
        for (List<DiscoveredDevice> group : groups.values()) {
            long distinctMethods = group.stream().map(DiscoveredDevice::method).distinct().count();
            if (distinctMethods < 2) {
                merged.addAll(group);
            } else {
                merged.add(mergeGroup(group));
            }
        }
        return merged;
    }

    /**
     * @return the grouping key for cross-method identity: the candidate's
     *         address host, lower-cased, when present; otherwise the exact
     *         address (a host-less URI, e.g. a {@code v4l2 file:} URI, has no
     *         network identity to fuzzy-match on, so only an identical
     *         address groups together).
     */
    private static String networkIdentityKey(URI address) {
        String host = address.getHost();
        return host != null ? "host:" + host.toLowerCase(Locale.ROOT) : "addr:" + address;
    }

    /**
     * Orders group members by discovery-method trustworthiness for
     * tie-breaking: {@code onvif} (user-assigned scope names) first, then
     * {@code mdns}, then any other method alphabetically — deliberately
     * independent of scan completion order, which is not guaranteed
     * deterministic (ports run concurrently on separate virtual threads).
     */
    private static final Comparator<DiscoveredDevice> MERGE_PRIORITY = Comparator
            .comparingInt((DiscoveredDevice d) -> methodPriority(d.method()))
            .thenComparing(DiscoveredDevice::method)
            .thenComparing(d -> d.address().toString());

    private static int methodPriority(String method) {
        return switch (method) {
            case "onvif" -> 0;
            case "mdns" -> 1;
            default -> 2;
        };
    }

    /**
     * Merges a group of same-network-identity candidates from two or more
     * distinct methods into a single {@link DiscoveredDevice}:
     * <ul>
     *   <li>{@code method} — every distinct method in the group, sorted
     *       alphabetically and joined with {@code "+"} (e.g. {@code
     *       "mdns+onvif"}), independent of discovery order.</li>
     *   <li>{@code name} — the first, in {@link #MERGE_PRIORITY} order, whose
     *       name isn't just the address/host literal (see {@link
     *       #looksLikeAddressLiteral}); falls back to the highest-priority
     *       candidate's name if every candidate's name is a literal.</li>
     *   <li>{@code suggestedStream}/{@code address} — the first candidate (in
     *       priority order) with an {@code rtsp}-protocol stream, else the
     *       first with any non-null stream; {@code address} is that same
     *       candidate's address, or the highest-priority candidate's address
     *       if none supplied a stream.</li>
     *   <li>{@code suggestedCategory} — the first non-null, in priority
     *       order.</li>
     *   <li>{@code details} — the union of every candidate's details; a key
     *       contributed by more than one distinct method is namespaced as
     *       {@code "<method>.<key>"} for every contributing candidate (not
     *       just the losing one) to avoid silently dropping either value.</li>
     * </ul>
     */
    private static DiscoveredDevice mergeGroup(List<DiscoveredDevice> group) {
        List<DiscoveredDevice> ordered = group.stream().sorted(MERGE_PRIORITY).toList();

        String mergedMethod = ordered.stream()
                .map(DiscoveredDevice::method)
                .distinct()
                .sorted()
                .collect(Collectors.joining("+"));

        String mergedName = ordered.stream()
                .filter(d -> !looksLikeAddressLiteral(d))
                .findFirst()
                .map(DiscoveredDevice::name)
                .orElse(ordered.get(0).name());

        Optional<DiscoveredDevice> rtspSource = ordered.stream()
                .filter(d -> d.suggestedStream() != null && "rtsp".equals(d.suggestedStream().protocol()))
                .findFirst();
        Optional<DiscoveredDevice> streamSource = rtspSource.isPresent()
                ? rtspSource
                : ordered.stream().filter(d -> d.suggestedStream() != null).findFirst();

        StreamDescriptor mergedStream = streamSource.map(DiscoveredDevice::suggestedStream).orElse(null);
        URI mergedAddress = streamSource.map(DiscoveredDevice::address).orElse(ordered.get(0).address());

        CategoryId mergedCategory = ordered.stream()
                .map(DiscoveredDevice::suggestedCategory)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);

        return new DiscoveredDevice(mergedMethod, mergedName, mergedAddress, mergedCategory, mergedStream,
                mergeDetails(ordered));
    }

    /**
     * @return {@code true} if {@code d.name()} carries no information beyond
     *         its own address — either equal (case-insensitively) to the
     *         address's host, or to the address's full string form (the
     *         fallback for host-less addresses).
     */
    private static boolean looksLikeAddressLiteral(DiscoveredDevice d) {
        String host = d.address().getHost();
        if (host != null && d.name().equalsIgnoreCase(host)) {
            return true;
        }
        return d.name().equalsIgnoreCase(d.address().toString());
    }

    /**
     * Unions every candidate's {@code details} map; a key contributed by more
     * than one distinct method is renamed {@code "<method>.<key>"} for every
     * candidate that contributed it (symmetric renaming, not just on the
     * losing side) so no value is silently overwritten.
     */
    private static Map<String, String> mergeDetails(List<DiscoveredDevice> ordered) {
        Map<String, Set<String>> contributingMethods = new LinkedHashMap<>();
        for (DiscoveredDevice d : ordered) {
            for (String key : d.details().keySet()) {
                contributingMethods.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(d.method());
            }
        }

        Map<String, String> merged = new LinkedHashMap<>();
        for (DiscoveredDevice d : ordered) {
            for (Map.Entry<String, String> entry : d.details().entrySet()) {
                boolean collides = contributingMethods.get(entry.getKey()).size() > 1;
                String outKey = collides ? d.method() + "." + entry.getKey() : entry.getKey();
                merged.put(outKey, entry.getValue());
            }
        }
        return merged;
    }

    /**
     * Starts {@code port.scan(timeout)} on a fresh virtual thread and returns
     * a future that completes with its result, or exceptionally if it
     * throws. The thread is not tracked or interrupted if the caller stops
     * waiting on the returned future (see class javadoc) — a hanging adapter
     * leaks a blocked virtual thread rather than the scan itself hanging;
     * virtual threads are cheap and daemon by nature so this never blocks
     * JVM/application shutdown.
     */
    private static CompletableFuture<List<DiscoveredDevice>> scanAsync(DeviceDiscoveryPort port, Duration timeout) {
        CompletableFuture<List<DiscoveredDevice>> future = new CompletableFuture<>();
        Thread.ofVirtual().name("discovery-" + port.method()).start(() -> {
            try {
                future.complete(port.scan(timeout));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    private record DedupKey(String method, URI address) {
    }
}
