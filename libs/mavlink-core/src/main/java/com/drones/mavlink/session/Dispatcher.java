package com.drones.mavlink.session;

import com.drones.mavlink.codec.MavFrame;

import java.util.function.Consumer;

/**
 * Fan-out of decoded frames to subscribers, filtered by {@link MessageFilter}.
 *
 * <h2>The one rule that matters</h2>
 * {@code handler} runs synchronously on the RX thread that decoded the frame, and <b>must not
 * block</b>. This dispatcher does not queue and does not retry — a handler that needs to buffer
 * (a slow HTTP/SSE push, a future Kafka producer) wraps itself in {@link BoundedSubscriber} instead
 * of blocking here. A handler that throws is logged and isolated: it never prevents another
 * subscriber from receiving the same frame, and never kills the read loop (plan §5.1 B5).
 */
public interface Dispatcher {

    /** Registers {@code handler} for every frame {@code filter} matches. */
    Subscription subscribe(MessageFilter filter, Consumer<MavFrame> handler);
}
