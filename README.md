# E-commerce Order Processing System

Backend-only order processing service built for the PeerIslands take-home assignment.

## Overview

A REST API to create, retrieve, list, update the status of, and cancel orders. A background job
moves PENDING orders to PROCESSING every 5 minutes. MongoDB is the single source of truth; Redis is
used only for caching and rate limiting.

Full design: [docs/DESIGN.md](docs/DESIGN.md).

## Tech stack

- Java 21, Spring Boot 3.x, Maven
- Spring Data MongoDB (MongoDB 7)
- Spring Data Redis cache, Bucket4j rate limiting (Redis 7)
- ShedLock (Mongo lock provider) for the scheduler
- springdoc-openapi (Swagger UI), Spring Boot Actuator, Bean Validation, Lombok
- JUnit 5, Mockito, AssertJ, Testcontainers

## How to run

Prerequisites: Java 21 and Docker. Maven is provided by the Maven Wrapper (`./mvnw`).

```bash
docker compose up -d      # MongoDB + Redis
./mvnw spring-boot:run    # start the app on :8080
```

- Swagger UI: http://localhost:8080/swagger-ui.html
- Health: http://localhost:8080/actuator/health

> **Already running Redis on port 6379?** (e.g. installed with Homebrew) The app would connect to that
> Redis instead of the Docker one. Either stop it, or start only MongoDB from compose and run Redis on
> another port:
>
> ```bash
> docker compose up -d mongodb
> docker run -d --rm --name orders-redis -p 6380:6379 redis:7
> REDIS_PORT=6380 ./mvnw spring-boot:run
> ```

### Configuration (environment variables)

Every value has a default in `src/main/resources/application.yml`. Any `orders.*` property can be
overridden with Spring Boot's environment-variable form (dots and dashes become underscores,
upper case), e.g. `orders.scheduler.min-pending-age` → `ORDERS_SCHEDULER_MIN_PENDING_AGE`.

| Variable | Default | Purpose |
|----------|---------|---------|
| `MONGODB_URI` | `mongodb://localhost:27017/orders?maxPoolSize=50&...` | MongoDB connection string, including pool settings |
| `MONGODB_READ_PREFERENCE` | `primary` | Read preference used in the default URI |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Redis location (cache + rate limiting) |
| `REDIS_TIMEOUT` / `REDIS_CONNECT_TIMEOUT` | `500ms` / `500ms` | Kept short so a Redis outage falls back fast |
| `ORDERS_CACHE_ORDER_TTL` | `PT60S` | Cache TTL for `GET /orders/{id}` |
| `ORDERS_RATE_LIMIT_ENABLED` | `true` | Turn rate limiting on/off |
| `ORDERS_RATE_LIMIT_CAPACITY` | `100` | Bucket size per client IP |
| `ORDERS_RATE_LIMIT_REFILL_TOKENS` / `ORDERS_RATE_LIMIT_REFILL_PERIOD` | `100` / `PT1M` | Greedy refill rate |
| `ORDERS_SCHEDULER_ENABLED` | `true` | Run the PENDING → PROCESSING job on this instance |
| `ORDERS_SCHEDULER_CRON` | `0 */5 * * * *` | When the job runs (every 5 minutes, on the clock) |
| `ORDERS_SCHEDULER_MIN_PENDING_AGE` | `PT0S` | Minimum order age before promotion (`PT5M` = guaranteed 5-minute cancel window) |
| `ORDERS_SCHEDULER_BATCH_SIZE` | `1000` | Orders promoted per database update (1–10,000) |
| `ORDERS_SCHEDULER_LOCK_AT_MOST_FOR` | `PT4M` | ShedLock safety timeout if an instance dies mid-run |

All settings are validated at startup; an invalid value (e.g. a bad cron or a negative duration)
stops the app with a clear message.

## API summary

Base path: `/api/v1`

| Method | Path | Description |
|--------|------|-------------|
| POST   | `/orders` | Create an order (optional `Idempotency-Key` header) |
| GET    | `/orders/{id}` | Get an order by ID |
| GET    | `/orders?status=&size=&cursor=` | List orders, newest first, keyset pagination |
| PATCH  | `/orders/{id}/status` | Update status (PROCESSING → SHIPPED → DELIVERED) |
| POST   | `/orders/{id}/cancel` | Cancel an order (only while PENDING) |

