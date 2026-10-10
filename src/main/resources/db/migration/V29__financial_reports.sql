-- =====================================================================
-- V29: financial statements (ACC-11) and the monthly VAT report
-- (TAX-05). The income statement of a period, the balance sheet at a
-- day and the general ledger of a period, each exported to Excel with
-- the trial balance; the VAT report: output VAT per tax letter from the
-- invoices and credit notes of a month, input VAT from the ledger, VAT
-- payable. They read the journals: no table of their own.
-- =====================================================================

INSERT INTO pages (code, name, module, path, icon, sort_order) VALUES
('FINANCIAL_STATEMENTS', 'Financial Statements', 'Accounting', '/accounting/income-statement', 'bar-chart', 7),
('VAT_REPORT',           'VAT Report',           'Accounting', '/accounting/vat-report',       'percent',   7);

-- SRS 2.2: the accountant, the owner and the auditor read the statements
INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE p.code IN ('FINANCIAL_STATEMENTS', 'VAT_REPORT') AND r.code IN ('ADMIN', 'ACCOUNTANT', 'OWNER', 'AUDITOR')
ON CONFLICT DO NOTHING;
