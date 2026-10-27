-- Digital receipt sharing: one public, read-only link per business document.
-- The random token is the only key its reader holds; the number alone never opens the document.
CREATE TABLE document_share_links (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version         BIGINT       NOT NULL DEFAULT 0,
    token           VARCHAR(64)  NOT NULL,
    document_type   VARCHAR(30)  NOT NULL,
    document_id     UUID         NOT NULL,
    document_number VARCHAR(30)  NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      VARCHAR(50),
    updated_at      TIMESTAMP,
    updated_by      VARCHAR(50),
    CONSTRAINT uk_document_share_links_token UNIQUE (token),
    CONSTRAINT uk_document_share_links_document UNIQUE (document_type, document_id),
    CONSTRAINT chk_document_share_links_type CHECK (document_type IN (
        'INVOICE', 'CREDIT_NOTE', 'QUOTATION', 'SALES_ORDER', 'DELIVERY_NOTE', 'CUSTOMER_PAYMENT',
        'GOODS_RECEIPT', 'PURCHASE_ORDER'))
);

COMMENT ON TABLE document_share_links IS 'Public read-only links to business documents (digital receipt sharing)';
COMMENT ON COLUMN document_share_links.token IS 'Random URL-safe token; the only key a public reader holds';
