package com.drones.vision.adapter.cvgrpc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The {@link WireFormat#AUTO} decision rule (docs/plans/active/CV-RATE-CONTROL-PLAN.md wave R3). Getting
 * this wrong is quiet and expensive in one direction: a remote deployment misread as loopback sends
 * ~17x the bytes over a link that cannot carry them.
 */
class WireFormatTest {

    @Test
    void autoSendsRawOverLoopbackInEveryFormItIsWritten() {
        assertEquals(WireFormat.BGR24, WireFormat.AUTO.resolve("localhost:50051"));
        assertEquals(WireFormat.BGR24, WireFormat.AUTO.resolve("LOCALHOST:50051"));
        assertEquals(WireFormat.BGR24, WireFormat.AUTO.resolve("localhost"));
        assertEquals(WireFormat.BGR24, WireFormat.AUTO.resolve("127.0.0.1:50051"));
        assertEquals(WireFormat.BGR24, WireFormat.AUTO.resolve("127.1.2.3:50051"));
        assertEquals(WireFormat.BGR24, WireFormat.AUTO.resolve("[::1]:50051"));
    }

    @Test
    void autoSendsJpegToAnythingItCannotProveIsLocal() {
        assertEquals(WireFormat.JPEG, WireFormat.AUTO.resolve("192.168.0.106:50051"), "the GB4005 box");
        assertEquals(WireFormat.JPEG, WireFormat.AUTO.resolve("cv.internal:50051"));
        assertEquals(WireFormat.JPEG, WireFormat.AUTO.resolve(null), "unknown is not local");
        assertEquals(WireFormat.JPEG, WireFormat.AUTO.resolve(""));
        assertEquals(WireFormat.JPEG, WireFormat.AUTO.resolve("localhost.example.com:50051"),
                "a hostname merely STARTING with localhost is a different machine");
    }

    @Test
    void anExplicitFormatIgnoresTheEndpointEntirely() {
        assertEquals(WireFormat.JPEG, WireFormat.JPEG.resolve("localhost:50051"));
        assertEquals(WireFormat.BGR24, WireFormat.BGR24.resolve("192.168.0.106:50051"));
    }

    @Test
    void parsesConfiguredValuesAndTreatsAbsenceAsAuto() {
        assertEquals(WireFormat.AUTO, WireFormat.parse(null));
        assertEquals(WireFormat.AUTO, WireFormat.parse("  "));
        assertEquals(WireFormat.BGR24, WireFormat.parse("bgr24"));
        assertEquals(WireFormat.JPEG, WireFormat.parse(" JPEG "));
        assertThrows(IllegalArgumentException.class, () -> WireFormat.parse("png"));
    }
}
