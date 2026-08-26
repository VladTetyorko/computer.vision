-- Gives a device an origin: LIVE (real hardware) or SIMULATED (a synthetic stand-in), independent
-- of the asset it belongs to (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md R4). Previously
-- "simulated" was only representable as a whole-asset category, so an operator could not fit a
-- synthetic camera onto an otherwise-real vehicle ("the drone has no camera yet").
--
-- Defaults every existing row to LIVE, then backfills the devices that actually belong to an asset
-- seeded/created under the 'simulated' category (see V2__seed_categories.sql) to SIMULATED, so those
-- pre-existing synthetic devices are not silently reclassified as real and DefaultSimulationService's
-- resumeAll() (which now filters on origin, not category) keeps recognizing them after this deploy.
ALTER TABLE devices ADD COLUMN origin VARCHAR(32) NOT NULL DEFAULT 'LIVE';

UPDATE devices
SET origin = 'SIMULATED'
WHERE id IN (
    SELECT ad.device_id
    FROM asset_devices ad
    JOIN assets a ON a.id = ad.asset_id
    WHERE a.category_id = 'simulated'
);
