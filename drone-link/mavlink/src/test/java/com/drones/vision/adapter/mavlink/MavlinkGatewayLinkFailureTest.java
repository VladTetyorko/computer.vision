package com.drones.vision.adapter.mavlink;

import com.drones.mavlink.transport.ByteChunk;
import com.drones.mavlink.transport.CarrierKind;
import com.drones.mavlink.transport.LinkDescriptor;
import com.drones.mavlink.transport.LinkId;
import com.drones.mavlink.transport.LinkPeer;
import com.drones.mavlink.transport.MavlinkLink;
import com.drones.mavlink.transport.SerialRole;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.Telemetry;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FLEET-RADIO-PLAN.md F7/D5, exercised through {@link MavlinkGateway}'s real production wiring
 * (a hand-built {@link MavlinkLink} is {@link MavlinkGateway#register} registered in place of a
 * real {@code UdpListenLink}, but everything downstream of that -- {@link
 * com.drones.mavlink.session.MavlinkSession}, {@link VehicleClaimPolicy}, this class's own {@code
 * handleLinkFailure} -- is the exact same code path production traffic runs through).
 *
 * <p>Before R4, a genuine socket failure was swallowed by {@code MavlinkSession}'s reader thread
 * (a WARNING log, then silent return) and every registered device's {@link SubmissionPublisher}
 * was simply abandoned -- neither completed nor errored, indistinguishable from a vehicle that had
 * merely gone quiet. These tests pin the two-sided fix: a genuine failure closes every registered
 * publisher <b>exceptionally</b> (D5) and promptly, while an ordinary, intentional teardown never
 * does (shutdown must not masquerade as a link failure).
 */
class MavlinkGatewayLinkFailureTest {

    @Test
    void aGenuineLinkFailureClosesEveryRegisteredPublisherExceptionallyAndPromptly() throws Exception {
        FailingLink link = new FailingLink();
        MavlinkGateway gateway = new MavlinkGateway(MavlinkSettings.defaults());
        gateway.register(link, new LinkDescriptor(CarrierKind.SERIAL, SerialRole.NONE, "failing-test-link", 0));
        try {
            SubmissionPublisher<Telemetry> publisher = new SubmissionPublisher<>();
            CountDownLatch errored = new CountDownLatch(1);
            AtomicReference<Throwable> receivedError = new AtomicReference<>();
            publisher.subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    subscription.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(Telemetry item) {
                }

                @Override
                public void onError(Throwable throwable) {
                    receivedError.set(throwable);
                    errored.countDown();
                }

                @Override
                public void onComplete() {
                }
            });
            gateway.register(DeviceId.random(), null, publisher);

            long start = System.nanoTime();
            link.fail(); // simulates the socket dying -- a genuine poll() IOException

            assertTrue(errored.await(5, TimeUnit.SECONDS), "a genuine link failure must close the publisher "
                    + "exceptionally, not leave it abandoned or complete it normally");
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();
            assertTrue(elapsedMillis < 5000, "must be reported promptly, within the link-failure grace "
                    + "window, not only once some unrelated silence timeout would eventually have noticed");
            assertInstanceOf(IOException.class, receivedError.get(),
                    "the reported failure must be the real IOException, not a generic wrapper");
        } finally {
            gateway.close();
        }
    }

    @Test
    void anOrdinaryUnregisterNeverClosesThePublisherExceptionally() {
        FailingLink link = new FailingLink(); // never told to fail
        MavlinkGateway gateway = new MavlinkGateway(MavlinkSettings.defaults());
        gateway.register(link, new LinkDescriptor(CarrierKind.SERIAL, SerialRole.NONE, "failing-test-link", 0));
        SubmissionPublisher<Telemetry> publisher = new SubmissionPublisher<>();
        AtomicReference<Throwable> receivedError = new AtomicReference<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(Telemetry item) {
            }

            @Override
            public void onError(Throwable throwable) {
                receivedError.set(throwable);
            }

            @Override
            public void onComplete() {
            }
        });
        VehicleRegistration registration = gateway.register(DeviceId.random(), null, publisher);

        // unregister()/close() join the reader thread synchronously (bounded by closeJoinTimeout)
        // before returning, so by the time this call returns, an intentional teardown has already
        // fully run its course -- no sleep/poll needed to observe its absence of effect.
        gateway.unregister(registration);

        assertNull(receivedError.get(), "an orderly unregister/close must never close a publisher "
                + "exceptionally -- shutdown must not masquerade as a link failure");
    }

    /**
     * A conforming {@link MavlinkLink}: {@link #poll} behaves exactly like a real link that simply
     * has nothing to say yet (returns {@code null} on every timeout) until {@link #fail()} is
     * called, at which point the next {@code poll()} call throws a genuine {@link IOException} --
     * modeling a socket that dies mid-flight, without needing to sabotage a real one via reflection.
     */
    private static final class FailingLink implements MavlinkLink {
        private final LinkId id = new LinkId("failing-test-link");
        private final CountDownLatch failGate = new CountDownLatch(1);
        private final IOException failure = new IOException("simulated read failure");

        @Override
        public LinkId id() {
            return id;
        }

        @Override
        public boolean preservesMessageBoundaries() {
            return true;
        }

        @Override
        public ByteChunk poll(Duration timeout) throws IOException {
            try {
                if (failGate.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw failure;
                }
                return null; // ordinary timeout, exactly like a link with nothing to say yet
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        @Override
        public void send(byte[] frame, int off, int len, LinkPeer target) {
            // not exercised by these tests
        }

        @Override
        public LinkPeer defaultTarget() {
            return LinkPeer.NONE;
        }

        @Override
        public void close() {
            // deliberately independent of failGate -- this test controls exactly when poll() fails
        }

        void fail() {
            failGate.countDown();
        }
    }
}
