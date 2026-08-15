package com.drones.mavlink.session;

/** A live {@link Dispatcher#subscribe} registration. */
public interface Subscription {

    /** Stops delivering frames to this subscription's handler. Idempotent. */
    void close();
}
