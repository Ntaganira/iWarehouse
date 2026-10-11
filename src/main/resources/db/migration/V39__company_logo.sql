-- ============================================================================
-- V39: The company's logo, a setting like its name (Settings, General). Uploaded on its own (not through the settings
-- form): CompanyLogoService redraws it as a PNG, keeps it in MinIO and stores its storage key here; invoices, receipts,
-- quotations and report PDFs print it. Empty: documents print without a logo.
-- ============================================================================

INSERT INTO settings (setting_key, setting_value, created_by) VALUES
('company.logo', NULL, 'system');
