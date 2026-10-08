# TODO — iWarehouse

The road from today's code to a fully working warehouse, sales and fleet system for the glass business.

## How to use this file

- Read `CLAUDE.md` first. Requirement IDs (`INV-07`, `FLT-06`...) refer to SRS v2.1 in Claude Docs: "SRS — Glass Reproduction Warehouse & Mobile POS (ERP-Lite)" (https://claude.ai/code/artifact/96a595dc-fe7b-4e23-846e-91b60f43ab12).
- Build the milestones in order, top to bottom: each one needs the ones above it. Inside a milestone, build the items in order too.
- Priorities: **M** must, **S** should, **C** could. Sizes: (S) a day or less, (M) a few days, (L) a week or more.
- When an item lands: tick it, add the date and one line saying where it lives (screen, migration, main class), then move the finished milestone to the Done log at the end. Put anything the owner must decide under "Questions for the business".
- **Next migration: V14.** Stop the app before writing it (CLAUDE.md, Database).
- A module is done when it meets the definition of done below.

## Where we are (2026-10-08)

| Area | State | Where |
|---|---|---|
| Foundation: login, RBAC, audit trail, layout, EN/FR | Done | V1–V4 |
| Settings, document numbering, currencies and exchange rates | Done | V5–V7, `/settings`, `/currencies` |
| Master data: products, locations, suppliers, customers, price lists | Done | V8–V9 |
| Purchasing and receiving: orders, crates, stock units, labels | Done | V10, `/purchase-orders`, `/goods-receipts`, `/stock` |
| Landed cost and claims (AT-01 passes) | Done | V11, `/shipments` |
| Cutting jobs, off-cuts, cullet, yield (AT-02 passes) | Done | V12, `/cutting-jobs` |
| Inventory operations: reservations, transfers, adjustments, valuation, reorder | Done (M1, part 1) | V13, `/stock-transfers`, `/stock-adjustments`, `/stock/summary` |
| Stock counts, put-away, location labels | **Next** (M1, part 2) | — |
| Accounting: chart of accounts, posting engine, journals | Not started (M2) | — |
| Counter sales: POS, quotations, invoices, payments, returns, VAT | Not started (M3) | — |
| EBM / VSDC fiscal signing | Not started (M4) | — |
| Customer and supplier accounts, period close, financial statements | Not started (M5) | — |
| Dashboard, reports, alerts, notifications, files | Not started (M6) | — |
| Fleet: vehicles, drivers, trips, loading | Not started (M7) | — |
| Mobile POS app (PWA) with offline sync | Not started (M8) | — |
| End of day: return scan, reconciliation, audit cases, driver floats | Not started (M9) | — |
| Hardening and go-live | Not started (M10) | — |

Unit tests: 260, all passing. Acceptance tests (SRS 8.1): AT-01 and AT-02 pass; AT-03 to AT-10 wait for their milestones.

## Definition of done for a module

1. One Flyway migration `V{n}__{module}.sql`: tables with named `chk_`/`idx_`/`uk_` constraints, append-only triggers on ledgers, number sequences, pages, permissions and role grants (SRS 2.2).
2. Entities audited (`@AuditedEntity`), money `BigDecimal`, writes in `@Transactional` services, stock changes only through `StockService`, documents numbered by `DocumentNumberService`.
3. Controllers with `@PreAuthorize` on every handler; every action logged with `activityLogService.record(...)`, failures too; reasons where the SRS asks for one.
4. Screens: list, form, view with a History tab; buttons behind `sec:authorize`; EN and FR messages, ASCII properties.
5. Business rules in plain classes with unit tests, the SRS acceptance test included when there is one.
6. Checked in a browser: same components and sizes as the finished screens of its kind (CLAUDE.md "One look per component"), light and dark theme, French, no console errors, and every screen at 360, 390 (phone), 768 (tablet) and 1024 px (small laptop): no sideways page scroll, nothing cut off or squeezed, tables readable as cards on phones, dialogs fit.
7. `TODO.md` and `CLAUDE.md` updated; test data left in the dev database listed below.

## Definition of "fully working" (go-live checklist)

- [ ] Every M requirement of SRS phases 1 and 2 built (milestones M1–M9)
- [ ] AT-01 to AT-10 pass, in unit tests and once by hand on the real data
- [ ] Opening stock, customers, suppliers, prices and opening balances loaded (M10)
- [ ] EBM registered and tested with RRA; label printer and receipt printer tested
- [ ] Daily backups and a tested restore; HTTPS; real users and roles; approval limits set
- [ ] Staff trained per role (cashier, cutting operator, supervisor, driver, accountant, owner)

---

## M1 — Inventory operations (part 1 done 2026-10-08; counts next)

Goal: the warehouse can move, correct, reserve, count and value every piece. INV-05, INV-07..INV-10, MD-03.

- [x] Reservation rules in one place (INV-05) (M) — 2026-10-08: `StockAction` says which states allow cut, transfer, adjust, reserve, release, find; units on a pending adjustment are held (`StockService.holds` / `requireNotHeld`), checked by transfers, adjustments, cutting and reservations. Reserve a unit for a customer (with a note) and release it (reason) from its page; pieces cut for a customer now record the customer (V13 `reserved_customer_id`)
- [x] Transfers between locations (INV-07) (M) — 2026-10-08: `/stock-transfers` (V13). Scan or type the codes, choose the rack or slot; posted when saved (TRF-WH-2026-000001), one TRANSFER movement per unit, where each came from kept on the line. Refused: unknown codes, units being cut, on a vehicle or gone, held units, units already there, full sheets to an off-cut rack, over the rack's piece or kg limit. Rack orientation is not checked: units do not record how they stand (decide with the shop)
- [x] Adjustments with reason and approval (INV-07) (M) — 2026-10-08: `/stock-adjustments` (V13). Write off (damaged: BROKEN, missing: new status LOST), found again (LOST back to stock at its cost), new unit (a piece nobody recorded, at MAC), correct size (replaced by a new unit at the same cost per m², reservation kept). Up to `ADJUSTMENT_APPROVAL_LIMIT` it posts when saved; above, another person with PERM_APPROVE_ADJUSTMENT (supervisor, owner) approves or rejects with a reason, the requester can withdraw; never self-approved. MAC via `Costing.afterStockChange`. Shortcuts on the unit page: Move, Write off, Correct size, Found again. Also the way to correct a posted receipt (wrong count: write off missing or add new units; wrong size: correct size)
- [x] Stock valuation and summaries (INV-09) (M) — 2026-10-08: `/stock/summary` by glass, place or state: pieces, m², value = m² x MAC per glass (the value the inventory account will carry); CSV download (opens in Excel). Off-cut age and real Excel/PDF exports go with the reports (M6)
- [x] Reorder alerts (INV-10) (S) — 2026-10-08: glass whose AVAILABLE m² is below its reorder level, on the summary page. A dashboard card comes with the owner dashboard (M6)
- [ ] Stock counts by scanning (INV-08) (S): count a location or a product, scan units, list missing, extra and misplaced units, post the result as adjustments (with approval)
- [x] A clear message when two people change the same record at once (NFR-06) (S) — 2026-10-08: `StaleDataAdvice` sends the user back to the page they came from with "changed by someone else, reload"
- [ ] Decide whether a put-away scan is needed (units arrive RECEIVED, then AVAILABLE once racked) (S)
- [ ] Location labels (QR of the location code) for racks and slots, and a location's code fixed once its label is printed (MD-02) (S)

Done when: a unit can be transferred, written off, found, corrected and counted with its full movement history; the valuation report total equals the stock value used by the MAC.

## M2 — Accounting core: chart of accounts and posting engine

Goal: every business event posts a balanced journal in the same transaction (ACC-01, ACC-03, ACC-04, NFR-05). Must come before sales, so sales post from day one.

- [ ] Chart of accounts with a default glass-business template (ACC-03) (M): Inventory – Glass, Goods Received Not Invoiced, Accounts Payable (per currency), Accounts Receivable, Cash on Hand, Main Cash Vault, Bank, Mobile Money, Customer Deposits, Claims Receivable, Driver Float (per driver), Driver Shortage Receivable, Sales Revenue, Sales Returns, VAT Output, VAT Input, Cost of Goods Sold, Glass Spoilage Expense, Inventory Adjustment Expense, Cash Over/Short, FX Gain/Loss
- [ ] Journal entries and lines (M): JV numbers, date, period, source document, status; lines in RWF with the original currency and rate (ACC-01); append-only, debits = credits enforced in the database; corrections by reversal only
- [ ] `PostingService` with one rule per event of the posting matrix (SRS 4.9.1), called inside each module's transaction (M). Events that exist today:
  - [ ] Crate received: Dr Inventory / Cr Goods Received Not Invoiced, from the receipt cost
  - [ ] Shipment posting: import charges Dr Inventory (part that reached stock, `shipment_allocations.stock_amount`) / Cr Accounts Payable (bill with a supplier) or Cash; the expensed part to Cost of Goods Sold; the broken part to Claims Receivable while the claim is open, the shortfall of a settled or rejected claim to Glass Spoilage
  - [ ] Cut: cullet and breakage Dr Glass Spoilage / Cr Inventory (`cutting_jobs.cullet_cost` + `broken_cost`)
  - [ ] Adjustments (M1): loss Dr Inventory Adjustment Expense or Glass Spoilage / Cr Inventory; gain the reverse
- [ ] Journal viewer and trial balance (ACC-11, first part) (M), enough to check AT-10
- [ ] Test: every journal balances, and the Inventory account equals the stock valuation report (AT-10) (M)
- [ ] Go-live start: an opening-balances journal from the valuation at go-live; earlier dev events are not back-posted (S)

## M3 — Counter sales

Goal: the cashier sells stock and custom cuts, takes split payments and gives receipts (POS-01..POS-10, TAX-01, TAX-04, SRS 5.3). AT-08.

- [ ] VAT per line from the product's tax category (TAX-01) (M); buyer TIN on the invoice (TAX-04) (M)
- [ ] Till sessions: open with a float, close with counted cash, differences recorded (POS-10) (M)
- [ ] POS screen, two panes (POS-01, POS-02, POS-04) (L): search or scan stock (smallest fit first), custom sizes priced by chargeable area with `PriceListService.priceFor` + `Pricing.chargeableArea` (price, list and VAT flag stored on the line); a custom size creates a linked cutting job; cart; payment split over cash, mobile money (reference), card, bank transfer and customer credit
- [ ] Sales invoice and receipt: INV numbers, sold units leave stock (SOLD movement), journal (cash or credit sale, VAT, COGS at MAC), printable receipt as PDF (M)
- [ ] Credit limits: over the limit needs a manager's approval (POS-05) (M); discounts and price overrides above the role limit (`DISCOUNT_APPROVAL_PERCENT`) need approval with a reason (POS-06) (M)
- [ ] Quotations with validity, converted to an order or invoice (POS-03) (M); sales orders with deposits and balance on collection (POS-08) (S); cut pieces RESERVED for the order until collected
- [ ] Returns and credit notes against the original invoice, glass back to stock or to cullet (POS-09) (M)
- [ ] Core sale in 6 clicks or fewer; save, post and print in under 3 s without EBM (NFR-02, NFR-14) (S)

## M4 — EBM / VSDC fiscal signing

Goal: every sale, credit note and copy is signed by RRA (TAX-02, TAX-03, POS-07). AT-09.

- [ ] Confirm the business's EBM registration and VSDC device setup; confirm the tax type letters seeded in V5 (A exempt, B 18%, C zero-rated) (M)
- [ ] VSDC client behind an interface, with a simulator for development and tests (M). Reuse QT Global's VSDC experience
- [ ] Submission queue: a sale completes even when EBM is down; retries with backoff; unsigned invoices and their age on the dashboard (TAX-03) (M)
- [ ] Signature, receipt number and QR code on the invoice and receipt (TAX-02) (M)

## M5 — Customer and supplier accounts, closing the books

Goal: the accountant runs receivables, payables and the month end (ACC-05, ACC-08..ACC-12, TAX-05). AT-10.

- [ ] Customer subledger: invoices, receipts, credit notes, balance and ageing on the customer page (ACC-09) (M)
- [ ] Supplier invoices against receipts (Dr GRNI / Cr AP in the supplier's currency), supplier payments, shipment bills paid to a supplier in its balance, ageing (ACC-09) (M)
- [ ] Realised FX gain/loss when a foreign invoice is paid at another rate (ACC-08) (S); revaluation of open foreign balances at month end (S)
- [ ] Manual journals with approval; reversal, never delete (ACC-05) (M)
- [ ] Monthly period close; posting into a closed period is refused (ACC-10) (M)
- [ ] Trial balance, general ledger, income statement, balance sheet, Excel export (ACC-11) (M)
- [ ] Monthly VAT report, output and input (TAX-05) (S)
- [ ] Bank and mobile-money reconciliation (ACC-12) (S)

## M6 — Dashboard, reports, alerts

Goal: the owner sees the business at a glance and is told when something goes wrong (RPT-01..RPT-07).

- [ ] Owner dashboard: today's sales by channel and vehicle, stock value, cash position, open audit cases, unsigned invoices (RPT-01) (M)
- [ ] Stock reports: on hand by product and location, off-cut ageing, slow-moving stock (RPT-02) (M)
- [ ] Production reports: done in `/cutting-jobs/yield` (RPT-03); add export
- [ ] Sales reports: by customer, product, salesperson, period; gross margin per invoice at MAC (RPT-05) (M)
- [ ] PDF and Excel export for every report (RPT-07) (M)
- [ ] Notifications: bell, list, mark read, ported from iVura; email; alerts for EoD discrepancy, low stock, licence and insurance expiry, float not cleared within 24 h, EBM failures (RPT-06) (S)
- [ ] MinIO `FileStorageService` ported from iVura, for breakage photos and documents (M, needed by M8 and M9)

## M7 — Fleet: vehicles, drivers, trips, loading

Goal: vehicles are moving shops with controlled loading (FLT-01..FLT-07, FLT-12, MD-02). AT-03.

- [ ] Vehicles with plate, model, rack configuration, max kg and pieces, insurance and inspection expiry; each vehicle creates its own VEHICLE location (no parent), never edited on the Locations screen (FLT-01, FLT-02) (M)
- [ ] Drivers linked to users: national ID, licence number, category and expiry, default vehicle (FLT-03) (M); personal data handled per Law No. 058/2021 (NFR-12)
- [ ] Expired licence, insurance or inspection blocks a trip; alert 30 days before (FLT-04) (M)
- [ ] Trips (vehicle, driver, date, area) with a loading manifest of planned units; TRIP numbers exist (FLT-05) (M)
- [ ] Scan-load: units not on the manifest refused; departure refused over the vehicle's kg or piece limit (FLT-06) (M)
- [ ] Departure moves units to the vehicle location, ON_VEHICLE, and the driver becomes accountable (FLT-07) (M). The SRS says IN_TRANSIT; the code has ON_VEHICLE (SRS 6.2): keep ON_VEHICLE
- [ ] Odometer and fuel per trip (FLT-12) (C)

## M8 — Mobile POS app with offline sync

Goal: drivers sell from their vehicle stock on a phone, with or without network (MPOS-01..MPOS-07, SYNC-01..SYNC-08, NFR-03, NFR-10). AT-04, AT-05.

- [ ] Versioned JSON API under `/api/v1/**` with per-device, revocable tokens (NFR-10) (M)
- [ ] Installable PWA (Service Worker, IndexedDB), 360 px and up, large touch targets (MPOS-01, NFR-14) (L)
- [ ] Trip start download: manifest, vehicle stock, price list, customers, a block of offline invoice numbers (SYNC-01) (M)
- [ ] Sell only units in the driver's own vehicle, by scan or from the list; prices from the downloaded list, discount within a limit (MPOS-02..MPOS-04) (M)
- [ ] Cash and mobile money with reference; receipt printed or shared by SMS/WhatsApp (MPOS-05) (M)
- [ ] Offline queue with an "offline / N pending" indicator; client UUIDs; idempotent sync in creation order with retry and backoff; conflicts flagged for the supervisor, never dropped (SYNC-02..SYNC-05) (L)
- [ ] EBM on sync; offline receipts show "pending signature", signed copy retrievable (SYNC-06) (M)
- [ ] Trip cannot close with items pending; device data cleared at trip close (SYNC-07, SYNC-08) (M)
- [ ] `X-Device-Id`, `X-Client-Time`, `X-Request-Id` headers for the audit trail (AUD-07) (S)
- [ ] Mobile cash sales post to the driver's float account (ACC-06) (M)

## M9 — End of day: return, reconciliation, audit cases, floats

Goal: every unit and every franc that left in the morning is accounted for at night (FLT-08..FLT-11, MPOS-06, MPOS-07, ACC-06, ACC-07, RPT-04). AT-06, AT-07.

- [ ] En-route breakage with photo, reason and place, validated by the supervisor at EoD (FLT-08, MPOS-06) (M)
- [ ] Driver's cash declaration before trip closure (MPOS-07) (M)
- [ ] Return scan: expected closing stock = loaded − sold − documented breakage, compared with the scan; units back to their racks (FLT-09) (M)
- [ ] Any difference opens an audit case for the supervisor and notifies the owner; the trip cannot close until it is resolved or escalated (FLT-10) (M)
- [ ] Unresolved shortages charged to the driver's receivable with the owner's approval (FLT-11) (S)
- [ ] Float clearance by the accountant: cash to the main vault or bank; shortage to Driver Shortage Receivable, overage to Cash Over/Short (ACC-06, ACC-07) (M)
- [ ] Fleet reports: sales per trip and driver, EoD discrepancies, breakage rate per driver, floats outstanding (RPT-04) (M)

## M10 — Hardening and go-live

- [ ] Account lockout after 5 failed logins (`users.failed_login_attempts`, `locked_until`) (NFR-09) (M)
- [ ] Profile page, change password, forgot/reset password by single-use email link valid 60 minutes (NFR-09) (M)
- [ ] MockMvc tests: every handler refuses a user without its PAGE_/PERM_ (NFR-10) (S)
- [ ] Testcontainers integration tests on PostgreSQL: one audited save gives one change row and a rollback none (AUD-04); two concurrent `DocumentNumberService.next()` calls get different numbers and a rollback gives the number back (MD-07); journals balance under concurrency (M)
- [ ] Test coverage of at least 70% on posting, costing and sync logic (NFR-16) (S); CI build and tests on every push (S)
- [ ] Separate database login for the app without UPDATE/DELETE on audit and ledger tables (S)
- [ ] Docker image and compose file for a Linux server, configuration from the environment, HTTPS (TLS 1.2+) (NFR-18, NFR-09) (M)
- [ ] Daily full backup and WAL archiving, RPO 15 min, RTO 4 h, restore tested (NFR-08) (M)
- [ ] Structured logs, health checks, alerts on errors, EBM backlog and failed syncs (NFR-17) (S)
- [ ] Performance: stock search under 1 s with 100,000 units, 50 back-office users and 30 vehicles (NFR-01, NFR-04) (M)
- [ ] Dates shown DD/MM/YYYY, RWF without decimals everywhere (NFR-15) (S); Kinyarwanda translations (NFR-15) (S)
- [ ] Opening data: products, racks, customers, suppliers, prices, opening stock (migrated or counted fresh, with labels) and opening balances (M)
- [ ] Test the labels on the real printer: the HTML page at 100% (50 x 30 mm) and the ZPL file on the Zebra (INV-03) (M)
- [ ] OWASP Top 10 review and a penetration test (NFR-11) (S)

## Phase 3 — After go-live (could)

- [ ] Approval matrix (ADM-04); mobile-money provider APIs
- [ ] Processing steps (tempering, edging, drilling) as job stages with status (PRD-10). Job lines already carry the processing asked for
- [ ] Cullet sold as scrap by weight (PRD-11). Each job records its cullet kg
- [ ] On-site cutting by the driver, if a vehicle is set up for it (MPOS-08)
- [ ] Audit export to Excel/PDF (AUD-12); monthly partitioning (AUD-13); hash chain (AUD-14)
- [ ] Multiple branches from one installation (ADM-05)
- [ ] Automatic download of BNR daily rates when the server has internet (ACC-02)

---

## Questions for the business

Answers change what gets built; record each answer next to the question.

**Glass and production**
- [ ] Off-cut threshold: built as 0.25 m² and both sides at least 300 mm (Settings). Confirm both conditions (SRS 8.3)
- [ ] One sheet or off-cut per cutting job; bigger orders become several jobs or use "Cut the rest". Confirm
- [ ] Breakage reasons: handling, cutting error, glass defect, tool or table, other. Confirm the list
- [ ] Is tempering done in-house or outsourced? Should cullet be sold as scrap, and at what price basis? (SRS 8.3)
- [ ] Starting product range (18 seeded) and the real warehouse layout and rack limits (seeded: WH, zone WH-A, racks R01, R02, off-cut rack OC) (MD-01, MD-02)

**Purchasing and landed cost**
- [ ] Do suppliers quote per m² (built), per sheet or per tonne? (PRC-01)
- [ ] May a receipt bring more sheets than ordered? Built: refused. If suppliers add spare sheets, how are they priced? (PRC-02)
- [ ] Should sheets broken on arrival carry their share of freight and duty, so the claim includes it (built), or should the good sheets absorb it? (PRC-06)
- [ ] Is the RRA customs rate entered under Currencies & Rates for each date? Duty is converted at the CUSTOMS rate of the bill date (PRC-05)
- [ ] Who posts import bills: Procurement only (built), or also or instead the Accountant? (SRS 2.2)
- [ ] A bill posted after a sheet was cut is expensed for that sheet (built). Should it go to its pieces and off-cuts still in stock instead? (PRC-05, PRD-07)
- [ ] Which currencies does the business buy in (seeded active: USD, EUR, CNY)? Is any sale ever invoiced in a foreign currency? (ACC-02)

**Sales and money**
- [ ] Approvals of stock adjustments: built as the supervisor or the owner, never the person who asked; limit 0 so every adjustment needs approval. Confirm who approves and the limit (INV-07, ADM-04)
- [ ] Real selling prices: RETAIL holds test prices only (CLR-6 27,000, LAM-6.38 41,000.50, edging 1,500, drilling 500), plus a test CONTRACTOR list (MD-06)
- [ ] Fixed price lists, or negotiated per customer? Built: one list per customer group, the default list fills the gaps; a negotiated customer gets their own list (SRS 8.3)
- [ ] Who does what: procurement keeps suppliers; cashier and accountant add customers; only the accountant sets credit terms; only the owner sets prices (SRS 2.2)
- [ ] Approval limits: adjustments and discounts default to 0 (everything needs approval). Agree real values (INV-07, POS-06)
- [ ] Which mobile-money providers and card terminals are used? (SRS 8.3)
- [ ] Is the business registered on EBM, and with which VSDC setup? (SRS 8.3)

**Fleet and go-live**
- [ ] Do vehicles carry only pre-cut pieces, or also full sheets for on-site cutting? (SRS 8.3)
- [ ] How many vehicles, drivers, users and branches at go-live and in 2 years? (SRS 8.3)
- [ ] Is opening stock migrated from an existing system or counted fresh? (SRS 8.3)

## Dev database: test data to clear before go-live

- Purchase orders PO-WH-2026-000001 (Shandong, received), -000002 (Kigali Glass, closed short), -000003..8 (cancelled), -000009 (AT-01, received)
- GRN-WH-2026-000004 put 20 units on the test rack WH-A-R04
- Shipments SHP-WH-2026-000002 (AT-01, costs complete), -000003 (by value, claim settled), -000001 and -000004 (cancelled); a test CUSTOMS rate of 1,449 on 2026-10-07
- Cutting jobs CUT-WH-2026-000001 (AT-02 on U-WH-000036 for Umucyo Builders: 3 reserved pieces on WH-A-R04, off-cut U-WH-000059), -000002 (cut that off-cut, with breakage), -000003..5 (cancelled)
- 60 stock units; MAC on CLR-6, CLR-8, MIR-4
- Transfer TRF-WH-2026-000001 (U-WH-000038 to WH-A-R01); adjustments ADJ-WH-2026-000001 (U-WH-000039 broken), -000002 (rejected), -000003 (U-WH-000040 resized to U-WH-000061), -000004 (withdrawn)
- Automated-check user qa-admin (ADMIN role, created 2026-10-08 so scripted browser checks never sign the real admin out: one session per user) — disable before go-live
- Responsive checks: orders PO-WH-2026-000010 and -000011 and cutting jobs CUT-WH-2026-000006 and -000007, all cancelled ("Responsive check"); U-WH-000055 taken and released twice
- Disabled test users (cashier*, auditor*, super4032); test owner user owner38648 (password kept out of the repo) — disable before go-live
- Test prices on the RETAIL and CONTRACTOR lists

---

## Done log

### Foundation (2026-10-07)
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
- [x] Users, Roles, Permissions screens ported from iVura; "delete user" became "disable user" (ADM-01) — POST-only state changes, lock-out guards, sessions end on disable/reset/role change, V4 read access for OWNER/AUDITOR
- [x] Role-permission and role-page changes audited explicitly (AUD-03) — `UPDATE_ROLE_ACCESS` / `UPDATE_USER_ROLES` activity entries after commit
- [x] Settings screen: off-cut threshold, glass density, approval limits, numbering, tax categories (ADM-03) — `/settings` tabs General / Tax categories / Document numbering / History; V5 seeds, OWNER and AUDITOR read-only
- [x] Document numbering per type and branch, e.g. `INV-WH-2026-000123` (MD-07) — `DocumentNumberService.next(type)` under a row lock; yearly/monthly/never reset; next number only goes up; reset policy fixed after first use (V6)
- [x] Exchange rates by date and source (ACC-02) — `/currencies` (V7). Currencies with RWF as fixed base; rates per currency/date/source (BNR, Customs, Bank, Manual); `ExchangeRateService.rateFor()` refuses missing or stale rates (Settings: default source, max age); >10% jumps need confirming; corrections need a reason; CSV import, all or nothing

### Master data (2026-10-08)
- [x] Glass products: type × thickness, VAT category (MD-01) — `/products` (V8). Type + colour/finish + thickness (NUMERIC(5,2) for 6.38 mm laminated), unique together and fixed after creation; suggested codes (CLR-6, TNT-BRONZE-5); weight per m² from the density setting; reorder level (INV-10); tempered marked "made to size"
- [x] Locations: Site → Zone → Rack → Slot; rack limits kg/pieces (MD-02, MD-03) — `/locations` (V8). Tree page; the parent decides the type; next free code suggested (WH-A-R03); rack weight, pieces, orientation and off-cut flag; deactivate leaf first, reactivate under an active parent; VEHICLE type reserved for the Fleet module
- [x] Stock guards for master data, part 1: a location or product holding stock stays active; a product on open purchase order lines and a supplier with open orders stay active; receipts check rack piece and weight limits (slots count towards their rack)
- [x] Customers with TIN, credit limit, terms, price list (MD-04); suppliers with currency (MD-05) — `/customers`, `/suppliers` (V9). Codes from the numbering service (CUS-WH-00001, SUP-WH-0001); one customer per TIN; walk-ins never get credit; credit terms need PERM_MANAGE_CUSTOMER_TERMS (accountant); default WALK-IN customer; suppliers with ISO country, invoicing currency, Incoterms 2020, TIN (9 digits if Rwandan); a currency stays active while active suppliers use it
- [x] Price lists per m², processing surcharges, minimum chargeable area 0.25 m² (MD-06) — `/price-lists` (V9). Default list RETAIL fills what a customer's list leaves unpriced (`PriceListService.priceFor`); VAT included/excluded per list; minimum area per list or Settings; per-list price history; processing services per m², metre of edge, piece or hole

### Purchasing, receiving, stock units (2026-10-08)
- [x] Purchase orders in supplier currency (PRC-01) — `/purchase-orders` (V10). Draft → placed → partly received / received; cancel (nothing received) or close short, with a reason; lines priced per m² in the supplier currency, total rounded once
- [x] Crate receiving: one stock unit per sheet + label print (PRC-02, INV-03) — `/goods-receipts` (V10). Draft receipt with one row per crate; never more than the line waits for; posting fixes the receipt-date rate and creates AVAILABLE units U-WH-000001 on their racks; labels 50 x 30 mm with QR as a print page or ZPL (`/stock/labels`)
- [x] MAC per m² on the product (PRC-05) — `products.mac_per_m2` (RWF) moves with receipts, landed costs and cuts; products are locked while posting
- [x] `StockUnit` entity, area and weight computed, status lifecycle (INV-01, INV-02; SRS 6.2) — the 8 SRS states; size, area, weight fixed; only `StockService` changes status, location or cost. RECEIVED is unused until a put-away scan exists
- [x] Immutable stock movements for every location/status change (INV-04) — `stock_movements` append-only; RECEIPT and CUTTING_* movements so far
- [x] Smallest-fit search: "at least 1200 × 800 in 6 mm clear" (INV-06) — on `/stock`, either way round, available pieces only, smallest area first; a scanned label code opens the unit

### Landed cost (2026-10-08)
- [x] Shipment cost sheet: freight, insurance, duty, clearing, port, transport, other, each in its own currency (PRC-03) — `/shipments` (V11). Links posted receipts; bills are drafts until posted; later bills are the next posting; a posted bill is corrected by a credit note; close when complete, cancel (reason) while nothing is posted
- [x] Cost allocation by area / value / weight; landed cost per m² in RWF; MAC update (PRC-04, PRC-05) — each bill at the rate of its date (duty: customs rate), total rounded once, split over crates and sheets to the franc cent; sheets in stock get their part (`stock_cost_entries`, append-only); parts of sheets gone are expensed; parts of broken sheets go to the claim. AT-01 checked in `LandedCostTest` and in the browser
- [x] Broken-on-arrival claims (PRC-06) — broken sheets valued at purchase cost plus their share of import costs; claim recorded, then settled (amount received) or rejected (reason)

### Production (2026-10-08)
- [x] Cutting jobs for stock or a customer; fit check on the source; tempered refused (PRD-01, PRD-02) — `/cutting-jobs` (V12). Sizes with quantity, processing and the customer's mark; "Take a sheet" suggests units the pieces fit in, smallest first, or takes a scanned code; the unit goes IN_CUTTING; put back or cancel with a reason until cut
- [x] Recording the cut: source consumed, cut pieces and off-cuts as new units, cullet (PRD-03..PRD-05) — leftovers from the threshold (Settings) become off-cuts on an off-cut rack, smaller ones and the trim are cullet (m² and kg); pieces for a customer RESERVED, for stock AVAILABLE; rack limits checked; labels from the job page; `cutting_job_outputs` append-only
- [x] Area conservation within 1% and cost flow by area (PRD-06, PRD-07) — the sheet cost is shared by area to the franc cent (`CUTTING` cost entries), cullet and breakage expensed, MAC via `Costing.afterCut`; live area check on the form. AT-02 checked in `CuttingTest`, `CuttingJobServiceTest` and in the browser
- [x] Breakage with reason; yield report (PRD-08, PRD-09, RPT-03) — breakage while cutting with a reason code and note; "Cut the rest" makes a linked job; `/cutting-jobs/yield` by operator and glass for a period, breakage by reason

### Responsive layout (2026-10-08)
- [x] Every screen works from 360 px phones to desktops — all 86 reachable pages checked at 360, 390, 768, 1024 and 1280 px, plus the 7 that need a draft (order and job edit, receipt, shipment edit, taking a sheet, recording a cut) at 360 to 1024 px, with their dialogs. Phones and tablets (768 px and less) open the menu as a drawer from a button in the header (it was hidden there before, so most screens could not be reached). On phones (640 px and less) table rows become cards with each value labelled by its column, line forms included; list filters wrap two per row. Line forms keep usable field widths on tablets and scroll in their box. Known limit: a long rack choice ("WH-A-R04 · Rack R04 · 21/30 pcs · 1,234/3,000 kg") is cut short in the closed select on a phone; the phone picker shows it in full

### Design consistency (2026-10-08)
- [x] One look per component on every screen — measured on all 81 screens (computed styles per component, the odd ones out listed), then fixed at the source: buttons and fields now use the page font (form controls were in Arial on 42 screens) and one height (42 px, small 30, filters 38, line forms 38); one monospace font; links to other records styled (some were browser-blue); one note style (doc notes and form notes matched, red when cancelled); header actions one gap, Back first, no squeezed button column; counts as plain numbers; one date format (yyyy-MM-dd [HH:mm], seconds in logs only, was 7 formats); numbers through @num everywhere (dashboard used the locale's grouping); red tint as a theme token. Rules in CLAUDE.md "UI"
