package com.drones.vision.api.dto;

import com.drones.vision.api.exception.ApiExceptionHandler;

/**
 * Uniform error body returned by {@code com.drones.vision.api.exception.ApiExceptionHandler}.
 *
 * @param error   short machine-readable error code (e.g. {@code "NOT_FOUND"})
 * @param message human-readable detail, taken from the mapped exception's message
 */
public record ErrorResponse(String error, String message) {
}
