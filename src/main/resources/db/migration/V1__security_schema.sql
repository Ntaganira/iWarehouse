-- =====================================================================
-- V1: Users, roles, pages (menu access) and action permissions
-- Same RBAC model as iVura: ROLE_x + PAGE_x + PERM_x authorities.
-- Roles follow SRS section 2.2.
-- =====================================================================

CREATE TABLE roles (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(50)  NOT NULL UNIQUE,          -- e.g. ROLE_ADMIN (Spring authority)
    code        VARCHAR(50)  NOT NULL UNIQUE,          -- e.g. ADMIN
    description TEXT,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP
);

CREATE TABLE users (
    id         BIGSERIAL PRIMARY KEY,
    username   VARCHAR(50)  NOT NULL UNIQUE,
    email      VARCHAR(100) NOT NULL UNIQUE,
    password   VARCHAR(255) NOT NULL,
    full_name  VARCHAR(100) NOT NULL,
    phone      VARCHAR(20),
    photo_url  VARCHAR(255),
    enabled    BOOLEAN      NOT NULL DEFAULT TRUE,
    failed_login_attempts INTEGER NOT NULL DEFAULT 0,  -- NFR-09 lockout after 5
    locked_until TIMESTAMP,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE user_roles (
    user_id BIGINT NOT NULL REFERENCES users(id),
    role_id BIGINT NOT NULL REFERENCES roles(id),
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE permissions (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(100) NOT NULL UNIQUE,          -- e.g. CREATE_INVOICE -> PERM_CREATE_INVOICE
    name        VARCHAR(150) NOT NULL,
    module      VARCHAR(100) NOT NULL,
    action      VARCHAR(50)  NOT NULL,
    description TEXT,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP
);

CREATE TABLE pages (
    id         BIGSERIAL PRIMARY KEY,
    code       VARCHAR(100) NOT NULL UNIQUE,           -- e.g. STOCK -> PAGE_STOCK
    name       VARCHAR(150) NOT NULL,
    module     VARCHAR(100) NOT NULL,
    path       VARCHAR(255) NOT NULL,
    icon       VARCHAR(50),
    parent_id  BIGINT REFERENCES pages(id),
    sort_order INTEGER      NOT NULL DEFAULT 0,
    enabled    BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE role_permissions (
    role_id       BIGINT NOT NULL REFERENCES roles(id),
    permission_id BIGINT NOT NULL REFERENCES permissions(id),
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE role_pages (
    role_id BIGINT NOT NULL REFERENCES roles(id),
    page_id BIGINT NOT NULL REFERENCES pages(id),
    PRIMARY KEY (role_id, page_id)
);

-- ---------------------------------------------------------------------
-- Roles (SRS 2.2)
-- ---------------------------------------------------------------------
INSERT INTO roles (name, code, description) VALUES
('ROLE_ADMIN',                'ADMIN',                'System administrator: users, roles, settings, master data'),
('ROLE_OWNER',                'OWNER',                'Owner / General Manager: oversight and approvals'),
('ROLE_PROCUREMENT',          'PROCUREMENT',          'Purchase orders, imports, landed cost'),
('ROLE_WAREHOUSE_SUPERVISOR', 'WAREHOUSE_SUPERVISOR', 'Racks, stock movements, manifests, EoD stock'),
('ROLE_CUTTING_OPERATOR',     'CUTTING_OPERATOR',     'Cutting jobs, off-cuts and cullet'),
('ROLE_CASHIER',              'CASHIER',              'Counter sales and receipts'),
('ROLE_DRIVER',               'DRIVER',               'Moving-shop sales from own vehicle'),
('ROLE_FLEET_MANAGER',        'FLEET_MANAGER',        'Vehicles, drivers, trips'),
('ROLE_ACCOUNTANT',           'ACCOUNTANT',           'Ledgers, float clearance, period close'),
('ROLE_AUDITOR',              'AUDITOR',              'Read-only access to records and audit trail');

-- Default admin password: password123 (BCrypt, strength 12). Change on first login.
INSERT INTO users (username, email, password, full_name, phone) VALUES
('admin', 'admin@iwarehouse.local', '$2a$12$YkjpDkoLx5VZxafkfUdG6.J1W6B7VnXyAzyGZ0HNzIv1PFDxG2gZm', 'System Admin', '+250780000000');

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM users u CROSS JOIN roles r WHERE u.username = 'admin' AND r.code = 'ADMIN';

-- ---------------------------------------------------------------------
-- Pages (sidebar menu, SRS 3.3). Module pages exist from day one so the
-- menu is complete; each module migration adds its own permissions.
-- ---------------------------------------------------------------------
INSERT INTO pages (code, name, module, path, icon, sort_order) VALUES
('DASHBOARD',     'Dashboard',      'Dashboard',      '/dashboard',       'grid',     1),
('STOCK',         'Inventory',      'Inventory',      '/stock',           'box',      2),
('PRODUCTION',    'Production',     'Production',     '/cutting-jobs',    'scissors', 3),
('PROCUREMENT',   'Procurement',    'Procurement',    '/purchase-orders', 'ship',     4),
('FLEET',         'Fleet & Trips',  'Fleet',          '/trips',           'truck',    5),
('POS',           'Counter POS',    'Sales & POS',    '/pos',             'cart',     6),
('INVOICES',      'Invoices',       'Sales & POS',    '/invoices',        'file',     7),
('CUSTOMERS',     'Customers',      'Sales & POS',    '/customers',       'users',    8),
('ACCOUNTING',    'Accounting',     'Accounting',     '/accounting',      'book',     9),
('REPORTS',       'Reports',        'Reports',        '/reports',         'chart',   10),
('MY_ACTIVITY',   'My Activity',    'My Activity',    '/activity/me',     'clock',   11),
('USERS',         'Users',          'Administration', '/users',           'user',    20),
('ROLES',         'Roles',          'Administration', '/roles',           'shield',  21),
('PERMISSIONS',   'Permissions',    'Administration', '/permissions',     'lock',    22),
('ACTIVITY_LOGS', 'Activity Logs',  'Audit',          '/activity',        'list',    23),
('DATA_CHANGES',  'Data Changes',   'Audit',          '/audit',           'diff',    24),
('SETTINGS',      'Settings',       'Administration', '/settings',        'settings',25);

-- ---------------------------------------------------------------------
-- Foundation permissions (module permissions come with each module)
-- ---------------------------------------------------------------------
INSERT INTO permissions (code, name, module, action, description) VALUES
('VIEW_DASHBOARD',    'View Dashboard',     'Dashboard',             'VIEW',   'View the dashboard'),
('VIEW_USER',         'View Users',         'User Management',       'VIEW',   'View system users'),
('CREATE_USER',       'Create User',        'User Management',       'CREATE', 'Create a system user'),
('EDIT_USER',         'Edit User',          'User Management',       'EDIT',   'Edit a system user'),
('DISABLE_USER',      'Disable User',       'User Management',       'DISABLE','Disable a user (users are never deleted)'),
('RESET_PASSWORD',    'Reset Password',     'User Management',       'RESET',  'Reset a user password'),
('ASSIGN_ROLE',       'Assign Roles',       'User Management',       'ASSIGN', 'Assign roles to a user'),
('VIEW_ROLE',         'View Roles',         'Role Management',       'VIEW',   'View roles'),
('CREATE_ROLE',       'Create Role',        'Role Management',       'CREATE', 'Create a role'),
('EDIT_ROLE',         'Edit Role',          'Role Management',       'EDIT',   'Edit a role and its pages/permissions'),
('VIEW_PERMISSION',   'View Permissions',   'Permission Management', 'VIEW',   'View the permission catalogue'),
('EDIT_PERMISSION',   'Edit Permission',    'Permission Management', 'EDIT',   'Edit a permission'),
('VIEW_SETTINGS',     'View Settings',      'Settings',              'VIEW',   'View system settings'),
('EDIT_SETTINGS',     'Edit Settings',      'Settings',              'EDIT',   'Change system settings');

-- ---------------------------------------------------------------------
-- Grants: ADMIN gets every page and permission; everyone sees the
-- dashboard and their own activity.
-- ---------------------------------------------------------------------
INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p WHERE r.code = 'ADMIN';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p WHERE r.code = 'ADMIN';

INSERT INTO role_pages (role_id, page_id)
SELECT r.id, p.id FROM roles r CROSS JOIN pages p
WHERE r.code <> 'ADMIN' AND p.code IN ('DASHBOARD', 'MY_ACTIVITY');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code <> 'ADMIN' AND p.code = 'VIEW_DASHBOARD';
