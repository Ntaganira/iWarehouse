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

1. **Every business entity is audited.** Put `@AuditedEntity(ref = "<readable field>")` on it, e.g. `@AuditedEntity(ref = "invoiceNumber")`. Without the annotation, its changes leave no before/after record.
2. **Write through JPA, inside a `@Transactional` service method.** The change log is written by `DataChangeEventListener` at commit time, in the same transaction. These patterns bypass the listener, so don't use them on audited tables:
   - JPQL or SQL bulk `UPDATE` / `DELETE`
   - `@Modifying` queries
   - `JdbcTemplate` writes
   - writes outside a transaction

   If you truly need a bulk write, stop and ask first.
3. **Collections are not captured.** Changing a `@ManyToMany` set (e.g. a role's permissions) produces no change row. Record those changes explicitly in the service, with an activity log entry that names what was added and removed.
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
- **Dimensions:** whole millimetres (`INTEGER`). Areas in m² as `NUMERIC(10,4)`, computed as `width × height / 1_000_000`. Weight in kg = m² × thickness(mm) × 2.5.
- **Foreign currency:** keep amount, currency and rate on the document. Ledgers are always RWF.
- **Posted records are never edited or deleted.** This covers stock movements, invoices, journals and payments. Correct them with a reversing document.
- **Stock unit lifecycle:** cutting never changes a unit's size. The source unit becomes `CONSUMED` and new units are created. Area is conserved within 1% (PRD-06).

### Database (Flyway)

- **Never edit a migration that has run anywhere.** Add the next `V{n}__description.sql` instead.
- **Each module migration seeds its own security data:** its `pages` row(s), its `permissions`, and the `role_pages` / `role_permissions` grants for the SRS 2.2 roles. ADMIN gets everything.
- **Name constraints and indexes** (`chk_...`, `idx_...`, `uk_...`). Add `CHECK` constraints for status columns.

### Security

- **Opening a screen requires `PAGE_<CODE>`. Doing an action requires `PERM_<CODE>`.**
  - Controllers: `@PreAuthorize` on every handler.
  - Templates: `sec:authorize` on every menu link and action button.
  - URL rules in `SecurityConfig` only separate public from authenticated.
- **Get the current user from `AppUserPrincipal.current()`.** Do not query the users table during a request just to get the id.
- **Users are disabled, never deleted.**

### UI (iVura page anatomy, SRS 3.3)

- Every page wraps itself in the shell: `th:replace="~{layout/sidebar :: html(#{title.key}, ~{::content})}"`.
- List page: `page-header` → `section` with `section-header` + `table-toolbar` → `table-container` → `layout/pagination :: pager(page, baseUrl, query)`. Pass `query` without a leading `?`.
- Forms in `form.html`, details in `view.html`. Use Choices.js for searchable selects. Show validation errors with `layout/errors`.
- No inline colours: use the CSS variables in `iwarehouse.css`, and keep both light and dark themes working. Add new styles at the end of `iwarehouse.css` under "iWarehouse additions".
- No CDN links. Vendor any new library under `static/vendor/`, because the warehouse LAN may have no internet.

### i18n

- Every visible string is a message key. Add each new key to `messages.properties` (en) and `messages_fr.properties`.
  - `messages_rw.properties`: add the key once the translation is checked. Until then, English is used.
- Keep `.properties` files ASCII. Write accents as `é`. This avoids the double-encoding bug iVura had (`Â©`).

### Code style

- Put the iVura-style header block on new classes (Project / Package / File / Date / User / Desc).
- Use constructor injection. No field `@Autowired`.
- Services own transactions (`@Transactional` on the service, read-only by default for queries). Controllers stay thin.
- Validate inputs with Bean Validation on DTOs. Never bind entities directly to forms.

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
