-- =====================================================================
-- V15: Location labels (SRS 4.2: MD-02)
--
-- Racks and slots get a printed label: a QR code of the location code,
-- scanned to say where glass goes (transfers), where a count is (stock
-- counts) and what a rack holds (stock search). Once a label is printed
-- the code is fixed: a renamed rack would no longer match its label.
-- The first print is kept; reprints change nothing. Sites and zones get
-- no label.
-- =====================================================================

ALTER TABLE locations
    ADD COLUMN label_printed_at TIMESTAMP,
    ADD COLUMN label_printed_by VARCHAR(50);

ALTER TABLE locations ADD CONSTRAINT chk_locations_label CHECK (
    (label_printed_at IS NULL AND label_printed_by IS NULL)
    OR (label_printed_at IS NOT NULL AND location_type IN ('RACK', 'SLOT')));

-- The code of a labelled location never changes, and a print is never undone.
CREATE OR REPLACE FUNCTION forbid_labelled_code_change() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.label_printed_at IS NOT NULL AND NEW.code <> OLD.code THEN
        RAISE EXCEPTION 'Location % has a printed label: its code is fixed', OLD.code;
    END IF;
    IF OLD.label_printed_at IS NOT NULL AND NEW.label_printed_at IS DISTINCT FROM OLD.label_printed_at THEN
        RAISE EXCEPTION 'Location %: the first label print is kept', OLD.code;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_locations_labelled_code
    BEFORE UPDATE OF code, label_printed_at ON locations
    FOR EACH ROW EXECUTE FUNCTION forbid_labelled_code_change();