All errors use the RFC 7807 `application/problem+json` format (`type`, `title`, `status`,
`detail`, `instance`). Status codes: `201` created, `200` OK or idempotent replay, `400` invalid
input, `404` not found, `409` status conflict, `422` idempotency key reused with a different body,
`429` rate limited.

### curl examples

The responses below were captured from a real run of the app (IDs and timestamps will differ).

**Create an order** (`totalAmount` is computed by the server: 2 × 24.99 + 3 × 9.50 = 78.48)

```bash
curl -i -X POST http://localhost:8080/api/v1/orders \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: order-001' \
  -d '{"customerId":"cust-42","items":[
        {"productId":"sku-1","productName":"Wireless Mouse","quantity":2,"unitPrice":24.99},
        {"productId":"sku-2","productName":"USB-C Cable","quantity":3,"unitPrice":9.50}]}'
```
```
HTTP/1.1 201
Location: http://localhost:8080/api/v1/orders/6afff12c83fe4cdea92b0733
{"id":"6afff12c83fe4cdea92b0733","customerId":"cust-42","items":[{"productId":"sku-1","productName":"Wireless Mouse","quantity":2,"unitPrice":24.99},{"productId":"sku-2","productName":"USB-C Cable","quantity":3,"unitPrice":9.50}],"totalAmount":78.48,"status":"PENDING","createdAt":"2026-11-20T07:05:16.565Z","updatedAt":"2026-11-20T07:05:16.565Z"}
```

**Retry with the same key and body** → `200` with the same order (no duplicate is created).
**Same key, different body** → `422`:

```
{"type":"about:blank","title":"Idempotency key reused","status":422,"detail":"Idempotency-Key was already used with a different request payload","instance":"/api/v1/orders"}
```

**Get an order**

```bash
curl http://localhost:8080/api/v1/orders/6afff12c83fe4cdea92b0733
```

**List orders, newest first, with keyset pagination**

```bash
curl 'http://localhost:8080/api/v1/orders?status=PENDING&size=2'
```
```
{"items":[{"id":"6afff13583fe4cdea92b0735",...},{"id":"6afff13583fe4cdea92b0734",...}],
 "nextCursor":"MTc5NTE1ODMyNTE2MTo2YWZmZjEzNTgzZmU0Y2RlYTkyYjA3MzQ"}
```

Pass `nextCursor` back to get the next page; it is `null` on the last page.

```bash
curl 'http://localhost:8080/api/v1/orders?status=PENDING&size=2&cursor=MTc5NTE1ODMyNTE2MTo2YWZmZjEzNTgzZmU0Y2RlYTkyYjA3MzQ'
```

**Update status** (PENDING → PROCESSING → SHIPPED → DELIVERED, one step at a time)

```bash
curl -X PATCH http://localhost:8080/api/v1/orders/6afff13583fe4cdea92b0734/status \
  -H 'Content-Type: application/json' -d '{"status":"PROCESSING"}'
```

Skipping a step → `409`:

```
{"type":"about:blank","title":"Invalid status transition","status":409,"detail":"Order 6afff13583fe4cdea92b0735 is PENDING; cannot change status to SHIPPED","instance":"/api/v1/orders/6afff13583fe4cdea92b0735/status","currentStatus":"PENDING","targetStatus":"SHIPPED"}
```

`CANCELLED` via PATCH → `400`:

```
{"type":"about:blank","title":"Status not allowed","status":400,"detail":"Status CANCELLED cannot be set via PATCH; use POST /api/v1/orders/6afff12c83fe4cdea92b0733/cancel","instance":"/api/v1/orders/6afff12c83fe4cdea92b0733/status"}
```

**Cancel** (only while PENDING)

```bash
curl -X POST http://localhost:8080/api/v1/orders/6afff12c83fe4cdea92b0733/cancel
```

Returns `200` with `"status":"CANCELLED"`. Cancelling again → `409`:

