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
- [x] Port Users, Roles, Permissions screens from iVura (`UserController`, `RoleController`, `PermissionController`, templates). Change "delete user" to "disable user" (M) — 2026-10-07: POST-only state changes, lock-out guards, sessions end on disable/reset/role change, V4 read access for OWNER/AUDITOR
- [ ] Profile page, change password, forgot/reset password by email, single-use 60-minute link (NFR-09) (M)
- [ ] Account lockout after 5 failed logins using `users.failed_login_attempts` / `locked_until` (NFR-09) (M)
- [x] Audit role-permission and role-page changes explicitly, since collection changes are not captured (AUD-03) (M) — 2026-10-07: `UPDATE_ROLE_ACCESS` / `UPDATE_USER_ROLES` activity entries after commit
- [ ] MockMvc tests for the RBAC screens: every handler refuses a user without its PAGE_/PERM_ (NFR-10) (S)
- [ ] Notifications (bell, list, mark read) ported from iVura; alert hooks for RPT-06 (S)
- [ ] MinIO `FileStorageService` (photos, documents, breakage photos) ported from iVura (M)
- [x] Settings screen: off-cut threshold, glass density, approval limits, numbering, tax categories (ADM-03) (M) — 2026-10-07: `/settings` tabs General / Tax categories / Document numbering / History; V5 seeds, OWNER and AUDITOR read-only
- [x] Document numbering service per type and branch, e.g. `INV-WH-2026-000123` (MD-07) (M) — 2026-10-07: `DocumentNumberService.next(type)` under a row lock; yearly/monthly/never reset; next number only goes up; reset policy fixed after first use (V6)
- [ ] Confirm the EBM tax type letters seeded in V5 (A exempt, B 18%, C zero-rated) against the VSDC specification (TAX-02) (M)
- [ ] Approval limits default to 0 (everything needs approval): agree real values with the owner before go-live (INV-07, POS-06) (S)
- [ ] Kinyarwanda translations for new keys in `messages_rw.properties` (NFR-15) (S)
- [ ] Testcontainers integration test: one audited save produces one `data_change_logs` row with correct before/after; rollback leaves none (AUD-04) (M). Also: two concurrent `DocumentNumberService.next()` calls get different numbers, and a rolled-back document gives its number back (MD-07)
- [ ] CI: build + tests on push (S)
- [ ] Separate DB login for the app without UPDATE/DELETE on audit tables (defence in depth on top of triggers) (S)

## Phase 1 — Core warehouse, counter sales, accounting

### Master data (MD)
- [x] Glass products: type × thickness, VAT category (MD-01) — 2026-10-08: `/products` (V8). Type + colour/finish + thickness (NUMERIC(5,2) for 6.38 mm laminated), unique together and fixed after creation; suggested codes (CLR-6, TNT-BRONZE-5); weight per m² from the density setting; reorder level (INV-10); tempered marked "made to size"
- [x] Locations: Site → Zone → Rack → Slot; rack limits kg/pieces (MD-02, MD-03) — 2026-10-08: `/locations` (V8). Tree page; the parent decides the type; next free code suggested (WH-A-R03); rack weight, pieces, orientation and off-cut flag; deactivate leaf first, reactivate under an active parent; VEHICLE type reserved for the Fleet module
- [ ] Confirm the starting product range (18 seeded: clear 3–12 mm, tinted bronze/grey, reflective blue, frosted, mirror, laminated 6.38/8.38) and the real warehouse layout and rack limits (seeded: WH, zone WH-A, racks R01, R02 and off-cut rack OC) (MD-01, MD-02) (S)
- [x] Stock guards for master data, part 1 — 2026-10-08: a location or product holding stock stays active; a product on open purchase order lines and a supplier with open orders stay active; receipts check rack piece and weight limits (slots count towards their rack)
- [ ] Stock guards, part 2: check rack limits and orientation on transfers and loading; fix a location code once location labels are printed (MD-03, INV-07, FLT-06) (M)
- [ ] Cutting jobs refuse a tempered source unit (`GlassType.isCuttable()`) (PRD-02) (M)
- [x] MAC per m² on the product, updated by receiving (PRC-05) — 2026-10-08: `products.mac_per_m2` (RWF) moves when a receipt is posted, at the PO price and the receipt-date rate; products are locked while posting. Freight, duty and clearing are added by shipment postings (PRC-03/04, done 2026-10-08)
- [x] Customers with TIN, credit limit, terms, price list (MD-04); suppliers with currency (MD-05) — 2026-10-08: `/customers`, `/suppliers` (V9). Codes from the numbering service (CUS-WH-00001, SUP-WH-0001); one customer per TIN; walk-ins never get credit; credit limit, terms and price list need PERM_MANAGE_CUSTOMER_TERMS (accountant); default WALK-IN customer for anonymous counter sales; suppliers with ISO country, invoicing currency, Incoterms 2020 (what the price covers), TIN (9 digits if Rwandan); a currency stays active while active suppliers use it
- [x] Price lists per m², processing surcharges, minimum chargeable area 0.25 m² (MD-06) — 2026-10-08: `/price-lists` (V9). Default list RETAIL fills what a customer's list leaves unpriced (`PriceListService.priceFor`); VAT included/excluded per list; minimum area per list or Settings; all prices of a list on one page; per-list price history (old → new); processing services per m², metre of edge, piece or hole
- [ ] Enter the real selling prices: the RETAIL list holds test prices only (CLR-6 27,000, LAM-6.38 41,000.50, edging 1,500, drilling 500) and a test CONTRACTOR list (MD-06) (M)
- [ ] Confirm who does what: procurement keeps suppliers; cashier and accountant add customers; only the accountant sets credit terms; only the owner sets prices (SRS 2.2) (S)
- [ ] Counter POS: price lines with `PriceListService.priceFor` and `Pricing.chargeableArea`, store price, list and VAT flag on the line; enforce credit limits with approval (POS-02, POS-05) (M)
- [ ] Customer balance and ageing on the customer page; supplier balance on the supplier page (ACC-09) (M)

