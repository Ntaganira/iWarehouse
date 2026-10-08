-- =====================================================================
-- V4: Read-only access to the Users, Roles and Permissions screens for
-- OWNER and AUDITOR (SRS 2.2: Owner "read all"; Auditor "read all
-- records and the audit trail; no changes"). ADMIN already has everything.
-- =====================================================================

INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE r.code IN ('OWNER', 'AUDITOR') AND p.code IN ('USERS', 'ROLES', 'PERMISSIONS')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code IN ('OWNER', 'AUDITOR') AND p.code IN ('VIEW_USER', 'VIEW_ROLE', 'VIEW_PERMISSION')
ON CONFLICT DO NOTHING;
