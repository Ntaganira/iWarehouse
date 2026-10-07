# TODO — iWarehouse

Requirement IDs refer to SRS v2.1. Priorities: **M** must, **S** should, **C** could.
Tick items as they land; add the commit or PR next to the item.

## Phase 0 — Foundation

### Done (initial scaffold, 2026-10-07)
- [x] Spring Boot 3.4 / Java 17 project on iVura's stack; Maven wrapper; Docker Compose (PostgreSQL 16 + MinIO)
- [x] Flyway V1 security schema: users, roles (SRS 2.2), pages, permissions, grants; admin seed
- [x] Layout shell from iVura: sidebar with warehouse menu, header, themes, EN/FR/RW switcher, toasts, pagination, 403/404
- [x] Form login, one session per user, 15-minute idle timeout, ROLE_/PAGE_/PERM_ authorities (NFR-09, NFR-10)
- [x] Activity log (AUD-01): logins, failures, logouts, access denied; saved in its own transaction so failures survive rollbacks
- [x] Data change log with before/after JSONB snapshots, written in the business transaction (AUD-02, AUD-04, AUD-05)
- [x] Masked fields (AUD-06), device id and client time (AUD-07), append-only triggers (AUD-08)
- [x] Screens: Activity Logs, My Activity, Data Changes list, change detail (before/after), record history (AUD-10, AUD-11)
- [x] `BaseEntity` (UUID, @Version, created/updated by and at) with JPA auditing
- [x] Lean EN/FR message bundles; fixed iVura pagination link bug and double-encoded `©`

### Remaining
- [ ] Port Users, Roles, Permissions screens from iVura (`UserController`, `RoleController`, `PermissionController`, templates). Change "delete user" to "disable user" (M)
- [ ] Profile page, change password, forgot/reset password by email, single-use 60-minute link (NFR-09) (M)
- [ ] Account lockout after 5 failed logins using `users.failed_login_attempts` / `locked_until` (NFR-09) (M)
- [ ] Audit role-permission and role-page changes explicitly, since collection changes are not captured (AUD-03) (M)
- [ ] Notifications (bell, list, mark read) ported from iVura; alert hooks for RPT-06 (S)
- [ ] MinIO `FileStorageService` (photos, documents, breakage photos) ported from iVura (M)
- [ ] Settings screen: off-cut threshold, glass density, approval limits, numbering, tax categories (ADM-03) (M)
- [ ] Document numbering service per type and branch, e.g. `INV-WH-2026-000123` (MD-07) (M)
- [ ] Kinyarwanda translations for new keys in `messages_rw.properties` (NFR-15) (S)
- [ ] Testcontainers integration test: one audited save produces one `data_change_logs` row with correct before/after; rollback leaves none (AUD-04) (M)
- [ ] CI: build + tests on push (S)
- [ ] Separate DB login for the app without UPDATE/DELETE on audit tables (defence in depth on top of triggers) (S)

## Phase 1 — Core warehouse, counter sales, accounting

### Master data (MD)
- [ ] Glass products: type × thickness, VAT category (MD-01)
- [ ] Locations: Site → Zone → Rack → Slot; rack limits kg/pieces (MD-02, MD-03)
- [ ] Customers with TIN, credit limit, terms, price list (MD-04); suppliers with currency (MD-05)
- [ ] Price lists per m², processing surcharges, minimum chargeable area 0.25 m² (MD-06)

### Procurement and landed cost (PRC)
- [ ] Purchase orders in supplier currency (PRC-01)
- [ ] Crate receiving: one stock unit per sheet + label print (PRC-02, INV-03)
- [ ] Shipment cost sheet: freight, insurance, duty, clearing, transport, multi-currency (PRC-03)
- [ ] Cost allocation by area / value / weight; landed cost per m² in RWF; MAC update (PRC-04, PRC-05)
- [ ] Broken-on-arrival claims (PRC-06) (S)

### Inventory (INV)
- [ ] `StockUnit` entity, area and weight computed, status lifecycle (INV-01, INV-02; SRS 6.2)
- [ ] Immutable stock movements for every location/status change (INV-04)
- [ ] Reservation and locking rules (INV-05)
- [ ] Smallest-fit search: "at least 1200 × 800 in 6 mm clear" (INV-06)
- [ ] Transfers and adjustments with approval and reason (INV-07)
- [ ] Stock counts by scanning (INV-08) (S); summaries in pieces / m² / RWF (INV-09); reorder alerts (INV-10) (S)

