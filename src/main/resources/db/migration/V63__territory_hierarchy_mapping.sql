-- =====================================================================
-- V63: territories mapped to the Rwandan administrative hierarchy
--   A territory covers one province, district, sector, cell or village
--   (BP 6, Res. 7). The unit is fixed once the territory is used, and
--   one territory covers a given unit. Existing territories stay
--   unmapped until they are edited, so customer and order history is
--   never changed by the migration.
-- =====================================================================

ALTER TABLE territories ADD COLUMN tier VARCHAR(10);
ALTER TABLE territories ADD COLUMN province_id UUID;
ALTER TABLE territories ADD COLUMN district_id UUID;
ALTER TABLE territories ADD COLUMN sector_id UUID;
ALTER TABLE territories ADD COLUMN cell_id UUID;
ALTER TABLE territories ADD COLUMN village_id UUID;

ALTER TABLE territories ADD CONSTRAINT fk_territories_province
    FOREIGN KEY (province_id) REFERENCES provinces (id) ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE territories ADD CONSTRAINT fk_territories_district
    FOREIGN KEY (district_id) REFERENCES districts (id) ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE territories ADD CONSTRAINT fk_territories_sector
    FOREIGN KEY (sector_id) REFERENCES sectors (id) ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE territories ADD CONSTRAINT fk_territories_cell
    FOREIGN KEY (cell_id) REFERENCES cells (id) ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE territories ADD CONSTRAINT fk_territories_village
    FOREIGN KEY (village_id) REFERENCES villages (id) ON DELETE RESTRICT ON UPDATE CASCADE;

ALTER TABLE territories ADD CONSTRAINT chk_territories_tier
    CHECK (tier IS NULL OR tier IN ('PROVINCE', 'DISTRICT', 'SECTOR', 'CELL', 'VILLAGE'));

ALTER TABLE territories ADD CONSTRAINT chk_territories_area
    CHECK (
        (tier IS NULL AND province_id IS NULL AND district_id IS NULL AND sector_id IS NULL
            AND cell_id IS NULL AND village_id IS NULL)
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
    );

CREATE INDEX idx_territories_tier ON territories (tier);
CREATE UNIQUE INDEX uk_territories_province ON territories (province_id) WHERE province_id IS NOT NULL;
CREATE UNIQUE INDEX uk_territories_district ON territories (district_id) WHERE district_id IS NOT NULL;
CREATE UNIQUE INDEX uk_territories_sector ON territories (sector_id) WHERE sector_id IS NOT NULL;
CREATE UNIQUE INDEX uk_territories_cell ON territories (cell_id) WHERE cell_id IS NOT NULL;
CREATE UNIQUE INDEX uk_territories_village ON territories (village_id) WHERE village_id IS NOT NULL;
