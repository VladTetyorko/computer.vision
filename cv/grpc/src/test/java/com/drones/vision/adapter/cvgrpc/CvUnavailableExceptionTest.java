package com.drones.vision.adapter.cvgrpc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Unit test for {@link CvUnavailableException} — it must be genuinely stackless (see class javadoc). */
class CvUnavailableExceptionTest {

    @Test
    void hasNoStackFrames() {
        CvUnavailableException exception = new CvUnavailableException("cv-service at localhost:1 unreachable");

        assertEquals(0, exception.getStackTrace().length, "this exception must never capture a stack trace");
    }

    @Test
    void messageIsPreservedVerbatim() {
        String message = "cv-service at localhost:1 unreachable (state=TRANSIENT_FAILURE, outage=12s, 3 reconnect attempt(s))";

        CvUnavailableException exception = new CvUnavailableException(message);

        assertEquals(message, exception.getMessage());
    }
}