### Procurement and landed cost (PRC)
- [x] Purchase orders in supplier currency (PRC-01) — 2026-10-08: `/purchase-orders` (V10). Draft → placed → partly received / received; cancel (nothing received) or close short, with a reason; lines priced per m² in the supplier currency, total rounded once; numbers PO-WH-2026-000001; order and line history together
- [x] Crate receiving: one stock unit per sheet + label print (PRC-02, INV-03) — 2026-10-08: `/goods-receipts` (V10). Draft receipt with one row per crate (order line, crate marking, size, good and broken sheets, rack); never more than the line waits for; posting fixes the receipt-date rate and creates AVAILABLE units U-WH-000001 on their racks; labels 50 x 30 mm with QR as a print page or ZPL (`/stock/labels`)
- [x] Shipment cost sheet: freight, insurance, duty, clearing, transport, multi-currency (PRC-03) — 2026-10-08: `/shipments` (V11). A shipment links its posted receipts and lists its bills (freight, insurance, customs duty, clearing agent, port, transport, other), each in its own currency and dated like the bill, optionally paid to a supplier. Bills are drafts until posted; bills arriving later are posted as the next posting; a posted bill is corrected by a credit note (negative amount). Close when complete, cancel (reason) while nothing is posted
- [x] Cost allocation by area / value / weight; landed cost per m² in RWF; MAC update (PRC-04, PRC-05) — 2026-10-08: each bill at the rate of its date (duty: customs rate), total rounded once, split over the crates by m² / purchase value / kg shipped (broken sheets included) and over each crate's sheets so the parts add up to the franc cent. Sheets in stock get their part added to their cost (`stock_cost_entries`, append-only) and the MAC moves; parts of sheets already gone are expensed; parts of broken sheets go to the claim. Shipment page shows purchase, import and landed cost per m² per crate, with the draft bills previewed. AT-01 checked in `LandedCostTest` and in the browser
- [x] Broken-on-arrival claims (PRC-06) (S) — 2026-10-08: on the shipment: broken sheets per crate valued at purchase cost plus their share of the import costs; claim recorded (supplier or insurer, reference, date, amount proposed), then settled (amount received) or rejected (reason)

