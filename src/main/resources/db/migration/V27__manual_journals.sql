-- =====================================================================
-- V27: manual journals (ACC-05). The accountant asks for a journal by
-- account (accruals, bank charges, a vault deposit, import charges paid):
-- it balances and waits for another person's approval, which posts it.
-- A posted journal is never deleted: it is reversed by a new journal on a
-- later day, with a reason. Control accounts (inventory, receivables,
-- payables, GRNI, till cash, driver floats, claims) are posted by their
-- documents only, so their subledgers always match.
-- =====================================================================

CREATE TABLE manual_journals (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version              BIGINT         NOT NULL DEFAULT 0,
    number               VARCHAR(30)    NOT NULL,          -- MJ-WH-2026-000001
    entry_date           DATE           NOT NULL,          -- the journal's date
    description          VARCHAR(255)   NOT NULL,
    total                NUMERIC(18,2)  NOT NULL,          -- the debits, equal to the credits
    status               VARCHAR(20)    NOT NULL,          -- PENDING_APPROVAL, POSTED, REJECTED, CANCELLED, REVERSED
    requested_by_id      BIGINT,
    requested_by         VARCHAR(50)    NOT NULL,
    requested_at         TIMESTAMP      NOT NULL,
    decided_by           VARCHAR(50),                      -- approved, rejected or withdrawn by
    decided_at           TIMESTAMP,
    decision_note        VARCHAR(255),                     -- the reason of a rejection or withdrawal
    journal_id           UUID           REFERENCES journal_entries(id),
    reversal_date        DATE,
    reversal_reason      VARCHAR(255),
    reversed_by          VARCHAR(50),
    reversed_at          TIMESTAMP,
    reversal_journal_id  UUID           REFERENCES journal_entries(id),
    created_at           TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by           VARCHAR(50),
    updated_at           TIMESTAMP,
    updated_by           VARCHAR(50),
    CONSTRAINT uk_manual_journals_number UNIQUE (number),
    CONSTRAINT chk_manual_journals_status CHECK (status IN ('PENDING_APPROVAL', 'POSTED', 'REJECTED', 'CANCELLED', 'REVERSED')),
    CONSTRAINT chk_manual_journals_total CHECK (total > 0),
    CONSTRAINT chk_manual_journals_decided CHECK (status = 'PENDING_APPROVAL' OR (decided_by IS NOT NULL AND decided_at IS NOT NULL)),
    CONSTRAINT chk_manual_journals_note CHECK (status NOT IN ('REJECTED', 'CANCELLED') OR decision_note IS NOT NULL),
    CONSTRAINT chk_manual_journals_posted CHECK ((status IN ('POSTED', 'REVERSED')) = (journal_id IS NOT NULL)),
    CONSTRAINT chk_manual_journals_reversed CHECK ((status = 'REVERSED') = (reversal_journal_id IS NOT NULL)),
    CONSTRAINT chk_manual_journals_reversal CHECK (reversal_journal_id IS NULL OR (reversal_date >= entry_date
        AND reversal_reason IS NOT NULL AND reversed_by IS NOT NULL AND reversed_at IS NOT NULL))
);
CREATE INDEX idx_manual_journals_status ON manual_journals (status, entry_date);

-- Its lines, by account: fixed when it is asked for
CREATE TABLE manual_journal_lines (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    manual_journal_id    UUID           NOT NULL REFERENCES manual_journals(id),
    line_no              INTEGER        NOT NULL,
    account_id           UUID           NOT NULL REFERENCES accounts(id),
    debit                NUMERIC(18,2)  NOT NULL DEFAULT 0,
    credit               NUMERIC(18,2)  NOT NULL DEFAULT 0,
    memo                 VARCHAR(255),
    CONSTRAINT uk_manual_journal_lines_no UNIQUE (manual_journal_id, line_no),
    CONSTRAINT chk_manual_journal_lines_amount CHECK (debit >= 0 AND credit >= 0 AND (debit = 0) <> (credit = 0))
);
CREATE INDEX idx_manual_journal_lines_journal ON manual_journal_lines (manual_journal_id);
CREATE TRIGGER trg_manual_journal_lines_append_only BEFORE UPDATE OR DELETE ON manual_journal_lines
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_modification();

ALTER TABLE journal_entries DROP CONSTRAINT chk_journal_entries_source;
ALTER TABLE journal_entries ADD CONSTRAINT chk_journal_entries_source CHECK (source_type IN ('GOODS_RECEIPT', 'SHIPMENT',
    'CLAIM_OPENED', 'CLAIM_SETTLED', 'CLAIM_REJECTED', 'CUTTING_JOB', 'ADJUSTMENT', 'OPENING_STOCK',
    'SALES_INVOICE', 'TILL_OPENED', 'TILL_CLOSED', 'SALES_DELIVERY', 'SALES_BALANCE', 'CREDIT_NOTE', 'CUSTOMER_PAYMENT',
    'SUPPLIER_INVOICE', 'SUPPLIER_PAYMENT', 'FX_REVALUATION', 'MANUAL_JOURNAL'));

INSERT INTO number_sequences (doc_type, branch_code, prefix, created_by) VALUES
('MANUAL_JOURNAL', 'WH', 'MJ', 'system');

-- ---------------------------------------------------------------------
-- Pages, permissions, grants (SRS 2.2: the accountant asks for and
-- reverses manual journals; the owner approves them; the auditor reads)
-- ---------------------------------------------------------------------
INSERT INTO pages (code, name, module, path, icon, sort_order) VALUES
('MANUAL_JOURNALS', 'Manual Journals', 'Accounting', '/accounting/manual-journals', 'edit', 3);

INSERT INTO permissions (code, name, module, action, description) VALUES
('CREATE_MANUAL_JOURNAL',  'Create Manual Journals',  'Accounting', 'CREATE',  'Ask for a manual journal; another person approves it'),
('APPROVE_MANUAL_JOURNAL', 'Approve Manual Journals', 'Accounting', 'APPROVE', 'Approve (post) or reject a manual journal asked by someone else'),
('REVERSE_MANUAL_JOURNAL', 'Reverse Manual Journals', 'Accounting', 'REVERSE', 'Reverse a posted manual journal, with a reason');

INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE p.code = 'MANUAL_JOURNALS' AND r.code IN ('ADMIN', 'ACCOUNTANT', 'OWNER', 'AUDITOR')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE (p.code IN ('CREATE_MANUAL_JOURNAL', 'REVERSE_MANUAL_JOURNAL') AND r.code IN ('ADMIN', 'ACCOUNTANT'))
   OR (p.code = 'APPROVE_MANUAL_JOURNAL' AND r.code IN ('ADMIN', 'OWNER'))
ON CONFLICT DO NOTHING;
