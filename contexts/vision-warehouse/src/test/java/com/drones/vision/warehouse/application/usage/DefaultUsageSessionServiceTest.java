package com.drones.vision.warehouse.application.usage;

import com.drones.vision.warehouse.domain.model.AssetUsage;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.UsageId;
import com.drones.vision.kernel.UsageOrigin;
import com.drones.vision.kernel.UserId;
import com.drones.vision.warehouse.domain.model.UsagePhase;
import com.drones.vision.warehouse.domain.port.AssetUsageRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md D1/R3: this is the only place in the codebase
 * that constructs a new {@link AssetUsage} or writes one to {@link AssetUsageRepositoryPort} — see
 * {@link UsageSessionService}'s own javadoc.
 */
class DefaultUsageSessionServiceTest {

    private AssetUsageRepositoryPort usageRepository;
    private DefaultUsageSessionService service;

    @BeforeEach
    void setUp() {
        usageRepository = mock(AssetUsageRepositoryPort.class);
        service = new DefaultUsageSessionService(usageRepository);
        when(usageRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void openConstructsAndPersistsAPreflightUsageStampedWithTheGivenStream() {
        AssetId assetId = AssetId.random();
        StreamId streamId = StreamId.random();
        Instant startedAt = Instant.parse("2026-01-01T00:00:00Z");

        AssetUsage opened = service.open(assetId, streamId, UsageOrigin.STREAM, startedAt, null);

        assertEquals(assetId, opened.assetId());
        assertEquals(streamId, opened.streamId());
        assertEquals(startedAt, opened.startedAt());
        assertNull(opened.endedAt());
        assertEquals(0L, opened.sampleCount());
        assertEquals(UsagePhase.PREFLIGHT, opened.phase());
        verify(usageRepository).save(opened);
    }

    @Test
    void openWithNoStreamOpensATelemetryOnlyUsage() {
        AssetUsage opened = service.open(AssetId.random(), null, UsageOrigin.STREAM, Instant.now(), null);

        assertNull(opened.streamId());
    }

    // -- origin (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md D2, wave R2) ------------------

    @Test
    void openWithoutAnExplicitOriginAlwaysAttributesStream() {
        AssetUsage opened = service.open(AssetId.random(), StreamId.random(), UsageOrigin.STREAM, Instant.now(), null);

        assertEquals(UsageOrigin.STREAM, opened.origin());
    }

    @Test
    void openWithAnExplicitOriginAttributesTheGivenOrigin() {
        AssetId assetId = AssetId.random();
        Instant startedAt = Instant.parse("2026-01-01T00:00:00Z");

        AssetUsage opened = service.open(assetId, null, UsageOrigin.OPERATOR, startedAt, null);

        assertEquals(assetId, opened.assetId());
        assertNull(opened.streamId(), "an operator-engaged usage has no video stream to stamp");
        assertEquals(UsageOrigin.OPERATOR, opened.origin());
        assertEquals(UsagePhase.PREFLIGHT, opened.phase());
        verify(usageRepository).save(opened);
    }

    @Test
    void openWithAnExplicitOriginRejectsNullOrigin() {
        assertThrows(NullPointerException.class,
                () -> service.open(AssetId.random(), StreamId.random(), null, Instant.now(), null));
    }

    // -- pilot (docs/plans/active/ASSET-FLOWS-PLAN.md §2, D1p) -------------------------------------

    @Test
    void openWithAKnownPilotAttributesTheUsageToThatPilot() {
        UserId pilotId = UserId.random();

        AssetUsage opened = service.open(AssetId.random(), null, UsageOrigin.OPERATOR, Instant.now(), pilotId);

        assertEquals(pilotId, opened.pilotId());
    }

    @Test
    void openWithNoPilotLeavesPilotIdHonestlyNull() {
        AssetUsage opened = service.open(AssetId.random(), StreamId.random(), UsageOrigin.STREAM, Instant.now(), null);

        assertNull(opened.pilotId());
    }

    @Test
    void foldPinsStartPositionOnTheFirstPositionedSampleAndNeverPersists() {
        AssetUsage usage = service.open(AssetId.random(), StreamId.random(), UsageOrigin.STREAM, Instant.now(), null);
        GeoPosition first = new GeoPosition(50.0, 30.0, null);

        AssetUsage folded = service.fold(usage, first, UsagePhase.IN_FLIGHT);

        assertEquals(first, folded.startPosition());
        assertEquals(first, folded.lastPosition());
        assertEquals(1, folded.sampleCount());
        assertEquals(UsagePhase.IN_FLIGHT, folded.phase());
        verify(usageRepository, never()).save(folded);
    }

    @Test
    void foldKeepsStartPositionPinnedOnceSetButAdvancesLastPosition() {
        AssetUsage usage = service.open(AssetId.random(), StreamId.random(), UsageOrigin.STREAM, Instant.now(), null);
        GeoPosition first = new GeoPosition(50.0, 30.0, null);
        GeoPosition second = new GeoPosition(50.001, 30.001, null);
        AssetUsage afterFirst = service.fold(usage, first, UsagePhase.IN_FLIGHT);

        AssetUsage afterSecond = service.fold(afterFirst, second, UsagePhase.IN_FLIGHT);

        assertEquals(first, afterSecond.startPosition(), "start position must stay pinned to the first sample");
        assertEquals(second, afterSecond.lastPosition());
        assertEquals(2, afterSecond.sampleCount());
    }

    @Test
    void foldWithNoPositionStillIncrementsSampleCountButLeavesPositionsUntouched() {
        AssetUsage usage = service.open(AssetId.random(), StreamId.random(), UsageOrigin.STREAM, Instant.now(), null);

        AssetUsage folded = service.fold(usage, null, UsagePhase.PREFLIGHT);

        assertNull(folded.startPosition());
        assertNull(folded.lastPosition());
        assertEquals(1, folded.sampleCount());
    }

    @Test
    void updatePhaseReplacesOnlyThePhaseAndNeverPersists() {
        AssetUsage usage = service.open(AssetId.random(), StreamId.random(), UsageOrigin.STREAM, Instant.now(), null);

        AssetUsage updated = service.updatePhase(usage, UsagePhase.LINK_LOST);

        assertEquals(UsagePhase.LINK_LOST, updated.phase());
        assertEquals(usage.id(), updated.id());
        assertEquals(usage.sampleCount(), updated.sampleCount());
        verify(usageRepository, never()).save(updated);
    }

    @Test
    void closeTransformsAndPersistsTheFinalUsage() {
        AssetUsage usage = service.open(AssetId.random(), StreamId.random(), UsageOrigin.STREAM, Instant.parse("2026-01-01T00:00:00Z"), null);
        Instant endedAt = Instant.parse("2026-01-01T00:05:00Z");
        org.mockito.Mockito.clearInvocations(usageRepository); // open() already persisted once; isolate close()'s save

        AssetUsage closed = service.close(usage, UsagePhase.ABANDONED, endedAt);

        assertEquals(endedAt, closed.endedAt());
        assertEquals(UsagePhase.ABANDONED, closed.phase());
        assertTrue(!closed.endedAt().isBefore(closed.startedAt()));
        ArgumentCaptor<AssetUsage> captor = ArgumentCaptor.forClass(AssetUsage.class);
        verify(usageRepository).save(captor.capture());
        assertEquals(closed, captor.getValue());
    }

    @Test
    void savePersistsTheGivenUsageAsIs() {
        AssetUsage usage = service.open(AssetId.random(), StreamId.random(), UsageOrigin.STREAM, Instant.now(), null);

        AssetUsage saved = service.save(usage);

        assertEquals(usage, saved);
        verify(usageRepository, org.mockito.Mockito.times(2)).save(usage); // once from open(), once from this call
    }

    @Test
    void rejectsNullArguments() {
        AssetUsage usage = service.open(AssetId.random(), StreamId.random(), UsageOrigin.STREAM, Instant.now(), null);
        assertThrows(NullPointerException.class, () -> service.open(null, StreamId.random(), UsageOrigin.STREAM, Instant.now(), null));
        assertThrows(NullPointerException.class, () -> service.open(AssetId.random(), StreamId.random(), UsageOrigin.STREAM, null, null));
        assertThrows(NullPointerException.class, () -> service.fold(null, null, UsagePhase.PREFLIGHT));
        assertThrows(NullPointerException.class, () -> service.fold(usage, null, null));
        assertThrows(NullPointerException.class, () -> service.updatePhase(null, UsagePhase.PREFLIGHT));
        assertThrows(NullPointerException.class, () -> service.updatePhase(usage, null));
        assertThrows(NullPointerException.class, () -> service.close(null, UsagePhase.CLOSED, Instant.now()));
        assertThrows(NullPointerException.class, () -> service.close(usage, null, Instant.now()));
        assertThrows(NullPointerException.class, () -> service.close(usage, UsagePhase.CLOSED, null));
        assertThrows(NullPointerException.class, () -> service.save(null));
    }

    @Test
    void constructorRejectsNullRepository() {
        assertThrows(NullPointerException.class, () -> new DefaultUsageSessionService(null));
    }

    // -- usageBelongsToAsset (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md R5) --------------

    @Test
    void usageBelongsToAssetIsTrueWhenTheUsageIsOwnedByThatAsset() {
        AssetId assetId = AssetId.random();
        AssetUsage usage = service.open(assetId, StreamId.random(), UsageOrigin.STREAM, Instant.now(), null);
        when(usageRepository.findById(usage.id())).thenReturn(Optional.of(usage));

        assertTrue(service.usageBelongsToAsset(usage.id(), assetId));
    }

    @Test
    void usageBelongsToAssetIsFalseWhenTheUsageBelongsToADifferentAsset() {
        AssetUsage usage = service.open(AssetId.random(), StreamId.random(), UsageOrigin.STREAM, Instant.now(), null);
        when(usageRepository.findById(usage.id())).thenReturn(Optional.of(usage));

        assertEquals(false, service.usageBelongsToAsset(usage.id(), AssetId.random()));
    }

    @Test
    void usageBelongsToAssetIsFalseForAnUnknownUsage() {
        assertEquals(false, service.usageBelongsToAsset(UsageId.random(), AssetId.random()));
    }

    @Test
    void usageBelongsToAssetRejectsNullArguments() {
        AssetUsage usage = service.open(AssetId.random(), StreamId.random(), UsageOrigin.STREAM, Instant.now(), null);
        assertThrows(NullPointerException.class, () -> service.usageBelongsToAsset(null, AssetId.random()));
        assertThrows(NullPointerException.class, () -> service.usageBelongsToAsset(usage.id(), null));
    }
}
