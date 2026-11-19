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

Configuration overrides via environment variables: _TODO (table of key variables)_

## API summary

Base path: `/api/v1`

| Method | Path | Description |
|--------|------|-------------|
| POST   | `/orders` | Create an order (optional `Idempotency-Key` header) |
| GET    | `/orders/{id}` | Get an order by ID |
| GET    | `/orders?status=&size=&cursor=` | List orders, newest first, keyset pagination |
| PATCH  | `/orders/{id}/status` | Update status (PROCESSING → SHIPPED → DELIVERED) |
| POST   | `/orders/{id}/cancel` | Cancel an order (only while PENDING) |

### curl examples

_TODO_

## Design highlights

- **State machine** — `OrderStatus` enum defines allowed transitions:
  PENDING → PROCESSING | CANCELLED, PROCESSING → SHIPPED, SHIPPED → DELIVERED.
  CANCELLED only via the cancel endpoint. _TODO: expand_
- **Race handling** — every status change is a conditional atomic MongoDB update filtered on the
  expected current status; cancel and scheduler can never both win. _TODO: expand_
- **Scheduler** — cron-driven, ShedLock-protected, batched, idempotent and resumable; fully
  configurable via `orders.scheduler.*`. _TODO: expand_

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

## AI usage

See [docs/AI_USAGE.md](docs/AI_USAGE.md).

## Testing

```bash
./mvnw clean verify       # unit + integration tests (Docker required for Testcontainers)
```

_TODO: describe test coverage (state transitions, service tests, Testcontainers integration tests,
cancel-vs-scheduler race test with `@RepeatedTest(200)`)_
