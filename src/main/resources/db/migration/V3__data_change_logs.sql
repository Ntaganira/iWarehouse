-- =====================================================================
-- V3: Data change log — the record BEFORE and AFTER every change
-- (SRS 4.13, layer 2: AUD-02 .. AUD-08)
-- One row per created / updated / deleted record of an @AuditedEntity.
-- Written by DataChangeEventListener in the SAME transaction as the change.
-- =====================================================================

CREATE TABLE data_change_logs (
    id             BIGSERIAL PRIMARY KEY,
    request_id     VARCHAR(36),                 -- same value as activity_logs.request_id
    entity_type    VARCHAR(100) NOT NULL,       -- e.g. Customer
    entity_id      VARCHAR(64)  NOT NULL,       -- primary key as text (Long or UUID)
    entity_ref     VARCHAR(100),                -- readable reference, e.g. INV-WH-2026-000123
    operation      VARCHAR(20)  NOT NULL,       -- CREATE / UPDATE / DELETE
    before_data    JSONB,                       -- null on CREATE
    after_data     JSONB,                       -- null on DELETE
    changed_fields TEXT[],                      -- names of fields whose value changed
    reason         TEXT,                        -- mandatory for adjustments, overrides, reversals
    user_id        BIGINT,
    username       VARCHAR(50),
    ip_address     VARCHAR(45),
    user_agent     VARCHAR(255),
    device_id      VARCHAR(100),                -- mobile PWA device
    client_time    TIMESTAMP,                   -- device clock for offline actions
    server_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_change_operation CHECK (operation IN ('CREATE', 'UPDATE', 'DELETE'))
);

CREATE INDEX idx_dcl_entity      ON data_change_logs (entity_type, entity_id, server_time DESC);
CREATE INDEX idx_dcl_server_time ON data_change_logs (server_time);
CREATE INDEX idx_dcl_user        ON data_change_logs (user_id, server_time DESC);
CREATE INDEX idx_dcl_request_id  ON data_change_logs (request_id);
CREATE INDEX idx_dcl_changed     ON data_change_logs USING GIN (changed_fields);

-- ---------------------------------------------------------------------
-- AUD-08: audit tables are append-only. Any UPDATE or DELETE fails,
-- whichever database user attempts it.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION forbid_audit_modification() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Audit table % is append-only: % is not allowed', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_data_change_logs_append_only
    BEFORE UPDATE OR DELETE ON data_change_logs
    FOR EACH ROW EXECUTE FUNCTION forbid_audit_modification();

CREATE TRIGGER trg_activity_logs_append_only
    BEFORE UPDATE OR DELETE ON activity_logs
    FOR EACH ROW EXECUTE FUNCTION forbid_audit_modification();

-- ---------------------------------------------------------------------
-- Permissions and page access
-- ---------------------------------------------------------------------
INSERT INTO permissions (code, name, module, action, description) VALUES
('VIEW_DATA_CHANGES', 'View Data Changes', 'Audit', 'VIEW',   'View before/after history of records'),
('EXPORT_AUDIT',      'Export Audit',      'Audit', 'EXPORT', 'Export activity and change logs');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code IN ('ADMIN', 'OWNER', 'AUDITOR') AND p.code IN ('VIEW_DATA_CHANGES', 'EXPORT_AUDIT');

INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE r.code IN ('OWNER', 'AUDITOR') AND p.code = 'DATA_CHANGES';

-- Users are also audited (AUD-03): changes to accounts appear in Data Changes.
