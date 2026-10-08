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
- **Products and locations (MD-01..03):** a product's type, colour/finish and thickness are fixed after creation, so stock of it never changes meaning. A location's type follows from its parent (none = site), and type and parent are fixed. Vehicle locations are created by the Fleet module only. Deactivate locations leaf first.
- **Selling prices (MD-06):** get them from `PriceListService.priceFor(customer, product)` (the customer's list, then the default list) and charge `Pricing.chargeableArea(w, h, priceListService.minChargeableArea(list))`. Store the price, the list and its VAT flag on the document line. Never read `price_list_items` directly.
- **Customer credit terms** (credit limit, payment terms, price list) change only through `CustomerService` with `canSetTerms` = `AppUserPrincipal.currentHas("PERM_MANAGE_CUSTOMER_TERMS")`. Walk-in customers never have credit. The default WALK-IN customer stays an active walk-in.
- **Customer and supplier codes** come from `DocumentNumberService.next(DocumentType.CUSTOMER / SUPPLIER)`, like documents.
- **`open-in-view` is off:** load everything a page shows inside the service transaction (`@EntityGraph` on the repository method, or keep the reference as an id, as `Location.parentId` does). A lazy relation touched in a template throws. An entity graph that fetches two collections of one entity needs them as `Set`s (`Shipment.receipts` and `costs`): a `List` repeats its rows once per row of the other.
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
- **Landed cost (PRC-03..06):** import bills belong to a `Shipment` that links posted receipts. Bills are drafts until posted; `ShipmentService.post` converts each at the rate of its own date (`CostType.rateSource()`: CUSTOMS for duty), rounds the RWF total once, splits it with `LandedCost.split` (largest remainder, parts add up exactly) over the crates by the shipment's method (m², purchase value or kg shipped, broken sheets included) and over each crate's sheets. Sheets in stock get their part (`addLandedCost`), sheets already gone are expensed, broken sheets go to the claim; each crate's split is a `ShipmentAllocation` (append-only) and the MAC moves with `Costing.addValue`. Posting locks the shipment, then its products. Receipts and method are fixed after the first posting; a posted bill is corrected by a credit note (negative bill), never edited.
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
- **Set a status and the fields its CHECK needs in one step, after the operation's queries.** A query flushes pending changes, so an entity left POSTED without its `posted_at` while the service still reads stock breaks the constraint (a 500 the unit tests cannot see). See `StockAdjustmentService.post`.
- **Optimistic lock failures** (`@Version`) are answered by `StaleDataAdvice`: back to the page with "changed by someone else, reload". Controllers do not catch them.
- **Labels (INV-03):** `Labels.qrSvg(code)` for pages, `Labels.zpl(...)` for label printers (50 x 30 mm, 203 dpi). Print through `/stock/labels?receipt=|crate=|job=|unit=`.

### Database (Flyway)

- **Never edit a migration that has run anywhere.** Add the next `V{n}__description.sql` instead.
- **Stop the app before writing a migration.** Under devtools, the IDE's auto-build copies a saved file into `target/classes` and the restart applies it at once, half-finished or not. Check `flyway_schema_history` before touching a recent migration.
- **Each module migration seeds its own security data:** its `pages` row(s), its `permissions`, and the `role_pages` / `role_permissions` grants for the SRS 2.2 roles. ADMIN gets everything.
- **Name constraints and indexes** (`chk_...`, `idx_...`, `uk_...`). Add `CHECK` constraints for status columns.
- **Ledger tables are append-only** (stock movements, stock cost entries, shipment allocations, cutting job outputs, stock count lines now; journal lines later): give them a `BEFORE UPDATE OR DELETE` trigger calling `forbid_ledger_modification()` (V10), make the entity `@Immutable`, and give its repository only `save` and finders.
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
