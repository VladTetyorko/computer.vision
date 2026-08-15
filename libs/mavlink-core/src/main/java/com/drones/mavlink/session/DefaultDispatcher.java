package com.drones.mavlink.session;

import com.drones.mavlink.codec.MavFrame;

import java.lang.System.Logger.Level;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The one {@link Dispatcher} implementation. Backed by a {@link CopyOnWriteArrayList}: subscribing
 * and unsubscribing are rare compared to dispatching (which happens once per decoded frame, on
 * whichever link's reader thread decoded it), so a copy-on-write snapshot for the common read path
 * is the right trade — {@link #dispatch} always iterates a stable snapshot, immune to concurrent
 * subscribe/unsubscribe calls from other threads.
 *
 * <p>{@link #dispatch} is package-private: only {@link MavlinkSession} (the sole caller, after
 * updating {@link PeerDirectory}/{@link LinkHealth} and offering to {@link Correlator}) drives it.
 */
final class DefaultDispatcher implements Dispatcher {

    private static final System.Logger LOG = System.getLogger(DefaultDispatcher.class.getName());

    /** Reference-identity, not structural equality -- see {@link #subscribe} javadoc. */
    private final CopyOnWriteArrayList<Entry> entries = new CopyOnWriteArrayList<>();

    @Override
    public Subscription subscribe(MessageFilter filter, Consumer<MavFrame> handler) {
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(handler, "handler");
        Entry entry = new Entry(filter, handler);
        entries.add(entry);
        // entry is deliberately a plain class (identity equals/hashCode), not a record: two
        // subscriptions registered with an equals()-identical (filter, handler) pair must remain
        // independently closeable -- closing one must never remove the other's entry too.
        return () -> entries.remove(entry);
    }

    /**
     * Offers {@code frame} to every current subscriber whose filter matches, in registration order.
     * Runs entirely on the calling (RX) thread. Each handler invocation is isolated: a handler that
     * throws is logged and skipped, never preventing another subscriber from seeing this frame and
     * never propagating out of this method.
     */
    void dispatch(MavFrame frame) {
        for (Entry entry : entries) {
            try {
                if (entry.filter.test(frame)) {
                    entry.handler.accept(frame);
                }
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Dispatcher subscriber threw handling a frame; isolating it, other "
                        + "subscribers still receive it", e);
            }
        }
    }

    private static final class Entry {
        final MessageFilter filter;
        final Consumer<MavFrame> handler;

        Entry(MessageFilter filter, Consumer<MavFrame> handler) {
            this.filter = filter;
            this.handler = handler;
        }
    }
}
