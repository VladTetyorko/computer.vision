package com.drones.vision.api.demo;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The demo package's read-only view of a local folder of video files — one flat listing, never a
 * recursive walk.
 *
 * <p>Defaults to {@code $HOME/Videos} and lists only that directory's own entries: sub-directories
 * are skipped outright, never descended into. Override with {@code vision.demo.videos-dir}.
 *
 * <p>A missing, unreadable or empty directory is not an error — {@link #videos()} simply returns an
 * empty list and {@link DemoFleet} falls back to fully synthetic simulations (the same
 * {@code videoPath == null} path {@code POST /api/simulations {}} already takes), so the demo
 * button still fills the app on a machine with no video files at all.
 *
 * <h2>Threading</h2>
 * Immutable; {@link #videos()} re-reads the directory on every call so a file dropped in while the
 * app runs is picked up by the next seed.
 */
@Component
@ConditionalOnProperty(prefix = "vision.demo", name = "enabled", matchIfMissing = true)
public class DemoVideoLibrary {

    /** Extensions {@link #videos()} accepts, lower-cased and without the dot. */
    static final Set<String> VIDEO_EXTENSIONS =
            Set.of("mp4", "mov", "mkv", "avi", "m4v", "webm", "mpg", "mpeg", "ts", "m2ts", "wmv", "flv");

    /** Sub-directory of the user's home the library reads when no directory is configured. */
    static final String DEFAULT_DIRECTORY_NAME = "Videos";

    private final Path directory;

    /**
     * Production constructor. {@code @Autowired} is required, not decorative: the package-private
     * test seam below is a second candidate constructor, and Spring cannot choose between two on
     * its own — the same disambiguation {@code LiveUpdateRegistry} carries for the same reason.
     */
    @Autowired
    public DemoVideoLibrary(@Value("${vision.demo.videos-dir:}") String configuredDirectory) {
        this.directory = resolve(configuredDirectory);
    }

    /** Test seam: a library rooted at an explicit directory, bypassing property resolution. */
    DemoVideoLibrary(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory must not be null");
    }

    /**
     * The folder this library lists — reported by {@code GET /api/demo} so an operator can see
     * where the demo is looking for footage before pressing the button.
     *
     * @return the configured (or default {@code $HOME/Videos}) directory, whether or not it exists
     */
    public Path directory() {
        return directory;
    }

    /**
     * The playable video files directly inside {@link #directory()}, ordered by file name.
     *
     * @return an immutable snapshot, empty when the directory is missing, unreadable or holds no
     *         recognized video file
     */
    public List<Path> videos() {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(Files::isRegularFile)
                    .filter(Files::isReadable)
                    .filter(DemoVideoLibrary::isVideo)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static boolean isVideo(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 && VIDEO_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static Path resolve(String configuredDirectory) {
        if (configuredDirectory != null && !configuredDirectory.isBlank()) {
            try {
                return Path.of(configuredDirectory.trim());
            } catch (InvalidPathException e) {
                // A malformed override must not stop the context from starting: fall back to the
                // default, exactly as a missing directory falls back to synthetic simulations.
                return defaultDirectory();
            }
        }
        return defaultDirectory();
    }

    private static Path defaultDirectory() {
        return Path.of(System.getProperty("user.home", "."), DEFAULT_DIRECTORY_NAME);
    }
}
