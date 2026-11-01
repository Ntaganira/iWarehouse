# iWarehouse Task Tracker

> Work in progress. Tick each item only when it is implemented and verified.
> Commit or push only when the user explicitly authorizes it.

## 1. Sales, COGS and payment reporting

- [x] Add a date-filtered revenue and COGS query joining sales lines, inventory products, trips and deliveries.
- [x] Group revenue, COGS and gross profit by seller user ID and vehicle plate.
- [x] Use invoiced/posted customer sales totals as revenue and historical inventory cost as COGS.
- [x] Add a driver revenue leaderboard from closed delivery invoices, sorted highest first.
- [x] Add daily reconciliation totals for cash, mobile money and bank transfers.
- [x] Expose the reports through the existing reporting UI with date filters, tables and exports where supported.
- [x] Add English and French message keys for the new report labels.
- [ ] Add unit tests for report calculations and integration coverage for repository queries.
  - Unit tests for the sales/driver calculations are in `SalesAnalysisTest`; repository integration coverage still waits on Testcontainers.

## 2. WhatsApp confirmations

- [x] Add validated `whatsapp_number` storage to users and customers.
- [x] Add `whatsapp_number` fields with `+250` international-format validation to registration and profile forms.
- [x] Add configurable WhatsApp gateway settings without hard-coded credentials.
- [x] Add an asynchronous post-commit event listener for completed transactions and generated invoices.
- [x] Send a polite confirmation through the external WhatsApp gateway automatically.
- [x] Ensure a gateway failure never rolls back the completed sale and is logged safely.
- [x] Add English and French message keys for labels, confirmations and errors.
- [x] Add validation, listener and dispatch tests that do not call the real gateway.

## 3. Credit guardrails

- [x] Define how open invoice debt is derived for `UNPAID` and `PARTIALLY PAID` invoices.
- [x] Refuse new credit when the customer has previous open invoice debt.
- [x] Add the custom `CreditLimitExceededException` and a clear UI warning.
- [x] Allow managers to configure hard credit collection intervals.
- [x] Store or derive a checkout `due_date` from the customer's payment term days.
- [x] Flag overdue customer accounts in administration grids.
- [x] Reuse the existing ageing and receivable logic where possible.
- [x] Add English and French message keys for the new credit warnings and labels.
- [x] Add tests for open-debt detection, due-date calculation and overdue flags.

## 4. Odometer controls

- [x] Add configurable monthly distance targets for maintenance tracking.
- [x] Require an active manual mileage reading before a trip can be created.
- [x] Validate that a trip's start odometer is not below the previous completed trip's end odometer.
- [x] Show clear validation errors on the trip form.
- [x] Add English and French message keys for odometer and target labels and errors.
- [x] Add tests for trip odometer validation and monthly target calculations.

## 5. Product aliases

- [x] Add the `product_alias` table and relationship to `Product`.
- [x] Add alias management to product forms and services.
- [x] Search products by primary name/code and aliases with a `LEFT JOIN`.
- [x] Use alias-aware search in the POS so every alias resolves the same product card.
- [x] Keep global product search consistent with the alias-aware query.
- [x] Add English and French message keys for alias labels and validation.
- [ ] Add tests for alias persistence and alias-aware search.
  - Persistence and normalization are tested in `ProductServiceTest`; the repository search test still waits on Testcontainers.

## 6. Rwanda administrative hierarchy

- [x] Add `V61__rwanda_administrative_hierarchy.sql`: five UUID/audited tables with official code lengths, unique codes,
      parent foreign keys, `is_enabled` and `allow_edit`.
- [x] Seed the canonical 5 / 30 / 416 / 2,148 / 14,837 dataset and verify the counts in PostgreSQL.
- [x] Add `V62__rwanda_hierarchy_access.sql`: page, view/manage permissions and role grants.
- [x] Add entities, repositories, `HierarchyService` and `HierarchyController` with cascading filters, pagination,
      effective visibility, rename, toggle, typed-code cascade delete and the `allow_edit` lock.
- [x] Add the dashboard template, modals, `hierarchy.js`, responsive CSS, EN/FR messages, sidebar link and global search.
- [x] Fix empty filters so the default page lists rows, and style the filter selects; smoke-tested 20 rows with "All" selected.
- [x] Make each full-path segment a drill-down link to the next tier (a village filters by its own code); smoke-tested 3 districts, 10 sectors and 1 village.
- [x] Add `HierarchyServiceTest` (10 tests) and run the full suite (742 tests, 0 failures).
- [ ] Add Testcontainers coverage for repository filtering, status queries and descendant counts.
- [ ] Check the dashboard and its modals at 360, 390, 768 and 1024 px in light and dark themes.
- [ ] Commit and push after explicit user approval.

## 7. Verification

- [x] Run the full Maven test suite.
- [ ] Verify the new screens and forms at phone, tablet and desktop widths in light and dark themes.
- [x] Verify Flyway startup and schema validation.
- [x] Review `TASK.md` and tick all completed items.
- [x] Commit and push after explicit user approval.
