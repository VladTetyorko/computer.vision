package com.drones.vision.warehouse.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AssetImageTest {

    @Test
    void rejectsNullData() {
        assertThrows(IllegalArgumentException.class, () -> new AssetImage(null, "image/jpeg"));
    }

    @Test
    void rejectsEmptyData() {
        assertThrows(IllegalArgumentException.class, () -> new AssetImage(new byte[0], "image/jpeg"));
    }

    @Test
    void rejectsNullContentType() {
        assertThrows(IllegalArgumentException.class, () -> new AssetImage(new byte[]{1, 2, 3}, null));
    }

    @Test
    void rejectsBlankContentType() {
        assertThrows(IllegalArgumentException.class, () -> new AssetImage(new byte[]{1, 2, 3}, "   "));
    }

    @Test
    void dataAccessorReturnsAFreshCopyEachCall() {
        byte[] source = {1, 2, 3, 4};
        AssetImage image = new AssetImage(source, "image/png");

        byte[] first = image.data();
        byte[] second = image.data();

        assertArrayEquals(source, first);
        assertNotSame(first, second, "each call to data() must return a distinct array instance");
        assertNotSame(source, first, "data() must never return the caller's original array");
    }

    @Test
    void mutatingTheOriginalArrayAfterConstructionDoesNotAffectStoredContent() {
        byte[] source = {1, 2, 3, 4};
        AssetImage image = new AssetImage(source, "image/png");

        source[0] = 99;

        assertEquals(1, image.data()[0], "constructor must defensively copy the input array");
    }

    @Test
    void mutatingAReturnedCopyDoesNotAffectLaterAccessorCalls() {
        AssetImage image = new AssetImage(new byte[]{1, 2, 3}, "image/jpeg");

        byte[] returned = image.data();
        returned[0] = 99;

        assertEquals(1, image.data()[0], "mutating a returned copy must not affect storage");
    }

    @Test
    void contentTypeRoundTrips() {
        AssetImage image = new AssetImage(new byte[]{1}, "image/jpeg");

        assertEquals("image/jpeg", image.contentType());
    }
}
