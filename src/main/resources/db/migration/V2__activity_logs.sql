-- =====================================================================
-- V2: Activity log — WHO did WHAT (SRS 4.13, layer 1; reused from iVura)
-- Adds request_id (links to data_change_logs), user_agent and device_id.
-- No FK to users: audit rows must survive any change to the users table.
-- =====================================================================

CREATE TABLE activity_logs (
    id          BIGSERIAL PRIMARY KEY,
    request_id  VARCHAR(36),
    user_id     BIGINT,
    username    VARCHAR(50),
    module      VARCHAR(100) NOT NULL,
    action      VARCHAR(50)  NOT NULL,
    description TEXT,
    status      VARCHAR(20)  NOT NULL DEFAULT 'SUCCESS',
    ip_address  VARCHAR(45),
    user_agent  VARCHAR(255),
    device_id   VARCHAR(100),
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_activity_status CHECK (status IN ('SUCCESS', 'FAILED'))
);

CREATE INDEX idx_activity_logs_user_id    ON activity_logs (user_id, created_at DESC);
CREATE INDEX idx_activity_logs_created_at ON activity_logs (created_at);
CREATE INDEX idx_activity_logs_module     ON activity_logs (module);
CREATE INDEX idx_activity_logs_action     ON activity_logs (action);
CREATE INDEX idx_activity_logs_request_id ON activity_logs (request_id);

INSERT INTO permissions (code, name, module, action, description) VALUES
('VIEW_ACTIVITY_LOG', 'View Activity Logs', 'Audit', 'VIEW', 'View activity logs of all users');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code IN ('ADMIN', 'OWNER', 'AUDITOR') AND p.code = 'VIEW_ACTIVITY_LOG';

INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE r.code IN ('OWNER', 'AUDITOR') AND p.code = 'ACTIVITY_LOGS';
