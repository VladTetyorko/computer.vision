package com.drones.vision.warehouse.domain.model;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.StreamDescriptor;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DiscoveryCandidateTest {

    private static final Instant NOW = Instant.parse("2026-08-31T12:00:00Z");

    private static DiscoveredDevice mavlinkCandidate(int sysid) {
        StreamDescriptor stream = new StreamDescriptor("mavlink", URI.create("udp://0.0.0.0:14550"),
                Map.of("sysid", String.valueOf(sysid)));
        return new DiscoveredDevice("mavlink", "ArduPilot rover (sysid " + sysid + ")",
                URI.create("udp://0.0.0.0:14550"), new CategoryId("drone"), stream, Map.of("sysid",
                String.valueOf(sysid)));
    }

    private static DiscoveredDevice onvifCandidate() {
        return new DiscoveredDevice("onvif", "cam-1", URI.create("http://10.0.0.5/onvif"),
                new CategoryId("ip-camera"), null, Map.of());
    }

    private static DiscoveryCandidate newCandidate() {
        return DiscoveryCandidate.newlyReported(DiscoveryCandidateId.random(), onvifCandidate(), NOW);
    }

    // -- identityKeyFor -----------------------------------------------------------------------

    @Test
    void identityKeyIsMethodAndAddressWithoutSysid() {
        assertEquals("onvif|http://10.0.0.5/onvif", DiscoveryCandidate.identityKeyFor(onvifCandidate()));
    }

    @Test
    void identityKeyAppendsSysidReadFromSuggestedStreamOptionsAndDropsTheAddress() {
        // Identity-first (docs/plans/active/LINK-PAIRING-PLAN.md §3.3): once a sysid is known, the
        // key is keyed on identity alone, no address component -- a device re-heard at a new
        // address must resolve to the same identity key, not a different one.
        assertEquals("mavlink|sysid=7", DiscoveryCandidate.identityKeyFor(mavlinkCandidate(7)));
    }

    @Test
    void identityKeyFallsBackToDetailsSysidWhenNoSuggestedStreamExists() {
        DiscoveredDevice noStreamYet = new DiscoveredDevice("mavlink", "sysid 3", URI.create("udp://0.0.0.0:14550"),
                null, null, Map.of("sysid", "3"));

        assertEquals("mavlink|sysid=3", DiscoveryCandidate.identityKeyFor(noStreamYet));
    }

    @Test
    void identityKeyIgnoresDetailsSysidWhenSuggestedStreamAlreadyCarriesOne() {
        // suggestedStream wins over details -- the same field the codebase's own duplicate check
        // (AssetService#createFromCandidate) reads from.
        StreamDescriptor stream = new StreamDescriptor("mavlink", URI.create("udp://0.0.0.0:14550"),
                Map.of("sysid", "7"));
        DiscoveredDevice mismatched = new DiscoveredDevice("mavlink", "sysid 7", URI.create("udp://0.0.0.0:14550"),
                null, stream, Map.of("sysid", "99"));

        assertEquals("mavlink|sysid=7", DiscoveryCandidate.identityKeyFor(mismatched));
    }

    @Test
    void identityKeySameSysidAtADifferentAddressIsTheSameIdentity() {
        StreamDescriptor movedStream = new StreamDescriptor("mavlink", URI.create("udp://10.0.0.99:19999"),
                Map.of("sysid", "7"));
        DiscoveredDevice moved = new DiscoveredDevice("mavlink", "sysid 7", URI.create("udp://10.0.0.99:19999"),
                null, movedStream, Map.of("sysid", "7"));

        assertEquals(DiscoveryCandidate.identityKeyFor(mavlinkCandidate(7)), DiscoveryCandidate.identityKeyFor(moved));
    }

    @Test
    void identityKeyForRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> DiscoveryCandidate.identityKeyFor(null));
    }

    // -- newlyReported --------------------------------------------------------------------------

    @Test
    void newlyReportedStartsNewWithFirstSeenEqualToLastSeenAndNoRegisteredAsset() {
        DiscoveryCandidateId id = DiscoveryCandidateId.random();
        DiscoveredDevice discovered = onvifCandidate();

        DiscoveryCandidate candidate = DiscoveryCandidate.newlyReported(id, discovered, NOW);

        assertEquals(id, candidate.id());
        assertEquals(DiscoveryCandidate.identityKeyFor(discovered), candidate.identityKey());
        assertEquals(discovered, candidate.discovered());
        assertEquals(NOW, candidate.firstSeen());
        assertEquals(NOW, candidate.lastSeen());
        assertEquals(CandidateStatus.NEW, candidate.status());
        assertNull(candidate.registeredAsset());
    }

    // -- reSeen -----------------------------------------------------------------------------------

    @Test
    void reSeenRefreshesDiscoveredAndLastSeenButKeepsFirstSeenStatusAndRegisteredAsset() {
        DiscoveryCandidate original = newCandidate();
        AssetId owner = AssetId.random();
        DiscoveryCandidate registered = original.registeredTo(owner);
        Instant later = NOW.plusSeconds(60);
        DiscoveredDevice refreshedDevice = onvifCandidate();

        DiscoveryCandidate reSeen = registered.reSeen(refreshedDevice, later);

        assertEquals(refreshedDevice, reSeen.discovered());
        assertEquals(later, reSeen.lastSeen());
        assertEquals(original.firstSeen(), reSeen.firstSeen());
        assertEquals(CandidateStatus.REGISTERED, reSeen.status());
        assertEquals(owner, reSeen.registeredAsset());
    }

    // -- dismiss / registeredTo ---------------------------------------------------------------

    @Test
    void dismissSetsDismissedAndClearsRegisteredAsset() {
        DiscoveryCandidate candidate = newCandidate().registeredTo(AssetId.random());

        DiscoveryCandidate dismissed = candidate.dismiss();

        assertEquals(CandidateStatus.DISMISSED, dismissed.status());
        assertNull(dismissed.registeredAsset());
    }

    @Test
    void registeredToSetsRegisteredWithTheGivenAsset() {
        AssetId owner = AssetId.random();

        DiscoveryCandidate registered = newCandidate().registeredTo(owner);

        assertEquals(CandidateStatus.REGISTERED, registered.status());
        assertEquals(owner, registered.registeredAsset());
    }

    @Test
    void registeredToAcceptsANullOwnerWhenTheMatchingDeviceIsNotYetOwned() {
        DiscoveryCandidate registered = newCandidate().registeredTo(null);

        assertEquals(CandidateStatus.REGISTERED, registered.status());
        assertNull(registered.registeredAsset());
    }

    // -- compact constructor validation --------------------------------------------------------

    @Test
    void rejectsInvalidArguments() {
        DiscoveryCandidateId id = DiscoveryCandidateId.random();
        DiscoveredDevice discovered = onvifCandidate();
        String key = DiscoveryCandidate.identityKeyFor(discovered);

        assertThrows(IllegalArgumentException.class,
                () -> new DiscoveryCandidate(null, key, discovered, NOW, NOW, CandidateStatus.NEW, null));
        assertThrows(IllegalArgumentException.class,
                () -> new DiscoveryCandidate(id, null, discovered, NOW, NOW, CandidateStatus.NEW, null));
        assertThrows(IllegalArgumentException.class,
                () -> new DiscoveryCandidate(id, "", discovered, NOW, NOW, CandidateStatus.NEW, null));
        assertThrows(IllegalArgumentException.class,
                () -> new DiscoveryCandidate(id, key, null, NOW, NOW, CandidateStatus.NEW, null));
        assertThrows(IllegalArgumentException.class,
                () -> new DiscoveryCandidate(id, key, discovered, null, NOW, CandidateStatus.NEW, null));
        assertThrows(IllegalArgumentException.class,
                () -> new DiscoveryCandidate(id, key, discovered, NOW, null, CandidateStatus.NEW, null));
        assertThrows(IllegalArgumentException.class,
                () -> new DiscoveryCandidate(id, key, discovered, NOW, NOW.minusSeconds(1), CandidateStatus.NEW,
                        null));
        assertThrows(IllegalArgumentException.class,
                () -> new DiscoveryCandidate(id, key, discovered, NOW, NOW, null, null));
    }

    @Test
    void rejectsARegisteredAssetOnANonRegisteredStatus() {
        DiscoveryCandidateId id = DiscoveryCandidateId.random();
        DiscoveredDevice discovered = onvifCandidate();
        String key = DiscoveryCandidate.identityKeyFor(discovered);
        AssetId owner = AssetId.random();

        assertThrows(IllegalArgumentException.class,
                () -> new DiscoveryCandidate(id, key, discovered, NOW, NOW, CandidateStatus.NEW, owner));
        assertThrows(IllegalArgumentException.class,
                () -> new DiscoveryCandidate(id, key, discovered, NOW, NOW, CandidateStatus.DISMISSED, owner));
    }
}