- [ ] Test the labels on the real printer: the HTML page at 100% (50 x 30 mm) and the ZPL file on the Zebra (INV-03) (M)
- [ ] Confirm how suppliers quote: per m² (built), or per sheet / per tonne (PRC-01) (S)
- [ ] Confirm that a receipt may not bring more sheets than ordered (built: refused). If suppliers add spare sheets, decide how they are priced (PRC-02) (S)
- [ ] Posting engine: "Crate received" Dr Inventory / Cr Goods Received Not Invoiced from the receipt cost (SRS 4.9.1, ACC-04) (M)
- [ ] Posting engine for shipment postings: import charges Dr Inventory (the part that reached stock, `shipment_allocations.stock_amount`) / Cr Accounts Payable (bills with a supplier) or Cash; the expensed part to Cost of Goods Sold; the broken part to a claim receivable while the claim is open, the shortfall of a settled or rejected claim to Glass Spoilage (SRS 4.9.1, ACC-04) (M)
- [ ] Supplier balance: shipment bills paid to a supplier count in its payable once AP exists (ACC-09) (S)
- [ ] When cutting exists: a bill posted after a sheet was cut is expensed for that sheet; decide whether to push it to its pieces still in stock instead (PRC-05, PRD-07) (S)
- [ ] Confirm the customs rate source: duty is converted at the CUSTOMS rate of the bill date, entered under Currencies & Rates (a CUSTOMS rate of 1,449 on 2026-10-07 is test data) (PRC-05) (S)
- [ ] Dev database holds test data: PO-WH-2026-000001 (Shandong, received), -000002 (Kigali Glass, closed short), -000003..8 (cancelled), -000009 (AT-01, received); GRN-WH-2026-000004 put 20 units on the test rack WH-A-R04; shipments SHP-WH-2026-000002 (AT-01, costs complete), -000003 (by value, claim settled), -000001/-000004 (cancelled); 55 stock units, MAC on CLR-6, CLR-8, MIR-4; a test CUSTOMS rate; disabled test users (cashier*, auditor*, super4032). Clear before go-live

### Inventory (INV)
- [x] `StockUnit` entity, area and weight computed, status lifecycle (INV-01, INV-02; SRS 6.2) — 2026-10-08: the 8 SRS states; size, area, weight and cost fixed; only `StockService` changes status or location. RECEIVED is unused until a put-away scan exists
- [x] Immutable stock movements for every location/status change (INV-04) — 2026-10-08: `stock_movements` append-only (trigger), RECEIPT movements written now; each later module adds its movement type
- [ ] Reservation and locking rules (INV-05)
- [x] Smallest-fit search: "at least 1200 × 800 in 6 mm clear" (INV-06) — 2026-10-08: on `/stock`, either way round, available pieces only, smallest area first; a scanned label code in the search opens the unit
- [ ] Transfers and adjustments with approval and reason (INV-07). Also the way to correct a posted receipt (wrong size or count): a posted receipt is never edited
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
- [x] Exchange rates by date and source (ACC-02) — 2026-10-07: `/currencies` (V7). Currencies with RWF as fixed base; rates per currency/date/source (BNR, Customs, Bank, Manual); `ExchangeRateService.rateFor()` refuses missing or stale rates (Settings: default source, max age); >10% jumps need confirming; corrections need a reason; CSV import, all or nothing
- [ ] Confirm which currencies the business buys in (seeded active: USD, EUR, CNY) and whether any sale is ever invoiced in a foreign currency (ACC-02) (S)
- [ ] Exchange gain/loss postings when a foreign invoice is paid at another rate, and period-end revaluation of open foreign balances (ACC-04, ACC-10) (M)
- [ ] Optional automatic download of BNR daily rates when the server has internet (ACC-02) (C)
- [ ] Posting engine implementing the posting matrix (SRS 4.9.1); balanced journal in same transaction (ACC-04)
- [ ] Manual journals with approval; reversal, no delete (ACC-05)
- [ ] Subledgers with ageing (ACC-09); period close (ACC-10); TB, GL, P&L, BS (ACC-11)

### Reports (RPT)
- [ ] Owner dashboard KPIs (RPT-01); stock, production and sales reports (RPT-02, RPT-03, RPT-05); PDF + Excel export (RPT-07)

## Phase 2 — Fleet and moving shops

- [ ] Vehicles as stock locations, limits, insurance/inspection expiry (FLT-01, FLT-02). Each vehicle creates its own `locations` row of type VEHICLE (no parent); the Locations screen never adds or changes those
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
- [ ] Fixed price lists or negotiated per customer? Built: several lists, one per customer (e.g. Contractors), the default list fills the gaps. Per-customer negotiated prices would be a list per customer; confirm that is enough
- [ ] Do vehicles carry full sheets for on-site cutting?
- [ ] Mobile-money providers and card terminals in use?
- [ ] EBM registration status and VSDC setup?
- [ ] Vehicles, drivers, users and branches at go-live and in 2 years?
- [ ] Tempering in-house or outsourced? Cullet sold as scrap? Opening stock migration or fresh count?
