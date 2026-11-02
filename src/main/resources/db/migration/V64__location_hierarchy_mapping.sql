-- =====================================================================
-- V64: storage sites mapped to the Rwandan administrative hierarchy
--   A site may cover one province, district, sector, cell or village.
--   Zones, racks and slots inherit their site's area; vehicles are not
--   mapped. Multiple sites may share a unit. Existing sites stay
--   unmapped until they are edited, so existing stock history is never
--   changed by the migration.
-- =====================================================================

ALTER TABLE locations ADD COLUMN tier VARCHAR(10);
ALTER TABLE locations ADD COLUMN province_id UUID;
ALTER TABLE locations ADD COLUMN district_id UUID;
ALTER TABLE locations ADD COLUMN sector_id UUID;
ALTER TABLE locations ADD COLUMN cell_id UUID;
ALTER TABLE locations ADD COLUMN village_id UUID;

ALTER TABLE locations ADD CONSTRAINT fk_locations_province
    FOREIGN KEY (province_id) REFERENCES provinces (id) ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE locations ADD CONSTRAINT fk_locations_district
    FOREIGN KEY (district_id) REFERENCES districts (id) ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE locations ADD CONSTRAINT fk_locations_sector
    FOREIGN KEY (sector_id) REFERENCES sectors (id) ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE locations ADD CONSTRAINT fk_locations_cell
    FOREIGN KEY (cell_id) REFERENCES cells (id) ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE locations ADD CONSTRAINT fk_locations_village
    FOREIGN KEY (village_id) REFERENCES villages (id) ON DELETE RESTRICT ON UPDATE CASCADE;

ALTER TABLE locations ADD CONSTRAINT chk_locations_tier
    CHECK (tier IS NULL OR tier IN ('PROVINCE', 'DISTRICT', 'SECTOR', 'CELL', 'VILLAGE'));

ALTER TABLE locations ADD CONSTRAINT chk_locations_area
    CHECK (
        (location_type <> 'SITE'
            AND tier IS NULL AND province_id IS NULL AND district_id IS NULL
            AND sector_id IS NULL AND cell_id IS NULL AND village_id IS NULL)
        OR (location_type = 'SITE' AND (
            (tier IS NULL AND province_id IS NULL AND district_id IS NULL
                AND sector_id IS NULL AND cell_id IS NULL AND village_id IS NULL)
            OR (tier = 'PROVINCE' AND province_id IS NOT NULL AND district_id IS NULL
                AND sector_id IS NULL AND cell_id IS NULL AND village_id IS NULL)
            OR (tier = 'DISTRICT' AND province_id IS NOT NULL AND district_id IS NOT NULL
                AND sector_id IS NULL AND cell_id IS NULL AND village_id IS NULL)
            OR (tier = 'SECTOR' AND province_id IS NOT NULL AND district_id IS NOT NULL
                AND sector_id IS NOT NULL AND cell_id IS NULL AND village_id IS NULL)
            OR (tier = 'CELL' AND province_id IS NOT NULL AND district_id IS NOT NULL
                AND sector_id IS NOT NULL AND cell_id IS NOT NULL AND village_id IS NULL)
            OR (tier = 'VILLAGE' AND province_id IS NOT NULL AND district_id IS NOT NULL
                AND sector_id IS NOT NULL AND cell_id IS NOT NULL AND village_id IS NOT NULL)
        ))
    );

CREATE INDEX idx_locations_tier ON locations (tier);
CREATE INDEX idx_locations_province ON locations (province_id);
CREATE INDEX idx_locations_district ON locations (district_id);
CREATE INDEX idx_locations_sector ON locations (sector_id);
CREATE INDEX idx_locations_cell ON locations (cell_id);
CREATE INDEX idx_locations_village ON locations (village_id);
