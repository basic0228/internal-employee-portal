<<<<<<< HEAD
# Internal Employee Portal — Deployment Starter

Spring Boot 3.5.0 / Java 21 / MyBatis / PostgreSQL. This is a **deployment starter**, not the completed assignment. Vue 3 will be added later.

## Local development

Install JDK 21, Maven 3.9+, and PostgreSQL. Create a local database named `employee_portal` and set environment variables `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGPASSWORD` as needed.

```bash
cd backend
mvn spring-boot:run
```

- `GET http://localhost:8080/api/health` → `{ "status": "UP", "service": "employee-portal" }`
- `GET http://localhost:8080/api/health/db` → `{ "database": "UP" }` when PostgreSQL is reachable.

## Railway deployment

1. Push this entire repository to GitHub.
2. Connect the GitHub repository to the Railway application service. Keep the Dockerfile at repository root; choose Dockerfile builder if necessary.
3. In the **application service**, set Railway reference variables (the DB service is named `Postgres`):
   - `PGHOST` = `${{Postgres.PGHOST}}`
   - `PGPORT` = `${{Postgres.PGPORT}}`
   - `PGDATABASE` = `${{Postgres.PGDATABASE}}`
   - `PGUSER` = `${{Postgres.PGUSER}}`
   - `PGPASSWORD` = `${{Postgres.PGPASSWORD}}`
4. Deploy and generate a public domain for the application service.
5. Visit `/api/health` and `/api/health/db` to check deployment and DB connectivity.

**Security note:** `/api/health/db` is temporarily public and reveals only UP/DOWN. Restrict or remove it before final submission. Spring Security currently uses a generated development password; real account authentication and authorization are not yet implemented. Do not submit this starter as the final assignment.

No credentials are included in this repository. Do not commit `.env` files or candidate API keys.
=======
# internal-employee-portal
internal-employee-portal
>>>>>>> origin/main
