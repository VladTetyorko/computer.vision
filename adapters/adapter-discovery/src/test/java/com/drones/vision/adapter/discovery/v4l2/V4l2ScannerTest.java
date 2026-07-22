package com.drones.vision.adapter.discovery.v4l2;

import com.drones.vision.domain.model.CategoryId;
import com.drones.vision.domain.model.DiscoveredDevice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V4l2ScannerTest {

    @Test
    void findsNamedAndUnnamedVideoNodesAndIgnoresNonVideoFiles(@TempDir Path tempDir) throws Exception {
        Path devBase = Files.createDirectory(tempDir.resolve("dev"));
        Path sysBase = Files.createDirectory(tempDir.resolve("sys"));

        Files.createFile(devBase.resolve("video0")); // named, via sysBase
        Files.createFile(devBase.resolve("video1")); // unnamed: no sysBase entry
        Files.createFile(devBase.resolve("not-a-video-device")); // ignored
        Files.createFile(devBase.resolve("video")); // no trailing digits: ignored
        Files.createDirectory(devBase.resolve("videoblah")); // non-numeric suffix: ignored

        Path video0SysDir = Files.createDirectories(sysBase.resolve("class/video4linux/video0"));
        Files.writeString(video0SysDir.resolve("name"), "USB2.0 HD UVC WebCam\n");

        V4l2Scanner scanner = new V4l2Scanner(devBase, sysBase);

        List<DiscoveredDevice> found = scanner.scan(Duration.ofSeconds(1)).stream()
                .sorted(Comparator.comparing(DiscoveredDevice::address, Comparator.comparing(URI::toString)))
                .toList();

        assertEquals(2, found.size());

        DiscoveredDevice video0 = found.get(0);
        assertEquals("v4l2", video0.method());
        assertEquals("USB2.0 HD UVC WebCam", video0.name());
        assertEquals(URI.create("file:/dev/video0"), video0.address());
        assertEquals(new CategoryId("usb-camera"), video0.suggestedCategory());
        assertEquals("v4l2", video0.suggestedStream().protocol());
        assertEquals(URI.create("file:/dev/video0"), video0.suggestedStream().uri());

        DiscoveredDevice video1 = found.get(1);
        assertEquals("video1", video1.name()); // no /sys entry -> falls back to the node name
        assertEquals(URI.create("file:/dev/video1"), video1.address());
        assertEquals(new CategoryId("usb-camera"), video1.suggestedCategory());
    }

    @Test
    void producedUriIsAlwaysTheRealDevPathRegardlessOfDevBase(@TempDir Path tempDir) throws Exception {
        Path devBase = Files.createDirectory(tempDir.resolve("fake-dev"));
        Files.createFile(devBase.resolve("video7"));
        Path sysBase = tempDir.resolve("fake-sys"); // never created; readFriendlyName must tolerate this

        V4l2Scanner scanner = new V4l2Scanner(devBase, sysBase);

        List<DiscoveredDevice> found = scanner.scan(Duration.ofSeconds(1));

        assertEquals(1, found.size());
        assertEquals(URI.create("file:/dev/video7"), found.get(0).address());
        assertEquals(URI.create("file:/dev/video7"), found.get(0).suggestedStream().uri());
    }

    @Test
    void absentDevBaseYieldsEmptyListRatherThanThrowing(@TempDir Path tempDir) {
        Path missingDevBase = tempDir.resolve("does-not-exist");
        Path missingSysBase = tempDir.resolve("also-does-not-exist");

        V4l2Scanner scanner = new V4l2Scanner(missingDevBase, missingSysBase);

        List<DiscoveredDevice> found = assertDoesNotThrow(() -> scanner.scan(Duration.ofSeconds(1)));

        assertTrue(found.isEmpty());
    }

    @Test
    void devBaseThatIsNotADirectoryYieldsEmptyListRatherThanThrowing(@TempDir Path tempDir) throws Exception {
        Path notADirectory = Files.createFile(tempDir.resolve("dev-as-a-plain-file"));
        Path sysBase = tempDir.resolve("sys");

        V4l2Scanner scanner = new V4l2Scanner(notADirectory, sysBase);

        List<DiscoveredDevice> found = assertDoesNotThrow(() -> scanner.scan(Duration.ofSeconds(1)));

        assertTrue(found.isEmpty());
    }

    @Test
    void methodKeyIsV4l2() {
        assertEquals("v4l2", new V4l2Scanner().method());
    }
}
