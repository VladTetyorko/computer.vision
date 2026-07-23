-- Same default category set InMemoryCategoryRepository seeds in its
-- constructor (vision-app/devsupport), so a persistence-enabled app has the
-- same out-of-the-box categories as the in-memory fallback. Two batches so
-- parent rows exist before their children (fpv-drone -> drone, esp32-cam ->
-- ip-camera) satisfy the self-referencing foreign key.

INSERT INTO categories (id, name, parent_id, attribute_hints) VALUES
    ('drone', 'Drone', NULL, '["model","range-km","max-altitude-m","weight-kg"]'::jsonb),
    ('ip-camera', 'IP Camera', NULL, '["ip-address","resolution","onvif-profile"]'::jsonb),
    ('usb-camera', 'USB Camera', NULL, '["device-path","resolution"]'::jsonb),
    ('robot', 'Robot', NULL, '["wheelbase-m","max-speed-mps","payload-kg"]'::jsonb),
    ('simulated', 'Simulated', NULL, '["scenario","seed"]'::jsonb)
ON CONFLICT (id) DO NOTHING;

INSERT INTO categories (id, name, parent_id, attribute_hints) VALUES
    ('fpv-drone', 'FPV Drone', 'drone', '["frame-size-in","vtx-power-mw","flight-controller"]'::jsonb),
    ('esp32-cam', 'ESP32-CAM', 'ip-camera', '["firmware-version","wifi-ssid"]'::jsonb)
ON CONFLICT (id) DO NOTHING;
