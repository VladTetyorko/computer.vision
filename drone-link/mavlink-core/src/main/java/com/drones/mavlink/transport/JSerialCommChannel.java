package com.drones.mavlink.transport;

import com.fazecast.jSerialComm.SerialPort;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Production {@link SerialChannel}: one already-{@link SerialPort#openPort() opened} jSerialComm
 * port. {@link #open} is the only place this module calls into jSerialComm's port-enumeration/open
 * API — every other class talks to the {@link SerialChannel} seam instead, so a native-library
 * failure (port busy, permission denied, driver missing) surfaces as a plain {@link IOException}
 * from one place.
 *
 * <p>{@link SerialPort#setComPortTimeouts} configures the port, not one read call — {@link #read}
 * only re-issues it when the requested timeout actually changed from the last call, since jNI calls
 * into the native layer are not free and {@link SerialLink#poll} is typically called in a tight
 * loop with the same session-wide timeout every time.
 */
final class JSerialCommChannel implements SerialChannel {

    private final SerialPort port;
    private final InputStream in;
    private final OutputStream out;
    private final String descriptor;
    private volatile int currentReadTimeoutMillis = -1;

    /** Opens {@code systemPortName} (e.g. {@code "/dev/ttyUSB0"}) at {@code baudRate}, 8N1. */
    static JSerialCommChannel open(String systemPortName, int baudRate) throws IOException {
        SerialPort port = SerialPort.getCommPort(systemPortName);
        port.setBaudRate(baudRate);
        port.setNumDataBits(8);
        port.setNumStopBits(SerialPort.ONE_STOP_BIT);
        port.setParity(SerialPort.NO_PARITY);
        if (!port.openPort()) {
            throw new IOException("Failed to open serial port " + systemPortName + " at " + baudRate + " baud");
        }
        return new JSerialCommChannel(port);
    }

    private JSerialCommChannel(SerialPort port) {
        this.port = port;
        this.descriptor = port.getSystemPortName();
        this.in = port.getInputStream();
        this.out = port.getOutputStream();
    }

    @Override
    public String portDescriptor() {
        return descriptor;
    }

    @Override
    public int read(byte[] buffer, int timeoutMillis) throws IOException {
        if (timeoutMillis != currentReadTimeoutMillis) {
            port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING | SerialPort.TIMEOUT_WRITE_BLOCKING,
                    timeoutMillis, 0);
            currentReadTimeoutMillis = timeoutMillis;
        }
        return in.read(buffer);
    }

    @Override
    public void write(byte[] data, int off, int len) throws IOException {
        out.write(data, off, len);
        out.flush();
    }

    @Override
    public void close() {
        try {
            in.close();
        } catch (IOException ignored) {
            // best-effort -- the port is going away either way
        }
        try {
            out.close();
        } catch (IOException ignored) {
            // best-effort -- the port is going away either way
        }
        port.closePort();
    }
}
