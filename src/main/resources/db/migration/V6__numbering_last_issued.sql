-- =====================================================================
-- V6: When each numbering sequence last issued a number (MD-07).
-- NULL = never used: the reset policy may still change and a starting
-- number set by hand is kept for the first document. Once a number has
-- been issued the reset policy is fixed, so numbers never repeat.
-- period_key stays '' until the first number of a yearly/monthly sequence.
-- =====================================================================

ALTER TABLE number_sequences ADD COLUMN last_issued_at TIMESTAMP;
