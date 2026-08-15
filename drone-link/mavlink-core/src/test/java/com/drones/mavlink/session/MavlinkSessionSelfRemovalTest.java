package com.drones.mavlink.session;

import com.drones.mavlink.CompId;
import com.drones.mavlink.SysId;
import com.drones.mavlink.codec.FrameWriter;
import com.drones.mavlink.config.MavlinkCoreSettings;
import com.drones.mavlink.transport.LinkId;
import com.drones.mavlink.transport.UdpListenLink;
import com.drones.mavlink.transport.UdpTargetLink;

import io.dronefleet.mavlink.common.CommandLong;
import io.dronefleet.mavlink.common.MavCmd;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@link Dispatcher} handler runs on the RX thread that decoded the frame (this class's own
 * javadoc). If that handler synchronously calls {@link MavlinkSession#removeLink}/{@link
 * MavlinkSession#close} on its own link, {@code LinkRuntime.stop()} must not join that very thread
 * against itself and stall for {@code closeJoinTimeout} -- see {@code MavlinkSocketHub#shutdown}'s
 * own {@code thread != Thread.currentThread()} guard, which this class mirrors.
 */
class MavlinkSessionSelfRemovalTest {

    @Test
    void aHandlerRemovingItsOwnLinkFromTheRxThreadReturnsPromptly() throws Exception {
        // A long closeJoinTimeout so a self-join bug would make this test obviously slow/hang
        // rather than pass by accident within a short bound.
        MavlinkCoreSettings settings = MavlinkCoreSettings.defaults().withCloseJoinTimeout(Duration.ofSeconds(10));

        try (UdpListenLink listenLink = new UdpListenLink("127.0.0.1", 0)) {
            MavlinkSession session = new MavlinkSession(MavlinkNode.groundStation(), settings);
            try {
                LinkId linkId = listenLink.id();
                session.addLink(listenLink);

                CountDownLatch handlerRan = new CountDownLatch(1);
                session.dispatcher().subscribe(MessageFilter.any(), frame -> {
                    session.removeLink(linkId); // synchronously, from the RX thread itself
                    handlerRan.countDown();
                });

                int port = Integer.parseInt(linkId.value().substring(linkId.value().lastIndexOf(':') + 1));
                try (UdpTargetLink sender = new UdpTargetLink("127.0.0.1", port)) {
                    FrameWriter writer = new FrameWriter(new SysId(9), new CompId(1));
                    writer.addLink(sender);
                    CommandLong command = CommandLong.builder()
                            .targetSystem(1).targetComponent(1)
                            .command(MavCmd.MAV_CMD_DO_SET_MODE).confirmation(0).build();
                    writer.broadcast(command, sender.id());

                    long start = System.nanoTime();
                    assertTrue(handlerRan.await(2, TimeUnit.SECONDS), "the handler must have run");
                    long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();

                    assertTrue(elapsedMillis < 2000,
                            "removeLink called from the RX thread itself must not stall for closeJoinTimeout, "
                                    + "took " + elapsedMillis + "ms");
                }
            } finally {
                session.close();
            }
        }
    }
}
