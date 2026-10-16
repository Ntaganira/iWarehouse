-- =====================================================================
-- V56: access fixes found while documenting M18 and reissuing the coverage analysis
--   - the COO approves damage reports: a damage write-off is the Delegation of Authority's STOCK_ADJUSTMENT decision,
--     which the COO decides up to 2,000,000 RWF (stand-in amounts) and already approves for stock adjustments
--   - Treasury (Cash flow) and the Order Desk (sales against target, the weekly report) reach their reports from the
--     Reports page, which neither role had
-- =====================================================================

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'COO' AND p.code = 'APPROVE_DAMAGE'
ON CONFLICT DO NOTHING;

INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE p.code = 'REPORTS' AND r.code IN ('TREASURY', 'ORDER_DESK')
ON CONFLICT DO NOTHING;
