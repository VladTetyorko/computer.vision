package com.drones.vision.warehouse.domain.port;

import com.drones.vision.warehouse.domain.model.DiscoveredDevice;
import com.drones.vision.warehouse.domain.model.SourceStatus;

import java.time.Duration;
import java.util.List;

/**
 * Driven port: one discovery mechanism (mDNS, ONVIF WS-Discovery, V4L2
 * enumeration, ...) able to find candidate devices on the network or host.
 *
 * <p>One implementation exists per discovery mechanism. The application
 * layer ({@code DiscoveryService}) fans out to every registered
 * implementation in parallel and aggregates results; adding a new mechanism
 * means adding a new adapter behind this port, with no change to core code
 * (open/closed) — the same extension pattern as {@link VideoSourcePort}.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #method()} returns a stable, lower-case key identifying this
 *       mechanism (e.g. {@code "onvif"}, {@code "mdns"}, {@code "v4l2"}),
 *       used to select which mechanisms run and to report failures.</li>
 *   <li>{@link #scan(Duration)} is blocking and must return within
 *       approximately {@code timeout} — implementations own their own
 *       internal time-boxing (e.g. closing a multicast socket when the
 *       timeout elapses) rather than relying solely on the caller
 *       interrupting the calling thread.</li>
 *   <li>An empty list means "nothing found on this scan", not an error.</li>
 *   <li>Genuine failures (e.g. socket bind failure) may be thrown; the
 *       caller isolates per-adapter failures so one failing or slow
 *       mechanism never fails or stalls the whole scan.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * {@link #scan(Duration)} may be called from any thread (the application
 * layer runs each registered port on its own virtual thread) and must be
 * safe to call concurrently for independent invocations.
 */
public interface DeviceDiscoveryPort {

    /**
     * @return the stable, lower-case key identifying this discovery mechanism
     */
    String method();

    /**
     * Blocking scan for candidate devices. Must return within approximately
     * {@code timeout}.
     *
     * @param timeout how long to scan for
     * @return discovered candidates, possibly empty; never {@code null}
     */
    List<DiscoveredDevice> scan(Duration timeout);

    /**
     * Reachability of this mechanism's remote/external dependency, as observed by the most recent
     * {@link #scan(Duration)} call (docs/plans/active/ASSET-FLOWS-PLAN.md &sect;2, A3) — lets a
     * caller distinguish "this source could not be reached" from "reached fine, nothing found",
     * both of which {@link #scan(Duration)} alone answers identically with an empty list.
     *
     * <p>Defaults to always {@link SourceStatus#OK}: a mechanism with no such dependency to poll
     * (mDNS/ONVIF/V4L2 — a genuine setup failure there is thrown per this interface's own contract,
     * never swallowed into an empty list) has nothing ambiguous to report. Only a mechanism that
     * itself collapses a real failure into an empty {@link #scan(Duration)} result (e.g. the
     * mediamtx push-registry scanner polling a Control API that may be down) needs to override this.
     *
     * <p>Not required to be thread-safe against a concurrent {@link #scan(Duration)} beyond
     * eventual visibility of the last completed attempt — the same "correctness over throughput for
     * an infrequent call pattern" tradeoff this port's implementations already accept elsewhere.
     *
     * @return the last scan's reachability; {@link SourceStatus#NEVER_SCANNED} before any scan has
     *         run — reporting {@link SourceStatus#OK} before ever asking would be a fabricated fact
     *         (CLAUDE.md &sect;9)
     */
    default SourceStatus lastStatus() {
        return SourceStatus.NEVER_SCANNED;
    }
}
