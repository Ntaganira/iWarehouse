-- =====================================================================
-- V33: documents on records (SRS 3.1 file storage, needed by M8 and
-- M9): a PDF or a photo kept on a goods receipt (delivery note, broken
-- sheets on arrival), a shipment (customs and freight papers), a
-- supplier invoice or a stock adjustment (photos of broken glass). The
-- file is in MinIO (object_key); a removed document is marked with who,
-- when and why, and its file stays.
-- =====================================================================

CREATE TABLE attachments (
    id            UUID          PRIMARY KEY,
    version       BIGINT        NOT NULL DEFAULT 0,
    owner_type    VARCHAR(20)   NOT NULL,
    owner_id      UUID          NOT NULL,
    object_key    VARCHAR(200)  NOT NULL,
    file_name     VARCHAR(200)  NOT NULL,
    content_type  VARCHAR(60)   NOT NULL,
    size_bytes    BIGINT        NOT NULL,
    note          VARCHAR(255),
    removed_at    TIMESTAMP,
    removed_by    VARCHAR(50),
    remove_reason VARCHAR(255),
    created_at    TIMESTAMP     NOT NULL,
    created_by    VARCHAR(50),
    updated_at    TIMESTAMP,
    updated_by    VARCHAR(50),
    CONSTRAINT chk_attachments_object CHECK (object_key <> ''),
    CONSTRAINT chk_attachments_owner CHECK (owner_type IN ('GOODS_RECEIPT', 'SHIPMENT', 'SUPPLIER_INVOICE', 'STOCK_ADJUSTMENT')),
    CONSTRAINT chk_attachments_type CHECK (content_type IN ('application/pdf', 'image/jpeg', 'image/png', 'image/webp')),
    CONSTRAINT chk_attachments_size CHECK (size_bytes > 0 AND size_bytes <= 5242880),
    CONSTRAINT chk_attachments_removed CHECK ((removed_at IS NULL) = (remove_reason IS NULL))
);

CREATE UNIQUE INDEX uk_attachments_object_key ON attachments (object_key);
CREATE INDEX idx_attachments_owner ON attachments (owner_type, owner_id, created_at DESC);

INSERT INTO permissions (code, name, module, action, description) VALUES
('ATTACH_DOCUMENT', 'Keep Documents', 'Documents', 'CREATE', 'Keep a PDF or a photo on a receipt, shipment, supplier invoice or adjustment, and remove one with a reason');

-- SRS 2.2: procurement keeps supplier and customs papers, the supervisor photos of breakage, the accountant invoices
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE p.code = 'ATTACH_DOCUMENT' AND r.code IN ('ADMIN', 'PROCUREMENT', 'WAREHOUSE_SUPERVISOR', 'ACCOUNTANT')
ON CONFLICT DO NOTHING;
