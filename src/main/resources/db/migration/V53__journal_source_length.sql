-- =====================================================================
-- V53: a journal's source names up to 30 characters (PETTY_CASH_REPLENISHMENT, V52, has 24)
-- =====================================================================
ALTER TABLE journal_entries ALTER COLUMN source_type TYPE VARCHAR(30);
