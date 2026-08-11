-- docs/plans/done/FC-INTEGRATIONS-PLAN.md F-b: flight-controller-reported state (FlightState) alongside each
-- telemetry sample. Nullable -- most existing rows pre-date this column, and plenty of devices
-- (simulated/mjpeg/rtsp-only sources with no flight controller) never report one at all; both
-- cases round-trip as Telemetry#flightState() == null, same "unknown, not fabricated" discipline
-- as every other nullable column in this table.
ALTER TABLE telemetry_samples
    ADD COLUMN flight_state JSONB;
