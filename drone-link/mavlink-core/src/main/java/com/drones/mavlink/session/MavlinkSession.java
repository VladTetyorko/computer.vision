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
import java.util.function.BiConsumer;

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

    /**
     * {@code volatile}, not guarded by {@link #lock}: read from whichever reader thread just hit a
     * genuine I/O failure, written (rarely, typically once) from whatever thread wires this session
     * up. A plain no-op default (never {@code null} — CLAUDE.md's "no parameter means off by being
     * null" rule) means a session nobody has called {@link #onLinkFailure} on behaves exactly as
     * before this field existed.
     */
    private volatile BiConsumer<LinkId, IOException> linkFailureListener = (id, cause) -> { };

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

    /**
     * Registers the listener a reader thread invokes when {@link MavlinkLink#poll} throws — a
     * genuine I/O failure, not a timeout or an intentional close (see that method's own contract),
     * and not this session winding down (see below) — instead of {@code runLoop}'s previous behavior
     * of logging a WARNING and silently returning (FLEET-RADIO-PLAN.md F7: "the silent death"). At
     * most one listener is meaningful per session; a second call replaces the first rather than
     * chaining, matching every other single-collaborator setter in this codebase.
     *
     * <h2>Contract the listener must honor</h2>
     * <b>Called synchronously from the dying reader thread, inside the same {@code catch} block that
     * is about to let that thread exit.</b> The listener must not block — this thread is already
     * unwinding, and a hang here means it never actually exits — and must not throw — any
     * {@link RuntimeException} it raises is caught and logged by this class rather than propagating,
     * so a buggy listener cannot itself become a second silent-death path. Do the real work
     * (deciding what "this link is dead" means for a caller — e.g. closing affected publishers
     * exceptionally, D5) on whatever thread the listener hands off to; do not do it inline unless it
     * is already non-blocking.
     *
     * <h2>Shutdown must not masquerade as failure</h2>
     * A link intentionally closed while this session (or the caller) is shutting down must not be
     * reported as a failure — an orderly {@link #close()}/{@link #removeLink} is not "the radio
     * died". {@code runLoop} checks the same {@link LinkRuntime#running} flag {@link
     * LinkRuntime#stop()} already sets before ever notifying, rather than inventing a second
     * "are we shutting down" signal: {@code running} already answers exactly that question, and it
     * is set (by {@code stop()}) <i>before</i> the link is closed, so by the time an intentional
     * close makes {@link MavlinkLink#poll} throw — if a particular {@link MavlinkLink} ever did that
     * for a close, rather than honoring its own null-on-close contract — {@code running} already
     * reads {@code false} and this path stays silent, matching a conforming link's own already-silent
     * behavior on close.
     */
    public void onLinkFailure(BiConsumer<LinkId, IOException> listener) {
        this.linkFailureListener = Objects.requireNonNull(listener, "listener");
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
                    // running.get() distinguishes a genuine failure from stop() having already fired
                    // (see onLinkFailure's javadoc, "shutdown must not masquerade as failure") --
                    // reusing this flag rather than adding a second one, per this wave's own brief.
                    if (running.get()) {
                        notifyLinkFailure(e);
                    }
                    return;
                } catch (RuntimeException e) {
                    // Deliberately does NOT notify the link-failure listener (FLEET-RADIO-PLAN.md R4):
                    // this branch means one frame's processing blew up (a decoder bug, a malformed
                    // buffer FrameReader itself did not resync past) while the link and the socket
                    // underneath it are still perfectly healthy -- the loop does not even return, it
                    // keeps polling the same link. Firing a listener whose whole contract is "this
                    // link is dead, go close things" here would tear down live publishers over a
                    // transient, unrelated bug that has nothing to do with the radio.
                    LOG.log(Level.WARNING, "Unexpected error processing a frame on link " + link.id(), e);
                }
            }
        }

        /** See {@link MavlinkSession#onLinkFailure}'s javadoc for the contract this call site honors. */
        private void notifyLinkFailure(IOException cause) {
            try {
                linkFailureListener.accept(link.id(), cause);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Link-failure listener threw for link " + link.id(), e);
            }
        }
    }
}
