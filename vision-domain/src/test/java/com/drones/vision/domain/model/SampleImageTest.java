package com.drones.vision.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SampleImageTest {

    @Test
    void rejectsNullData() {
        assertThrows(IllegalArgumentException.class, () -> new SampleImage(null, "image/jpeg"));
    }

    @Test
    void rejectsEmptyData() {
        assertThrows(IllegalArgumentException.class, () -> new SampleImage(new byte[0], "image/jpeg"));
    }

    @Test
    void rejectsNullContentType() {
        assertThrows(IllegalArgumentException.class, () -> new SampleImage(new byte[]{1, 2, 3}, null));
    }

    @Test
    void rejectsBlankContentType() {
        assertThrows(IllegalArgumentException.class, () -> new SampleImage(new byte[]{1, 2, 3}, "   "));
    }

    @Test
    void dataAccessorReturnsAFreshCopyEachCall() {
        byte[] source = {1, 2, 3, 4};
        SampleImage image = new SampleImage(source, "image/jpeg");

        byte[] first = image.data();
        byte[] second = image.data();

        assertArrayEquals(source, first);
        assertNotSame(first, second, "each call to data() must return a distinct array instance");
        assertNotSame(source, first, "data() must never return the caller's original array");
    }

    @Test
    void mutatingTheOriginalArrayAfterConstructionDoesNotAffectStoredContent() {
        byte[] source = {1, 2, 3, 4};
        SampleImage image = new SampleImage(source, "image/jpeg");

        source[0] = 99;

        assertEquals(1, image.data()[0], "constructor must defensively copy the input array");
    }

    @Test
    void mutatingAReturnedCopyDoesNotAffectLaterAccessorCalls() {
        SampleImage image = new SampleImage(new byte[]{1, 2, 3}, "image/jpeg");

        byte[] returned = image.data();
        returned[0] = 99;

        assertEquals(1, image.data()[0], "mutating a returned copy must not affect storage");
    }

    @Test
    void contentTypeRoundTrips() {
        SampleImage image = new SampleImage(new byte[]{1}, "image/jpeg");

        assertEquals("image/jpeg", image.contentType());
    }
}
