# Build Plan

Work one phase at a time. Before coding a phase: show a plan and wait for approval.
A phase is done only when `mvn clean verify` passes and all its boxes are ticked.

---

## Phase 1 — Project skeleton

- [x] Maven Wrapper (`./mvnw`) so reviewers do not need Maven installed
- [x] `pom.xml`: Java 21, Spring Boot 3.x (latest stable), web, validation, data-mongodb, data-redis,
      actuator, Lombok, springdoc-openapi, ShedLock (spring + mongo provider), Bucket4j (Redis),
      test deps (JUnit 5, Mockito, AssertJ, Testcontainers MongoDB + Redis)
- [x] `docker-compose.yml` with MongoDB 7 and Redis 7
- [x] `application.yml` (Mongo URI with pool options, Redis, read preference, scheduler props placeholders)
- [x] Main application class in `com.peerislands.orders` and package layout
- [x] Actuator health endpoint reachable
- [x] **Tests:** context-loads test with Testcontainers
- [x] `./mvnw clean verify` passes
- [ ] Update `docs/AI_USAGE.md`

## Phase 2 — Domain model + state machine

- [x] `OrderStatus` enum with per-constant allowed next states (State pattern)
- [x] `requiredPredecessorOf(target)` helper
- [x] `Order` and `OrderItem` (Lombok builder, `@Version`, `Decimal128` mapping for `BigDecimal`)
- [x] Server-side total calculation
- [x] **Tests:** unit test for every valid transition
- [x] **Tests:** unit test for every invalid transition (including terminal states)
- [x] **Tests:** total calculation (BigDecimal precision)
- [x] `./mvnw clean verify` passes
- [x] **Tests:** `OrderMappingIT` — Decimal128 / string-enum / version storage against real MongoDB (added)
- [ ] Update `docs/AI_USAGE.md`

## Phase 3 — Create + get order, validation, idempotency, error handling

- [x] Request/response DTO records + mapper
- [x] `POST /api/v1/orders` (201 + Location; status PENDING; total computed server-side)
- [x] `Idempotency-Key` handling: same key + same hash → 200 with original; different hash → 422; `DuplicateKeyException` race → same check
- [x] Unique sparse index on `idempotencyKey`
- [x] `GET /api/v1/orders/{id}` (404 if missing)
- [x] `@RestControllerAdvice` with RFC 7807 `ProblemDetail` (400, 404)
- [x] **Tests:** service tests (Mockito) for create / idempotent replay / duplicate-key race / not found
- [x] **Tests:** integration tests (Testcontainers) for POST and GET, validation errors, Decimal128 round-trip
- [x] **Tests:** concurrent create with same idempotency key returns one order
- [x] `./mvnw clean verify` passes
- [ ] Update `docs/AI_USAGE.md`

## Phase 4 — List with status filter + keyset pagination + indexes

- [x] Indexes: `{status, createdAt, _id}`, `{createdAt, _id}`, `{customerId, createdAt: -1}`
- [x] Cursor encode/decode of `(createdAt, id)`; 400 on invalid cursor
- [x] `GET /api/v1/orders?status=&size=&cursor=` newest first, `{ items, nextCursor }`, max size 100
- [x] Keyset query via MongoTemplate (no skip/offset)
- [x] **Tests:** unit tests for cursor codec
- [x] **Tests:** integration tests: paging through all pages, ties on `createdAt`, status filter, last page `nextCursor = null`, size > 100 → 400
- [x] **Tests:** indexes exist after startup; explain() confirms list queries use them with no in-memory SORT
- [x] `./mvnw clean verify` passes
- [ ] Update `docs/AI_USAGE.md`

## Phase 5 — Cancel + PATCH status with conditional atomic updates

- [x] Repository fragment: `findAndModify` filtered on `{_id, status: expected}`, sets status/updatedAt, increments version
- [x] `POST /api/v1/orders/{id}/cancel` (200; 404 missing; 409 not PENDING)
- [x] `PATCH /api/v1/orders/{id}/status` (filter on required predecessor; 404; 409; CANCELLED not allowed)
- [x] 404 vs 409 disambiguation only after a failed atomic update
- [x] Publish `OrderStatusChangedEvent` on success
- [x] **Tests:** service tests (Mockito) for success / 404 / 409 / event published
- [x] **Tests:** integration tests for every PATCH transition and cancel paths; version increments
- [x] **Tests:** concurrent cancel + PATCH on the same order → exactly one wins (mutation-checked: a read-check-write version fails it)
- [x] `./mvnw clean verify` passes
- [ ] Update `docs/AI_USAGE.md`

## Phase 6 — Scheduler + ShedLock + SchedulerProperties + race test

- [x] `SchedulerProperties` validated `@ConfigurationProperties` record (`enabled`, `cron`, `min-pending-age`, `batch-size`, `lock-at-most-for`)
- [x] ShedLock config with Mongo lock provider (`shedLock` collection)
- [x] `OrderStatusScheduler` (thin, `@Scheduled` + `@SchedulerLock`, conditional on `enabled`)
- [x] `PendingOrderProcessor`: cutoff, ID-only batched fetch sorted by `createdAt`, `updateMulti` with `{_id in ids, status: PENDING}`, publish `OrdersProcessedEvent`, loop until empty
- [x] Env-var overrides verified
- [x] Scheduler disabled in test profile
- [x] **Tests:** processor integration tests: promotes eligible orders, respects `min-pending-age`, multiple batches, idempotent re-run, skips non-PENDING
- [x] **Tests:** properties validation (invalid values fail startup)
- [x] **Tests:** race test — cancel vs processor on one order with `CountDownLatch`, `@RepeatedTest(200)`, exactly one succeeds (both outcomes exercised; mutation-checked)
- [x] `./mvnw clean verify` passes
- [ ] Update `docs/AI_USAGE.md`

## Phase 7 — Redis cache + eviction events + rate limiting

- [x] Cache-aside on GET by id, TTL 60 s (configurable)
- [x] Event listeners evict on `OrderStatusChangedEvent` and `OrdersProcessedEvent`
- [x] Redis unavailable → reads fall back to MongoDB
- [x] Bucket4j + Redis rate limiting per client IP; configurable capacity/refill
- [x] 429 `ProblemDetail` with `Retry-After`
- [x] **Tests:** integration tests: cache hit, eviction after cancel/PATCH/scheduler, TTL config
- [x] **Tests:** rate limit exceeded → 429 + `Retry-After`
- [x] `./mvnw clean verify` passes
- [ ] Update `docs/AI_USAGE.md`

## Phase 8 — Swagger docs, error polish, README completion

- [ ] springdoc-openapi annotations / descriptions for all 5 endpoints, headers, error responses
- [ ] Swagger UI reachable at `/swagger-ui.html`
- [ ] Review all error responses for consistent `ProblemDetail` shape
- [ ] Complete `README.md` (run instructions, curl examples, design highlights, assumptions, testing)
- [ ] Final check that docs/DESIGN.md matches the implementation
- [ ] **Tests:** full `mvn clean verify` green
- [ ] Finalize `docs/AI_USAGE.md` (lessons learned)
