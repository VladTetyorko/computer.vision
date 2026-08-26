package com.drones.vision.adapter.cvgrpc;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CvTargetTest {

    @Test
    void parsesPlainHostPort() {
        CvTarget target = CvTarget.parse("cv-inference-1:50051");
        assertEquals("cv-inference-1", target.host());
        assertEquals(50051, target.port());
    }

    @Test
    void parsesAndStripsAnOptionalSchemePrefix() {
        CvTarget target = CvTarget.parse("dns://cv-inference-1:50051");
        assertEquals("cv-inference-1", target.host());
        assertEquals(50051, target.port());
    }

    @Test
    void toStringIsHostColonPort() {
        assertEquals("localhost:50051", new CvTarget("localhost", 50051).toString());
    }

    @Test
    void parseAllPreservesOrder() {
        List<CvTarget> targets = CvTarget.parseAll(List.of("a:1", "b:2", "c:3"));
        assertEquals(List.of(new CvTarget("a", 1), new CvTarget("b", 2), new CvTarget("c", 3)), targets);
    }

    @Test
    void parseRejectsAValueWithNoColon() {
        assertThrows(IllegalArgumentException.class, () -> CvTarget.parse("no-port-here"));
    }

    @Test
    void parseRejectsATrailingColonWithNoPort() {
        assertThrows(IllegalArgumentException.class, () -> CvTarget.parse("host:"));
    }

    @Test
    void parseRejectsANonNumericPort() {
        assertThrows(IllegalArgumentException.class, () -> CvTarget.parse("host:notaport"));
    }

    @Test
    void constructorRejectsABlankHost() {
        assertThrows(IllegalArgumentException.class, () -> new CvTarget("  ", 50051));
    }

    @Test
    void constructorRejectsANonPositivePort() {
        assertThrows(IllegalArgumentException.class, () -> new CvTarget("host", 0));
    }

    @Test
    void constructorRejectsAPortAboveTheValidRange() {
        assertThrows(IllegalArgumentException.class, () -> new CvTarget("host", 65536));
    }
}
