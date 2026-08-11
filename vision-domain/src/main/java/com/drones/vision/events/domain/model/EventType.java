package com.drones.vision.events.domain.model;

/**
 * Kind of semantic occurrence carried by an {@link Event}.
 */
public enum EventType {
    DETECTION,
    DEVICE_ONLINE,
    DEVICE_OFFLINE,
    STREAM_STARTED,
    STREAM_STOPPED,
    PIPELINE_ERROR,
    TRAINING,
    GEOFENCE_BREACH
}