```
{"type":"about:blank","title":"Invalid status transition","status":409,"detail":"Order 6afff12c83fe4cdea92b0733 is CANCELLED and cannot be cancelled; only PENDING orders can be cancelled","instance":"/api/v1/orders/6afff12c83fe4cdea92b0733/cancel","currentStatus":"CANCELLED","targetStatus":"CANCELLED"}
```

**Validation error** → `400` with every failing field:

```bash
curl -X POST http://localhost:8080/api/v1/orders -H 'Content-Type: application/json' \
  -d '{"customerId":"","items":[{"productId":"p1","productName":"Pen","quantity":0,"unitPrice":10.555}]}'
```
```
{"type":"about:blank","title":"Validation failed","status":400,"detail":"Request validation failed","instance":"/api/v1/orders","errors":[{"field":"items[0].quantity","message":"must be greater than or equal to 1"},{"field":"customerId","message":"must not be blank"},{"field":"items[0].unitPrice","message":"must have at most 2 decimal places"}]}
```

**Not found** → `404`; **rate limit exceeded** → `429` with a `Retry-After` header:

```
HTTP/1.1 429
Retry-After: 1
{"type":"about:blank","title":"Too many requests","status":429,"detail":"Rate limit exceeded; retry after 1 second(s)","instance":"/api/v1/orders/6afff13583fe4cdea92b0734"}
```

## Design highlights

- **State machine** — `OrderStatus` enum defines allowed transitions:
  PENDING → PROCESSING | CANCELLED, PROCESSING → SHIPPED, SHIPPED → DELIVERED.
  CANCELLED only via the cancel endpoint. DELIVERED and CANCELLED are final. The same table drives
  both validation and the database update filter, so they cannot disagree.
- **Race handling** — every status change is one conditional atomic MongoDB update filtered on the
  expected current status (`findAndModify({_id, status: PENDING})` for cancel, `updateMulti` with
  `status: PENDING` for the scheduler). There is never a read-check-write in Java, so cancel and the
  scheduler can never both win. Only when the update matches nothing does the service read the order
  to choose between 404 and 409. A test runs the cancel-vs-scheduler race 200 times; deliberately
  breaking the filter makes it fail, which proves the test catches the bug.
- **Scheduler** — cron-driven (every 5 minutes), protected by ShedLock in MongoDB so only one
  instance runs it at a time. It fetches PENDING order IDs in batches through an index-only query and
  promotes each batch with one `updateMulti`, so it is idempotent and safe to re-run after a crash.
  All settings are validated and configurable via `orders.scheduler.*`.
