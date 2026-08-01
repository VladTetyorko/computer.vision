package com.drones.vision.application;

import com.drones.vision.domain.port.out.DatasetExportPort;
import com.drones.vision.domain.port.out.DatasetRepositoryPort;
import com.drones.vision.domain.port.out.SampleImageStorePort;
import com.drones.vision.domain.port.out.TrainingSampleRepositoryPort;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class TrainingStoresTest {

    private final DatasetRepositoryPort datasets = mock(DatasetRepositoryPort.class);
    private final TrainingSampleRepositoryPort samples = mock(TrainingSampleRepositoryPort.class);
    private final SampleImageStorePort images = mock(SampleImageStorePort.class);
    private final DatasetExportPort exports = mock(DatasetExportPort.class);

    @Test
    void rejectsNullDatasets() {
        assertThrows(NullPointerException.class, () -> new TrainingStores(null, samples, images, exports));
    }

    @Test
    void rejectsNullSamples() {
        assertThrows(NullPointerException.class, () -> new TrainingStores(datasets, null, images, exports));
    }

    @Test
    void rejectsNullImages() {
        assertThrows(NullPointerException.class, () -> new TrainingStores(datasets, samples, null, exports));
    }

    @Test
    void rejectsNullExports() {
        assertThrows(NullPointerException.class, () -> new TrainingStores(datasets, samples, images, null));
    }
}
