package com.drones.vision.learning.application;

import com.drones.vision.learning.domain.model.DatasetId;
import com.drones.vision.kernel.StreamId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CaptureSpecTest {

    @Test
    void rejectsNullStreamId() {
        assertThrows(NullPointerException.class, () -> new CaptureSpec(null, DatasetId.random()));
    }

    @Test
    void rejectsNullDatasetId() {
        assertThrows(NullPointerException.class, () -> new CaptureSpec(StreamId.random(), null));
    }

    @Test
    void roundTripsBothIds() {
        StreamId streamId = StreamId.random();
        DatasetId datasetId = DatasetId.random();

        CaptureSpec spec = new CaptureSpec(streamId, datasetId);

        assertEquals(streamId, spec.streamId());
        assertEquals(datasetId, spec.datasetId());
    }
}
