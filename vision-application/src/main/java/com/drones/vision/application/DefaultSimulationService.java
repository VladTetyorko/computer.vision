package com.drones.vision.application;

import com.drones.vision.domain.model.Asset;
import com.drones.vision.domain.model.Capability;
import com.drones.vision.domain.model.CategoryId;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.model.PipelineConfig;
import com.drones.vision.domain.model.StreamDescriptor;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.UserId;
import com.drones.vision.domain.port.out.CategoryRepositoryPort;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The one implementation of {@link SimulationService}.
 *
 * <p>Builds one {@link AssetSpec} with two devices — a {@code "file"}-protocol video device
 * playing {@code spec.videoPath()} on a loop, and a {@code "sim"}-protocol telemetry device — and
 * delegates the actual creation/streaming to {@link AssetService}, so every rule {@code
 * AssetService#create}/{@code #startStream} already enforces (category validation, audit, device
 * registration) applies here too instead of being duplicated.
 *
 * <h2>Threading</h2>
 * Holds no mutable state — all shared state is reached through the injected collaborators.
 */
public final class DefaultSimulationService implements SimulationService {

    /** The category every simulated asset is created under; seeded by devsupport at startup. */
    static final CategoryId SIMULATED_CATEGORY = new CategoryId("simulated");

    private final AssetService assetService;
    private final CategoryRepositoryPort categoryRepository;

    public DefaultSimulationService(AssetService assetService, CategoryRepositoryPort categoryRepository) {
        this.assetService = Objects.requireNonNull(assetService, "assetService must not be null");
        this.categoryRepository = Objects.requireNonNull(categoryRepository, "categoryRepository must not be null");
    }

    @Override
    public SimulatedAsset simulate(SimulationSpec spec, Ownership ownership, UserId actor) {
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(ownership, "ownership must not be null");
        Objects.requireNonNull(actor, "actor must not be null");

        Path videoPath = validateVideoPath(spec.videoPath());
        requireSimulatedCategorySeeded();

        String displayName = resolveDisplayName(spec.displayName(), videoPath);
        AssetSpec assetSpec = new AssetSpec(displayName, SIMULATED_CATEGORY,
                Map.of("source", videoPath.toString()),
                List.of(videoDevice(displayName, videoPath), telemetryDevice(displayName, spec)));

        Asset asset = assetService.create(assetSpec, ownership, actor);

        StreamId streamId = null;
        if (spec.autoStart()) {
            streamId = assetService.startStream(asset.id(), null, PipelineConfig.defaults());
        }
        return new SimulatedAsset(asset.id(), streamId);
    }

    /**
     * Confirms {@code rawPath} names an existing, regular, readable file — the three checks a
     * simulation must pass before anything is created, so a bad path never leaves behind a
     * half-registered asset.
     */
    private static Path validateVideoPath(String rawPath) {
        Path path;
        try {
            path = Path.of(rawPath).toAbsolutePath();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Invalid video path: " + rawPath, e);
        }
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Video file does not exist: " + path);
        }
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Video path is not a regular file: " + path);
        }
        if (!Files.isReadable(path)) {
            throw new IllegalArgumentException("Video file is not readable: " + path);
        }
        return path;
    }

    private void requireSimulatedCategorySeeded() {
        categoryRepository.findById(SIMULATED_CATEGORY)
                .orElseThrow(() -> new IllegalStateException("category 'simulated' is not seeded"));
    }

    /** Derives a display name from the file name (extension stripped) when none was supplied. */
    private static String resolveDisplayName(String requested, Path videoPath) {
        if (requested != null && !requested.isBlank()) {
            return requested;
        }
        String fileName = videoPath.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static DeviceRegistration videoDevice(String displayName, Path videoPath) {
        return new DeviceRegistration(displayName + " · video", Set.of(Capability.VIDEO),
                new StreamDescriptor("file", videoPath.toUri(), Map.of("loop", "true")));
    }

    private static DeviceRegistration telemetryDevice(String displayName, SimulationSpec spec) {
        Map<String, String> options = new LinkedHashMap<>();
        if (spec.latitude() != null) {
            options.put("lat", String.valueOf(spec.latitude()));
        }
        if (spec.longitude() != null) {
            options.put("lon", String.valueOf(spec.longitude()));
        }
        URI telemetryUri = URI.create("sim://" + slug(displayName) + "-telemetry");
        return new DeviceRegistration(displayName + " · telemetry", Set.of(Capability.TELEMETRY),
                new StreamDescriptor("sim", telemetryUri, options));
    }

    /** A URI-safe stand-in for the display name; purely descriptive, never looked up. */
    private static String slug(String displayName) {
        String slug = displayName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return slug.isBlank() ? "sim" : slug;
    }
}
