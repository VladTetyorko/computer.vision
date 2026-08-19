package com.drones.vision.adapter.mavlink;

import com.drones.mavlink.codec.MavFrame;
import com.drones.mavlink.codec.MavHeader;
import com.drones.mavlink.session.Dispatcher;
import com.drones.mavlink.session.MessageFilter;
import com.drones.mavlink.session.Subscription;

import io.dronefleet.mavlink.annotations.MavlinkFieldInfo;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Passive, per-peer message inventory (docs/plans/active/DRONE-ONBOARDING-PLAN.md O1 — stage 1 of
 * the onboarding probe pipeline, §3.2's {@code "subscribe(MessageFilter.any()) - passive
 * inventory"}). Its own, independent {@link Dispatcher} subscription counts every observed
 * {@code (sysid, messageId)} pair's count/Hz over a rolling window and estimates each peer's total
 * bytes/s — pure observation: it never sends, never blocks frame routing, and never influences
 * {@link VehicleClaimPolicy} (an unclaimed vehicle is inventoried exactly like a claimed one).
 *
 * <h2>Per-sysid isolation</h2>
 * One {@link PeerInventory} per observed system id. Two vehicles sharing a link (a companion
 * computer relaying several, or two radios on one port) must never pool into the same counters —
 * a fleet operator reading "VFR_HUD at 5 Hz" needs to know which aircraft that describes. Proven by
 * {@code MavlinkMessageInventoryIntegrationTest} over a real loopback socket.
 *
 * <h2>Rolling, not cumulative</h2>
 * {@code count}/{@code hz} describe only the trailing {@link MavlinkSettings.Inventory#window()} —
 * a peer that has gone silent decays back to an empty snapshot as real time passes, even with no
 * new frame arriving to trigger the recompute (the query itself, not the write, ages buckets out).
 * This mirrors the plan's own §2.4 "never a frozen-looking live value" rule (the CV-DEMAND
 * stale-box lesson) rather than reporting a number that stopped being true minutes ago.
 *
 * <h2>{@code bytesPerSecond} is an estimate, not a wire measurement</h2>
 * {@link MavFrame} (frozen by {@code mavlink-core}'s {@code API.md}) carries a decoded header and
 * payload, never the original frame's raw byte length — {@code FrameReader} reads it
 * ({@code message.getRawBytes()}) only transiently, to pull two flag bytes out for {@link
 * MavHeader}, then discards the array. Reconstructing the true wire length would need a {@code
 * mavlink-core} change (a {@code MavFrame.wireLength()}-shaped field), out of this wave's scope
 * (task brief: "no library change") — flagged in this module's MODULE.md as a real gap for a future
 * wave. Absent that, this class estimates each frame's size as fixed protocol overhead
 * (version/signature dependent, {@link #MAVLINK1_OVERHEAD_BYTES}/{@link #mavlink2OverheadBytes}) plus
 * that message type's <b>maximum</b> payload length — the sum of every {@code @MavlinkFieldInfo}
 * field's {@code unitSize() * max(1, arraySize())}, i.e. exactly what {@code io.dronefleet.mavlink}'s
 * own {@code ReflectionPayloadSerializer} would allocate for it, computed once per payload class and
 * cached (never per frame — the field metadata is identical for every instance of one message type).
 * MAVLink 2 trims trailing all-zero bytes off the wire, so this is a documented <b>upper bound</b>,
 * not the true send size, whenever a v2 message's trailing fields happen to be zero.
 *
 * <p>Not thread-safe on its own — guarded by one private monitor, the same "one private monitor per
 * mutable-claim-state class" convention {@link MavlinkGateway}/{@link VehicleClaimPolicy} already
 * follow. Writes only ever happen on the session's one reader thread (mirroring {@link
 * MavlinkGateway#onFrame}'s own directness: this class does no queueing because its per-frame work
 * is O(1) amortized — map lookups and a cached-class-length lookup, never a blocking call — so it
 * needs no {@code BoundedSubscriber} wrapper); reads happen from whatever thread later queries a
 * snapshot (a future O4 configurator, or a test).
 */
final class MavlinkMessageInventory {

    /** v1 header (STX, LEN, SEQ, SYSID, COMPID, MSGID) + a 2-byte CRC — no payload, no signature (v1 has none). */
    private static final int MAVLINK1_OVERHEAD_BYTES = 6 + 2;
    /** v2 header (STX, LEN, INCOMPAT, COMPAT, SEQ, SYSID, COMPID, 3-byte MSGID) + a 2-byte CRC, unsigned. */
    private static final int MAVLINK2_OVERHEAD_BYTES = 10 + 2;
    /** MAVLink 2's optional signature block: link id (1) + timestamp (6) + signature (6). */
    private static final int MAVLINK2_SIGNATURE_BYTES = 13;

    /** Per-payload-class maximum wire length, computed once (see class javadoc) and reused forever. */
    private static final Map<Class<?>, Integer> MAX_PAYLOAD_LENGTH_CACHE = new ConcurrentHashMap<>();

    private final Object lock = new Object();
    private final long bucketWidthMillis;
    private final int bucketCount;
    private final int maxTrackedPeers;
    private final int maxTrackedMessageTypesPerPeer;
    private final Map<Integer, PeerInventory> peers;
    private final Subscription subscription;

    MavlinkMessageInventory(Dispatcher dispatcher, MavlinkSettings.Inventory settings) {
        Objects.requireNonNull(dispatcher, "dispatcher must not be null");
        Objects.requireNonNull(settings, "settings must not be null");
        this.bucketWidthMillis = settings.bucketWidth().toMillis();
        this.bucketCount = (int) Math.max(1, settings.window().toMillis() / bucketWidthMillis);
        this.maxTrackedPeers = settings.maxTrackedPeers();
        this.maxTrackedMessageTypesPerPeer = settings.maxTrackedMessageTypesPerPeer();
        this.peers = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, PeerInventory> eldest) {
                return size() > MavlinkMessageInventory.this.maxTrackedPeers;
            }
        };
        this.subscription = dispatcher.subscribe(MessageFilter.any(), this::onFrame);
    }

    /** Idempotent via {@link Subscription#close()}'s own contract. */
    void close() {
        subscription.close();
    }

    /** System ids this inventory currently has data for, oldest-tracked evicted first at the configured cap. */
    List<Integer> observedPeers() {
        synchronized (lock) {
            return List.copyOf(peers.keySet());
        }
    }

    /** {@code null} if {@code sysid} has never been observed, or was evicted under the tracked-peer cap. */
    PeerSnapshot snapshot(int sysid) {
        long now = System.currentTimeMillis();
        synchronized (lock) {
            PeerInventory peer = peers.get(sysid);
            return peer == null ? null : peer.snapshot(sysid, now);
        }
    }

    private void onFrame(MavFrame frame) {
        int sysid = frame.header().system().value();
        int messageId = frame.header().messageId();
        long bytes = estimatedFrameBytes(frame);
        long now = frame.receivedAt().toEpochMilli();
        synchronized (lock) {
            PeerInventory peer = peers.get(sysid);
            if (peer == null) {
                peer = new PeerInventory(bucketWidthMillis, bucketCount, maxTrackedMessageTypesPerPeer);
                peers.put(sysid, peer);
            }
            peer.record(messageId, bytes, now);
        }
    }

    private static long estimatedFrameBytes(MavFrame frame) {
        MavHeader header = frame.header();
        int overhead = header.version() == 2
                ? MAVLINK2_OVERHEAD_BYTES + (header.signed() ? MAVLINK2_SIGNATURE_BYTES : 0)
                : MAVLINK1_OVERHEAD_BYTES;
        return overhead + maxPayloadLength(frame.payload().getClass());
    }

    private static int maxPayloadLength(Class<?> payloadClass) {
        return MAX_PAYLOAD_LENGTH_CACHE.computeIfAbsent(payloadClass, MavlinkMessageInventory::computeMaxPayloadLength);
    }

    /** The sum every {@code @MavlinkFieldInfo}-annotated accessor contributes — see class javadoc. */
    private static int computeMaxPayloadLength(Class<?> payloadClass) {
        return Arrays.stream(payloadClass.getMethods())
                .filter(method -> method.isAnnotationPresent(MavlinkFieldInfo.class))
                .mapToInt(method -> {
                    MavlinkFieldInfo info = method.getAnnotation(MavlinkFieldInfo.class);
                    return info.unitSize() * Math.max(1, info.arraySize());
                })
                .sum();
    }

    /** One observed message type's rolling {@code count}/{@code hz} (never negative, never a lifetime total). */
    record MessageRate(int messageId, long count, double hz) {
    }

    /** One peer's rolling inventory: every message type heard from it, plus its total bytes/s estimate. */
    record PeerSnapshot(int sysid, List<MessageRate> messages, long bytesPerSecond) {
    }

    /** One system id's message-type breakdown, bounded/LRU-evicted at {@code maxTrackedMessageTypesPerPeer}. */
    private static final class PeerInventory {
        private final long bucketWidthMillis;
        private final int bucketCount;
        private final Map<Integer, RateCounter> byMessage;
        private final RateCounter bytesPerSecond;

        PeerInventory(long bucketWidthMillis, int bucketCount, int maxTrackedMessageTypes) {
            this.bucketWidthMillis = bucketWidthMillis;
            this.bucketCount = bucketCount;
            this.bytesPerSecond = new RateCounter(bucketWidthMillis, bucketCount);
            this.byMessage = new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, RateCounter> eldest) {
                    return size() > maxTrackedMessageTypes;
                }
            };
        }

        void record(int messageId, long bytes, long nowMillis) {
            RateCounter counter = byMessage.get(messageId);
            if (counter == null) {
                counter = new RateCounter(bucketWidthMillis, bucketCount);
                byMessage.put(messageId, counter);
            }
            counter.record(1, nowMillis);
            bytesPerSecond.record(bytes, nowMillis);
        }

        PeerSnapshot snapshot(int sysid, long nowMillis) {
            List<MessageRate> messages = byMessage.entrySet().stream()
                    .map(entry -> new MessageRate(
                            entry.getKey(), entry.getValue().windowedSum(nowMillis), entry.getValue().hz(nowMillis)))
                    .toList();
            return new PeerSnapshot(sysid, messages, Math.round(bytesPerSecond.hz(nowMillis)));
        }
    }

    /**
     * A fixed-size ring of per-{@code bucketWidth} sums covering the trailing {@code window}
     * ({@code bucketCount * bucketWidth}) — bounded memory regardless of arrival rate, unlike a
     * timestamp deque, and self-aging: a bucket whose stored epoch falls outside the current
     * window is treated as empty by {@link #windowedSum} even if {@link #record} is never called
     * again, so a peer that goes silent decays to zero rather than reporting a frozen rate forever.
     */
    private static final class RateCounter {
        private final long[] bucketValue;
        private final long[] bucketEpoch;
        private final long bucketWidthMillis;
        private final double windowSeconds;

        RateCounter(long bucketWidthMillis, int bucketCount) {
            this.bucketWidthMillis = bucketWidthMillis;
            this.bucketValue = new long[bucketCount];
            this.bucketEpoch = new long[bucketCount];
            Arrays.fill(bucketEpoch, Long.MIN_VALUE); // sentinel: no bucket has ever been written
            this.windowSeconds = bucketCount * bucketWidthMillis / 1000.0;
        }

        void record(long amount, long nowMillis) {
            long epoch = Math.floorDiv(nowMillis, bucketWidthMillis);
            int index = Math.floorMod(epoch, bucketValue.length);
            if (bucketEpoch[index] != epoch) {
                bucketEpoch[index] = epoch;
                bucketValue[index] = 0;
            }
            bucketValue[index] += amount;
        }

        long windowedSum(long nowMillis) {
            long currentEpoch = Math.floorDiv(nowMillis, bucketWidthMillis);
            long oldestValidEpoch = currentEpoch - bucketValue.length + 1;
            long sum = 0;
            for (int i = 0; i < bucketValue.length; i++) {
                if (bucketEpoch[i] >= oldestValidEpoch && bucketEpoch[i] <= currentEpoch) {
                    sum += bucketValue[i];
                }
            }
            return sum;
        }

        double hz(long nowMillis) {
            return windowedSum(nowMillis) / windowSeconds;
        }
    }
}
