package com.drones.vision.api.dto;

/**
 * Client&rarr;server {@code /ws/manual-control} frame requesting to take control of an asset
 * (docs/plans/done/RC-CONTROL-PHASE1-PLAN.md §4).
 *
 * @param type    {@code "engage"}
 * @param assetId the asset to take control of, as a canonical UUID string
 */
public record ManualControlEngageRequest(String type, String assetId) {
}
