package com.drones.mavlink.api;

import java.util.concurrent.CompletionStage;

/**
 * The broker seam (plan §5.1): the one entry point a Kafka/NATS driving adapter, a REST controller
 * and an in-process caller all submit through identically. {@link #submit} never throws for an
 * unreachable or unknown vehicle — it completes with {@link CommandOutcome.Status#UNREACHABLE}; only
 * a malformed request throws, synchronously, before anything is dispatched.
 */
public interface CommandGateway {

    CompletionStage<CommandOutcome> submit(CommandRequest request);
}
