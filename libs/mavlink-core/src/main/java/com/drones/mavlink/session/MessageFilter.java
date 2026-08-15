package com.drones.mavlink.session;

import com.drones.mavlink.SysId;
import com.drones.mavlink.codec.MavFrame;

import java.util.List;
import java.util.Objects;

/**
 * A predicate a {@link Dispatcher} subscription tests every decoded frame against. The statics
 * cover the filters a MAVLink microservice actually needs; a caller with a bespoke need can still
 * implement this interface directly (it is a single abstract method).
 */
public interface MessageFilter {

    boolean test(MavFrame frame);

    /** Matches every frame. */
    static MessageFilter any() {
        return frame -> true;
    }

    /** Matches frames whose wire message id equals {@code id}. */
    static MessageFilter messageId(int id) {
        return frame -> frame.header().messageId() == id;
    }

    /** Matches frames whose payload is an instance of {@code type}. */
    static MessageFilter type(Class<?> type) {
        Objects.requireNonNull(type, "type");
        return frame -> frame.is(type);
    }

    /** Matches frames whose origin system equals {@code system}. */
    static MessageFilter fromSystem(SysId system) {
        Objects.requireNonNull(system, "system");
        return frame -> frame.header().system().equals(system);
    }

    /** Matches frames every one of {@code filters} matches (short-circuits on the first miss). */
    static MessageFilter and(MessageFilter... filters) {
        Objects.requireNonNull(filters, "filters");
        List<MessageFilter> copy = List.of(filters); // List.of rejects null elements
        return frame -> {
            for (MessageFilter filter : copy) {
                if (!filter.test(frame)) {
                    return false;
                }
            }
            return true;
        };
    }
}
