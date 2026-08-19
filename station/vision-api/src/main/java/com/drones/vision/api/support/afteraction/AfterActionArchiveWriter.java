package com.drones.vision.api.support.afteraction;

import com.drones.vision.api.dto.AfterActionManifestResponse;
import com.drones.vision.api.dto.AfterActionPartResponse;
import com.drones.vision.api.dto.FlightPassportResponse;
import com.drones.vision.events.application.UsageRecording;
import com.drones.vision.kernel.Telemetry;
import com.drones.vision.map.domain.model.Mark;
import com.drones.vision.platform.AuditEntry;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes one {@link AfterActionPackage} as the ZIP archive body of {@code GET
 * /api/assets/{assetId}/usages/{usageId}/after-action/archive} (docs/plans/active/
 * AFTER-ACTION-PLAN.md &sect;3.2's frozen wire contract) — eight entries, always all eight, always
 * in this order: {@code manifest.json, README.txt, telemetry.csv, detections.csv, marks.geojson,
 * passport.json, audit.csv, recording.txt}.
 *
 * <p>D8: {@code manifest.json}/{@code passport.json} go through {@link JsonMapper} (Jackson 3);
 * everything else is hand-written — {@code java.util.zip} for the container, RFC 4180 for the two
 * CSVs, RFC 7946 for the GeoJSON. No new Maven dependency.
 *
 * <p><b>ABSENT/FORBIDDEN entries still get a file, never a zero-byte one</b> (&sect;3.2). For the
 * two formats with a well-defined "structurally valid but empty" shape — CSV (header row, no data
 * rows; the format's own "header row always present" rule already makes this honest) and GeoJSON
 * (an empty {@code features} array) — this writer uses that shape rather than breaking the format
 * with prose; {@code passport.json} likewise stays a real (if field-omitted)
 * {@link FlightPassportResponse}, since &sect;3.2 calls it out as "the existing {@code
 * FlightPassportResponse} payload verbatim" regardless of state. {@code recording.txt} is the one
 * entry &sect;3.2 explicitly specifies a literal explanatory line for when absent, so that is what
 * it gets. The full "why" for every ABSENT/FORBIDDEN part is always in {@code manifest.json}'s own
 * {@code note} fields and repeated in {@code README.txt} — no entry's absence is silent.
 */
public final class AfterActionArchiveWriter {

    private final JsonMapper jsonMapper;

    public AfterActionArchiveWriter(JsonMapper jsonMapper) {
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper must not be null");
    }

    /**
     * Streams the archive for {@code pkg} to {@code out}. Never buffers the ZIP itself in memory —
     * each entry is written directly to a {@link ZipOutputStream} wrapping {@code out}.
     *
     * @param pkg      the assembled package
     * @param manifest {@code pkg}'s manifest, pre-mapped by the controller so {@code manifest.json}
     *                 is byte-identical to the JSON manifest endpoint's own body
     * @param out      the destination stream; not closed by the caller until this method returns
     * @throws IOException if writing to {@code out} fails
     */
    public void write(AfterActionPackage pkg, AfterActionManifestResponse manifest, OutputStream out)
            throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            writeEntry(zip, "manifest.json", jsonMapper.writeValueAsBytes(manifest));
            writeEntry(zip, "README.txt", readme(pkg, manifest));
            writeEntry(zip, "telemetry.csv", telemetryCsv(pkg.telemetry()));
            writeEntry(zip, "detections.csv", detectionsCsv(pkg.detections()));
            writeEntry(zip, "marks.geojson", marksGeoJson(pkg.marks()));
            writeEntry(zip, "passport.json", jsonMapper.writeValueAsBytes(FlightPassportResponse.from(pkg.passport())));
            writeEntry(zip, "audit.csv", auditCsv(pkg.auditEntries()));
            writeEntry(zip, "recording.txt", recordingTxt(pkg.recording()));
        }
    }

    private static void writeEntry(ZipOutputStream zip, String name, String content) throws IOException {
        writeEntry(zip, name, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeEntry(ZipOutputStream zip, String name, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    private static String readme(AfterActionPackage pkg, AfterActionManifestResponse manifest) {
        StringBuilder sb = new StringBuilder();
        sb.append("After-action evidence package\n");
        sb.append("==============================\n\n");
        sb.append("Asset: ").append(pkg.assetName()).append(" (").append(manifest.assetId()).append(")\n");
        sb.append("Usage (flight): ").append(manifest.usageId()).append('\n');
        sb.append("Flight window: ").append(manifest.startedAt()).append(" - ")
                .append(pkg.open() ? "still in progress" : manifest.endedAt()).append('\n');
        sb.append("Generated: ").append(manifest.generatedAt()).append('\n');
        sb.append("Scoped to: ").append(manifest.scopedTo()).append("\n\n");
        sb.append("This package is everything the platform recorded about this flight. Every part below\n");
        sb.append("is listed whether or not it had anything to include - a part reporting no data is a\n");
        sb.append("fact about the flight, not an omission.\n\n");
        sb.append("Video is referenced, not embedded: see recording.txt for its URL. This package does not\n");
        sb.append("carry the recording's bytes - the file lives in the video platform, and a stale copy\n");
        sb.append("would be less honest than a live reference.\n\n");
        sb.append("Parts:\n");
        for (AfterActionPartResponse part : manifest.parts()) {
            sb.append("  - ").append(part.part()).append(": ").append(part.state()).append(" (").append(part.count())
                    .append(')');
            if (part.note() != null) {
                sb.append(" - ").append(part.note());
            }
            sb.append('\n');
        }
        sb.append('\n');
        if (manifest.caveats().isEmpty()) {
            sb.append("Caveats: none.\n");
        } else {
            sb.append("Caveats:\n");
            for (String caveat : manifest.caveats()) {
                sb.append("  - ").append(caveat).append('\n');
            }
        }
        return sb.toString();
    }

    private static String telemetryCsv(List<Telemetry> telemetry) {
        StringBuilder sb = new StringBuilder();
        csvRow(sb, "at", "latitude", "longitude", "altitudeMeters", "groundSpeedMps", "headingDegrees",
                "batteryPercent");
        for (Telemetry t : telemetry) {
            Double groundSpeedMps = t.extra().get("groundspeedMps");
            csvRow(sb, t.at(), t.latitude(), t.longitude(), t.altitudeMeters(), groundSpeedMps, t.headingDegrees(),
                    t.batteryPercent());
        }
        return sb.toString();
    }

    private static String detectionsCsv(List<DetectionRow> detections) {
        StringBuilder sb = new StringBuilder();
        csvRow(sb, "capturedAt", "label", "confidence", "x", "y", "width", "height", "trackId");
        for (DetectionRow d : detections) {
            csvRow(sb, d.capturedAt(), d.label(), d.confidence(), d.x(), d.y(), d.width(), d.height(), d.trackId());
        }
        return sb.toString();
    }

    private static String auditCsv(List<AuditEntry> entries) {
        StringBuilder sb = new StringBuilder();
        csvRow(sb, "at", "actor", "action", "entity", "entityId", "detail");
        for (AuditEntry entry : entries) {
            csvRow(sb, entry.occurredAt(), entry.actor().value(), entry.action().name(), entry.targetType().name(),
                    entry.targetId(), entry.summary());
        }
        return sb.toString();
    }

    private static String marksGeoJson(List<Mark> marks) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[");
        boolean first = true;
        for (Mark mark : marks) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            double lat = mark.position().latitude();
            double lon = mark.position().longitude();
            sb.append("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
                    .append(lon).append(',').append(lat).append("]},\"properties\":{")
                    .append("\"id\":").append(jsonString(mark.id().value().toString())).append(',')
                    .append("\"kind\":").append(jsonString(mark.kind().name())).append(',')
                    .append("\"label\":").append(jsonString(mark.label())).append(',')
                    .append("\"status\":").append(jsonString(mark.status().name())).append(',')
                    .append("\"source\":").append(jsonString(mark.source().name())).append(',')
                    .append("\"createdAt\":").append(jsonString(mark.createdAt().toString())).append(',')
                    .append("\"verification\":").append(jsonString(mark.verification().state().name()))
                    .append("}}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String recordingTxt(Optional<UsageRecording> recording) {
        if (recording.isEmpty()) {
            return "no recording is configured for this stream\n";
        }
        UsageRecording r = recording.get();
        return "url: " + r.url() + "\nstart: " + r.start() + "\ndurationSeconds: " + r.durationSeconds() + "\n";
    }

    private static void csvRow(StringBuilder sb, Object... values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(csvField(values[i]));
        }
        sb.append("\r\n");
    }

    private static String csvField(Object value) {
        if (value == null) {
            return "";
        }
        String s = value instanceof Instant ? value.toString() : String.valueOf(value);
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static String jsonString(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
