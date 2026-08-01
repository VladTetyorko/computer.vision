package com.drones.vision.api.exceptions;

import com.drones.vision.api.AssetImageController;

/**
 * Thrown by {@link AssetImageController} when an uploaded image body exceeds the configured
 * maximum (CONTRACT 2, docs/UX-REWORK-PLAN.md §U-d item 3). Mapped to {@code 413} by {@link
 * ApiExceptionHandler} — distinct from {@link IllegalArgumentException} (400), since the request
 * is otherwise well-formed, just too big.
 */
public class PayloadTooLargeException extends RuntimeException {

    public PayloadTooLargeException(String message) {
        super(message);
    }
}
