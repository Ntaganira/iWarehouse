-- =====================================================================
-- V28: monthly period close (ACC-10). Months are closed in order, once
-- they have ended and their books are complete (no manual journal
-- waiting, foreign balances revalued); only the latest closed month can
-- be reopened, with a reason. The closed months are therefore always
-- the first ones: nothing is posted on or before the last day of the
-- latest closed month. The application refuses it first; a trigger on
-- journal_entries refuses it too.
-- =====================================================================

CREATE TABLE accounting_periods (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version          BIGINT        NOT NULL DEFAULT 0,
    period_end       DATE          NOT NULL,          -- the month's last day
    status           VARCHAR(10)   NOT NULL,          -- CLOSED, REOPENED
    closed_by        VARCHAR(50)   NOT NULL,
    closed_at        TIMESTAMP     NOT NULL,
    reopened_by      VARCHAR(50),
    reopened_at      TIMESTAMP,
    reopen_reason    VARCHAR(255),
    created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       VARCHAR(50),
    updated_at       TIMESTAMP,
    updated_by       VARCHAR(50),
    CONSTRAINT uk_accounting_periods_end UNIQUE (period_end),
    CONSTRAINT chk_accounting_periods_end CHECK (period_end = (date_trunc('month', period_end) + INTERVAL '1 month - 1 day')::date),
    CONSTRAINT chk_accounting_periods_status CHECK (status IN ('CLOSED', 'REOPENED')),
    CONSTRAINT chk_accounting_periods_reopened CHECK (status = 'CLOSED'
        OR (reopened_by IS NOT NULL AND reopened_at IS NOT NULL AND reopen_reason IS NOT NULL))
);

-- Nothing is posted in a closed month
CREATE OR REPLACE FUNCTION forbid_posting_in_closed_period() RETURNS TRIGGER AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM accounting_periods WHERE status = 'CLOSED' AND period_end >= NEW.entry_date) THEN
        RAISE EXCEPTION 'The books are closed on %: journal % cannot be posted', NEW.entry_date, NEW.number;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_journal_entries_open_period BEFORE INSERT ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_posting_in_closed_period();

-- ---------------------------------------------------------------------
-- Pages, permissions, grants (SRS 2.2: the accountant closes the month;
-- the owner reopens it; the auditor reads)
-- ---------------------------------------------------------------------
INSERT INTO pages (code, name, module, path, icon, sort_order) VALUES
('PERIODS', 'Period Close', 'Accounting', '/accounting/periods', 'lock', 8);

INSERT INTO permissions (code, name, module, action, description) VALUES
('CLOSE_PERIOD',  'Close Periods',  'Accounting', 'CLOSE',  'Close a month: nothing can be posted on it afterwards'),
('REOPEN_PERIOD', 'Reopen Periods', 'Accounting', 'REOPEN', 'Reopen the latest closed month, with a reason');

INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE p.code = 'PERIODS' AND r.code IN ('ADMIN', 'ACCOUNTANT', 'OWNER', 'AUDITOR')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE (p.code = 'CLOSE_PERIOD' AND r.code IN ('ADMIN', 'ACCOUNTANT'))
   OR (p.code = 'REOPEN_PERIOD' AND r.code IN ('ADMIN', 'OWNER'))
ON CONFLICT DO NOTHING;
