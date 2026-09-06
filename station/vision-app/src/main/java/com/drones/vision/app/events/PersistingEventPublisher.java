package com.drones.vision.app.events;

import com.drones.vision.platform.Event;
import com.drones.vision.platform.EventHistoryPort;
import com.drones.vision.platform.EventPublisherPort;
import com.drones.vision.platform.EventType;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * {@link EventPublisherPort} decorator that additionally records a durable copy of the event via
 * {@link EventHistoryPort} — the fix for docs/plans/active/ALWAYS-ON-FLOW-PLAN.md wave B3's finding
 * that the notification bell and {@code /manage/system} were a pure {@code computed} over the live
 * feed's in-memory log, with no durable store behind either of them at all.
 *
 * <p>Every event is still delegated to the real {@link EventPublisherPort} first, unchanged — same
 * precedent as every other decorator in this package ({@link LiveUpdateEventPublisher}, {@link
 * DetectionSessionCleanupEventPublisher}). This class is a pure addition: nothing that raises an
 * event learns a new collaborator, and the existing publish/live-update path cannot be affected by
 * anything this class does.
 *
 * <h2>Off the hot path</h2>
 * {@link EventPublisherPort#publish(Event)}'s own contract says it "is called from the hot pipeline
 * path... implementations that need to do slow I/O should hand off internally rather than blocking
 * the caller." The durable write is exactly that slow I/O, so it never runs on the calling thread:
 * {@link #historyWriteExecutor} spawns one virtual thread per event to record, the same "off the
 * scheduler thread" idiom {@code LiveUpdateRegistry#connectionWriteExecutor} already uses in
 * vision-api for the analogous problem. A failed write (DB outage, pool exhaustion) is caught and
 * logged, never rethrown — {@link #publish} has already returned to the caller by the time the write
 * even starts, so there is nothing left to propagate a failure to.
 *
 * <h2>What gets persisted</h2>
 * {@link EventType#DETECTION} is deliberately never recorded here — it is this pipeline's highest-
 * volume event by a wide margin (raised once per non-empty inference result, on every stream, per
 * {@code StreamPipeline}'s own javadoc), it is already durable elsewhere at full fidelity ({@code
 * DetectionRepositoryPort}/{@code DetectionEventRepositoryPort}), and the notification bell's own
 * {@code systemEventRows} logic (vision-web) already excludes it from what it renders — recording it
 * here would add write load and retention pressure for a type nothing durable-history-shaped ever
 * reads back. Every other {@link EventType} is edge-triggered (a device/link/battery/geofence state
 * change, a stream lifecycle transition, a pipeline error, a training milestone) rather than
 * per-frame, so the remaining volume is orders of magnitude lower.
 *
 * <p>Only wired ({@code ApplicationServiceWiring#eventPublisherPort}) when {@code
 * vision.events.history.enabled} is {@code true}; with it disabled (the default), this class is
 * never constructed and {@code eventPublisherPort} behaves exactly as it did before this wave.
 */
public final class PersistingEventPublisher implements EventPublisherPort {

    private static final System.Logger LOG = System.getLogger(PersistingEventPublisher.class.getName());

    private final EventPublisherPort delegate;
    private final EventHistoryPort eventHistoryPort;

    /**
     * One virtual thread per recorded event, unconditionally instantiated — this class is itself
     * only constructed when {@code vision.events.history.enabled=true}, so there is no cost to
     * creating it eagerly. See the class javadoc's "Off the hot path" section for why this exists
     * at all rather than a direct {@code eventHistoryPort.record(event)} call.
     */
    private final ExecutorService historyWriteExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public PersistingEventPublisher(EventPublisherPort delegate, EventHistoryPort eventHistoryPort) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.eventHistoryPort = Objects.requireNonNull(eventHistoryPort, "eventHistoryPort must not be null");
    }

    @Override
    public void publish(Event event) {
        delegate.publish(event);
        if (event.type() == EventType.DETECTION) {
            return;
        }
        historyWriteExecutor.execute(() -> {
            try {
                eventHistoryPort.record(event);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING,
                        "failed to record event id=" + event.id() + " type=" + event.type() + " to durable "
                                + "history; delivery to the caller already completed and is unaffected", e);
            }
        });
    }
}
