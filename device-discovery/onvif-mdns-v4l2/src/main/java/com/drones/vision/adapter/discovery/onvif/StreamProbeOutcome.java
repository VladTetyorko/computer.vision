package com.drones.vision.adapter.discovery.onvif;

import java.net.URI;
import java.time.Duration;

/**
 * Outcome of {@link OnvifDeviceClient#probeStream(URI, Duration)}: either a playable stream URI
 * was negotiated, the device demanded credentials this scanner does not have, or nothing usable
 * came back (timeout, network failure, malformed response, no Media capability, no profiles, ...).
 * The last two cases never carry a guessed URL — {@link OnvifWsDiscoveryScanner} folds {@link
 * Unavailable} into a candidate with {@code suggestedStream = null} exactly as before this class
 * existed, and {@link AuthRequired} into one with an honest {@code details["note"]} instead.
 */
sealed interface StreamProbeOutcome {

    /** A playable RTSP stream URI was negotiated over anonymous SOAP. */
    record Found(URI uri) implements StreamProbeOutcome {
    }

    /** The device answered HTTP 401 or a SOAP auth fault at some step of the chain. */
    record AuthRequired() implements StreamProbeOutcome {
    }

    /** Timeout, network failure, or a malformed/incomplete response — not an auth signal. */
    record Unavailable() implements StreamProbeOutcome {
    }
}
