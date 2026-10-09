# TODO — iWarehouse

The road from today's code to a fully working warehouse, sales and fleet system for the glass business.

## How to use this file

- Read `CLAUDE.md` first. Requirement IDs (`INV-07`, `FLT-06`...) refer to SRS v2.1 in Claude Docs: "SRS — Glass Reproduction Warehouse & Mobile POS (ERP-Lite)" (https://claude.ai/code/artifact/96a595dc-fe7b-4e23-846e-91b60f43ab12).
- Build the milestones in order, top to bottom: each one needs the ones above it. Inside a milestone, build the items in order too.
- Priorities: **M** must, **S** should, **C** could. Sizes: (S) a day or less, (M) a few days, (L) a week or more.
- When an item lands: tick it, add the date and one line saying where it lives (screen, migration, main class), then move the finished milestone to the Done log at the end. Put anything the owner must decide under "Questions for the business".
- **Next migration: V27.** Stop the app before writing it (CLAUDE.md, Database).
- A module is done when it meets the definition of done below.

## Where we are (2026-10-09)

| Area | State | Where |
|---|---|---|
| Foundation: login, RBAC, audit trail, layout, EN/FR | Done | V1–V4 |
| Settings, document numbering, currencies and exchange rates | Done | V5–V7, `/settings`, `/currencies` |
| Master data: products, locations, suppliers, customers, price lists | Done | V8–V9 |
| Purchasing and receiving: orders, crates, stock units, labels | Done | V10, `/purchase-orders`, `/goods-receipts`, `/stock` |
| Landed cost and claims (AT-01 passes) | Done | V11, `/shipments` |
| Cutting jobs, off-cuts, cullet, yield (AT-02 passes) | Done | V12, `/cutting-jobs` |
| Inventory operations: reservations, transfers, adjustments, valuation, reorder | Done (M1) | V13, `/stock-transfers`, `/stock-adjustments`, `/stock/summary` |
| Stock counts by scanning | Done (M1) | V14, `/stock-counts` |
| Rack and slot labels; put-away decided (none) | Done (M1) | V15, labels from `/locations/{id}` |
| Accounting core: chart of accounts, posting engine, journals, trial balance (AT-10 passes) | Done (M2) | V16, `/accounting/journals`, `/accounting/accounts`, `/accounting/trial-balance` |
| Counter sales, part 1: tills, POS for stock units, VAT, split payment, invoices, receipts (AT-08 passes) | Done (M3, part 1) | V17, `/pos`, `/invoices`, `/till-sessions` |
| Counter sales, part 2: custom cut sizes, credit and discount approvals, quotations, deposits, returns and order cancellations, performance | Done (M3) | V18–V23, `/pos`, `/invoices`, `/sale-approvals`, `/quotations`, `/credit-notes` |
| EBM / VSDC fiscal signing | Deferred (M4): after M5, at the owner's request (2026-10-09) | — |
| Customer and supplier accounts, period close, financial statements | **Next** (M5) | — |
| Dashboard, reports, alerts, notifications, files | Not started (M6) | — |
| Fleet: vehicles, drivers, trips, loading | Not started (M7) | — |
| Mobile POS app (PWA) with offline sync | Not started (M8) | — |
| End of day: return scan, reconciliation, audit cases, driver floats | Not started (M9) | — |
| Hardening and go-live | Not started (M10) | — |

Unit tests: 375, all passing. Acceptance tests (SRS 8.1): AT-01, AT-02, AT-08 and AT-10 (for the events built so far) pass; AT-03 to AT-07 and AT-09 wait for their milestones.

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

## M4 — EBM / VSDC fiscal signing (deferred: done after M5, at the owner's request, 2026-10-09)

Goal: every sale, credit note and copy is signed by RRA (TAX-02, TAX-03, POS-07). AT-09.

- [ ] Confirm the business's EBM registration and VSDC device setup; confirm the tax type letters seeded in V5 (A exempt, B 18%, C zero-rated) (M)
- [ ] VSDC client behind an interface, with a simulator for development and tests (M). Reuse QT Global's VSDC experience
- [ ] Submission queue: a sale completes even when EBM is down; retries with backoff; unsigned invoices and their age on the dashboard (TAX-03) (M)
- [ ] Signature, receipt number and QR code on the invoice and receipt (TAX-02) (M)

## M5 — Customer and supplier accounts, closing the books

Goal: the accountant runs receivables, payables and the month end (ACC-05, ACC-08..ACC-12, TAX-05). AT-10.

- [x] Customer subledger: invoices, receipts, credit notes, balance and ageing on the customer page (ACC-09) (M) — 2026-10-09 (V24): the customer page's Account tab shows what they owe, the overdue part, the oldest due date and the ageing (not due, 1-30, 31-60, 61-90, over 90 days: payments settle the oldest charges, each due its date plus the payment terms), and the statement: every receivable line naming them (sales on credit, order balances, credit notes, payments) with the balance after it. "Take a payment" (RCT-WH-2026-000001) takes at most what they owe: cash into the cashier's till (change from what was handed over), mobile money, card or transfer (Dr that account / Cr receivable); an 80 mm receipt prints what is still owed. `/customer-payments` lists them; `/accounting/receivables` ages every customer owing, with the totals; the till counts the cash taken on accounts ("Account payments" tab)
- [x] Supplier invoices against receipts (Dr GRNI / Cr AP in the supplier's currency), supplier payments, shipment bills paid to a supplier in its balance, ageing (ACC-09) (M) — 2026-10-09 (V25): the supplier page's Account tab shows what is owed (RWF as booked, and per currency), the ageing at the supplier's terms, the posted goods receipts not invoiced yet and the statement. "Record an invoice" (SINV-WH-2026-000001) ticks the receipts the supplier's invoice bills, its number, date (due + terms) and total, which must match: each receipt moves Dr GRNI / Cr AP at its own value and rate. Shipment bills naming a supplier are payables already. "Pay" (SPAY-WH-2026-000001) pays a currency owed, by transfer, cash from the vault or mobile money, at today's rate, up to what is owed; it settles the oldest items. `/supplier-invoices`, `/supplier-payments`, `/accounting/payables` (aged per supplier, with the foreign amounts). Receipts posted before the ledger started (GRN-WH-2026-000001..4) have no GRNI line and are not offered
- [x] Realised FX gain/loss when a foreign invoice is paid at another rate (ACC-08) (S) — 2026-10-09 (V25): a supplier payment settles the RWF its items were booked at; the RWF paid at the day's rate less that is the gain or loss, on the payment and its journal (FX gain/loss account)
- [x] Revaluation of open foreign balances at month end (unrealised FX, reversed the next day) (S) — 2026-10-09 (V26): `/accounting/fx-revaluations` (FX Revaluations, Accounting). "Revalue a month" offers each ended month not revalued yet, from the ledger's first month: what is owed in a foreign currency at its last day on Accounts Payable and GRNI (per supplier) and Accrued Import Charges, at that day's rate (a missing or stale rate is shown and stops the posting). Posting (FXR-WH-2026-000001) keeps each line's rate and posts the differences to the new account 5070 Unrealised FX Gain/Loss on the last day, reversed the next day; supplier balances, payments and the ageing keep the RWF items were booked at. Journals now link a reversal and the journal it reverses (`reverses_id`), ready for manual journals. Once per month: a document dated into a revalued month later is not revalued (the period close, ACC-10, will refuse it)
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
- [ ] RWF without decimals everywhere (NFR-15) (S); Kinyarwanda translations (NFR-15) (S). Dates shown DD/MM/YYYY: done 2026-10-08
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
- [ ] Starting product range (18 seeded) and the real warehouse layout and rack limits (seeded: WH, zone WH-A, racks R01, R02, off-cut rack OC) (MD-01, MD-02). Agree the rack codes before printing their labels: a printed code is fixed
- [ ] Do crates wait in a receiving area before they are racked? Built: no put-away step, the receipt puts each crate on its rack. If they wait, add a put-away scan (RECEIVED, then AVAILABLE once racked) (INV-02)
- [ ] French word for a rack: the location screens say "Rack" ("Rack à chutes"), cutting, transfers and adjustments say "râtelier". Pick one

**Purchasing and landed cost**
- [ ] Do suppliers quote per m² (built), per sheet or per tonne? (PRC-01)
- [ ] May a receipt bring more sheets than ordered? Built: refused. If suppliers add spare sheets, how are they priced? (PRC-02)
- [ ] Should sheets broken on arrival carry their share of freight and duty, so the claim includes it (built), or should the good sheets absorb it? (PRC-06)
- [ ] Is the RRA customs rate entered under Currencies & Rates for each date? Duty is converted at the CUSTOMS rate of the bill date (PRC-05)
- [ ] Who posts import bills: Procurement only (built), or also or instead the Accountant? (SRS 2.2)
- [ ] A bill posted after a sheet was cut is expensed for that sheet (built). Should it go to its pieces and off-cuts still in stock instead? (PRC-05, PRD-07)
- [ ] Which currencies does the business buy in (seeded active: USD, EUR, CNY)? Is any sale ever invoiced in a foreign currency? (ACC-02)

**Accounting**
- [ ] The chart of accounts (V16) is a template in English: confirm the numbering and names with the accountant, and whether they should read in French (ACC-03)
- [ ] Import bills entered without a supplier (duty, port charges) wait in Accrued Import Charges until their payment is recorded (M5). Are they paid in cash at the border, by bank, by the clearing agent? (SRS 4.9.1: "Accounts Payable / Cash")
- [ ] Import VAT paid at customs is recoverable (VAT Input), not a cost of the glass: keep it out of the shipment bills. Confirm how it is paid and declared (TAX-05)
- [ ] Import costs reaching glass already sold or cut go to Cost of Goods Sold (built). Agree
- [ ] Sheets broken on arrival are expensed to Glass Spoilage at receipt; a claim brings back what it recovers (built). Agree

**Sales and money**
- [ ] Contractor prices exclude VAT: the POS adds 18% to them; retail prices include it (V9 lists). Confirm, and whether a VAT-excluded list should print net prices on the invoice
- [ ] Card payments are debited to Bank (built). Do card settlements arrive in a separate account, net of fees? Which mobile-money accounts receive payments?
- [ ] Pieces reserved before V13 (cut for a customer before reservations recorded the customer) can be sold to anyone. Release or re-reserve them (U-WH-000056..58)
- [ ] Approvals of stock adjustments: built as the supervisor or the owner, never the person who asked; limit 0 so every adjustment needs approval. Confirm who approves and the limit (INV-07, ADM-04)
- [ ] Real selling prices: RETAIL holds test prices only (CLR-6 27,000, LAM-6.38 41,000.50, edging 1,500, drilling 500), plus a test CONTRACTOR list (MD-06)
- [ ] Fixed price lists, or negotiated per customer? Built: one list per customer group, the default list fills the gaps; a negotiated customer gets their own list (SRS 8.3)
- [ ] Who does what: procurement keeps suppliers; cashier and accountant add customers; only the accountant sets credit terms; only the owner sets prices (SRS 2.2)
- [ ] Approval limits: adjustments and discounts default to 0 (everything needs approval); per role, the owner gives any discount (V19). Agree real values: the cashier's discount limit, and who approves credit over the limit (built: the owner) (INV-07, POS-05, POS-06)
- [ ] Returns: built for the cashier, the owner and the admin (RETURN_SALE), with a reason and no approval; cash refunds only as far as the till holds. Should a refund above an amount, or cullet returns (no glass back on the rack), need the owner's approval? (POS-09, ADM-04)
- [ ] Deposits on orders: built as SRS 5.3 says (the invoice and its VAT at the deposit, the balance a receivable until collection), the smallest deposit 50% of the order (Settings). Confirm the share with the owner, and with the accountant that VAT at the deposit is right for EBM (POS-08)
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
- Transfers TRF-WH-2026-000002 and -000003 (U-WH-000002 to WH-A-R04 by its scanned label, and back to WH-A-R01)
- Counter sales on 2026-10-09 by qa-admin: tills TILL-WH-2026-000001..6 (all closed; -000002 sold U-WH-000061 and U-WH-000011 on INV-WH-2026-000001, 389,408 RWF to "Jean Habimana", TIN 102938475, half cash half mobile money MP-778812, and closed 500 RWF short, "Change given twice"; the others opened and closed for checks without a sale), and their journals
- FX revaluation tests on 2026-10-09: USD BNR rate of 30/09 recorded as 1,455 (replace with the real rate); PO-WH-2026-000017 (Shandong, 1 sheet CLR-6) received as GRN-WH-2026-000006 (E2E-FXR-1, on WH-A-R02, at 1,470) and invoiced as SINV-WH-2026-000002 (INV-E2E-FXR, 31.42 USD, dated 30/09/2026, still owed); FXR-WH-2026-000001 revalued September (payable +468.18, GRNI -468.18, nothing to Unrealised FX) with JV-WH-2026-000072 and its reversal -000073. Journals up to JV-WH-2026-000073
- Supplier account tests on 2026-10-09: USD BNR rate of 09/10 recorded as 1,460 then corrected to 1,470 (replace with the real rate); PO-WH-2026-000016 (Shandong, 2 sheets CLR-6) received as GRN-WH-2026-000005 (E2E-SUP-1, on WH-A-R02), invoiced as SINV-WH-2026-000001 (INV-E2E-001, 62.84 USD) and paid by SPAY-WH-2026-000001 (SWIFT-E2E, 634.60 FX loss); PO-WH-2026-000013..15 cancelled. Journals up to JV-WH-2026-000069
- Customer account tests on 2026-10-09: RCT-WH-2026-000001 (Umucyo, 100,000 by transfer TRF-901) and -000002 (Umucyo, 50,000 cash, 60,000 handed over, in TILL-WH-2026-000027, closed): Umucyo now owes 46,019. Journals up to JV-WH-2026-000066
- Order cancellation tests on 2026-10-09: CN-WH-2026-000003 and -000004 gave up the two pieces of INV-WH-2026-000012 (6,750 by mobile money MP-7781, 6,750 by transfer TRF-55); CUT-WH-2026-000015 cancelled "Order cancelled". Journals up to JV-WH-2026-000063
- Timing tests on 2026-10-09 (NFR-02): INV-WH-2026-000015..21 sold U-WH-000042, -44, -43, -20, -21, -22 and -23 for cash (qa-admin, TILL-WH-2026-000023..26, closed)
- Return tests on 2026-10-09: INV-WH-2026-000013 (U-WH-000041 sold for cash, kept), INV-WH-2026-000014 (U-WH-000043 sold for cash, back on WH-A-R04 by CN-WH-2026-000001, 195,008 refunded from TILL-WH-2026-000022, closed), CN-WH-2026-000002 on INV-WH-2026-000010 (Umucyo: U-WH-000018 back as cullet, 196,019 to their account: they now owe 196,019 less). Journals up to JV-WH-2026-000050
- Deposit tests on 2026-10-09: INV-WH-2026-000012 (qa-admin, walk-in "Mukamana Alice", a size CLR-6 600 x 400 x 2, 13,500): deposit 7,000 in TILL-WH-2026-000018, balance 6,500 taken in TILL-WH-2026-000019 (10,000 handed over, 3,500 change), both closed; CUT-WH-2026-000015 still a draft job. Journals up to JV-WH-2026-000040
- Quotation tests on 2026-10-09: QUO-WH-2026-000001 (qa-admin, Mugisha Eric, rung up and paid as INV-WH-2026-000011 with U-WH-000015 and a size cut by CUT-WH-2026-000014, still a draft job; till TILL-WH-2026-000016), -000002 (owner38648, Umucyo, 10% off, rung up, the sale cancelled, then cancelled "Test"), -000003 (a copy of -000001, cancelled). Journals up to JV-WH-2026-000036
- Approval tests on 2026-10-09 (qa-admin cashier with the ADMIN role's limit set to 5% for the test and put back; owner38648 approving): APR-WH-2026-000001..7 (approved, rejected, withdrawn; 7 is credit), INV-WH-2026-000008 and -000009 (U-WH-000014 and -000017 at 23,760/m², 12% off), INV-WH-2026-000010 (U-WH-000018 and -000019 to Umucyo Builders Ltd, 392,038 RWF on credit: Umucyo owes it; its limit was lowered to 100,000 for the test and put back to 5,000,000), tills TILL-WH-2026-000013..15, journals up to JV-WH-2026-000034
- Custom cut sales on 2026-10-09 by qa-admin (2 pieces CLR-6 600 x 400 with edging and drilling, 21,500 RWF each, walk-in): INV-WH-2026-000002..7, their jobs CUT-WH-2026-000008 and -000010..13 (pieces U-WH-000062..76 handed over, off-cuts left on the off-cut rack or cut again) and tills TILL-WH-2026-000007..12. Before the hand-over fix, INV-WH-2026-000003 was handed over the pieces of -000002: -000002 shows 0 of 2 handed over and -000003 2 of 2; -000003's job CUT-WH-2026-000009 was cancelled (U-WH-000064 back in stock). Journals up to JV-WH-2026-000029
- Ledger started on 2026-10-09: opening stock JV-WH-2026-000001 (7,135,242.24 RWF), then ADJ-WH-2026-000006 (U-WH-000060 written off as broken, approved by owner38648) and its journal JV-WH-2026-000002. Account 5190 "Office rent (test)", deactivated. Go-live starts the ledger again from an empty database
- Labels printed for WH-A-OC, WH-A-R01, WH-A-R02 and WH-A-R04 by qa-admin on 2026-10-09: their codes are fixed (the real racks get their own codes and labels at go-live)
- Stock counts CNT-WH-2026-000001..7: -000003 counted WH-A-R04 (U-WH-000001 moved there from WH-A-R01 by the count; its adjustment ADJ-WH-2026-000005 rejected, so nothing was written off), the others cancelled
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
- [x] `StockUnit` entity, area and weight computed, status lifecycle (INV-01, INV-02; SRS 6.2) — the 8 SRS states; size, area, weight fixed; only `StockService` changes status, location or cost. RECEIVED is unused: no put-away scan (decided in M1)
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

### Inventory operations, M1 (2026-10-08..09)
- [x] Reservation rules in one place (INV-05) (M) — 2026-10-08: `StockAction` says which states allow cut, transfer, adjust, reserve, release, find; units on a pending adjustment are held (`StockService.holds` / `requireNotHeld`), checked by transfers, adjustments, cutting and reservations. Reserve a unit for a customer (with a note) and release it (reason) from its page; pieces cut for a customer now record the customer (V13 `reserved_customer_id`)
- [x] Transfers between locations (INV-07) (M) — 2026-10-08: `/stock-transfers` (V13). Scan or type the codes, choose the rack or slot; posted when saved (TRF-WH-2026-000001), one TRANSFER movement per unit, where each came from kept on the line. Refused: unknown codes, units being cut, on a vehicle or gone, held units, units already there, full sheets to an off-cut rack, over the rack's piece or kg limit. Rack orientation is not checked: units do not record how they stand (decide with the shop)
- [x] Adjustments with reason and approval (INV-07) (M) — 2026-10-08: `/stock-adjustments` (V13). Write off (damaged: BROKEN, missing: new status LOST), found again (LOST back to stock at its cost), new unit (a piece nobody recorded, at MAC), correct size (replaced by a new unit at the same cost per m², reservation kept). Up to `ADJUSTMENT_APPROVAL_LIMIT` it posts when saved; above, another person with PERM_APPROVE_ADJUSTMENT (supervisor, owner) approves or rejects with a reason, the requester can withdraw; never self-approved. MAC via `Costing.afterStockChange`. Shortcuts on the unit page: Move, Write off, Correct size, Found again. Also the way to correct a posted receipt (wrong count: write off missing or add new units; wrong size: correct size)
- [x] Stock valuation and summaries (INV-09) (M) — 2026-10-08: `/stock/summary` by glass, place or state: pieces, m², value = m² x MAC per glass (the value the inventory account will carry); CSV download (opens in Excel). Off-cut age and real Excel/PDF exports go with the reports (M6)
- [x] Reorder alerts (INV-10) (S) — 2026-10-08: glass whose AVAILABLE m² is below its reorder level, on the summary page. A dashboard card comes with the owner dashboard (M6)
- [x] Stock counts by scanning (INV-08) (S) — 2026-10-09: `/stock-counts` (V14). Count a rack or slot (cycle count), a zone or the site (full count), every glass or one; while open, the units on its places are held (`StockService.holds`: nothing moves, cuts, reserves or adjusts them) and two open counts never cover the same place. Scan labels where they are found (a scanner sends Enter; a pasted list scans together); live progress: scanned, not scanned yet, to check. Closing records one line per unit: matched, misplaced (moved where found, COUNT movement, racks left over their limit reported), missing (written off as LOST) and lost units found, both on one adjustment approved as any other; units recorded as being cut, on a vehicle, gone, or unknown labels are listed to check. Cancel with a reason
- [x] A clear message when two people change the same record at once (NFR-06) (S) — 2026-10-08: `StaleDataAdvice` sends the user back to the page they came from with "changed by someone else, reload"
- [x] Put-away scan: not needed (S) — 2026-10-09: the SRS receiving flow ends with "sheets become Available on their racks", and the receipt already names each crate's rack, so posting puts the sheets there as AVAILABLE. A crate set down elsewhere is moved with a transfer (scan its sheets and the rack's label) or found by a count. RECEIVED stays unused; a put-away step comes back only if the warehouse adds a receiving bay (question below)
- [x] Rack and slot labels (MD-02) (S) — 2026-10-09: V15. "Print labels" on a rack, slot, zone or site page prints its active racks and slots (50 x 30 mm, QR of the code, type, name, where it is; print page or ZPL, `Labels.placeZpl`). The first print fixes each code (who and when are kept; the edit form shows the code read-only; a database trigger refuses a change), so it asks first; a reprint changes nothing. Scanning a rack or slot label: in a transfer it chooses where the units go; in a count it sets where the next labels were found (a zone or a place outside the count is refused); in the stock search it lists what is on that place
- Done when: a unit can be transferred, written off, found, corrected and counted with its full movement history; the valuation report total equals the stock value used by the MAC. Met: transfers, adjustments, counts and the valuation report are in place, and every change is a movement on the unit

### Accounting core, M2 (2026-10-09)
- [x] Chart of accounts with the glass-business template (ACC-03) (M) — `/accounting/accounts` (V16). 26 accounts: the SRS list (cash, vault, bank, mobile money, driver float, receivables, claims, driver shortages, VAT input, Inventory - Glass, payables, GRNI, customer deposits, VAT output, capital, retained earnings, sales, sales returns, COGS, spoilage, adjustments, cash over/short, FX) plus Accrued Import Charges, Opening Balance Equity and Inventory Revaluation. The posting rules find their accounts by system key (`AccountKey`), so the accountant renames and renumbers freely; those accounts keep their type and stay active; a type is fixed once journals post to it. Each account's ledger and History. One Accounts Payable account: each line keeps its currency, amount and rate, the per-supplier and per-currency balances come with the subledger (M5). Driver floats per driver come with the drivers (M7)
- [x] Journal entries and lines (ACC-01) (M) — JV-WH-2026-000001, date, event, document, total; lines in RWF with the foreign currency, amount and rate where the document had one, the glass on stock lines, the supplier on payable lines. Append-only (triggers), and a journal that does not balance (or has fewer than two lines) is refused by the database at commit. `reverses_id` is ready for reversing journals (manual journals, M5). Accounting periods and their close are M5 (ACC-10)
- [x] `PostingService`, one rule per event of the posting matrix, inside the event's transaction (ACC-04) (M). Stock lines are the change of each glass's value (m² held x MAC, rounded per glass, as the valuation computes it), so the inventory account equals the valuation; the event's own amounts go to the other accounts and the moving-average rounding to Inventory Revaluation:
  - [x] Crate received: Dr Inventory, Dr Glass Spoilage (sheets broken on arrival, at purchase cost) / Cr GRNI, every sheet the supplier will invoice, with its USD amount and rate
  - [x] Import bills posted on a shipment: Dr Inventory (glass in stock), COGS (glass already gone), Glass Spoilage (the broken sheets' part) / Cr Accounts Payable (bill with a supplier) or Accrued Import Charges (bill without); credit notes the other way. The bills' RWF total is rounded once and split back over them exactly
  - [x] Claims (PRC-06): sent Dr Claims Receivable / Cr Glass Spoilage; settled Dr Bank, Cash, Mobile Money or Accounts Payable (asked on the settle form) and Glass Spoilage for the shortfall / Cr Claims Receivable; rejected Dr Glass Spoilage / Cr Claims Receivable. A claim sent before the ledger started posts nothing when decided
  - [x] Cut: Dr Glass Spoilage (cullet and breakage at the sheet's cost) / Cr Inventory
  - [x] Adjustments: damaged glass to Glass Spoilage; missing, found, added and resized glass to Inventory Adjustment Expense (a gain credits it) / Inventory
- [x] Journals and trial balance (ACC-11, first part) (M) — `/accounting/journals` (list by event, search; a journal with its lines, its document and the document's other journals), `/accounting/trial-balance` (as at a day, paged, totals); each posted document links its journals (receipt, shipment, cutting job, adjustment)
- [x] AT-10 (M) — `PostingServiceTest` runs a full test day (opening, a crate with a broken sheet, USD and RWF import bills, a cut, write-offs and a unit found, a claim sent and settled): every journal balances, the books balance and the inventory account equals m² x MAC after each event. The trial balance shows the inventory account against the stock valuation, glass by glass. Checked in the browser on the dev data (opening 7,135,242.24, then a write-off approved by the owner)
- [x] Go-live start (S) — "Post the opening stock" on the trial balance (PERM_POST_OPENING_BALANCES, accountant): brings the inventory account of each glass to its stock value of that moment (Dr Inventory / Cr Opening Balance Equity), once; earlier events are not back-posted. Other opening balances (receivables, payables, cash) come with the go-live data (M10)

### Counter sales, M3 (2026-10-09)
Goal: the cashier sells stock and custom cuts, takes split payments and gives receipts (POS-01..POS-10, TAX-01, TAX-04, SRS 5.3). AT-08.

- [x] VAT per line from the product's tax category (TAX-01) (M); buyer TIN on the invoice (TAX-04) (M) — 2026-10-09: `Vat` (pure): a line is whole RWF with VAT included (price per m² x chargeable area, VAT added when the list excludes it, rounded once); VAT per tax letter on the invoice totals, as EBM reports it (A exempt, B 18%, C zero-rated). The letter and rate are copied on the line. Buyer's name and TIN (9 digits) on the invoice; an account customer's TIN by default
- [x] Till sessions (POS-10) (M) — 2026-10-09: `/pos` opens the cashier's till with a float (journal: Dr Cash on Hand / Cr Main Cash Vault), one open till per cashier; closing compares the cash counted with the float plus the cash kept from sales: a difference needs a note, goes to Cash Over/Short and the counted cash back to the vault. A till does not close while a sale is rung up in it. `/till-sessions` lists them with their takings per payment method, invoices, journals and History
- [x] POS screen for units from stock (POS-01, POS-04) (L) — 2026-10-09: scan a label (Enter adds it) or search the smallest available piece that fits a size (INV-06), priced for the sale's customer; the sale is a draft invoice of the till, its units held (`StockService.holds`) until it is paid or cancelled; the customer's price list reprices it (a unit reserved for another customer is refused); payment dialog split over cash (handed over, change shown), mobile money, card and bank transfer (with references) and customer credit (account customers, within the credit limit: over it is refused until the approval below exists). Two panes from 1600 px, stacked below
- [x] Custom cut sizes on the POS (POS-02) (L) — 2026-10-09 (V18): width x height x quantity of a cuttable glass with the customer's mark, priced by chargeable area like a unit; its processing (edging per metre, drilling per hole...) are service lines under the size, priced from the customer's list. Paying issues the invoice and creates one cutting job per glass for it (customer reference = the invoice number; the sizes with their processing and mark; the job links the invoice and each job line its size); nothing leaves stock yet. The pieces cut are RESERVED and cannot be rung up at the counter. The invoice hands over only the pieces its own jobs cut, ticked or scanned: SOLD (SALE movement), COGS at MAC (Dr COGS / Cr Inventory), "Handed over x of y" per size, `sales_deliveries` append-only (DELIVER_SALE: cashier, warehouse supervisor). Deposits come with the orders (POS-08)
- [x] Sales invoice and receipt (M) — 2026-10-09: paying issues the invoice (INV-WH-2026-000001), sells the units (SOLD, SALE movement), posts the journal (Dr cash / mobile money / bank / the customer's receivable, Cr sales net and VAT output; Dr COGS / Cr Inventory at MAC) and opens the invoice: lines, VAT per letter, payments, journal, History. 80 mm receipt to print (`/invoices/{id}/receipt`). A PDF copy comes with the reports' exports (M6); the EBM signature with M4
- [x] Credit limits: over the limit needs a manager's approval (POS-05) (M); discounts and price overrides above the role limit need approval with a reason (POS-06) (M) — 2026-10-09 (V19): each role has a discount limit (role form; empty = the Settings value, the owner 100%). The POS changes a line's price (discount or new price, with a reason): within the cashier's limit at once, above it a request APR-WH-2026-000001 waits; credit above what the customer has left is asked from the payment dialog. Requests show on `/sale-approvals` (sidebar count for approvers); another person with APPROVE_SALE (owner) approves or rejects with a reason, never their own. A pending request stops the payment; the POS reloads by itself once it is decided. A request that no longer applies is withdrawn with a note. The invoice and receipt show the list price, the discount, the reason and who approved
- [x] Quotations with validity, converted to an order or invoice (POS-03) (M) — 2026-10-09 (V20): `/quotations`. A draft (QUO-WH-2026-000001) for a customer with the name and TIN to print, valid for the Settings number of days (14); rows are whole sheets or sizes to cut with processing, holes and the customer's mark, priced from the customer's list on every save (the same `LinePricing` as the POS), with a discount per row within the author's limit. Send fixes it; print it on A4 (or save as PDF from the print dialog); copy it into a new one (an expired one is priced again that way); cancel with a reason. "Ring up at the till" puts a sent, valid quotation into the cashier's empty sale at its prices (whole sheets take units of that size from stock); paying converts it and links the invoice. A deposit makes that sale an order (next item)
- [x] Deposits on orders and balance on collection (POS-08) (S) — 2026-10-09 (V21): the POS payment dialog of a sale with sizes to cut offers "A deposit": at least the Settings share (50%) of the total, and the glass from stock in full; the default walk-in gives a buyer name first. The invoice is issued for the whole amount with its balance due (journal: the deposit to cash etc., the balance to the customer's receivable); the cutting jobs start at once. The invoice page shows the deposit and the balance, holds the hand-over and takes the balance in full in the cashier's own till (cash, mobile money, card, transfer; Dr those / Cr receivable). Each payment names the till that took it, so a balance taken the next day counts in that day's till ("Balances taken" tab); receipts print the balance due, then the balance paid. `/invoices?show=due` lists the orders still owed
- [x] Returns and credit notes against the original invoice, glass back to stock or to cullet (POS-09) (M) — 2026-10-09 (V22): "Return goods" on an invoice lists what the customer took (sheets from stock, pieces handed over), each once; tick what comes back, mark Cullet what cannot be sold again, choose the rack, the reason and the refund. The credit note (CN-WH-2026-000001) credits each line its share of the pieces back with its processing, VAT per letter; it reduces the balance due first, then refunds in cash from the user's till (as far as it holds), mobile money, card, transfer, or to an account customer's account. Glass back on the rack is available again at its own cost (RETURN movement, the MAC moves); cullet is BROKEN. Journal: Dr Sales Returns and VAT Output / Cr cash, bank, mobile money or the receivable; Dr Inventory / Cr COGS, cullet Dr Spoilage / Cr COGS. `/credit-notes` lists them; each has its lines, units, journal, History and an 80 mm slip; the till counts cash refunded ("Refunds" tab, `cash_refunds` when it closes)
- [x] Cancel the sizes of an order not cut or not handed over yet (an order changed or abandoned after the deposit) (S) — 2026-10-09 (V23): "Give up pieces" on an invoice with sizes not handed over: per size, how many pieces are given up (up to all but those on a sheet being cut), the reason and the refund. A credit note of kind CANCEL credits each size and its processing their share (the same running share as returns, so a size never credits more than its amount); it reduces the balance due first, then refunds. Pieces on no job go first, then the draft cutting jobs ask for fewer (a job left empty is cancelled with the reason), then pieces cut and waiting are released to stock where they lie (RELEASE). Nothing left stock, so the journal only reverses the sale (Dr Sales Returns and VAT Output / Cr the receivable and the refund). The invoice shows "Given up x of y"; the hand-over takes only what is left
- [x] Core sale in 6 clicks or fewer; save, post and print in under 3 s without EBM (NFR-02, NFR-14) (S) — 2026-10-09: measured on dev data with the production template cache: scan, pay and receipt take 0.47–0.69 s of server time (1.15 s just after a restart with dev's template cache off); 4 clicks: Pay, Take the payment (the total is already in cash), Print the receipt, the browser's Print; the scanner's Enter adds the sheet. Fixed: the POS cart query paged in memory on every action (`findFirst` + its lines: HHH90003004), now `findDraftOfTill`; Hibernate now refuses any paged query that fetches a collection; label scans use the unique index (exact upper-case match). Every screen crawled (141 pages, the first record of each list and its tabs): none fails, the slowest about 230 ms.

### Responsive layout (2026-10-08)
- [x] Every screen works from 360 px phones to desktops — all 86 reachable pages checked at 360, 390, 768, 1024 and 1280 px, plus the 7 that need a draft (order and job edit, receipt, shipment edit, taking a sheet, recording a cut) at 360 to 1024 px, with their dialogs. Phones and tablets (768 px and less) open the menu as a drawer from a button in the header (it was hidden there before, so most screens could not be reached). On phones (640 px and less) table rows become cards with each value labelled by its column, line forms included; list filters wrap two per row. Line forms keep usable field widths on tablets and scroll in their box. Known limit: a long rack choice ("WH-A-R04 · Rack R04 · 21/30 pcs · 1,234/3,000 kg") is cut short in the closed select on a phone; the phone picker shows it in full

### Design consistency (2026-10-08)
- [x] One look per component on every screen — measured on all 81 screens (computed styles per component, the odd ones out listed), then fixed at the source: buttons and fields now use the page font (form controls were in Arial on 42 screens) and one height (42 px, small 30, filters 38, line forms 38); one monospace font; links to other records styled (some were browser-blue); one note style (doc notes and form notes matched, red when cancelled); header actions one gap, Back first, no squeezed button column; counts as plain numbers; one date format (dd/MM/yyyy [HH:mm] as NFR-15 asks, seconds in logs only, was 7 formats); numbers through @num everywhere (dashboard used the locale's grouping); red tint as a theme token. Rules in CLAUDE.md "UI"

### Page numbers on every table (2026-10-08)
- [x] Every table that lists records shows its page numbers — the pager used to hide itself while a table fitted on one page, so most tables showed none; it now shows as soon as a table has rows ("1 - 3 / 3"). One page size, 20 rows (lists used 10, 15, 20 or 25; now `Paging.SIZE`). Lists that were never paged now are: a unit's movements and cost entries, the stock units of a receipt, a role's users, a location's sub-locations, a price list's glass prices, processing and customers, the price lists and services, tax categories, document numbering, currencies, the stock summary groups and reorder list, the three yield tables, the records changed by the same action, and every History tab (they showed only the first 20 changes). Detail pages open on the tab in the link. Shown whole on purpose: document lines with totals, forms, a cut's balance, a change's fields, dashboard previews, the location tree
