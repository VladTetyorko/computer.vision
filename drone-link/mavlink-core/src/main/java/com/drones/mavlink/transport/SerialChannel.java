package com.drones.mavlink.transport;

import java.io.IOException;

/**
 * The byte-level seam {@link SerialLink} polls/writes through — package-private so a unit test
 * (e.g. {@code SerialLinkTest}) can fake a serial port without ever loading the native jSerialComm
 * library, exactly like {@link MavlinkLink} itself lets {@code MavlinkGatewayLinkFailureTest} fake
 * a whole link.
 *
 * <p>Not a bare {@link java.io.InputStream}/{@link java.io.OutputStream} pair: jSerialComm has no
 * {@code Socket.setSoTimeout}-equivalent on the stream it hands back — the read timeout is a
 * property of the underlying {@code SerialPort} object, set via {@code setComPortTimeouts}, not of
 * any one read call. {@link #read} threads the timeout through explicitly instead, so {@link
 * SerialLink#poll} can honor a fresh {@link java.time.Duration} on every call the same way {@code
 * TcpClientLink#poll} does with {@code Socket#setSoTimeout}.
 */
interface SerialChannel extends AutoCloseable {

    /** The stable port name this channel talks to, e.g. {@code "/dev/ttyUSB0"} or {@code "COM3"}. */
    String portDescriptor();

    /**
     * Reads up to {@code buffer.length} bytes, waiting at most {@code timeoutMillis} for at least
     * one to arrive.
     *
     * @return the number of bytes read, {@code 0} on timeout with nothing available (jSerialComm's
     *         semi-blocking read contract — never blocks past {@code timeoutMillis}), or a
     *         negative value if the underlying port has reached end-of-stream (unplugged mid-read)
     */
    int read(byte[] buffer, int timeoutMillis) throws IOException;

    /** Writes {@code len} bytes starting at {@code off} and flushes. Not required to be thread-safe. */
    void write(byte[] data, int off, int len) throws IOException;

    /** Idempotent; never throws. */
    @Override
    void close();
}
