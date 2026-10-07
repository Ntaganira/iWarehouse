# iWarehouse

Glass Reproduction Warehouse & Mobile POS Management System (ERP-Lite). It tracks imported glass sheets, cutting into customer pieces and off-cuts, counter and mobile ("moving shop") sales, and RWF accounting. It keeps a before/after audit trail of every change.

Built on the same stack and conventions as [iVura](https://github.com/Ntaganira/iVura).

## Run locally

Requirements: Java 17+, Docker.

```bash
docker compose up -d        # PostgreSQL 16 on 5432 (db iwarehouse), MinIO on 9000/9001
./mvnw spring-boot:run      # http://localhost:8080
```

Sign in with **admin / password123** and change the password.

## Stack

| Layer | Technology |
| --- | --- |
| Backend | Java 17, Spring Boot 3.4, Spring MVC, Spring Security, Bean Validation |
| Persistence | Spring Data JPA (Hibernate 6.6), Flyway, PostgreSQL 16 |
| Frontend | Thymeleaf + Spring Security extras, custom CSS design system, Choices.js, Chart.js (vendored) |
| Files / PDF / labels | MinIO, OpenHTMLToPDF, ZXing |
| Build | Maven wrapper, Lombok |

## Audit trail

- **Activity log** (`/activity`, `/activity/me`): who did what, with outcome, IP and request id.
- **Data changes** (`/audit`): for every create, update or delete of an audited record, the full record before and after, changed fields, user, IP, device and reason. The change is stored in the same transaction as the business change. Both tables are append-only.

## Project docs

- `CLAUDE.md`: architecture, conventions and rules for contributors (and Claude).
- `TODO.md`: phased backlog mapped to SRS requirement IDs.
