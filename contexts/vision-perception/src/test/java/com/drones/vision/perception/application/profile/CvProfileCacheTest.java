package com.drones.vision.perception.application.profile;

import com.drones.vision.kernel.GroupId;
import com.drones.vision.perception.domain.model.BindingScope;
import com.drones.vision.perception.domain.model.CvProfile;
import com.drones.vision.perception.domain.model.CvProfileBinding;
import com.drones.vision.perception.domain.model.CvProfileId;
import com.drones.vision.perception.domain.model.EventRuleConfig;
import com.drones.vision.perception.domain.model.ModelRef;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link CvProfileCache}: write-through mutations, and the lazy TTL reload boundary. */
class CvProfileCacheTest {

    private static CvProfile profile(String name) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new CvProfile(CvProfileId.random(), name, "", false, GroupId.random(),
                new ModelRef("yolo26n.pt", "latest"), 0.4, 10, List.of(), List.of(), true, null,
                EventRuleConfig.defaults(), null, now, now);
    }

    @Test
    void saveIsVisibleInTheVeryNextSnapshotWithNoTtlDelay() {
        InMemoryCvProfileRepositoryPort repository = new InMemoryCvProfileRepositoryPort();
        CvProfileCache cache = new CvProfileCache(repository, new CvProfileCacheSettings(Duration.ofMinutes(5)));
        CvProfile saved = profile("people-vehicles");

        cache.save(saved);

        assertEquals(List.of(saved), cache.snapshot().profiles());
    }

    @Test
    void deleteIsVisibleInTheVeryNextSnapshotWithNoTtlDelay() {
        InMemoryCvProfileRepositoryPort repository = new InMemoryCvProfileRepositoryPort();
        CvProfileCache cache = new CvProfileCache(repository, new CvProfileCacheSettings(Duration.ofMinutes(5)));
        CvProfile saved = cache.save(profile("wide-search"));

        cache.delete(saved.id());

        assertTrue(cache.snapshot().profiles().isEmpty());
    }

    @Test
    void bindingWriteThroughMethodsAreVisibleImmediately() {
        InMemoryCvProfileRepositoryPort repository = new InMemoryCvProfileRepositoryPort();
        CvProfileCache cache = new CvProfileCache(repository, new CvProfileCacheSettings(Duration.ofMinutes(5)));
        CvProfile saved = cache.save(profile("mast-cams"));
        CvProfileBinding binding = new CvProfileBinding(BindingScope.CATEGORY, "fixed-camera", saved.id(),
                Instant.parse("2026-01-01T00:00:00Z"));

        cache.saveBinding(binding);
        assertEquals(List.of(binding), cache.snapshot().bindings());
        assertEquals(1, cache.countBindingsFor(saved.id()));

        cache.deleteBinding(BindingScope.CATEGORY, "fixed-camera");
        assertTrue(cache.snapshot().bindings().isEmpty());
        assertEquals(0, cache.countBindingsFor(saved.id()));
    }

    @Test
    void snapshotStaysTheHeldOneBeforeTheTtlElapses() {
        InMemoryCvProfileRepositoryPort repository = new InMemoryCvProfileRepositoryPort();
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        CvProfileCache cache =
                new CvProfileCache(repository, new CvProfileCacheSettings(Duration.ofSeconds(60)), now::get);

        // A write made directly against the repository, bypassing the cache -- simulates another
        // process's write, the only case the TTL reload exists for.
        repository.save(profile("bypassed-the-cache"));

        now.set(now.get().plusSeconds(59));
        assertTrue(cache.snapshot().profiles().isEmpty(), "must not reload before the TTL elapses");
    }

    @Test
    void snapshotReloadsOnceTheTtlElapses() {
        InMemoryCvProfileRepositoryPort repository = new InMemoryCvProfileRepositoryPort();
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        CvProfileCache cache =
                new CvProfileCache(repository, new CvProfileCacheSettings(Duration.ofSeconds(60)), now::get);
        CvProfile written = profile("bypassed-the-cache");
        repository.save(written);

        now.set(now.get().plusSeconds(60));

        assertEquals(List.of(written), cache.snapshot().profiles(), "must reload once the TTL has elapsed");
    }

    @Test
    void countBindingsForIsALivePassThroughEvenBeforeTheCacheWouldReload() {
        // Pins the deliberate exception to "no read is fresher than TTL": countBindingsFor must
        // never be stale, since the delete-profile 409 rule needs a live answer -- unlike every
        // other read, it is never subject to the TTL at all.
        InMemoryCvProfileRepositoryPort repository = new InMemoryCvProfileRepositoryPort();
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        CvProfileCache cache =
                new CvProfileCache(repository, new CvProfileCacheSettings(Duration.ofMinutes(30)), now::get);
        CvProfile saved = cache.save(profile("mast-cams"));
        CvProfileBinding binding = new CvProfileBinding(BindingScope.ASSET, saved.id().value().toString(), saved.id(),
                now.get());
        repository.saveBinding(binding); // bypasses the cache directly, same as another process would

        assertEquals(1, cache.countBindingsFor(saved.id()), "never cached, so this reflects the repository at once");
    }
}
