package com.drones.vision.application.training;

import com.drones.vision.domain.port.out.DatasetRepositoryPort;
import com.drones.vision.domain.port.out.DatasetUploadPort;
import com.drones.vision.domain.port.out.SampleImageStorePort;
import com.drones.vision.domain.port.out.TrainingSampleRepositoryPort;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class TrainingStoresTest {

    private final DatasetRepositoryPort datasets = mock(DatasetRepositoryPort.class);
    private final TrainingSampleRepositoryPort samples = mock(TrainingSampleRepositoryPort.class);
    private final SampleImageStorePort images = mock(SampleImageStorePort.class);
    private final DatasetUploadPort uploads = mock(DatasetUploadPort.class);

    @Test
    void rejectsNullDatasets() {
        assertThrows(NullPointerException.class, () -> new TrainingStores(null, samples, images, uploads));
    }

    @Test
    void rejectsNullSamples() {
        assertThrows(NullPointerException.class, () -> new TrainingStores(datasets, null, images, uploads));
    }

    @Test
    void rejectsNullImages() {
        assertThrows(NullPointerException.class, () -> new TrainingStores(datasets, samples, null, uploads));
    }

    @Test
    void rejectsNullUploads() {
        assertThrows(NullPointerException.class, () -> new TrainingStores(datasets, samples, images, null));
    }
}
