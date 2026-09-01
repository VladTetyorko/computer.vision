package com.drones.vision.adapter.discovery.mediamtx;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal hand-rolled parsing of mediamtx's {@code GET /v3/paths/list} response -- no JSON stack,
 * deliberately, mirroring this module's {@code OnvifWsDiscoveryScanner}/{@code OnvifDeviceClient}
 * KISS precedent (regex/string extraction over a real parser); {@code adapter-discovery}'s pom has
 * no JSON dependency and this class does not add one.
 *
 * <p>Real shape, mediamtx 1.19.3 (verified for the sibling {@code /v3/paths/get/{name}} endpoint by
 * {@code adapter-publish-hls}'s {@code MediamtxControlApi}, whose javadoc cites the transcript this
 * module cannot import across the adapter boundary but whose per-path object shape {@code
 * /v3/paths/list}'s {@code items} array reuses verbatim, per mediamtx's own API):
 * {@code {"itemCount":N,"pageCount":N,"items":[{"name":"...","confName":"...","ready":bool,
 * "readyTime":...,"source":{"type":"...","id":"..."},"tracks":[...],"readers":[...],...}, ...]}}.
 *
 * <p>The {@code items} array, and each item's {@code source}/{@code readers} sub-values, are
 * located by brace/bracket-depth tracking ({@link #extractBracketedValue}/{@link
 * #splitTopLevelObjects}) rather than a naive first-{@code ]}/first-{@code }} regex, specifically so
 * a nested object or array can never desynchronize where one item ends and the next begins.
 * Individual scalar fields within an already-isolated item are then read with the same kind of
 * single-field regex {@code MediamtxControlApi} uses for the sibling endpoint.
 */
final class MediamtxPathListParser {

    private static final Pattern NAME_FIELD = Pattern.compile("\"name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern READY_FIELD = Pattern.compile("\"ready\"\\s*:\\s*(true|false)");
    private static final Pattern SOURCE_TYPE_FIELD =
            Pattern.compile("\"source\"\\s*:\\s*\\{[^}]*?\"type\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private MediamtxPathListParser() {
    }

    /** One {@code items[]} entry, reduced to the fields {@link MediamtxPathScanner} needs. */
    record PathItem(String name, boolean ready, String sourceType, int readerCount) {
    }

    /**
     * @param body raw {@code GET /v3/paths/list} response body
     * @return every path item found, in response order; empty if {@code body} is {@code null},
     *         blank, or has no {@code items} array at all -- {@link MediamtxPathScanner} treats
     *         that identically to an unreachable API: an honest empty result, never a thrown
     *         exception. An individual item missing its own {@code name} field is skipped rather
     *         than failing the whole response.
     */
    static List<PathItem> parseItems(String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        String itemsArray = extractBracketedValue(body, "\"items\"", '[', ']');
        if (itemsArray == null) {
            return List.of();
        }
        List<PathItem> items = new ArrayList<>();
        for (String object : splitTopLevelObjects(itemsArray)) {
            Matcher nameMatcher = NAME_FIELD.matcher(object);
            if (!nameMatcher.find()) {
                continue;
            }
            items.add(new PathItem(nameMatcher.group(1), extractReady(object), extractSourceType(object),
                    extractReaderCount(object)));
        }
        return List.copyOf(items);
    }

    private static boolean extractReady(String object) {
        Matcher matcher = READY_FIELD.matcher(object);
        return matcher.find() && Boolean.parseBoolean(matcher.group(1));
    }

    private static String extractSourceType(String object) {
        Matcher matcher = SOURCE_TYPE_FIELD.matcher(object);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static int extractReaderCount(String object) {
        String readersArray = extractBracketedValue(object, "\"readers\"", '[', ']');
        if (readersArray == null || readersArray.isBlank()) {
            return 0;
        }
        return splitTopLevelObjects(readersArray).size();
    }

    /**
     * Finds {@code key} in {@code json}, then returns the substring strictly between the next
     * {@code open}/{@code close} pair, matched by depth (so a nested object/array inside the value
     * cannot end the search early) rather than the first occurrence of {@code close}.
     *
     * @return the bracketed content (exclusive of the brackets themselves), or {@code null} if
     *         {@code key} is absent or its value is truncated/unbalanced
     */
    private static String extractBracketedValue(String json, String key, char open, char close) {
        int keyIndex = json.indexOf(key);
        if (keyIndex < 0) {
            return null;
        }
        int openIndex = json.indexOf(open, keyIndex + key.length());
        if (openIndex < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = openIndex; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return json.substring(openIndex + 1, i);
                }
            }
        }
        return null;
    }

    /**
     * Splits the interior of a JSON array (already stripped of its surrounding {@code []}) into its
     * top-level {@code {...}} object elements, respecting nested braces/brackets and string
     * literals so a nested object never fools the split into breaking mid-element. Non-object
     * top-level values (mediamtx never emits any inside {@code items}/{@code readers}) are simply
     * not collected.
     */
    private static List<String> splitTopLevelObjects(String arrayContent) {
        List<String> objects = new ArrayList<>();
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        int objectStart = -1;
        for (int i = 0; i < arrayContent.length(); i++) {
            char c = arrayContent.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '{' -> {
                    if (depth == 0) {
                        objectStart = i;
                    }
                    depth++;
                }
                case '}' -> {
                    depth--;
                    if (depth == 0 && objectStart >= 0) {
                        objects.add(arrayContent.substring(objectStart, i + 1));
                        objectStart = -1;
                    }
                }
                default -> {
                    // scalars between top-level objects (commas, whitespace) carry no element to collect
                }
            }
        }
        return objects;
    }
}
