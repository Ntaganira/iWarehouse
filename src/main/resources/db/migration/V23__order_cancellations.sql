-- =====================================================================
-- V23: cancelling an order's sizes (POS-08, POS-09). A customer who paid
-- an order (in full or by a deposit) may give up pieces not handed over
-- yet. A credit note of kind CANCEL credits them: the credit reduces the
-- balance due first, the rest is refunded. Pieces not cut come off the
-- draft cutting jobs (a job left empty is cancelled); pieces already cut
-- and reserved for the order are released to stock (RELEASE). Nothing
-- had left stock, so no cost moves.
-- =====================================================================

ALTER TABLE credit_notes ADD COLUMN kind VARCHAR(10) NOT NULL DEFAULT 'RETURN';
ALTER TABLE credit_notes ADD CONSTRAINT chk_credit_notes_kind CHECK (kind IN ('RETURN', 'CANCEL'));

-- A piece cut for the order and released to stock stays where it is
ALTER TABLE credit_note_units DROP CONSTRAINT chk_credit_note_units_outcome;
ALTER TABLE credit_note_units DROP CONSTRAINT chk_credit_note_units_location;
ALTER TABLE credit_note_units ADD CONSTRAINT chk_credit_note_units_outcome CHECK (outcome IN ('RESTOCK', 'CULLET', 'RELEASE'));
ALTER TABLE credit_note_units ADD CONSTRAINT chk_credit_note_units_location CHECK ((outcome = 'RESTOCK' AND location_id IS NOT NULL)
    OR (outcome = 'CULLET' AND location_id IS NULL) OR outcome = 'RELEASE');
