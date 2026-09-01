package com.drones.vision.adapter.discovery.mediamtx;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-parsing unit tests for {@link MediamtxPathListParser} against mediamtx's real {@code
 * GET /v3/paths/list} response shape (docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md &sect;11
 * Z3 amendment; per-item field shape verified for the sibling {@code /v3/paths/get/{name}} endpoint
 * by {@code adapter-publish-hls}'s own {@code MediamtxControlApiTest}). No socket, no HTTP server --
 * mirrors {@code OnvifDeviceClientTest}'s "pure parsing" section.
 */
class MediamtxPathListParserTest {

    private static final String FOUR_PATHS_RESPONSE = """
            {"itemCount":4,"pageCount":1,"items":[
              {"name":"ingest/rover-abc123","confName":"all_others","ready":true,"readyTime":"2026-08-31T00:00:00Z",
               "available":true,"availableTime":"2026-08-31T00:00:00Z","online":true,
               "onlineTime":"2026-08-31T00:00:00Z","source":{"type":"rtspSession","id":"xyz"},
               "tracks":["H264"],"tracks2":[],"readers":[],
               "inboundBytes":100,"outboundBytes":0,"inboundFramesInError":0,"bytesReceived":100,"bytesSent":0},
              {"name":"ingest/cam-def456","confName":"all_others","ready":true,"readyTime":"2026-08-31T00:00:01Z",
               "available":true,"availableTime":"2026-08-31T00:00:01Z","online":true,
               "onlineTime":"2026-08-31T00:00:01Z","source":{"type":"rtspSession","id":"abc"},
               "tracks":["H264"],"tracks2":[],
               "readers":[{"type":"rtspSession","id":"r1"},{"type":"hlsMuxer","id":"r2"}],
               "inboundBytes":200,"outboundBytes":0,"inboundFramesInError":0,"bytesReceived":200,"bytesSent":0},
              {"name":"stream/vision-abc","confName":"all_others","ready":true,"readyTime":"2026-08-31T00:00:02Z",
               "available":true,"availableTime":"2026-08-31T00:00:02Z","online":true,
               "onlineTime":"2026-08-31T00:00:02Z","source":{"type":"rtspSession","id":"vvv"},
               "tracks":["H264"],"tracks2":[],"readers":[],
               "inboundBytes":300,"outboundBytes":0,"inboundFramesInError":0,"bytesReceived":300,"bytesSent":0},
              {"name":"ingest/notready-xyz","confName":"all_others","ready":false,"readyTime":null,
               "available":false,"availableTime":null,"online":true,"onlineTime":"2026-08-31T00:00:03Z",
               "source":{"type":"rtspSource","id":""},"tracks":[],"tracks2":[],"readers":[],
               "inboundBytes":0,"outboundBytes":0,"inboundFramesInError":0,"bytesReceived":0,"bytesSent":0}
            ]}""";

    @Test
    void parsesEveryItemRegardlessOfReadinessOrPrefix() {
        List<MediamtxPathListParser.PathItem> items = MediamtxPathListParser.parseItems(FOUR_PATHS_RESPONSE);

        assertEquals(4, items.size());
    }

    @Test
    void extractsNameReadyAndSourceType() {
        List<MediamtxPathListParser.PathItem> items = MediamtxPathListParser.parseItems(FOUR_PATHS_RESPONSE);

        MediamtxPathListParser.PathItem first = items.get(0);
        assertEquals("ingest/rover-abc123", first.name());
        assertTrue(first.ready());
        assertEquals("rtspSession", first.sourceType());
        assertEquals(0, first.readerCount());
    }

    @Test
    void countsTopLevelReaderObjectsNotBytesOrCharacters() {
        List<MediamtxPathListParser.PathItem> items = MediamtxPathListParser.parseItems(FOUR_PATHS_RESPONSE);

        MediamtxPathListParser.PathItem withTwoReaders = items.get(1);
        assertEquals(2, withTwoReaders.readerCount());
    }

    @Test
    void notReadyItemIsStillParsedWithReadyFalse() {
        List<MediamtxPathListParser.PathItem> items = MediamtxPathListParser.parseItems(FOUR_PATHS_RESPONSE);

        MediamtxPathListParser.PathItem notReady = items.get(3);
        assertEquals("ingest/notready-xyz", notReady.name());
        assertFalse(notReady.ready());
    }

    @Test
    void emptyItemsArrayYieldsNoItems() {
        assertTrue(MediamtxPathListParser.parseItems("{\"itemCount\":0,\"pageCount\":1,\"items\":[]}").isEmpty());
    }

    @Test
    void nullOrBlankBodyYieldsNoItems() {
        assertTrue(MediamtxPathListParser.parseItems(null).isEmpty());
        assertTrue(MediamtxPathListParser.parseItems("").isEmpty());
        assertTrue(MediamtxPathListParser.parseItems("   ").isEmpty());
    }

    @Test
    void malformedJsonWithNoItemsKeyYieldsNoItemsRatherThanThrowing() {
        assertTrue(MediamtxPathListParser.parseItems("not json at all, just noise").isEmpty());
        assertTrue(MediamtxPathListParser.parseItems("{\"status\":\"error\",\"error\":\"boom\"}").isEmpty());
        assertTrue(MediamtxPathListParser.parseItems("{\"items\":[").isEmpty());
    }

    @Test
    void itemMissingItsNameFieldIsSkippedNotFailed() {
        String body = "{\"items\":[{\"ready\":true},{\"name\":\"ingest/ok\",\"ready\":true}]}";

        List<MediamtxPathListParser.PathItem> items = MediamtxPathListParser.parseItems(body);

        assertEquals(1, items.size());
        assertEquals("ingest/ok", items.get(0).name());
    }

    @Test
    void aNestedBraceInsideSourceDoesNotDesynchronizeTheNextItem() {
        // "source" here carries an extra nested object before "type" to prove item-boundary
        // detection tracks real brace depth rather than the first "}" it sees.
        String body = "{\"items\":["
                + "{\"name\":\"ingest/a\",\"ready\":true,\"source\":{\"extra\":{\"nested\":true},\"type\":\"rtspSession\"}},"
                + "{\"name\":\"ingest/b\",\"ready\":false}"
                + "]}";

        List<MediamtxPathListParser.PathItem> items = MediamtxPathListParser.parseItems(body);

        assertEquals(2, items.size());
        assertEquals("ingest/a", items.get(0).name());
        assertEquals("ingest/b", items.get(1).name());
        assertFalse(items.get(1).ready());
    }
}
