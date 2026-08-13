package com.drones.vision.perception.domain.model;

/**
 * Encoding of the pixel data carried by a {@link VideoFrame}.
 */
public enum PixelFormat {
    BGR24,
    RGB24,
    YUV420P,
    JPEG,
    H264_PACKET,
    UNKNOWN
}
