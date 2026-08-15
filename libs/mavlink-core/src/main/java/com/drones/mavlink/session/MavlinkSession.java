package com.drones.mavlink.session;

import com.drones.mavlink.codec.FrameReader;
import com.drones.mavlink.codec.FrameSink;
import com.drones.mavlink.codec.FrameWriter;
import com.drones.mavlink.codec.MavFrame;
import com.drones.mavlink.config.MavlinkCoreSettings;
import com.drones.mavlink.transport.ByteChunk;
import com.drones.mavlink.transport.LinkId;
import com.drones.mavlink.transport.MavlinkLink;

import java.io.IOException;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The L3 composition root: one reader thread per registered {@link MavlinkLink}, driving that
 * link's own {@link FrameReader} and, for every frame it decodes, updating {@link PeerDirectory}
 * and {@link LinkHealth}, then offering the frame to {@link Correlator} and finally to
 * {@link Dispatcher} — in that fixed order, never any other. A waiter registered with
 * {@link Correlator#await} must never miss a reply because a slow {@link Dispatcher} handler ran
 * first, which is exactly why {@code Correlator} is offered the frame before {@code Dispatcher} is.
 *
 * <h2>Ownership</h2>
 * A {@code MavlinkSession} does not own the {@link MavlinkLink}s registered with it — callers open
 * and close them; {@link #removeLink} and {@link #close} stop <i>processing</i> a link (its reader
 * thread exits) without closing the link itself, mirroring {@link FrameWriter#removeLink}'s own
 * "caller owns that" convention. A registered link's send side <i>is</i> owned here: {@link #close}
 * also unregisters every link from the internal {@link FrameWriter}.
 *
 * <h2>Threading</h2>
 * {@link #addLink}/{@link #removeLink}/{@link #close} are guarded by one private monitor so they
 * are safe against each other and against the per-link reader threads they start/stop — the same
 * "one lock protects the registration table" discipline {@code adapter-mavlink}'s
 * {@code VehicleClaimRegistry} uses. Each reader thread (named {@code mavlink-rx-<linkId>}) is the
 * sole caller of {@link #onFrame} for its own link; {@link PeerDirectory}, {@link LinkHealth},
 * {@link Correlator} and {@link Dispatcher} are all independently safe for concurrent callers from
 * several such threads (a session may register more than one link). {@link #close} is idempotent
 * and joins every reader thread bounded by {@link MavlinkCoreSettings#closeJoinTimeout()}.
 *
 * <p>A reader thread never blocks indefinitely: {@link MavlinkLink#poll} is called with a short,
 * fixed internal timeout ({@link #POLL_TIMEOUT}, not a spec/config value — purely how often the
 * loop re-checks whether it should stop), so a link that never delivers anything still lets
 * {@link #removeLink}/{@link #close} return promptly instead of wedging on a join.
 */
public final class MavlinkSession implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(MavlinkSession.class.getName());

    /** How often a reader thread re-checks for shutdown between {@link MavlinkLink#poll} calls. */
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(200);

    private final MavlinkCoreSettings settings;
    private final DefaultPeerDirectory peerDirectory = new DefaultPeerDirectory();
    private final DefaultLinkHealth linkHealth;
    private final DefaultDispatcher dispatcher = new DefaultDispatcher();
    private final DefaultCorrelator correlator = new DefaultCorrelator();
    private final FrameWriter frameWriter;
    private final RoutingFrameSink sink;

    private final Object lock = new Object();
    private final Map<LinkId, LinkRuntime> links = new HashMap<>(); // guarded by lock
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public MavlinkSession(MavlinkNode self, MavlinkCoreSettings settings) {
        Objects.requireNonNull(self, "self");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.linkHealth = new DefaultLinkHealth(settings.peerTimeout());
        this.frameWriter = new FrameWriter(self.system(), self.component());
        this.sink = new RoutingFrameSink(peerDirectory, frameWriter);
    }

    /** Registers {@code link} and starts its dedicated reader thread. The session does not close it. */
    public void addLink(MavlinkLink link) {
        Objects.requireNonNull(link, "link");
        synchronized (lock) {
            if (closed.get()) {
                throw new IllegalStateException("MavlinkSession is closed");
            }
            if (links.containsKey(link.id())) {
                throw new IllegalArgumentException("Link " + link.id() + " is already registered");
            }
            frameWriter.addLink(link);
            LinkRuntime runtime = new LinkRuntime(link);
            links.put(link.id(), runtime);
            runtime.start();
        }
    }

    /** Stops processing {@code id}'s link (bounded join) and unregisters it from the sender. Idempotent. */
    public void removeLink(LinkId id) {
        Objects.requireNonNull(id, "id");
        LinkRuntime runtime;
        synchronized (lock) {
            runtime = links.remove(id);
        }
        if (runtime != null) {
            frameWriter.removeLink(id);
            runtime.stop();
        }
    }

    public PeerDirectory peers() {
        return peerDirectory;
    }

    public Dispatcher dispatcher() {
        return dispatcher;
    }

    public Correlator correlator() {
        return correlator;
    }

    public FrameSink sink() {
        return sink;
    }

    public LinkHealth health() {
        return linkHealth;
    }

    /** Idempotent. Stops every reader thread (bounded join) and unregisters every link from the sender. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List<LinkRuntime> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<>(links.values());
            links.clear();
        }
        for (LinkRuntime runtime : snapshot) {
            frameWriter.removeLink(runtime.link.id());
            runtime.stop();
        }
    }

    /** The fixed per-frame order this class exists to guarantee — see the class javadoc. */
    private void onFrame(MavFrame frame) {
        peerDirectory.recordFrame(frame);
        linkHealth.recordFrame(frame);
        correlator.offer(frame);
        dispatcher.dispatch(frame);
    }

    private final class LinkRuntime {
        private final MavlinkLink link;
        private final FrameReader reader;
        private final Thread thread;
        private final AtomicBoolean running = new AtomicBoolean(true);

        LinkRuntime(MavlinkLink link) {
            this.link = link;
            this.reader = new FrameReader(link, settings);
            this.thread = new Thread(this::runLoop, "mavlink-rx-" + link.id().value());
            this.thread.setDaemon(true);
        }

        void start() {
            thread.start();
        }

        void stop() {
            running.set(false);
            // A Dispatcher handler may itself call removeLink/close on its own link's frame,
            // synchronously, from this very reader thread (e.g. reacting to a link-error message).
            // Joining self would not deadlock forever (join(millis) always returns after the bound)
            // but would needlessly stall this thread for up to closeJoinTimeout -- skip it, matching
            // MavlinkSocketHub#shutdown's own "thread != Thread.currentThread()" guard.
            if (thread == Thread.currentThread()) {
                return;
            }
            try {
                thread.join(settings.closeJoinTimeout().toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void runLoop() {
            while (running.get()) {
                try {
                    ByteChunk chunk = link.poll(POLL_TIMEOUT);
                    if (chunk == null) {
                        continue; // timeout, or the caller closed the link -- either way, re-check running
                    }
                    reader.offer(chunk, MavlinkSession.this::onFrame);
                } catch (IOException e) {
                    LOG.log(Level.WARNING, "Read error on link " + link.id() + "; its reader thread is stopping", e);
                    return;
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "Unexpected error processing a frame on link " + link.id(), e);
                }
            }
        }
    }
}
