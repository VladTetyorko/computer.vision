package com.drones.vision.warehouse.application.usage;

import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.warehouse.domain.model.AssetUsage;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.warehouse.domain.model.Custody;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.warehouse.domain.model.Identity;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.UserId;
import com.drones.vision.kernel.UsageId;
import com.drones.vision.kernel.UsageOrigin;
import com.drones.vision.warehouse.domain.model.UsagePhase;
import com.drones.vision.warehouse.domain.port.AssetRepositoryPort;
import com.drones.vision.warehouse.domain.port.AssetUsageRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultUsageServiceTest {

    private AssetUsageRepositoryPort usageRepository;
    private AssetRepositoryPort assetRepository;
    private DefaultUsageService service;

    @BeforeEach
    void setUp() {
        usageRepository = mock(AssetUsageRepositoryPort.class);
        assetRepository = mock(AssetRepositoryPort.class);
        service = new DefaultUsageService(usageRepository, assetRepository);
    }

    private static Asset assetIn(AssetId id, GroupId groupId, String displayName) {
        return Asset.register(id, displayName, new CategoryId("drone"), new Ownership(UserId.random(), groupId),
                Set.of(DeviceId.random()), java.util.Map.of(), Identity.NONE, Custody.NONE);
    }

    private static AssetUsage usage(UsageId id, AssetId assetId, Instant startedAt, Instant endedAt) {
        return new AssetUsage(id, assetId, startedAt, endedAt, null, null, 7, null, UsagePhase.PREFLIGHT,
                UsageOrigin.STREAM, null);
    }

    private static AssetUsage usageOfStream(AssetId assetId, StreamId streamId) {
        Instant start = Instant.parse("2026-08-04T10:00:00Z");
        return new AssetUsage(UsageId.random(), assetId, start, start.plusSeconds(90), null, null, 7, streamId,
                UsagePhase.PREFLIGHT, UsageOrigin.STREAM, null);
    }

    @Test
    void rejectsNonPositiveLimit() {
        assertThrows(IllegalArgumentException.class, () -> service.recent(VisibilityScope.unbounded(), null, 0));
        assertThrows(IllegalArgumentException.class, () -> service.recent(VisibilityScope.unbounded(), null, -1));
    }

    @Test
    void unboundedScopeReturnsEveryUsageWithResolvedAssetNameAndDuration() {
        AssetId assetId = AssetId.random();
        Instant start = Instant.parse("2026-08-04T10:36:29.895Z");
        Instant end = Instant.parse("2026-08-04T11:20:00.000Z");
        AssetUsage usage = usage(UsageId.random(), assetId, start, end);
        when(usageRepository.findRecent(50)).thenReturn(List.of(usage));
        when(assetRepository.findById(assetId)).thenReturn(Optional.of(assetIn(assetId, GroupId.random(), "Falcon-2")));

        List<UsageSummary> result = service.recent(VisibilityScope.unbounded(), null, 50);

        assertEquals(1, result.size());
        UsageSummary row = result.get(0);
        assertEquals(usage.id(), row.usageId());
        assertEquals(assetId, row.assetId());
        assertEquals("Falcon-2", row.assetName());
        assertEquals(start, row.startedAt());
        assertEquals(end, row.endedAt());
        assertEquals(2610L, row.durationSeconds());
        assertEquals(7, row.sampleCount());
    }

    @Test
    void pilotIdFlowsThroughToTheSummaryRowWhenKnown() {
        AssetId assetId = AssetId.random();
        Instant start = Instant.parse("2026-08-04T10:36:29.895Z");
        UserId pilotId = UserId.random();
        AssetUsage usage = new AssetUsage(UsageId.random(), assetId, start, null, null, null, 0, null,
                UsagePhase.PREFLIGHT, UsageOrigin.OPERATOR, pilotId);
        when(usageRepository.findRecent(50)).thenReturn(List.of(usage));
        when(assetRepository.findById(assetId)).thenReturn(Optional.of(assetIn(assetId, GroupId.random(), "Falcon-2")));

        UsageSummary row = service.recent(VisibilityScope.unbounded(), null, 50).get(0);

        assertEquals(pilotId, row.pilotId());
    }

    @Test
    void pilotIdIsNullOnTheSummaryRowWhenGenuinelyUnknown() {
        AssetId assetId = AssetId.random();
        Instant start = Instant.parse("2026-08-04T10:36:29.895Z");
        AssetUsage usage = usage(UsageId.random(), assetId, start, null);
        when(usageRepository.findRecent(50)).thenReturn(List.of(usage));
        when(assetRepository.findById(assetId)).thenReturn(Optional.of(assetIn(assetId, GroupId.random(), "Falcon-2")));

        UsageSummary row = service.recent(VisibilityScope.unbounded(), null, 50).get(0);

        assertNull(row.pilotId());
    }

    @Test
    void stillOpenUsageHasNullEndedAtAndNullDuration() {
        AssetId assetId = AssetId.random();
        Instant start = Instant.parse("2026-08-04T10:36:29.895Z");
        AssetUsage usage = usage(UsageId.random(), assetId, start, null);
        when(usageRepository.findRecent(50)).thenReturn(List.of(usage));
        when(assetRepository.findById(assetId)).thenReturn(Optional.of(assetIn(assetId, GroupId.random(), "Falcon-2")));

        UsageSummary row = service.recent(VisibilityScope.unbounded(), null, 50).get(0);

        assertNull(row.endedAt());
        assertNull(row.durationSeconds());
    }

    @Test
    void groupScopeDropsUsagesOfAssetsOutsideTheVisibleGroups() {
        GroupId visibleGroup = GroupId.random();
        GroupId otherGroup = GroupId.random();
        AssetId visibleAsset = AssetId.random();
        AssetId hiddenAsset = AssetId.random();
        Instant start = Instant.parse("2026-08-04T10:00:00Z");
        AssetUsage visibleUsage = usage(UsageId.random(), visibleAsset, start, start.plusSeconds(60));
        AssetUsage hiddenUsage = usage(UsageId.random(), hiddenAsset, start, start.plusSeconds(60));
        when(usageRepository.findRecent(50)).thenReturn(List.of(hiddenUsage, visibleUsage));
        when(assetRepository.findById(visibleAsset))
                .thenReturn(Optional.of(assetIn(visibleAsset, visibleGroup, "Mine")));
        when(assetRepository.findById(hiddenAsset))
                .thenReturn(Optional.of(assetIn(hiddenAsset, otherGroup, "Not mine")));

        List<UsageSummary> result = service.recent(VisibilityScope.groups(Set.of(visibleGroup)), null, 50);

        assertEquals(1, result.size());
        assertEquals(visibleAsset, result.get(0).assetId());
    }

    @Test
    void goneAssetIsIncludedWithEmptyNameOnlyForAnUnboundedScope() {
        AssetId assetId = AssetId.random();
        Instant start = Instant.parse("2026-08-04T10:00:00Z");
        AssetUsage usage = usage(UsageId.random(), assetId, start, start.plusSeconds(60));
        when(usageRepository.findRecent(50)).thenReturn(List.of(usage));
        when(assetRepository.findById(assetId)).thenReturn(Optional.empty());

        List<UsageSummary> unboundedResult = service.recent(VisibilityScope.unbounded(), null, 50);
        assertEquals(1, unboundedResult.size());
        assertEquals("", unboundedResult.get(0).assetName());

        List<UsageSummary> scopedResult =
                service.recent(VisibilityScope.groups(Set.of(GroupId.random())), null, 50);
        assertTrue(scopedResult.isEmpty());
    }

    @Test
    void assetIdFilterDelegatesToFindRecentByAssetInsteadOfFleetWide() {
        AssetId assetId = AssetId.random();
        when(usageRepository.findRecentByAsset(eq(assetId), any(Integer.class))).thenReturn(List.of());

        service.recent(VisibilityScope.unbounded(), assetId, 10);

        verify(usageRepository).findRecentByAsset(assetId, 10);
        verify(usageRepository, never()).findRecent(any(Integer.class));
    }

    @Test
    void limitIsClampedToTheCeilingBeforeReachingTheRepository() {
        when(usageRepository.findRecent(DefaultUsageService.MAX_LIMIT)).thenReturn(List.of());

        service.recent(VisibilityScope.unbounded(), null, DefaultUsageService.MAX_LIMIT + 1000);

        verify(usageRepository).findRecent(DefaultUsageService.MAX_LIMIT);
    }

    @Test
    void byStreamResolvesTheSameRowShapeRecentWouldHaveListed() {
        AssetId assetId = AssetId.random();
        StreamId streamId = StreamId.random();
        AssetUsage usage = usageOfStream(assetId, streamId);
        when(usageRepository.findByStream(streamId)).thenReturn(Optional.of(usage));
        when(assetRepository.findById(assetId)).thenReturn(Optional.of(assetIn(assetId, GroupId.random(), "Falcon-2")));

        Optional<UsageSummary> result = service.byStream(VisibilityScope.unbounded(), streamId);

        assertTrue(result.isPresent());
        assertEquals(usage.id(), result.get().usageId());
        assertEquals("Falcon-2", result.get().assetName());
        assertEquals(90L, result.get().durationSeconds());
    }

    @Test
    void byStreamIsEmptyWhenNoUsageCarriesThatStreamId() {
        StreamId streamId = StreamId.random();
        when(usageRepository.findByStream(streamId)).thenReturn(Optional.empty());

        assertTrue(service.byStream(VisibilityScope.unbounded(), streamId).isEmpty());
    }

    @Test
    void byStreamHidesAUsageOutsideTheCallersScopeAsAnAbsenceNotAnError() {
        AssetId assetId = AssetId.random();
        StreamId streamId = StreamId.random();
        when(usageRepository.findByStream(streamId)).thenReturn(Optional.of(usageOfStream(assetId, streamId)));
        when(assetRepository.findById(assetId))
                .thenReturn(Optional.of(assetIn(assetId, GroupId.random(), "Not mine")));

        Optional<UsageSummary> result = service.byStream(VisibilityScope.groups(Set.of(GroupId.random())), streamId);

        assertTrue(result.isEmpty());
    }

    @Test
    void byStreamOfAGoneAssetFollowsTheSameUnboundedOnlyRuleRecentDoes() {
        AssetId assetId = AssetId.random();
        StreamId streamId = StreamId.random();
        when(usageRepository.findByStream(streamId)).thenReturn(Optional.of(usageOfStream(assetId, streamId)));
        when(assetRepository.findById(assetId)).thenReturn(Optional.empty());

        assertEquals("", service.byStream(VisibilityScope.unbounded(), streamId).orElseThrow().assetName());
        assertTrue(service.byStream(VisibilityScope.groups(Set.of(GroupId.random())), streamId).isEmpty());
    }

    @Test
    void byStreamRejectsNullArguments() {
        assertThrows(NullPointerException.class, () -> service.byStream(null, StreamId.random()));
        assertThrows(NullPointerException.class, () -> service.byStream(VisibilityScope.unbounded(), null));
    }
}
