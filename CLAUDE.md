# CLAUDE.md — iWarehouse

Guidance for Claude (and any developer) working in this repository. Read it before changing code.

## What this is

iWarehouse is an ERP-lite for a glass business in Rwanda. It imports large glass sheets, cuts them to customer sizes, and sells two ways: at the warehouse counter and from delivery vehicles that act as mobile shops ("moving shops").

- **Requirements:** SRS v2.1 (Claude Docs: "SRS — Glass Reproduction Warehouse & Mobile POS (ERP-Lite)"). Requirement IDs such as `INV-03`, `FLT-09` and `AUD-04` come from it. Quote the ID in commits and code comments when you implement one.
- **Reference implementation:** [iVura](https://github.com/Ntaganira/iVura), the same author's hospital system. iWarehouse copies its stack, layout shell, RBAC model and activity log. When unsure how something is done here, look at how iVura does it, then apply the rules below.
- **Base currency:** RWF. **Time zone:** Africa/Kigali (CAT, UTC+2).

## Commands

```bash
docker compose up -d                 # PostgreSQL 16 (db iwarehouse) + MinIO
./mvnw spring-boot:run               # http://localhost:8080  (admin / password123)
./mvnw test                          # unit tests
./mvnw clean package -DskipTests     # build target/iWarehouse-0.0.1-SNAPSHOT.jar
```

Flyway runs on startup. `ddl-auto` is `validate`, so the app refuses to start if an entity and the schema disagree.

## Stack

Java 17 · Spring Boot 3.4 (MVC, Security, Validation, Data JPA, Mail, Actuator) · Hibernate 6.6 · PostgreSQL 16 · Flyway · Thymeleaf + thymeleaf-extras-springsecurity6 · custom CSS (`static/css/iwarehouse.css`, iVura's design system) · Choices.js, Chart.js (vendored, no CDN) · MinIO · OpenHTMLToPDF · ZXing · Lombok · Maven wrapper.

Planned: the mobile POS is a PWA (Service Worker + IndexedDB) calling a versioned JSON API under `/api/v1/**`.

## Layout

```
src/main/java/com/ntaganira/heritier/iWarehouse/
  audit/        before/after change capture (listener, context, annotations)  <- read before touching entities
  config/       security, web, JPA auditing, global model
  controller/   MVC controllers (one per screen group)
  dto/          form and view objects
  entity/       JPA entities (BaseEntity for business data)
  enums/        status and type enums
  repository/   Spring Data repositories
  security/     AppUserPrincipal
  service/      business logic and transactions
src/main/resources/
  db/migration/ Flyway scripts V{n}__{module}.sql
  templates/    layout/, fragments/, error/, one folder per module (list/form/view.html)
  static/       css/iwarehouse.css, js/iwarehouse.js, js/session-timeout.js, vendor/
  messages*.properties   en (default), fr, rw
```

New module = one folder in `templates/`, a controller, a service, entities, a repository, and one Flyway migration. Follow the module table in SRS section 3.3.

## Rules

### Audit (SRS 4.13) — non-negotiable

1. **Every business entity is audited.** Put `@AuditedEntity(ref = "<readable field>")` on it, e.g. `@AuditedEntity(ref = "invoiceNumber")`. Without the annotation, its changes leave no before/after record. The exception is an append-only ledger such as `StockMovement`: each row is itself the record and can never change.
2. **Write through JPA, inside a `@Transactional` service method.** The change log is written by `DataChangeEventListener` at commit time, in the same transaction. These patterns bypass the listener, so don't use them on audited tables:
   - JPQL or SQL bulk `UPDATE` / `DELETE`
   - `@Modifying` queries
   - `JdbcTemplate` writes
   - writes outside a transaction

   If you truly need a bulk write, stop and ask first.
3. **Collections are not captured.** Changing a `@ManyToMany` set (e.g. a role's permissions) produces no change row. Record those changes explicitly in the service, with an activity log entry that names what was added and removed. Build the text with `SetChange.of(before, after)` and write it inside `AfterCommit.run(...)`, so a rolled-back change logs nothing (see `RoleService`).
4. **Give a reason when the SRS requires one.** Adjustments, price overrides, cancellations, reversals, write-offs and shortage charges must carry one:
   ```java
   AuditContext.withReason(form.getReason(), () -> stockService.adjust(unitId, newQty));
   ```
5. **Log every user action** with `activityLogService.record(module, ACTION, description, SUCCESS|FAILED)`. Use `CREATE_x`, `UPDATE_x`, `CANCEL_x`, `APPROVE_x` style action names; the activity list colours badges by those words. Log failures too.
6. **Hide secrets.** Mask them with `@AuditMask` or by adding the name to `app.audit.masked-fields`. Use `@AuditIgnore` only for noise such as caches and derived values.
7. **Never update or delete audit rows.** A database trigger blocks it. `DataChangeLog` and `ActivityLog` are `@Immutable`. Never call `save` on `DataChangeLog`.
8. **Add a History tab to every detail page of an audited record:**
   ```html
   <th:block th:replace="~{audit/history :: timeline(${history})}"></th:block>
   ```

### Data and money

- **Entities:**
  - Business entities extend `BaseEntity`, which provides a UUID id, `@Version`, and created/updated by and at.
  - Security tables (users, roles, pages, permissions) keep BIGSERIAL ids, as in iVura.
  - Use Lombok `@Getter @Setter` (plus `@Builder` / `@NoArgsConstructor` as needed) on entities. Never use `@Data`: generated `equals`/`hashCode` over lazy relations breaks Hibernate.
- **Money:**
  - `BigDecimal` in Java and `NUMERIC(18,2)` in SQL. Never `double` or `float`.
  - RWF is shown without decimals; round with `RoundingMode.HALF_UP` at the document total.
- **Dimensions:** whole millimetres (`INTEGER`). Areas in m² as `NUMERIC(10,4)`, computed as `width × height / 1_000_000`. Weight in kg = m² × thickness(mm) × glass density (setting `GLASS_DENSITY`, default 2.5).
  - Thickness is the exception: `NUMERIC(5,2)`, because laminated glass is sold as 6.38 mm. Take it from the product (`Product.thicknessMm`) and use `GlassProducts.weightPerM2` for kg per m².
  - Tempered glass cannot be cut: check `GlassType.isCuttable()` before a unit becomes a cutting source.
- **Products and locations (MD-01..03):** a product's type, colour/finish and thickness are fixed after creation, so stock of it never changes meaning. A location's type follows from its parent (none = site), and type and parent are fixed. Vehicle locations are created by the Fleet module only. Deactivate locations leaf first. Racks and slots carry a printed label; the first print fixes the code (`LocationService.printLabels`, `label_printed_at`, a V15 trigger refuses a change), because the label carries it.
- **Selling prices (MD-06):** get them from `PriceListService.priceFor(customer, product)` (the customer's list, then the default list) and charge `Pricing.chargeableArea(w, h, priceListService.minChargeableArea(list))`. Store the price, the list and its VAT flag on the document line. Never read `price_list_items` directly. Sale and quotation lines are priced through `LinePricing` (glass, processing, the amount), so the counter and quotations always agree.
- **Customer credit terms** (credit limit, payment terms, price list) change only through `CustomerService` with `canSetTerms` = `AppUserPrincipal.currentHas("PERM_MANAGE_CUSTOMER_TERMS")`. Walk-in customers never have credit. The default WALK-IN customer stays an active walk-in.
- **Customer and supplier codes** come from `DocumentNumberService.next(DocumentType.CUSTOMER / SUPPLIER)`, like documents.
- **`open-in-view` is off:** load everything a page shows inside the service transaction (`@EntityGraph` on the repository method, or keep the reference as an id, as `Location.parentId` does). A lazy relation touched in a template throws. An entity graph that fetches two collections of one entity needs them as `Set`s (`Shipment.receipts` and `costs`): a `List` repeats its rows once per row of the other.
- **Never page or limit a query that fetches a collection** (a `Pageable`, `findFirst...`, `findTop...` with a collection in its entity graph): Hibernate would load every row and page in memory (HHH90003004). `hibernate.query.fail_on_pagination_over_collection_fetch` is on, so such a query fails at once. Where one row is certain (a unique key, like a till's draft sale: `SalesInvoiceRepository.findDraftOfTill`), query without a limit; otherwise page the parents and fetch the collection in a second query.
- **Codes are looked up exactly, upper case** (`StockService.findByCode`, `LocationService.findByCode`): they are stored upper case, so the unique index serves every scan; `...IgnoreCase` compares `upper(code)` and reads the whole table.
- **Configurable values come from `SettingService`** (`getDecimal(SettingKey.OFFCUT_MIN_AREA)` etc.): off-cut threshold, density, minimum chargeable area, approval limits, company details. Never hard-code them. A new setting = a `SettingKey` constant + a `SettingsDto` field + a seed row in the module's migration.
- **Document numbers come from `DocumentNumberService.next(DocumentType.X)`**, called inside the service method that saves the document (it is `MANDATORY`: it locks the sequence row, and a rollback gives the number back). Never build a number by hand. Give each document table a `uk_..._number` unique constraint. A new document type = a `DocumentType` constant + a `number_sequences` row in its migration.
- **Dates for business rules use the injected `java.time.Clock`** (Africa/Kigali), so tests can fix the date.
- **Foreign currency:** keep amount, currency and rate on the document. Ledgers are always RWF.
  - Get the rate from `ExchangeRateService.rateFor(currencyCode, documentDate)` (default source from Settings) or `rateFor(code, date, RateSource.CUSTOMS)` for import duty. It refuses a missing or stale rate; never fall back to a guessed rate. Its refusal does not roll back the caller's transaction (`noRollbackFor`), so a page may catch it to show the missing rate; give any other lookup a page catches the same `noRollbackFor = BusinessException.class`.
  - Store all four values of the returned `AppliedRate` on the document (currency, rate, rate date, source), so later rate corrections never change it.
  - Convert with `CurrencyMath` / `AppliedRate.toBase`: no rounding on lines, round once at the document total with the currency's decimals.
- **Posted records are never edited or deleted.** This covers stock movements, invoices, journals and payments. Correct them with a reversing document.
- **Stock unit lifecycle:** cutting never changes a unit's size. The source unit becomes `CONSUMED` and new units are created. Area is conserved within 1% (PRD-06).
- **Stock units change only through `StockService`.** It creates units (label code from `DocumentType.STOCK_UNIT`, U-WH-000001) and changes status or location, and it writes one `StockMovement` per change (INV-04). A new kind of movement = a `MovementType` constant + `chk_stock_movements_type` in the module's migration. "In stock" means `StockStatus.onHand()`; check rack limits with `StockService.rackLoads` + `RackLoad` (a slot counts towards its rack) wherever glass is put on a rack. A unit's cost changes only through `StockService` too (receipt cost, `addLandedCost`), and each change is a `StockCostEntry` (append-only) with the cost after it.
- **Purchasing (PRC-01, PRC-02):** order lines are priced per m² in the order's currency (the supplier's, fixed on the order). A goods receipt is a draft until posted; posting takes `rateFor(currency, receivedDate)`, stores it on the receipt, costs each sheet with `Costing` (cost per m² 4 decimals, unit cost 2 decimals), creates the units, adds good + broken sheets to the order line and moves the product MAC (`Costing.movingAverage`). Posting locks the products, then the order, before reading stock. Posted receipts are corrected by stock adjustments, never edited.
- **Landed cost (PRC-03..06):** import bills belong to a `Shipment` that links posted receipts. Bills are drafts until posted; `ShipmentService.post` converts each at the rate of its own date (`CostType.rateSource()`: CUSTOMS for duty), rounds the RWF total once, splits it with `LandedCost.split` (largest remainder, parts add up exactly) over the crates by the shipment's method (m², purchase value or kg shipped, broken sheets included) and over each crate's sheets. Sheets in stock get their part (`addLandedCost`), sheets already gone are expensed (COGS), broken sheets' part to spoilage, recovered by the claim (settling it asks where the money came in); each crate's split is a `ShipmentAllocation` (append-only) and the MAC moves with `Costing.addValue`. Posting locks the shipment, then its products. Receipts and method are fixed after the first posting; a posted bill is corrected by a credit note (negative bill), never edited.
- **Cutting (PRD-01..09):** a job lists the sizes wanted and takes one source unit (`CuttingJobService.start`): same product, cuttable, AVAILABLE, every piece fits it either way round (`Cutting.fits`) and their area is within its area. The unit goes IN_CUTTING and the job's sizes are fixed. Recording the cut (`complete`) locks the job, then the product, and reads the stock held before changing it.
  - Units change only through `StockService`: `consumeByCutting` for the source, `createCut` for each piece and each leftover that is an off-cut (`Cutting.isOffcut` with the Settings threshold). Off-cuts go to an off-cut rack; pieces for a customer are RESERVED.
  - Smaller leftovers and the trim (the unrecorded rest) are cullet. `Cutting.Balance` refuses outputs more than 1% over the source (PRD-06).
  - `Cutting.costByArea` splits the source cost by area (parts add up exactly). Units get a `CUTTING` cost entry; cullet and breakage are expensed; the MAC follows `Costing.afterCut`. Each output is a `CuttingJobOutput` (append-only).
  - A completed job is never changed. Pieces not cut go on a new job (`cutRest`).
- **Stock operations (INV-05, INV-07, INV-09):**
  - What a unit may do follows its state: `StockAction.allows(status)` (cut, transfer, adjust, reserve, release, find). Units on a pending adjustment or on the places of an open stock count are held: call `stockService.holds(...)` or `requireNotHeld(units, exceptNumber)` before moving, cutting, reserving or adjusting them. A new document that holds units (a sales order, a loading manifest) adds its own hold query there.
  - Reservations go through `StockReservationService` (customer and note on the unit, cleared when it leaves RESERVED). Transfers through `StockTransferService` (posted when saved, rack limits, no full sheet on an off-cut rack). Adjustments through `StockAdjustmentService`: write-off (damaged: BROKEN, missing: LOST), found, new unit at MAC, resize at the same cost per m². Above the `ADJUSTMENT_APPROVAL_LIMIT` setting another person approves; never the requester.
  - **Stock counts (INV-08)** go through `StockCountService`. A count covers a place and every place under it (fixed at the start, `stock_count_places`), optionally one glass; two open counts never cover the same place. Expected units = those on its places in `StockCounting.ON_RACK` states. `StockCounting.compare` (pure, tested) gives matched, misplaced, missing and the extras. Closing sets the result and CLOSED together, then creates the adjustment (missing: WRITE_OFF MISSING; lost found: FOUND), so the count no longer holds what the adjustment checks. Misplaced units move with `StockService.countMove` (a COUNT movement, no rack-limit check: the glass is there; over-limit racks are reported). Units another document holds are reported, not acted on. Result lines (`stock_count_lines`) are append-only.
  - Stock value is m² x MAC per product (`StockSummary`): the value the inventory account carries. Any change that moves area and value at a unit's own cost moves the MAC with `Costing.afterStockChange`.
- **Accounting (ACC-01, ACC-03, ACC-04, AT-10):** every business event of the posting matrix posts its journal in its own transaction, through one rule of `PostingService` (MANDATORY), once the stock, costs and MAC have changed.
  - Take `postingService.stockValues(products)` after locking the products and before changing the stock, and hand it to the rule. Stock lines are the change of each glass's value (m² held x MAC, rounded per glass, exactly as `StockSummary` values it), so the inventory account always equals the stock valuation; the event's own amounts (what the supplier will invoice, what was expensed) go to the other accounts and `Journal.balanceOn(STOCK_REVALUATION)` takes the moving-average rounding.
  - **Anything that changes the m² on hand or a MAC must post through a rule**, or the inventory account drifts from the valuation (the trial balance shows the difference, glass by glass).
  - Rules name accounts by `AccountKey`, never by code: the accountant renames and renumbers accounts. A new event = a `JournalSource` constant + `chk_journal_entries_source` in its migration + a rule in `PostingService` + its case in `PostingServiceTest` (AT-10 must keep passing); a new account a rule needs = an `AccountKey` + the account seeded with that key.
  - Amounts are RWF with 2 decimals; a line from a foreign document keeps its currency, amount and rate (`Journal.Fx`); split a total rounded once over its parts with `Journal.roundTo`. Journals never change (append-only, balanced in the database at commit): a mistake is corrected by a new journal from a correcting document.
  - The ledger starts with the opening stock journal (`PostingService.openingStock`, once).
- **Counter sales (POS-01, POS-04, POS-10, TAX-01, TAX-04)** go through `SalesService` and `TillService`.
  - A cashier sells in their own open till (`TillService.lockCurrent`: one open per cashier, locked while a sale is changed or paid). The sale being rung up is the till's DRAFT invoice (one per till); its units are held (`StockService.holds` reads `SalesInvoiceLineRepository.findHolds`), so nothing else moves, cuts or sells them.
  - Price a line with `PriceListService.priceFor` + `Pricing.chargeableArea` and charge `Vat.lineAmount` (whole RWF, VAT included; VAT added when the list excludes it). Copy the list, its VAT flag and the glass's tax letter and rate onto the line. VAT is `Vat.totals` per tax letter on the invoice, never summed per line.
  - Paying: `SalePayments.split` (pure) splits the total; customer credit only for account customers within limit minus what they owe (`JournalService.receivable`). Then units are sold (`StockService.sell`, SALE movement), the INV number is taken, the status and totals are set together, and `PostingService.sale` posts the journal (COGS at MAC as the change of stock value).
  - An issued invoice never changes (`trg_sales_invoice_lines_posted`, payments append-only): corrections are credit notes (POS-09).
  - **Returns and credit notes (POS-09)** go through `CreditNoteService`: a credit note (CN-WH-2026-000001) against an issued invoice, posted when saved, with a reason. What can come back (`returnables`): the invoice's sheets from stock and the pieces it handed over, still SOLD and not back already (`credit_note_units`, once per unit and invoice). Each unit goes back on a rack (`StockService.returnToStock`: AVAILABLE at its own cost, RETURN movement; rack limits, no full sheet on an off-cut rack; the MAC moves with `Costing.afterStockChange`) or to cullet (`returnAsCullet`: BROKEN). Each invoice line, and the processing under a size, is credited `CreditNotes.share` (the running share of its amount for the pieces back, so several credit notes never credit more than the line); VAT is `Vat.totals` of the credit lines. The credit reduces the invoice's balance due first (`CreditNotes.refund`); the rest is refunded in cash from the user's till (only as far as it holds), by mobile money, card or transfer (with a reference), or to the customer's account (`PaymentMethod.CREDIT`, never for walk-ins). Issuing locks the till (cash), then the invoice, then the products. `PostingService.creditNote`: Dr Sales Returns and VAT Output / Cr the receivable (balance reduced) and the refund's account; Dr Inventory (the change of stock value) / Cr COGS at the units' own cost, cullet Dr Spoilage / Cr COGS. A till's expected cash is float + cash kept from sales − cash refunded (`till_sessions.cash_refunds` when it closes).
  - **Giving up an order's pieces** (`CreditNoteService.cancelPieces`, a credit note of kind CANCEL): per size at most its pieces not handed over, minus those on a sheet being cut (`Cancellable.getCancellableNow`). Pieces on no job go first, then `CuttingJobService.takeOffSale` takes them off the sale's draft jobs (a job left empty is CANCELLED with the reason), then pieces cut and waiting are released to stock (`StockService.release`, a `credit_note_units` row with outcome RELEASE). The share counts every earlier credit note of the line (`CreditNoteLineRepository.creditedPieces`), so returns and cancellations together never credit more than the line. `SalesService.progress` subtracts the pieces given up (`cancelledPieces`), so the hand-over takes only what is left. No stock leaves, so the journal has no cost lines.
  - **Custom cut sizes (POS-02, SRS 5.3):** a size is a CUSTOM_PIECE line (cuttable glass, priced like a unit over its chargeable area); its processing are SERVICE lines under it (`parent_line_id`), priced by `PriceListService.servicePriceFor` over `Pricing.serviceQuantity` (m², metre of edge, piece, hole). Removing a size removes its processing; changing the customer reprices both.
  - Paying sells only the STOCK_UNIT lines and creates one cutting job per glass for the invoice (`cutting_jobs.sales_invoice_id`, each job line's `sales_line_id` = its size). Nothing else leaves stock until hand-over.
  - An invoice hands over only the pieces its own jobs cut: the piece's `CuttingJobOutput` → its job's `salesInvoiceId`, and its job line's `salesLineId` → the size. Never match pieces by customer, glass and size: a walk-in buys the same sizes on many invoices. Handing over sells the units (`StockService.sell`), writes `sales_deliveries` (append-only) and posts `PostingService.saleDelivery` (Dr COGS / Cr Inventory at MAC). A piece cut for a sale cannot be rung up at the counter (`sale.unit.cutForSale`).
  - **Quotations (POS-03)** go through `QuotationService`: a draft is priced again on every save (`LinePricing`); rows are whole sheets (SHEET) or sizes to cut (CUSTOM_PIECE) with their processing as SERVICE lines under them; a row's discount stays within the author's `discountLimit()` (no approval requests on quotations: above it a manager makes the quotation). Draft, then sent (fixed), converted when the sale it was rung up into is paid (`sales_invoices.quotation_id`), or cancelled with a reason, never while a till rings it up. Expired = sent and past `valid_until`, derived, not stored.
  - `SalesService.ringUp` puts a sent, valid quotation into the till's empty sale. Whole sheets take AVAILABLE units of that glass and exact size, either way round, held by nothing (`StockUnitRepository.findOfSize`), all found before the sale changes. Lines charge the quotation's price; where it differs from today's list price the line keeps the list price with "Quotation QUO-..." as its reason. Changing the sale's customer prices it again and drops the quotation.
  - **Deposits on orders (POS-08, SRS 5.3):** a sale with sizes to cut may be paid by a deposit (`pay(entered, true)`) for a customer known by name (the default walk-in gives a buyer name): at least `depositMinimum` (`SalePayments.depositMinimum`, pure: the Settings `DEPOSIT_MIN_PERCENT` of the total rounded up, never less than the glass from stock, which leaves at once). The invoice is issued for its whole amount with its `balance_due`, and the sale journal debits the customer's receivable with it. The balance is taken in full by `payBalance` (never on credit) in the till of whoever takes it: it locks the till, then the invoice, and `PostingService.saleBalance` posts Dr the methods / Cr Receivable. Every payment row names its till (`till_session_id`: a till's takings sum by it), whether it pays a balance and, for cash, what was handed over. `deliver` refuses while a balance is due.
  - **Approvals at the counter (POS-05, POS-06):** a line's price changes only through `SalesService.changePrice` (with a reason; back to the list price without one), and its amount only through `setPrice`; the line keeps `list_price` and `price_reason`. The cashier's limit is `SalesService.discountLimit()`: the largest `discount_limit_percent` of their active roles, a role without one taking the Settings value `DISCOUNT_APPROVAL_PERCENT` (`Discounts`, pure and tested). Within it the price applies at once; above it a `SaleApproval` (PRICE, with the list price, price asked, discount, limit and the line's amount before and after) waits and the line keeps its price.
  - Credit above what the customer has left is a `SaleApproval` (CREDIT: limit, owed, credit asked) from `requestCredit`; paying then accepts credit up to the approved amount. Any pending request stops the payment.
  - Another person with PERM_APPROVE_SALE decides (`SaleApprovalService`), never the requester. Approving locks the till, then the request: the order the cashier's own operations take them in. A request that no longer applies (its line removed, the customer changed, the sale emptied or cancelled, a newer request) is WITHDRAWN with a note, never deleted. The POS asks `/pos/approvals/state` every 8 s while requests wait and reloads once one is decided.
- **Customer accounts (ACC-09)** go through `CustomerAccountService`. What a customer owes is the receivable account's journal lines that name them (credit sales, deposit balances, credit notes to their account, payments): never keep a second balance. The statement reads them in posting order with the balance after each; `Ageing` (pure) settles the oldest charges first, each due its date plus the customer's `paymentTermsDays`, and buckets the rest by days past due. A payment on the account (`CustomerPayment`, RCT number, `receive`) settles at most what is owed: cash into the user's open till (what was handed over gives the change), mobile money, card or transfer with a reference; it locks the till (cash), then the customer (`CustomerRepository.lockById`), and `PostingService.customerPayment` posts Dr that account / Cr the receivable. A till's expected cash adds the cash taken on accounts (`till_sessions.cash_account_payments` when it closes).
- **Supplier accounts (ACC-08, ACC-09)** go through `SupplierAccountService`. What is owed to a supplier is the payable account's journal lines that name them (supplier invoices, shipment bills with a supplier, payments, credit notes), in each line's currency (`Payables`, pure: per currency the oldest items are settled first and each keeps the RWF it was booked at). A supplier invoice (SINV, `recordInvoice`) bills posted goods receipts not invoiced yet (`supplier_invoice_lines`, each receipt once), one currency, its total equal to theirs: `PostingService.supplierInvoice` moves each receipt Dr GRNI / Cr AP at the value and rate its GRNI line has, so GRNI clears exactly and the FX is realised when paid. A payment (SPAY, `pay`) pays at most what is owed in a currency at `rateFor(currency, today)` (kept on it); `Payables.settle` gives the RWF the settled items were booked at, and `PostingService.supplierPayment` posts Dr AP that RWF / Cr bank, vault (cash) or mobile money the RWF paid, the difference to FX gain/loss. Both lock the supplier first (`SupplierRepository.lockById`). Payables ageing is in RWF as booked, at the supplier's terms.
- **Set a status and the fields its CHECK needs in one step, after the operation's queries.** A query flushes pending changes, so an entity left POSTED without its `posted_at` while the service still reads stock breaks the constraint (a 500 the unit tests cannot see). See `StockAdjustmentService.post`.
- **Optimistic lock failures** (`@Version`) are answered by `StaleDataAdvice`: back to the page with "changed by someone else, reload". Controllers do not catch them.
- **Labels (INV-03, MD-02):** `Labels.qrSvg(code)` for pages, `Labels.zpl(...)` (units) and `Labels.placeZpl(...)` (racks and slots) for label printers (50 x 30 mm, 203 dpi). Print units through `/stock/labels?receipt=|crate=|job=|unit=`, places through `/locations/{id}/labels` (its active racks and slots). A scanned code that is no unit's but a location's is a place: a transfer takes it as the destination, a count as where the next labels were found, the stock search as its location filter. Look units up first, then places.

### Database (Flyway)

- **Never edit a migration that has run anywhere.** Add the next `V{n}__description.sql` instead.
- **Stop the app before writing a migration.** Under devtools, the IDE's auto-build copies a saved file into `target/classes` and the restart applies it at once, half-finished or not. Check `flyway_schema_history` before touching a recent migration.
- **Each module migration seeds its own security data:** its `pages` row(s), its `permissions`, and the `role_pages` / `role_permissions` grants for the SRS 2.2 roles. ADMIN gets everything.
- **Name constraints and indexes** (`chk_...`, `idx_...`, `uk_...`). Add `CHECK` constraints for status columns.
- **Ledger tables are append-only** (stock movements, stock cost entries, shipment allocations, cutting job outputs, stock count lines, journal entries and lines, sales payments, credit note lines and units): give them a `BEFORE UPDATE OR DELETE` trigger calling `forbid_ledger_modification()` (V10), make the entity `@Immutable`, and give its repository only `save` and finders.
- **Child rows that get renumbered** (order lines, receipt crates) use `DEFERRABLE INITIALLY DEFERRED` unique keys, so a draft can reorder them in one save.

### Security

- **Opening a screen requires `PAGE_<CODE>`. Doing an action requires `PERM_<CODE>`.**
  - Controllers: `@PreAuthorize` on every handler.
  - Templates: `sec:authorize` on every menu link and action button.
  - URL rules in `SecurityConfig` only separate public from authenticated.
- **Get the current user from `AppUserPrincipal.current()`.** Do not query the users table during a request just to get the id.
- **Users are disabled, never deleted.** Roles and permissions are deactivated, never deleted. Permissions are created only by module migrations, because code checks them by code.
- **State changes are POST** (with CSRF), never GET links. For a confirmation, use `<form class="confirm-submit" data-confirm="...">` (add `danger` for destructive actions).
- **When access is taken away, end the sessions:** call `UserSessions.expire(userId)` inside `AfterCommit.run(...)` (disable, password reset, role change).
- **Check an action inside a handler with `AppUserPrincipal.currentHas("PERM_X")`**, for example to decide whether a form may change roles.

### UI (iVura page anatomy, SRS 3.3)

- Every page wraps itself in the shell: `th:replace="~{layout/sidebar :: html(#{title.key}, ~{::content})}"`.
- **Every screen works from a 360 px phone to a desktop:** the warehouse floor uses phones and tablets.
  - At 768 px and less the sidebar is a drawer opened from the header's menu button (`iwarehouse.js`).
  - At 640 px and less table rows become cards. `iwarehouse.js` labels each cell with its column header, so every table needs a `<thead>`. An actions column takes `<th><span class="sr-only">…</span></th>` (no label on the card). Opt out with `data-cards="off"`.
  - Line forms keep usable field widths on tablets and scroll in their box.
  - Never let the page scroll sideways. Before calling a screen done, check it at 360, 390, 768 and 1024 px with its dialogs open.
- List page: `page-header` → `section` with `section-header` + `table-toolbar` → `table-container` → `layout/pagination :: pager(page, baseUrl, query)`. Pass `query` without a leading `?`.
- **Every table that lists records has page numbers.** This includes lists inside a detail page: History tabs, a unit's movements, a role's users, a price list's rows, report groups. The pager shows as soon as the table has rows. Rows per page: `Paging.SIZE` (20) everywhere.
  - A query pages in the database.
  - A list already in memory pages with `Paging.of(list, page)`.
  - On a detail page with tabs, the open tab takes the page: link with `?tab=x&page=n`, use `Paging.pageOf("x", openTab, page)` (the other tabs start at their first page), and make every tab follow the `tab` model attribute.
  - A second paged table on the same page uses `pagerParam(page, baseUrl, query, 'spage')`, with the other table's page in its query.
  - Shown whole on purpose: a document's own lines with their totals, entry forms, a cut's balance, one change's fields, the dashboard's latest-six previews (with "View all") and the location tree (it folds).
- Forms in `form.html`, details in `view.html`. Use Choices.js for searchable selects: `data-multiselect` for multi-selects, `data-searchable` for long single selects (countries). Show validation errors with `layout/errors`. A `data-multiselect` select can carry `data-placeholder`, `data-no-choices` and `data-no-results` texts; never pass `addItems: false` to Choices for a select (it disables the select).
- In templates, look a map up by a variable with `map.get(key)`, never `map[key]`: SpEL reads a bare name in brackets as the literal key "key" and returns nothing.
- Never name a model attribute `session`, `request`, `param` or `application`: Thymeleaf reserves them (`${session.x}` reads the HTTP session and shows nothing). A till session is `till`.
- Don't call static classes (`T(...)`) in templates: put the values in the model (Thymeleaf 3.1 restricts them).
- Before adding a message key prefix, check it is free: `receipt.*` belongs to goods receipts, so the sales receipt uses `posReceipt.*`.
- **Forms with editable rows** (document lines) use `js/line-form.js`: a `<template class="line-template">` row with `{i}` in the field names, `tbody.line-body`, a `.line-add` button. The controller drops blank rows, then validates with `SpringValidatorAdapter` (no `@Valid` on the handler), so a spare empty row is not an error. Business errors on a row use `BusinessException.onField("lines[2].quantity", ...)`. A form with several row tables (recording a cut: leftovers, breakage) uses `js/cutting-form.js`: each `tbody.line-body` carries `data-prefix`, and its `.line-add` button and `template.line-template` a matching `data-for`.
- **Rows written in one transaction share a timestamp**, so `ORDER BY created_at, id` (a random UUID) does not keep their order. Sort them by a business key in the service, as `CuttingJobService.outcome` does.
- **Detail pages of documents with child rows** show their History with `dataChangeService.historyWithChildren(type, id, childType, parentField, ...)` and `audit/history :: timeline-records`, so removed rows still appear. A cancel or close with a reason uses `fragments/reason-modal :: reasonModal(...)` opened by `data-modal-open`.
- No inline colours: use the CSS variables in `iwarehouse.css` (tints: `--tint-blue/green/purple/orange/neutral/red`; monospace: `--font-mono`), and keep both light and dark themes working. Add new styles at the end of `iwarehouse.css` under "iWarehouse additions".
- **One look per component on every screen.** Reuse the existing classes; never restyle a component for one page.
  - **Sizes:** fields and buttons are 42 px (`.btn-sm` 30 px). List filters, the fit search and report filters are 38 px, buttons included. Fields in line forms are 38 px.
  - **Page header:** the title on the left; actions in a `.btn-group` on the right, with Back first (`#{common.back}`), then the actions, then the primary one.
  - **Links:** the record's own link in a list is `a.row-link`. A link to another record is a plain `<a>` (or `a.mono` for a code), which the stylesheet colours. Don't give it a class of its own.
  - **Notes:** `.form-note` for help under a form or list. `.doc-note` (`.is-danger` when cancelled or rejected) for a document's status. `.hint` under a field.
  - **Counts and numbers:** a count in a table is a plain number in `td.num`; badges are for states. Numbers go through `@num` (`money`, `amount`, `price`, `m2`, `kg`, `rate`), never `#numbers` with a locale grouping.
  - **Dates (NFR-15):** `dd/MM/yyyy`, or `dd/MM/yyyy HH:mm` with a time. Seconds only in logs (activity, audit, movements). The value of a date input stays `yyyy-MM-dd`, as browsers require.
  - **Filters:**
    - A list's filters sit in its table section header (`.table-toolbar`), and selects submit on change.
    - A report's filters are a full-width `.table-toolbar.report-filters` card above the figures, left-aligned, in two `.filter-group`s: the period, then the other filters with the apply button (`btn-outline btn-sm`). The bar then wraps between the groups and never leaves the button alone. See `cutting-jobs/yield.html`.
  - **Forms:** a single-card form uses `.form-container` (800 px) with `.form-actions` inside it. A document form with line tables uses full-width sections and a `.form-actions.sticky-actions` bar.
  - **Check:** compare a new screen with a finished one of the same kind (list, detail, form) in light and dark themes before calling it done.
- No CDN links. Vendor any new library under `static/vendor/`, because the warehouse LAN may have no internet.

### i18n

- Every visible string is a message key. Add each new key to `messages.properties` (en) and `messages_fr.properties`.
  - `messages_rw.properties`: add the key once the translation is checked. Until then, English is used.
- Keep `.properties` files ASCII. Write accents as `é`. This avoids the double-encoding bug iVura had (`Â©`).

### Code style

- Put the iVura-style header block on new classes (Project / Package / File / Date / User / Desc).
- Use constructor injection. No field `@Autowired`.
- Services own transactions (`@Transactional` on the service, read-only by default for queries). Controllers stay thin.
- Validate inputs with Bean Validation on DTOs. Never bind entities directly to forms. Give constraint messages as keys: `message = "{user.email.required}"`.
- When a business rule refuses a request, throw `BusinessException.onField(field, key, args)` (shown under the field, form kept) or `BusinessException.of(key, args)` (shown as a toast). Controllers resolve the text with `Messages.get(...)` and log the FAILED activity. A missing record is `NotFoundException`, which renders the 404 page.

## Testing

- Put pure business rules in plain classes (like `audit/AuditSnapshots`) and unit-test them: costing, area/cullet split, EoD reconciliation, VAT, posting matrix.
- For the posting engine, assert every journal balances (debits = credits) and the inventory GL matches the stock valuation (SRS AT-10).
- Integration tests against PostgreSQL (Testcontainers) are planned. See TODO.md.

## Don't

- Don't use `ddl-auto=update` and don't hand-edit the schema.
- Don't use `double` for money or area.
- Don't bulk-update audited tables (see Audit rule 2).
- Don't load secrets from code. Use environment variables (`DB_*`, `MAIL_*`, `MINIO_*`).
- Don't remove or reword an SRS requirement in code comments to make a test pass. Raise it instead.
