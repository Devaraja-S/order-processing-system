# CLAUDE.md

## Project summary

Backend-only E-commerce Order Processing System (PeerIslands take-home). It exposes a REST API to
create, fetch, list, update the status of, and cancel orders. A background job moves PENDING orders
to PROCESSING every 5 minutes. MongoDB is the only source of truth; Redis holds only cache entries
and rate-limit buckets. **Full HLD/LLD lives in [docs/DESIGN.md](docs/DESIGN.md)**, the build plan in
[docs/TASKS.md](docs/TASKS.md), and AI usage is logged in [docs/AI_USAGE.md](docs/AI_USAGE.md).

## Tech stack

Java 21 · Spring Boot 3.x (latest stable) · Maven · Spring Data MongoDB (MongoRepository + MongoTemplate)
· MongoDB 7 · Redis 7 · ShedLock (Mongo provider) · Bucket4j (Redis) · Spring Data Redis cache
· springdoc-openapi · Lombok · Bean Validation · Actuator · JUnit 5 · Mockito · AssertJ · Testcontainers.
Base package: `com.peerislands.orders`
(sub-packages: `controller, service, domain, repository, scheduler, event, config, exception, mapper`).

## Commands

```bash
docker compose up -d          # start MongoDB 7 + Redis 7
./mvnw clean verify           # build + unit + integration tests (Testcontainers needs Docker)
./mvnw spring-boot:run        # run the app
# Swagger UI: http://localhost:8080/swagger-ui.html
# Health:     http://localhost:8080/actuator/health
```

## Non-negotiable rules

1. **Conditional atomic updates only.** Every status change is a single MongoDB operation that
   filters on the expected current status (`findAndModify` for one order, `updateMulti` for scheduler
   batches) and increments `version`. **Never read-check-write in Java.**
   - Cancel: `findAndModify({_id, status: PENDING})` → CANCELLED. Null result → 404 if missing, else 409.
   - Cancel vs scheduler: exactly one wins; both winning must be impossible.
2. **Money is `BigDecimal`** (stored as `Decimal128`). Never `double`/`float`. `totalAmount` is
   computed server-side, never accepted from the client.
3. **No JPA/Hibernate.** Spring Data MongoDB only.
4. **Keyset pagination only** (cursor = `createdAt` + `id`). Never `skip`/offset. Max page size 100.
5. **Config-driven scheduler.** All values in the validated `SchedulerProperties` record
   (`orders.scheduler.*`), overridable via env vars. Nothing hard-coded.
6. **Exactly 5 endpoints** under `/api/v1` (see DESIGN.md §API). Do not add endpoints.
7. **No UI.** Backend only.
8. **MongoDB is the single source of truth.** Redis is cache + rate limiting only; losing Redis
   must never lose data.
9. CANCELLED is reachable **only** via `POST /orders/{id}/cancel`, never via PATCH status.
10. Only the patterns listed in DESIGN.md (State, Repository, layered + DTOs, Builder, Observer,
    centralized exception handling). Do not add patterns for show.

## Workflow rules

- Work on **one phase at a time** (docs/TASKS.md).
- **Show a plan and wait for approval before writing code** for each phase.
- Run tests (`./mvnw clean verify`) and **fix failures before declaring a phase done**.
- Tick the checkboxes in docs/TASKS.md as tasks complete.
- Add an entry to docs/AI_USAGE.md at the end of each phase.
- **Never change a design decision silently.** Propose the change to docs/DESIGN.md first and ask.
- If something is unclear, ask instead of inventing.
- Do not commit unless asked.

## Coding conventions

- Constructor injection only (Lombok `@RequiredArgsConstructor` is fine); no field `@Autowired`.
- Java `record`s for request/response DTOs; mapping lives in `mapper`.
- No business logic in controllers — they validate, delegate to services, and map responses.
- Throw meaningful domain exceptions (e.g. `OrderNotFoundException`, `InvalidStatusTransitionException`);
  translate to RFC 7807 `ProblemDetail` in a single `@RestControllerAdvice`.
- Logging via SLF4J (`@Slf4j`). Never `System.out` / `printStackTrace`.
- Use `java.time.Instant` for timestamps; inject a `Clock` where time matters for tests.
- Tests: JUnit 5 + AssertJ; Mockito for service tests; Testcontainers for integration tests.
  Scheduler is disabled in tests — call `PendingOrderProcessor` directly.
