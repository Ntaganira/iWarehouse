-- =====================================================================
-- V62: Rwanda administrative hierarchy dashboard access
--   Reading the hierarchy is for Administration, the owner, the warehouse
--   manager, the Internal Controller and the COO. Only ADMIN and OWNER
--   may rename, toggle or delete a unit (the allow_edit lock still applies).
-- =====================================================================

INSERT INTO pages (code, name, module, path, icon, sort_order) VALUES
('RWANDA_HIERARCHY', 'Rwanda Administrative Hierarchy', 'Administration', '/admin/hierarchy', 'map', 90)
ON CONFLICT DO NOTHING;

INSERT INTO permissions (code, name, module, action, description) VALUES
('VIEW_RWANDA_HIERARCHY',   'View Rwanda Administrative Hierarchy',   'Administration', 'VIEW',
 'View provinces, districts, sectors, cells and villages with their codes and rollup paths'),
('MANAGE_RWANDA_HIERARCHY', 'Manage Rwanda Administrative Hierarchy', 'Administration', 'MANAGE',
 'Rename, hide or delete an administrative unit within its allow_edit lock')
ON CONFLICT DO NOTHING;

INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE p.code = 'RWANDA_HIERARCHY'
  AND r.code IN ('ADMIN', 'OWNER', 'WAREHOUSE_MANAGER', 'INTERNAL_CONTROLLER', 'COO')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE p.code = 'VIEW_RWANDA_HIERARCHY'
  AND r.code IN ('ADMIN', 'OWNER', 'WAREHOUSE_MANAGER', 'INTERNAL_CONTROLLER', 'COO')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE p.code = 'MANAGE_RWANDA_HIERARCHY'
  AND r.code IN ('ADMIN', 'OWNER')
ON CONFLICT DO NOTHING;