### Production (PRD)
- [ ] Cutting jobs from orders or for stock; fit check on source sheet (PRD-01, PRD-02)
- [ ] Job completion: consume source, create cut pieces, off-cuts (≥ 0.25 m² and ≥ 300 mm side), cullet (PRD-03..05)
- [ ] Area conservation within 1% and cost flow by area (PRD-06, PRD-07)
- [ ] Breakage with reason; yield % report (PRD-08, PRD-09)

### Counter POS (POS)
- [ ] Two-pane POS screen: search/scan, custom sizes, cart, split payment (POS-01, POS-02, POS-04)
- [ ] Quotations → order/invoice (POS-03); deposits (POS-08) (S)
- [ ] Credit limits and approval-gated discounts (POS-05, POS-06)
- [ ] Returns and credit notes (POS-09); till sessions (POS-10)

### Tax and EBM (TAX)
- [ ] VAT categories and per-line calculation (TAX-01)
- [ ] RRA EBM / VSDC submission with retry queue, signature + QR on invoice (TAX-02, TAX-03). Reuse QT Global's VSDC experience.
- [ ] Buyer TIN on invoice (TAX-04); monthly VAT report (TAX-05) (S)

### Accounting (ACC)
- [ ] Chart of accounts with default template (ACC-03)
- [ ] Exchange rates by date and source (ACC-02)
- [ ] Posting engine implementing the posting matrix (SRS 4.9.1); balanced journal in same transaction (ACC-04)
- [ ] Manual journals with approval; reversal, no delete (ACC-05)
- [ ] Subledgers with ageing (ACC-09); period close (ACC-10); TB, GL, P&L, BS (ACC-11)

### Reports (RPT)
- [ ] Owner dashboard KPIs (RPT-01); stock, production and sales reports (RPT-02, RPT-03, RPT-05); PDF + Excel export (RPT-07)

## Phase 2 — Fleet and moving shops

- [ ] Vehicles as stock locations, limits, insurance/inspection expiry (FLT-01, FLT-02)
- [ ] Drivers linked to users, licence data; expiry blocks and 30-day alerts (FLT-03, FLT-04)
- [ ] Trips and manifests; scan-load with limit checks; departure moves stock to vehicle (FLT-05..07)
- [ ] Mobile PWA shell: bottom nav, vehicle stock only, scan to sell, receipts (MPOS-01..05)
- [ ] Offline: IndexedDB queue, client UUIDs, idempotent `/api/v1/sync`, conflict review, EBM on sync (SYNC-01..07)
- [ ] Send `X-Device-Id`, `X-Client-Time`, `X-Request-Id` from the PWA (AUD-07)
- [ ] En-route breakage with photo (FLT-08, MPOS-06); cash declaration (MPOS-07)
- [ ] EoD reconciliation: expected vs scanned; audit cases block trip closure (FLT-09, FLT-10)
- [ ] Driver float: post to float account, accountant clearance, shortage receivable (ACC-06, ACC-07)
- [ ] Fleet reports (RPT-04)

## Phase 3 — Optimise

- [ ] Approval matrix (ADM-04); mobile-money APIs; VAT return (TAX-05)
- [ ] Audit export to Excel/PDF (AUD-12); monthly partitioning (AUD-13); hash chain (AUD-14)
- [ ] Multi-branch (ADM-05); scrap sales (PRD-11); fuel/odometer (FLT-12)

## Open questions (SRS 8.3)

- [ ] Off-cut threshold: area only, or also a minimum side?
- [ ] Fixed price lists or negotiated per customer?
- [ ] Do vehicles carry full sheets for on-site cutting?
- [ ] Mobile-money providers and card terminals in use?
- [ ] EBM registration status and VSDC setup?
- [ ] Vehicles, drivers, users and branches at go-live and in 2 years?
- [ ] Tempering in-house or outsourced? Cullet sold as scrap? Opening stock migration or fresh count?
