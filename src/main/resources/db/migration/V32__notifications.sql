-- =====================================================================
-- V32: notifications and alerts (RPT-06), ported from iVura. Each user
-- gets messages (title and text as message keys with their arguments,
-- the page they point to, when read): glass below its reorder level,
-- requests waiting for their approval, decisions on their own requests.
-- Delivered after the business transaction commits; by email too when
-- Settings say so. alert_states remembers a watched condition, so an
-- alert is raised once until it clears.
-- =====================================================================

CREATE TABLE notifications (
    id          BIGSERIAL    PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES users(id),
    kind        VARCHAR(20)  NOT NULL,
    title_key   VARCHAR(100) NOT NULL,
    message_key VARCHAR(100),
    args        VARCHAR(1000),
    link        VARCHAR(255),
    read_at     TIMESTAMP,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_notifications_kind CHECK (kind IN ('LOW_STOCK', 'APPROVAL', 'DECISION', 'SYSTEM'))
);

CREATE INDEX idx_notifications_user ON notifications (user_id, created_at DESC, id DESC);
CREATE INDEX idx_notifications_unread ON notifications (user_id) WHERE read_at IS NULL;

CREATE TABLE alert_states (
    alert_key  VARCHAR(120) PRIMARY KEY,
    raised_at  TIMESTAMP    NOT NULL,
    cleared_at TIMESTAMP
);

-- Alerts go by email too (when a mail account is configured)
INSERT INTO settings (setting_key, setting_value, created_by) VALUES
('alerts.email', 'false', 'system');

INSERT INTO pages (code, name, module, path, icon, sort_order) VALUES
('NOTIFICATIONS', 'Notifications', 'Notifications', '/notifications', 'bell', 11);

INSERT INTO permissions (code, name, module, action, description) VALUES
('VIEW_NOTIFICATIONS', 'View Notifications', 'Notifications', 'VIEW',  'See and open one''s own notifications'),
('ALERT_LOW_STOCK',    'Low Stock Alerts',   'Notifications', 'ALERT', 'Be told when a glass falls below its reorder level');

-- Everyone receives notifications
INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE p.code = 'NOTIFICATIONS'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE p.code = 'VIEW_NOTIFICATIONS'
ON CONFLICT DO NOTHING;

-- SRS 2.2: the owner, the warehouse supervisor and procurement are told about low stock
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE p.code = 'ALERT_LOW_STOCK' AND r.code IN ('ADMIN', 'OWNER', 'WAREHOUSE_SUPERVISOR', 'PROCUREMENT')
ON CONFLICT DO NOTHING;
