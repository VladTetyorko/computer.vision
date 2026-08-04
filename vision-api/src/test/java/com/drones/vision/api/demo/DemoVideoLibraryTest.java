package com.drones.vision.api.demo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemoVideoLibraryTest {

    @TempDir
    Path videos;

    @Test
    void listsOnlyVideoFilesDirectlyInsideTheFolder() throws IOException {
        Files.createFile(videos.resolve("drone.mp4"));
        Files.createFile(videos.resolve("tank_fight.MP4"));
        Files.createFile(videos.resolve("notes.txt"));
        Files.createDirectory(videos.resolve("Kdenlive"));
        Files.createFile(videos.resolve("Kdenlive").resolve("nested.mp4"));

        List<String> found = new DemoVideoLibrary(videos).videos().stream()
                .map(path -> path.getFileName().toString())
                .toList();

        assertEquals(List.of("drone.mp4", "tank_fight.MP4"), found,
                "only the two videos in the root folder, sorted by name, and never the nested one");
    }

    @Test
    void acceptsUpperCaseExtensions() throws IOException {
        Files.createFile(videos.resolve("IMG_5533.MOV"));

        assertEquals(1, new DemoVideoLibrary(videos).videos().size());
    }

    @Test
    void missingFolderYieldsAnEmptyListRatherThanFailing() {
        DemoVideoLibrary library = new DemoVideoLibrary(videos.resolve("does-not-exist"));

        assertTrue(library.videos().isEmpty());
    }

    @Test
    void blankPropertyFallsBackToTheHomeVideosFolder() {
        DemoVideoLibrary library = new DemoVideoLibrary("");

        assertEquals(Path.of(System.getProperty("user.home"), DemoVideoLibrary.DEFAULT_DIRECTORY_NAME),
                library.directory());
    }

    @Test
    void configuredPropertyWins() {
        DemoVideoLibrary library = new DemoVideoLibrary(videos.toString());

        assertEquals(videos, library.directory());
    }
}
