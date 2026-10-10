-- =====================================================================
-- V34: EBM fiscal signing through RRA's VSDC (TAX-02, TAX-03, POS-07,
-- AT-09), RRA VSDC API v1.0.5.
-- Every issued invoice is a sale receipt and every credit note a refund
-- receipt, queued in the transaction that issues it (ebm_receipts) with
-- its own EBM invoice number (invcNo, never reset). Signing happens after
-- the commit and is retried with backoff while the VSDC is unreachable,
-- so a sale completes when EBM is down. A signed receipt keeps what the
-- VSDC returned (receipt numbers, internal data, signature, SDC ID, MRC)
-- and never changes; the first print is the original, later ones copies.
-- ebm_items: each glass and processing service registered with the VSDC
-- (item code, classification). ebm_devices: the device initialisations.
-- Settings: SIMULATOR (development, receipts marked "not fiscal") or
-- VSDC (the business's VSDC at its URL).
-- =====================================================================

CREATE TABLE ebm_devices (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version            BIGINT       NOT NULL DEFAULT 0,
    tin                VARCHAR(9)   NOT NULL,
    branch_id          VARCHAR(2)   NOT NULL,
    device_serial      VARCHAR(100) NOT NULL,
    simulated          BOOLEAN      NOT NULL,
    already_installed  BOOLEAN      NOT NULL DEFAULT FALSE,   -- the VSDC answered 902: installed before, no details returned
    taxpayer_name      VARCHAR(60),
    branch_name        VARCHAR(60),
    device_id          VARCHAR(20),
    sdc_id             VARCHAR(20),
    mrc_no             VARCHAR(20),
    last_invc_no       BIGINT,
    last_sale_rcpt_no  BIGINT,
    initialised_at     TIMESTAMP    NOT NULL,
    created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by         VARCHAR(50),
    updated_at         TIMESTAMP,
    updated_by         VARCHAR(50),
    CONSTRAINT uk_ebm_devices_device UNIQUE (tin, branch_id, device_serial, simulated),
    CONSTRAINT chk_ebm_devices_tin CHECK (tin ~ '^[0-9]{9}$'),
    CONSTRAINT chk_ebm_devices_branch CHECK (branch_id ~ '^[0-9]{2}$')
);

-- Item codes: country of origin + item type + packaging unit + quantity unit + serial (spec 4.17)
CREATE SEQUENCE ebm_item_serial;

CREATE TABLE ebm_items (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version        BIGINT       NOT NULL DEFAULT 0,
    item_code      VARCHAR(20)  NOT NULL,
    product_id     UUID         REFERENCES products(id),
    service_id     UUID         REFERENCES processing_services(id),
    simulated      BOOLEAN      NOT NULL,
    item_class     VARCHAR(10)  NOT NULL,
    tax_code       VARCHAR(1)   NOT NULL,
    qty_unit       VARCHAR(5)   NOT NULL,
    registered_at  TIMESTAMP    NOT NULL,
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by     VARCHAR(50),
    updated_at     TIMESTAMP,
    updated_by     VARCHAR(50),
    CONSTRAINT uk_ebm_items_code UNIQUE (item_code, simulated),
    CONSTRAINT chk_ebm_items_owner CHECK ((product_id IS NULL) <> (service_id IS NULL))
);

CREATE UNIQUE INDEX uk_ebm_items_product ON ebm_items (product_id, simulated) WHERE product_id IS NOT NULL;
CREATE UNIQUE INDEX uk_ebm_items_service ON ebm_items (service_id, simulated) WHERE service_id IS NOT NULL;

CREATE TABLE ebm_receipts (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version          BIGINT       NOT NULL DEFAULT 0,
    invc_no          BIGINT       NOT NULL,                  -- our EBM invoice number, sent as invcNo
    receipt_type     VARCHAR(1)   NOT NULL,                  -- S sale, R refund (rcptTyCd)
    invoice_id       UUID         NOT NULL REFERENCES sales_invoices(id),
    credit_note_id   UUID         REFERENCES credit_notes(id),
    document_number  VARCHAR(30)  NOT NULL,
    org_invc_no      BIGINT,                                 -- a refund: its sale's invcNo
    purchase_code    VARCHAR(6),                             -- the buyer's purchase code (prcOrdCd)
    status           VARCHAR(10)  NOT NULL DEFAULT 'QUEUED',
    attempts         INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMP,
    last_attempt_at  TIMESTAMP,
    result_code      VARCHAR(5),
    last_error       VARCHAR(500),
    request_json     TEXT,
    response_json    TEXT,
    simulated        BOOLEAN      NOT NULL DEFAULT FALSE,
    rcpt_no          BIGINT,
    tot_rcpt_no      BIGINT,
    intrl_data       VARCHAR(40),
    rcpt_sign        VARCHAR(40),
    sdc_id           VARCHAR(20),
    mrc_no           VARCHAR(20),
    vsdc_date        TIMESTAMP,
    signed_at        TIMESTAMP,
    printed_at       TIMESTAMP,                              -- the original printed
    copies           INTEGER      NOT NULL DEFAULT 0,
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       VARCHAR(50),
    updated_at       TIMESTAMP,
    updated_by       VARCHAR(50),
    CONSTRAINT uk_ebm_receipts_invc_no UNIQUE (invc_no),
    CONSTRAINT chk_ebm_receipts_type CHECK (receipt_type IN ('S', 'R')),
    CONSTRAINT chk_ebm_receipts_status CHECK (status IN ('QUEUED', 'SIGNED', 'FAILED')),
    CONSTRAINT chk_ebm_receipts_document CHECK ((receipt_type = 'R') = (credit_note_id IS NOT NULL)
        AND (receipt_type = 'R') = (org_invc_no IS NOT NULL)),
    CONSTRAINT chk_ebm_receipts_signed CHECK ((status = 'SIGNED') = (signed_at IS NOT NULL)
        AND (status <> 'SIGNED' OR (rcpt_no IS NOT NULL AND tot_rcpt_no IS NOT NULL AND intrl_data IS NOT NULL
             AND rcpt_sign IS NOT NULL AND sdc_id IS NOT NULL AND mrc_no IS NOT NULL))),
    CONSTRAINT chk_ebm_receipts_queued CHECK (status <> 'QUEUED' OR next_attempt_at IS NOT NULL),
    CONSTRAINT chk_ebm_receipts_printed CHECK (copies >= 0 AND (copies = 0 OR printed_at IS NOT NULL)
        AND (printed_at IS NULL OR status = 'SIGNED')),
    CONSTRAINT chk_ebm_receipts_purchase_code CHECK (purchase_code IS NULL OR purchase_code ~ '^[A-Za-z0-9]{1,6}$')
);

CREATE UNIQUE INDEX uk_ebm_receipts_sale ON ebm_receipts (invoice_id) WHERE receipt_type = 'S';
CREATE UNIQUE INDEX uk_ebm_receipts_credit_note ON ebm_receipts (credit_note_id) WHERE credit_note_id IS NOT NULL;
CREATE INDEX idx_ebm_receipts_due ON ebm_receipts (next_attempt_at) WHERE status = 'QUEUED';
CREATE INDEX idx_ebm_receipts_status ON ebm_receipts (status, created_at);

-- A signed receipt is the fiscal record: what the VSDC returned never changes, only its prints are counted.
CREATE OR REPLACE FUNCTION forbid_signed_ebm_receipt_change() RETURNS trigger AS $$
BEGIN
    IF OLD.status = 'SIGNED' AND (NEW.status <> 'SIGNED' OR NEW.invc_no <> OLD.invc_no
        OR NEW.rcpt_no IS DISTINCT FROM OLD.rcpt_no OR NEW.tot_rcpt_no IS DISTINCT FROM OLD.tot_rcpt_no
        OR NEW.intrl_data IS DISTINCT FROM OLD.intrl_data OR NEW.rcpt_sign IS DISTINCT FROM OLD.rcpt_sign
        OR NEW.sdc_id IS DISTINCT FROM OLD.sdc_id OR NEW.mrc_no IS DISTINCT FROM OLD.mrc_no
        OR NEW.vsdc_date IS DISTINCT FROM OLD.vsdc_date OR NEW.signed_at IS DISTINCT FROM OLD.signed_at
        OR NEW.request_json IS DISTINCT FROM OLD.request_json OR NEW.response_json IS DISTINCT FROM OLD.response_json
        OR NEW.simulated <> OLD.simulated) THEN
        RAISE EXCEPTION 'EBM receipt % is signed: it never changes', OLD.document_number;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ebm_receipts_signed
    BEFORE UPDATE ON ebm_receipts
    FOR EACH ROW EXECUTE FUNCTION forbid_signed_ebm_receipt_change();

CREATE TRIGGER trg_ebm_receipts_no_delete
    BEFORE DELETE ON ebm_receipts
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_modification();

-- EBM invoice numbers: plain integers, never reset (invcNo)
INSERT INTO number_sequences (doc_type, branch_code, prefix, reset_policy, padding, created_by) VALUES
('EBM_INVOICE', 'WH', 'EBM', 'NEVER', 6, 'system');

-- The buyer's purchase code at the counter (prcOrdCd, for buyers with a TIN)
ALTER TABLE sales_invoices ADD COLUMN purchase_code VARCHAR(6);
ALTER TABLE sales_invoices ADD CONSTRAINT chk_sales_invoices_purchase_code
    CHECK (purchase_code IS NULL OR purchase_code ~ '^[A-Za-z0-9]{1,6}$');

-- The refund reason EBM reports for a credit note (spec 4.16, rfdRsnCd)
ALTER TABLE credit_notes ADD COLUMN refund_reason VARCHAR(2);
ALTER TABLE credit_notes ADD CONSTRAINT chk_credit_notes_refund_reason
    CHECK (refund_reason IS NULL OR refund_reason ~ '^(0[1-9]|1[0-3])$');

-- EBM failures and a backlog of unsigned receipts are told to the alert's holders
ALTER TABLE notifications DROP CONSTRAINT chk_notifications_kind;
ALTER TABLE notifications ADD CONSTRAINT chk_notifications_kind
    CHECK (kind IN ('LOW_STOCK', 'APPROVAL', 'DECISION', 'SYSTEM', 'EBM'));

INSERT INTO settings (setting_key, setting_value, created_by) VALUES
('ebm.mode',              'SIMULATOR', 'system'),
('ebm.vsdc-url',          NULL,        'system'),
('ebm.branch-id',         '00',        'system'),
('ebm.device-serial',     NULL,        'system'),
('ebm.glass-item-class',  NULL,        'system'),
('ebm.service-item-class', NULL,       'system'),
('ebm.origin-country',    'RW',        'system'),
('ebm.receipt-url',       'https://myrra.rra.gov.rw/common/link/ebm/receipt/indexEbmReceiptData?Data=', 'system');

INSERT INTO pages (code, name, module, path, icon, sort_order) VALUES
('EBM', 'EBM Signing', 'Sales & POS', '/ebm', 'shield', 9);

INSERT INTO permissions (code, name, module, action, description) VALUES
('MANAGE_EBM', 'Manage EBM',  'EBM', 'MANAGE', 'Initialise the EBM device, retry receipts and correct a purchase code'),
('ALERT_EBM',  'EBM Alerts',  'EBM', 'ALERT',  'Be told when EBM refuses a receipt or receipts wait too long for their signature');

-- SRS 2.2: the owner, the accountant and the auditor watch fiscal signing; the admin and the accountant run it
INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE p.code = 'EBM' AND r.code IN ('ADMIN', 'OWNER', 'ACCOUNTANT', 'AUDITOR')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE (p.code = 'MANAGE_EBM' AND r.code IN ('ADMIN', 'ACCOUNTANT'))
   OR (p.code = 'ALERT_EBM' AND r.code IN ('ADMIN', 'OWNER', 'ACCOUNTANT'))
ON CONFLICT DO NOTHING;
