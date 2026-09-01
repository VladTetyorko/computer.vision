package com.drones.vision.adapter.discovery.mediamtx;

import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.warehouse.domain.model.DiscoveredDevice;
import com.drones.vision.warehouse.domain.port.DeviceDiscoveryPort;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@link DeviceDiscoveryPort} implementation polling mediamtx's Control API for device-pushed
 * streams (docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md &sect;3 P3, &sect;11 "Z3 amendment
 * (2026-08-31) -- poll, not hook").
 *
 * <h2>Why polling, not mediamtx's own {@code runOnAvailable} hook</h2>
 * The plan's original Z3 sketch assumed a {@code runOnAvailable} hook curling this app's own REST
 * API the moment a path becomes ready. The official {@code bluenviron/mediamtx} Docker image is
 * scratch-based (no shell, no curl), so that hook cannot run inside the container as deployed. This
 * scanner is the corrected mechanism instead: {@link #scan(Duration)} makes one {@code GET
 * /v3/paths/list} call against mediamtx's Control API each time it runs (in production, once per
 * {@code DiscoveryInboxRunner} sweep -- the same standing background loop every other discovery
 * method already rides) and reports every path that is both {@code ready} and lives under {@link
 * MediamtxScannerSettings#pathPrefix()} as a candidate. No new REST endpoint on this app's own side.
 *
 * <h2>The prefix is the device-push/vision-published boundary</h2>
 * mediamtx's {@code all_others} catch-all (see {@code mediamtx.yml}) accepts a push to ANY path
 * name, including the per-stream paths {@code MediamtxStreamPublisher} itself creates when this app
 * republishes a decoded/detected stream for HLS/WHEP viewing (MEDIA-SOT). Reporting those as "found
 * devices" would mean vision discovering its own output as if it were a new camera. {@link
 * MediamtxScannerSettings#pathPrefix()} (the {@code ingest/} convention documented in {@code
 * mediamtx.yml}) is the one thing distinguishing "a device pushed this" from "vision published
 * this", so a path outside it is silently skipped -- never reported, under any circumstance.
 *
 * <h2>What a reported candidate points at</h2>
 * {@code suggestedStream}/{@code address} is the mediamtx path's own RTSP URL (built from {@link
 * MediamtxScannerSettings#rtspBase()}), not the pushing device's own address -- vision never learns
 * or needs the device's address at all. Once registered, that URL is pulled back by {@code
 * adapter-rtsp} exactly like any ordinary RTSP camera; mediamtx is simply "the camera" as far as the
 * rest of the pipeline is concerned.
 *
 * <h2>Failure handling</h2>
 * A single bounded HTTP call, well inside {@link #scan(Duration)}'s own deadline ({@code
 * HttpRequest.Builder#timeout} covers connection establishment through response body -- the same
 * idiom this module's {@code OnvifDeviceClient} already relies on). mediamtx being unreachable, a
 * non-2xx response, and a response that does not parse as mediamtx's documented {@code paths/list}
 * shape are all treated identically to every other discovery method's own "nothing found this
 * round" case (see {@link DeviceDiscoveryPort}'s own contract): logged at {@code WARNING} (this
 * module has no established warn-once idiom to reuse, so every failed sweep logs -- honest, not
 * deduplicated) and an empty list returned, never thrown. mediamtx recovering on a later sweep is
 * the ordinary, expected case, not a fault this scanner should escalate into {@code
 * DiscoveryService}'s {@code failedMethods}.
 *
 * <p>Plain class, no framework dependency -- instantiated directly by {@code vision-app}'s wiring
 * configuration, mirroring every other scanner in this module.
 */
public final class MediamtxPathScanner implements DeviceDiscoveryPort {

    private static final System.Logger LOG = System.getLogger(MediamtxPathScanner.class.getName());
    private static final String METHOD = "mediamtx";

    private final MediamtxScannerSettings settings;
    private final HttpClient httpClient;
    private final URI pathsListUri;

    public MediamtxPathScanner(MediamtxScannerSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        this.httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        this.pathsListUri = URI.create(withoutTrailingSlash(settings.apiBase().toString()) + "/v3/paths/list");
    }

    @Override
    public String method() {
        return METHOD;
    }

    @Override
    public List<DiscoveredDevice> scan(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isZero() || timeout.isNegative()) {
            return List.of();
        }

        HttpResponse<String> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(pathsListUri).timeout(timeout).GET().build();
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING,
                    () -> "mediamtx Control API unreachable at " + pathsListUri + ": " + e.getMessage());
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }

        if (response.statusCode() != 200) {
            LOG.log(System.Logger.Level.WARNING, () -> "mediamtx Control API " + pathsListUri
                    + " returned unexpected HTTP " + response.statusCode());
            return List.of();
        }

        List<MediamtxPathListParser.PathItem> items;
        try {
            items = MediamtxPathListParser.parseItems(response.body());
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING,
                    () -> "mediamtx Control API " + pathsListUri + " returned an unparsable response: "
                            + e.getMessage());
            return List.of();
        }

        List<DiscoveredDevice> discovered = new ArrayList<>();
        for (MediamtxPathListParser.PathItem item : items) {
            if (isReportableIngestPath(item)) {
                discovered.add(toDiscoveredDevice(item));
            }
        }
        return List.copyOf(discovered);
    }

    private boolean isReportableIngestPath(MediamtxPathListParser.PathItem item) {
        String prefix = settings.pathPrefix();
        return item.ready() && item.name().startsWith(prefix) && item.name().length() > prefix.length();
    }

    private DiscoveredDevice toDiscoveredDevice(MediamtxPathListParser.PathItem item) {
        String name = item.name().substring(settings.pathPrefix().length());
        URI address = URI.create(withoutTrailingSlash(settings.rtspBase().toString()) + "/" + item.name());
        StreamDescriptor stream = new StreamDescriptor("rtsp", address, Map.of());

        Map<String, String> details = new LinkedHashMap<>();
        details.put("path", item.name());
        details.put("sourceType", item.sourceType());
        details.put("readers", String.valueOf(item.readerCount()));

        return new DiscoveredDevice(METHOD, name, address, null, stream, details);
    }

    private static String withoutTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
