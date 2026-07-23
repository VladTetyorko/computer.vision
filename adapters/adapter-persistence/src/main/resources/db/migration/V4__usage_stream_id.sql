-- docs/MVP2-PLAN.md P-b's replay follow-up, R-a2: AssetUsage (vision-domain) gained a nullable
-- streamId, recorded once at usage-open time (UsageTracker, vision-application) so a finished
-- flight's replay timeline can join its detections (DetectionRepositoryPort, keyed by stream_id)
-- back to the usage they belong to, instead of every usage's detections[] being unconditionally
-- empty (see ReplayService/DefaultReplayService, vision-application).
--
-- Purely additive: every row written before this migration reads back stream_id = NULL, exactly
-- the "legacy/streamless usage" case AssetUsage's own javadoc already documents as honest, not an
-- error. No backfill is possible or attempted -- a stream's id was never recorded anywhere prior
-- to this change, so there is nothing to derive old rows' stream_id from.

ALTER TABLE asset_usages ADD COLUMN stream_id UUID;
