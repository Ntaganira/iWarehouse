-- =====================================================================
-- V31: dashboard and reports (RPT-01, RPT-02, RPT-03, RPT-05, RPT-07).
-- The owner's figures on the dashboard (sales, margin, stock value, cash,
-- what waits), the sales reports with the gross margin at MAC, the stock
-- reports (glass by place, off-cut ageing, slow-moving stock) and every
-- report exported to Excel and PDF. They read the documents and the
-- journals: no table of their own.
-- =====================================================================

-- Glass held longer than this many days is slow-moving (RPT-02)
INSERT INTO settings (setting_key, setting_value, created_by) VALUES
('stock.slow-moving-days', '90', 'system');

-- The stock summary page now holds the stock reports (summary, off-cut ageing, slow-moving)
UPDATE pages SET name = 'Stock Reports' WHERE code = 'STOCK_SUMMARY';

INSERT INTO pages (code, name, module, path, icon, sort_order) VALUES
('SALES_REPORTS', 'Sales Reports', 'Reports', '/reports/sales', 'bar-chart', 10);

INSERT INTO permissions (code, name, module, action, description) VALUES
('VIEW_SALES_REPORTS',      'View Sales Reports',     'Reports',   'VIEW', 'Sales by customer, glass, salesperson, day or invoice, with the gross margin'),
('VIEW_BUSINESS_DASHBOARD', 'View Business Dashboard', 'Dashboard', 'VIEW', 'The owner''s figures on the dashboard: sales, margin, stock value, cash, what waits');

-- SRS 2.2: the owner, the accountant and the auditor read the sales and the business figures
INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE p.code = 'SALES_REPORTS' AND r.code IN ('ADMIN', 'OWNER', 'ACCOUNTANT', 'AUDITOR')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE p.code IN ('VIEW_SALES_REPORTS', 'VIEW_BUSINESS_DASHBOARD') AND r.code IN ('ADMIN', 'OWNER', 'ACCOUNTANT', 'AUDITOR')
ON CONFLICT DO NOTHING;

-- The Reports page lists every report a user may open: every role holding a report page gets it
INSERT INTO role_pages (role_id, page_id)
SELECT DISTINCT rp.role_id, reports.id
FROM role_pages rp
JOIN pages p ON p.id = rp.page_id
CROSS JOIN pages reports
WHERE reports.code = 'REPORTS'
  AND p.code IN ('SALES_REPORTS', 'STOCK_SUMMARY', 'CUTTING_YIELD', 'RECEIVABLES', 'PAYABLES', 'TRIAL_BALANCE',
                 'FINANCIAL_STATEMENTS', 'VAT_REPORT')
ON CONFLICT DO NOTHING;