- **Cancel window trade-off** — by default each run promotes every PENDING order, so the cancel
  window is 0–5 minutes. Setting `min-pending-age=PT5M` guarantees at least 5 minutes (5–10 minutes
  before PROCESSING). Exact per-order timers were rejected: they need extra infrastructure that can
  lose timer events, and a sweep job would still be needed as a fallback
  ([DESIGN.md §8, §17](docs/DESIGN.md#8-scheduler)).
- **Idempotent create** — `Idempotency-Key` header plus a SHA-256 hash of the normalized request,
  enforced by a unique index, so concurrent retries create exactly one order.
- **Keyset pagination** — cursor = `createdAt` + `id`, backed by compound indexes; page cost does
  not grow with depth (no `skip`).
- **Money** — `BigDecimal` stored as MongoDB `Decimal128`; totals computed server-side (scale 2,
  HALF_EVEN); more than 2 decimal places is rejected, never rounded.
- **Redis is optional at runtime** — cache-aside for `GET /orders/{id}` (evicted on every status
  change) and Bucket4j rate limiting. If Redis is down, both fail open: reads come from MongoDB and
  requests are not rate limited, with one warning per 5 seconds instead of one per request.

## Assumptions

- **`min-pending-age`**: default `PT0S` is the literal reading of the requirement — every 5-minute
  run promotes all PENDING orders, so an order may be promoted shortly after creation. Set
  `ORDERS_SCHEDULER_MIN_PENDING_AGE=PT5M` to guarantee a 5-minute cancellation window.
- Product name and unit price are snapshotted from the request; there are no customer or product
  collections.
- `totalAmount` is always computed server-side (scale 2, `RoundingMode.HALF_EVEN`).
- Single currency; no currency field is stored.
- **Validation** (violations → 400; values are rejected, never rounded or clamped):
  1–50 items per order; quantity 1–100; `unitPrice` > 0 and ≤ 1,000,000 with at most 2 decimal
  places; duplicate `productId` within one order rejected; `customerId`/`productId` required,
  not blank, max 64 chars; `productName` required, not blank, max 200 chars.
- **Pagination**: default page size 20; `size` must be 1–100, otherwise 400. The cursor is opaque
  (URL-safe Base64, no padding, of `createdAt` epoch millis + id); a malformed cursor → 400.
- **Idempotency**: `Idempotency-Key` must be 1–128 characters. The server stores a SHA-256 hash of
  the canonicalized request (items sorted by `productId`, so item order does not matter) with the key.
  Same key + same body → 200 with the original order; same key + different body → 422.
- **Unknown JSON fields are rejected (400)** — e.g. a client-supplied `totalAmount` or `status` is
  an error rather than silently ignored. Non-integer quantities are rejected, never truncated.
- **PATCH status**: `PENDING` and `CANCELLED` are never valid targets → 400 (use
  `POST /orders/{id}/cancel` to cancel). 409 is returned when a valid target conflicts with the
  current state.
- **Rate limiting**: per client IP, capacity 100, greedy refill of 100 tokens per minute (all
  configurable). Per-endpoint limits are a future improvement.

## Scaling roadmap

See [docs/DESIGN.md — Scaling roadmap](docs/DESIGN.md#16-scaling-roadmap) and
[Rejected alternatives](docs/DESIGN.md#17-rejected-alternatives).

## Security roadmap

Authentication and authorization were out of scope for this assignment and are **not built yet**.
What the service already does (strict input validation, server-set totals and statuses, no
string-built queries, no stack traces in responses, spoof-resistant per-IP rate limiting, minimal
Actuator exposure) and the production plan are in
[docs/DESIGN.md — Security roadmap](docs/DESIGN.md#18-security-roadmap). In short:

- **Before production:** OAuth 2.0 / OIDC JWT authentication; role-based access (only operations
  staff can PATCH status); object-level checks so customers see and cancel only their own orders
  (`customerId` from the token, 404 for others); idempotency keys scoped per client; TLS everywhere
  including MongoDB and Redis; secrets from a secret store; Swagger and Actuator locked down.
- **Hardening:** trusted-proxy client IP, per-client and per-endpoint rate limits, request size
  limits, security headers and CORS, status-change audit trail.
- **Continuous:** 401/403/404 security tests in the build, dependency and image scanning, DAST,
  encryption at rest.

## AI usage

See [docs/AI_USAGE.md](docs/AI_USAGE.md).

## Testing

```bash
./mvnw clean verify       # unit + integration tests (Docker required for Testcontainers)
```

557 tests (162 unit, 395 integration), all passing. Integration tests start real MongoDB 7 and
Redis 7 containers with Testcontainers, so no local setup is needed beyond Docker.

| Area | What is tested |
|------|----------------|
| State machine | All 25 status pairs (4 valid, 21 invalid) |
| Domain and mapping | Totals and rounding, Decimal128 storage in real MongoDB, request validation limits, request-hash normalization, cursor encoding and malformed cursors |
| Service (Mockito) | Idempotency paths, 404 vs 409, PATCH rules, events published, cache use |
| API end-to-end | Every endpoint, status code and error body |
| Concurrency | Parallel creates with one idempotency key → one order; parallel status changes → exactly one winner |
| Cancel vs scheduler race | Run 200 times with random timing; exactly one side wins every time |
| Scheduler | Batching, min-pending-age, re-runs, skips non-PENDING orders, ShedLock allows one runner, on/off switch |
| Indexes | MongoDB query plans prove list, scheduler and idempotency queries use their indexes |
| Redis | Cache hit, eviction on status change, rate limit 429 + `Retry-After`, API keeps working with Redis down |

The scheduler is turned off in tests; they call `PendingOrderProcessor` directly so timing is
deterministic.
