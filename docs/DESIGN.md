# Design — E-commerce Order Processing System

> Status: approved baseline. Any change to a decision in this document must be proposed here
> first and agreed before code changes (see CLAUDE.md).

## Contents

1. [Scope](#1-scope)
2. [High-level design](#2-high-level-design)
3. [Domain model](#3-domain-model)
4. [Order state machine](#4-order-state-machine)
5. [Persistence and indexes](#5-persistence-and-indexes)
6. [API](#6-api)
7. [Concurrency](#7-concurrency)
8. [Scheduler](#8-scheduler)
9. [Caching](#9-caching)
10. [Rate limiting](#10-rate-limiting)
11. [Connections and configuration](#11-connections-and-configuration)
12. [Error handling](#12-error-handling)
13. [Design patterns](#13-design-patterns)
14. [Low-level design (packages and classes)](#14-low-level-design-packages-and-classes)
15. [Testing strategy](#15-testing-strategy)
16. [Scaling roadmap](#16-scaling-roadmap)
17. [Rejected alternatives](#17-rejected-alternatives)

---

## 1. Scope

### In scope (assignment requirements)

- Create an order with multiple items.
- Retrieve an order by ID.
- Update order status: PENDING, PROCESSING, SHIPPED, DELIVERED.
- Background job that automatically moves PENDING orders to PROCESSING every 5 minutes.
- List all orders, optionally filtered by status.
- Cancel an order, only while it is still PENDING.
- Document AI usage, issues found, and fixes (docs/AI_USAGE.md).

### Out of scope

- Any UI.
- Customers and products collections (customer and product data are not validated against a catalog;
  product name and price are snapshotted on the order).
- Authentication / authorization.
- Payments, inventory, shipping integrations.
- Multiple currencies — a single currency is assumed; no currency field is stored.

---

## 2. High-level design

```
                 ┌──────────────────────────────────────────────────────────┐
  HTTP client ──►│ Rate-limit filter (Bucket4j, per client IP) ──► 429      │
                 │        │                                                 │
                 │        ▼                                                 │
                 │ OrderController (/api/v1/orders)                         │
                 │        │  DTOs (records) + Bean Validation               │
                 │        ▼                                                 │
                 │ OrderService ──publishes──► OrderStatusChangedEvent ─┐   │
                 │        │                    OrdersProcessedEvent     │   │
                 │        ▼                                             ▼   │
                 │ OrderRepository (MongoRepository)        CacheEviction   │
                 │ + custom fragment (MongoTemplate:          listener      │
                 │   findAndModify / updateMulti / keyset)      │           │
                 │        │                                     │           │
                 │ OrderStatusScheduler (@Scheduled + ShedLock) │           │
                 │        └─► PendingOrderProcessor ────────────┘           │
                 └────────┼──────────────────────────────┼──────────────────┘
                          ▼                              ▼
                   MongoDB 7 (source of truth)     Redis 7 (cache + rate-limit buckets)
                   collections: orders, shedLock
```

Key principles:

- **MongoDB is the only source of truth.** Redis holds only derived/ephemeral data (cache entries,
  rate-limit buckets). Losing Redis never loses an order.
- **Every status change is a conditional atomic update** in MongoDB (see §7).
- **Stateless app instances** — horizontal scaling is safe; ShedLock ensures the scheduler runs on
  one instance at a time.

---

## 3. Domain model

Single collection `orders`; items are embedded (they are always read and written with the order).

### Order

| Field            | Type                | Notes |
|------------------|---------------------|-------|
| `id`             | String (ObjectId)   | `_id` |
| `customerId`     | String              | No customers collection; stored as given. |
| `items`          | `List<OrderItem>`   | Embedded, at least one item. |
| `totalAmount`    | `BigDecimal`        | Stored as `Decimal128`. Computed server-side as Σ(quantity × unitPrice), scale 2, `RoundingMode.HALF_EVEN`. Never accepted from the client. |
| `status`         | `OrderStatus`       | Stored as string. |
| `idempotencyKey` | String (nullable)   | From `Idempotency-Key` header. Unique, sparse index. |
| `requestHash`    | String (nullable)   | SHA-256 (hex) of the canonicalized create-request body. Set only when `idempotencyKey` is present; used to detect key reuse with a different payload (§6). |
| `createdAt`      | `Instant`           | Set on create. |
| `updatedAt`      | `Instant`           | Set on every update. |
| `version`        | Long (`@Version`)   | Incremented on every status change (including atomic updates). |

### OrderItem (embedded)

| Field         | Type         | Notes |
|---------------|--------------|-------|
| `productId`   | String       | |
| `productName` | String       | Snapshot at order time. |
| `quantity`    | int          | Positive. |
| `unitPrice`   | `BigDecimal` | Snapshot at order time; stored as `Decimal128`. |

`BigDecimal` fields are mapped to `Decimal128` (e.g. `@Field(targetType = FieldType.DECIMAL128)`), never
to string or double.

### Validation rules (create request)

Violations return **400**. Values are **rejected, never rounded or clamped**.

| Field / rule      | Constraint |
|-------------------|------------|
| `customerId`      | Required, not blank, max 64 chars. |
| `items`           | Required, 1–50 items. |
| `items[].productId`   | Required, not blank, max 64 chars. |
| `items[].productName` | Required, not blank, max 200 chars. |
| `items[].quantity`    | Integer, 1–100. |
| `items[].unitPrice`   | `> 0` and `<= 1,000,000`, at most 2 decimal places (e.g. `@DecimalMin(value = "0", inclusive = false)`, `@DecimalMax("1000000")`, `@Digits(integer = 7, fraction = 2)`). |
| Duplicate `productId` within one order | Rejected with 400 (callers must merge quantities). |
| Currency          | Single currency assumed; no currency field. |

`totalAmount` = Σ(quantity × unitPrice), then `setScale(2, RoundingMode.HALF_EVEN)`. With prices limited
to 2 decimal places the scale step never changes the value in practice; it fixes the stored scale.

---

## 4. Order state machine

Implemented with the **State pattern** on the `OrderStatus` enum: each constant declares its allowed
next states.

```
            ┌──────────► CANCELLED (terminal)   [only via POST /cancel]
            │
 PENDING ───┴──► PROCESSING ──► SHIPPED ──► DELIVERED (terminal)
          (scheduler or PATCH)
```

| From        | Allowed next              |
|-------------|---------------------------|
| PENDING     | PROCESSING, CANCELLED     |
| PROCESSING  | SHIPPED                   |
| SHIPPED     | DELIVERED                 |
| DELIVERED   | — (terminal)              |
| CANCELLED   | — (terminal)              |

Sketch:

```java
public enum OrderStatus {
    PENDING    { Set<OrderStatus> next() { return EnumSet.of(PROCESSING, CANCELLED); } },
    PROCESSING { Set<OrderStatus> next() { return EnumSet.of(SHIPPED); } },
    SHIPPED    { Set<OrderStatus> next() { return EnumSet.of(DELIVERED); } },
    DELIVERED  { Set<OrderStatus> next() { return EnumSet.noneOf(OrderStatus.class); } },
    CANCELLED  { Set<OrderStatus> next() { return EnumSet.noneOf(OrderStatus.class); } };

    abstract Set<OrderStatus> next();
    public boolean canTransitionTo(OrderStatus target) { return next().contains(target); }
    /** The single status an order must currently be in to move to {@code target}. */
    public static OrderStatus requiredPredecessorOf(OrderStatus target) { ... }
}
```

Rules:

- CANCELLED is reachable **only** through `POST /orders/{id}/cancel`, not through `PATCH /status`.
- Because each non-initial status has exactly one predecessor, the PATCH endpoint can derive the
  **expected current status** from the target status and use it as the update filter (§7).

---

## 5. Persistence and indexes

Collection `orders`:

| Name                    | Index                                   | Purpose |
|-------------------------|-----------------------------------------|---------|
| `_id_`                  | `_id` (default)                         | Get by id. |
| `status_createdAt_id`   | `{ status: 1, createdAt: 1, _id: 1 }`   | Scheduler scan (PENDING, oldest first) and list-by-status keyset pagination (traversed in reverse for newest first). |
| `createdAt_id`          | `{ createdAt: 1, _id: 1 }`              | Unfiltered list keyset pagination. |
| `customerId_createdAt`  | `{ customerId: 1, createdAt: -1 }`      | Future customer order history (no endpoint yet). |
| `idempotencyKey_unique` | `{ idempotencyKey: 1 }` unique, sparse  | Duplicate-order prevention. |

Collection `shedLock`: managed by ShedLock's Mongo lock provider.

Indexes are created explicitly at startup (`MongoIndexConfig`, via `IndexOperations`) so a fresh
environment is consistent.

`createdAt` is truncated to milliseconds when set (MongoDB dates have millisecond precision), so the
value returned on create equals the stored value and the pagination cursor.

---

## 6. API

Base path `/api/v1`. Exactly these five endpoints.

| Method | Path                         | Success | Errors |
|--------|------------------------------|---------|--------|
| POST   | `/orders`                    | 201 Created (200 on idempotent replay) | 400, 422, 429 |
| GET    | `/orders/{id}`               | 200     | 404, 429 |
| GET    | `/orders?status=&size=&cursor=` | 200  | 400, 429 |
| PATCH  | `/orders/{id}/status`        | 200     | 400, 404, 409, 429 |
| POST   | `/orders/{id}/cancel`        | 200     | 404, 409, 429 |

### POST /orders

- Optional header `Idempotency-Key`: 1–128 characters, not blank; otherwise → **400**.
- Request: `customerId`, `items[]` (`productId`, `productName`, `quantity`, `unitPrice`). No
  `totalAmount`, `status`, or timestamps accepted from the client.
- **Unknown JSON fields are rejected with 400** (Jackson `FAIL_ON_UNKNOWN_PROPERTIES`, applied to all
  endpoints), so a client sending e.g. `totalAmount` learns it is not used. Non-integer `quantity`
  (e.g. `1.5`) is rejected, never truncated (`accept-float-as-int: false`).
- Validated per §3 *Validation rules*; violations → **400**.
- `unitPrice` may have at most 2 decimal places *as written* (`10.500` → 400, like any 3-decimal
  value); accepted prices are stored at scale 2 (`10.5` → `10.50`, `7` → `7.00`), never rounded.
- Server computes `totalAmount`, sets `status = PENDING`, `createdAt`, `updatedAt`.
- Idempotency (only when `Idempotency-Key` is supplied):
  - Compute `requestHash` = SHA-256 (hex) of the canonicalized, validated request: sorted JSON keys,
    no insignificant whitespace, prices with trailing zeros stripped (`10.5` = `10.50`), and **items
    sorted by `productId`** (unique per order, so item order does not change the hash).
    Semantically identical requests therefore hash identically.
  1. If an order with that key exists:
     - same `requestHash` → return the original order with **200**;
     - different `requestHash` → **422 Unprocessable Content** (key reused with a different payload).
  2. Otherwise insert (storing `idempotencyKey` + `requestHash`). If insert throws
     `DuplicateKeyException` on the `idempotencyKey` unique index (a concurrent request with the same
     key won the race) → load the existing order by key and apply the same hash comparison
     (200 or 422).
  3. On a fresh insert → **201 Created** with `Location: /api/v1/orders/{id}`.

### GET /orders/{id}

- 200 with the order, 404 if missing. Served via cache-aside (§9).

### GET /orders?status=&size=&cursor=

- `status` optional, exact upper-case enum name (`?status=pending` → **400**); `cursor` optional, opaque.
- `size` optional: default **20**, valid range **1–100**. `size < 1` or `size > 100` → **400**
  (no silent clamping).
- Ordering: newest first — `createdAt DESC, _id DESC`.
- **Keyset pagination**: cursor encodes the last returned `(createdAt, id)`. Next page condition is
  `createdAt < t OR (createdAt == t AND _id < id)`, written as
  `{ createdAt: { $lte: t }, $or: [ { createdAt: { $lt: t } }, { _id: { $lt: ObjectId(id) } } ] }`
  (plus `status` if given) so the top-level `$lte` gives a tight index range. **Never** `skip`/offset.
- The service fetches `size + 1` rows: if more than `size` come back, the page is trimmed and
  `nextCursor` is built from the last returned item; otherwise `nextCursor` is null. A result that is
  an exact multiple of `size` therefore never produces an empty extra page.
- **Cursor format**: opaque, URL-safe Base64 **without padding** of the UTF-8 text
  `"<createdAt epoch millis>:<24-hex ObjectId>"`. Clients must treat it as opaque. Malformed cursor
  (bad Base64, wrong structure, non-numeric or negative millis, invalid ObjectId) → **400**.
- Response: `{ "items": [...], "nextCursor": "..." | null }`. `nextCursor` is null on the last page.
- Unknown `status` value → 400.

### PATCH /orders/{id}/status

- Body `{ "status": "PROCESSING" | "SHIPPED" | "DELIVERED" }`.
- Target `PENDING` or `CANCELLED` → **400** (never valid on this endpoint, regardless of current
  state). The 400 detail for `CANCELLED` points the caller to `POST /api/v1/orders/{id}/cancel`.
- Conditional atomic update filtered on the required predecessor status (§7).
- 200 with updated order; 404 if missing; **409** when a valid target conflicts with the order's
  current state (e.g. SHIPPED requested while the order is PENDING).

### POST /orders/{id}/cancel

- `findAndModify({_id, status: PENDING})` → CANCELLED.
- 200 with updated order; 404 if missing; 409 if not PENDING.

---

## 7. Concurrency

**Rule: every status change is a single conditional atomic update in MongoDB that filters on the
expected current status and increments `version`. Never read-check-write in Java.**

| Operation  | MongoDB operation | Filter | Update |
|------------|-------------------|--------|--------|
| Cancel     | `findAndModify` (return new) | `{_id: id, status: PENDING}` | `status=CANCELLED, updatedAt=now, $inc version` |
| PATCH      | `findAndModify` (return new) | `{_id: id, status: requiredPredecessorOf(target)}` | `status=target, updatedAt=now, $inc version` |
| Scheduler  | `updateMulti` | `{_id: {$in: ids}, status: PENDING}` | `status=PROCESSING, updatedAt=now, $inc version` |

Null result from `findAndModify` → disambiguate with an existence check:
- order does not exist → **404**
- order exists (in some other status) → **409**

The existence check (`findById`) runs only *after* the atomic update failed, to choose the error code
and to put the order's current status in the 409 detail (informational only; it may already be stale).
It never gates a write.

On success the service publishes `OrderStatusChangedEvent(orderId, from, to, changedAt)` synchronously
via `ApplicationEventPublisher` (consumed by cache eviction in Phase 7).

### Cancel vs scheduler race

Both operations filter on `status: PENDING`. MongoDB applies single-document writes atomically, so
whichever write lands first changes the status; the other's filter no longer matches and it
modifies nothing. **Exactly one wins; both winning is impossible.** The cancel caller receives 409 if
the scheduler won; the scheduler simply counts one fewer modified document.

Verified by a `@RepeatedTest(200)` race test (§15).

---

## 8. Scheduler

### Components

- `OrderStatusScheduler` — thin: `@Scheduled(cron = "${orders.scheduler.cron}")` +
  `@SchedulerLock(name = "pendingOrdersToProcessing", lockAtMostFor = "${orders.scheduler.lock-at-most-for}")`,
  delegates to the processor. The bean always exists; `orders.scheduler.enabled` controls only
  `@EnableScheduling` (whether the cron trigger fires). Tests can therefore call the real,
  ShedLock-proxied method to verify locking while no timer ever fires.
- No `lockAtLeastFor`: the job is idempotent, so a duplicate run caused by clock skew between nodes
  changes nothing.
- `PendingOrderProcessor` — contains the logic; separately unit/integration testable.
- ShedLock with the Mongo lock provider (`shedLock` collection) ensures only one instance runs a
  given execution.

### Algorithm

```
cutoff = now - minPendingAge
loop:
    ids = find _id of orders where status = PENDING and createdAt <= cutoff
          sort createdAt asc, limit batchSize          // projection: _id only
    if ids is empty: break
    result = updateMulti({_id: {$in: ids}, status: PENDING},
                         {status: PROCESSING, updatedAt: now, $inc: {version: 1}})
    publish OrdersProcessedEvent(ids, now)             // cache eviction (all fetched ids; an id
                                                       // cancelled meanwhile is a harmless extra eviction)
    log batch size / modified count
```

Properties:

- **Idempotent**: the update filter requires `status: PENDING`; re-running cannot double-transition.
- **Resumable**: no in-memory progress state; after a crash the next run simply picks up remaining
  PENDING orders.
- **Safe against cancel**: an order cancelled between the ID fetch and the update is excluded by the
  `status: PENDING` filter.
- Uses the `{ status, createdAt, _id }` index; the ID fetch projects only `_id`, so it is a covered
  query (answered from the index without reading documents).
- Loop terminates because every processed order leaves the PENDING set and `cutoff` is fixed for the run.

### Configuration — `SchedulerProperties`

Validated `@ConfigurationProperties(prefix = "orders.scheduler")` record (`@Validated`). Every value is
overridable via environment variables (e.g. `ORDERS_SCHEDULER_CRON`).

| Property             | Default            | Meaning |
|----------------------|--------------------|---------|
| `enabled`            | `true`             | Disabled in tests. |
| `cron`               | `0 */5 * * * *`    | Every 5 minutes. Must be a valid Spring cron expression. |
| `min-pending-age`    | `PT0S`             | See below. Must be ≥ 0. |
| `batch-size`         | `1000`             | IDs per batch. Must be 1–10,000. |
| `lock-at-most-for`   | `PT4M`             | ShedLock safety upper bound (below the 5-min interval). Must be > 0. |

Invalid values fail application startup.

**`min-pending-age` interpretation.** The assignment says the job moves PENDING orders to PROCESSING
every 5 minutes. `PT0S` (default) is the literal reading: every run promotes *all* PENDING orders,
so an order may be promoted seconds after creation. Setting `PT5M` guarantees every order stays
PENDING (and cancellable) for at least 5 minutes.

### Future option (not built)

Dynamic trigger via `SchedulingConfigurer` so the schedule can change at runtime without restart.

---

## 9. Caching

- **Cache-aside** on `GET /orders/{id}` using Spring Data Redis (`StringRedisTemplate`), implemented
  explicitly in `OrderCache` (not `@Cacheable`): the cached value is the `OrderResponse` JSON under key
  `orders:v1:{id}`, TTL **60 s** (`orders.cache.order-ttl`, configurable). Caching the API view avoids
  making the immutable domain `Order` Jackson-deserializable and avoids polymorphic type info in Redis.
- Eviction via the **Observer** pattern (`ApplicationEventPublisher` + synchronous `@EventListener`):
  - `OrderStatusChangedEvent(orderId, from, to, changedAt)` — published after cancel / PATCH succeeds → evict that id.
  - `OrdersProcessedEvent(ids, changedAt)` — published per scheduler batch → evict those ids.
- Redis failures never break requests: every cache get/put/evict catches Redis errors, logs a warning
  and continues (a failed get falls back to MongoDB). Redis connect and command timeouts are **500 ms**
  (`REDIS_CONNECT_TIMEOUT`, `REDIS_TIMEOUT`) so an unavailable Redis adds at most ~0.5 s, not Lettuce's 60 s default.
- **Known, accepted staleness window** (bounded by the TTL): a reader misses the cache and reads PENDING
  from MongoDB → a cancel commits and evicts → the reader then stores its stale PENDING copy. The same
  applies if an eviction fails while Redis is unreachable. Closing this fully would need versioned
  tombstones in Redis; not worth the complexity for a 60 s window.
- Creating an order does not populate the cache. The list endpoint is not cached.
- Note: with Redis down, `/actuator/health` reports DOWN overall (the redis component) even though the
  API keeps serving from MongoDB.

---

## 10. Rate limiting

- Bucket4j with Redis-backed buckets (shared across instances), **per client IP**, one bucket across
  all endpoints, key `rate-limit:v1:{ip}`; keys expire once the bucket would be full again.
- Client IP = `request.getRemoteAddr()`. Raw `X-Forwarded-For` is never trusted (spoofable); behind a
  load balancer, operators set `server.forward-headers-strategy` so the servlet container resolves the
  real client address.
- **Fail open**: if Redis is unavailable the request is allowed and a warning is logged (MongoDB is the
  source of truth; a cache/limiter outage must not take the API down). The limiter connects lazily, so
  the application also starts without Redis.
- Applies to `/api/v1/*` only (not actuator or Swagger). `orders.rate-limit.enabled` can switch it off.
- Defaults (all three configurable / overridable via env vars):

  | Property | Default | Meaning |
  |----------|---------|---------|
  | `orders.rate-limit.capacity` | `100` | Max burst size. |
  | `orders.rate-limit.refill-tokens` | `100` | Tokens added per refill period. |
  | `orders.rate-limit.refill-period` | `PT1M` | Greedy refill (tokens trickle in continuously across the period). |

- On exhaustion: **429** ProblemDetail with `Retry-After` header (seconds).
- Implemented as a servlet filter / interceptor in front of `/api/v1/**`.
- Future improvement: per-endpoint limits (e.g. stricter on `POST /orders`).

---

## 11. Connections and configuration

### MongoDB pool (via connection string)

```
mongodb://.../orders?maxPoolSize=50&minPoolSize=5&waitQueueTimeoutMS=2000&maxIdleTimeMS=60000
```

- `maxPoolSize=50` — per application instance.
- `waitQueueTimeoutMS=2000` — fail fast instead of piling up requests when the pool is exhausted.
- **Capacity rule**: `instances × maxPoolSize` (plus other clients) must stay below MongoDB's
  connection limit. E.g. 10 instances × 50 = 500 connections.
- **Read preference** configurable; `primary` by default (read-your-writes).

### Configuration principles

- All tunables (scheduler, cache TTL, rate limits, Mongo URI, Redis host) come from `application.yml`
  and can be overridden by environment variables. Nothing hard-coded.

---

## 12. Error handling

Centralized `@RestControllerAdvice` returning RFC 7807 `ProblemDetail`.

| Situation                                  | Status |
|--------------------------------------------|--------|
| Bean Validation failure, duplicate `productId`, malformed cursor, `size` outside 1–100, malformed body, unknown JSON field, invalid `Idempotency-Key`, unknown status value | 400 |
| PATCH status target `PENDING` or `CANCELLED` (detail for CANCELLED points to the cancel endpoint) | 400 |
| Order not found                            | 404 |
| Valid PATCH target conflicting with current state / cancel when not PENDING | 409 |
| `Idempotency-Key` reused with a different request payload | 422 |
| Rate limit exceeded                        | 429 (+ `Retry-After`) |

Domain exceptions (e.g. `OrderNotFoundException`, `InvalidStatusTransitionException`) are mapped in
one place; controllers do not build error responses.

---

## 13. Design patterns

Only patterns that solve a concrete problem here:

| Pattern | Where | Why |
|---------|-------|-----|
| State | `OrderStatus` enum | Transition rules live with each state. |
| Repository | `OrderRepository` + MongoTemplate fragment | Isolates persistence. |
| Layered architecture + DTOs | controller → service → repository; records for API | Separation of concerns; API decoupled from storage. |
| Builder | Lombok `@Builder` on domain classes | Readable construction. |
| Observer | Spring `ApplicationEventPublisher` + listeners | Cache eviction decoupled from business logic. |
| Centralized exception handling | `@RestControllerAdvice` | Consistent ProblemDetail responses. |

No additional patterns are to be introduced for show.

---

## 14. Low-level design (packages and classes)

Base package `com.peerislands.orders`. Names are indicative; final names agreed per phase.

| Package      | Contents |
|--------------|----------|
| `controller` | `OrderController` |
| `service`    | `OrderService` |
| `domain`     | `Order`, `OrderItem`, `OrderStatus` |
| `repository` | `OrderRepository` (MongoRepository), `OrderRepositoryCustom` + impl (MongoTemplate: conditional updates, keyset queries, ID fetch for scheduler) |
| `scheduler`  | `OrderStatusScheduler`, `PendingOrderProcessor`, `SchedulerProperties` |
| `event`      | `OrderStatusChangedEvent`, `OrdersProcessedEvent`, cache-eviction listener |
| `config`     | Mongo (Decimal128 mapping, indexes, read preference), Redis cache, ShedLock, Bucket4j/rate-limit filter, OpenAPI |
| `exception`  | Domain exceptions, `GlobalExceptionHandler` (`@RestControllerAdvice`) |
| `mapper`     | Order ↔ DTO mapping; cursor encode/decode |

DTOs (records): `CreateOrderRequest`, `OrderItemRequest`, `UpdateStatusRequest`, `OrderResponse`,
`OrderItemResponse`, `OrderPageResponse { items, nextCursor }`.

---

## 15. Testing strategy

| Level | What |
|-------|------|
| Unit | Every valid and every invalid `OrderStatus` transition; total calculation (scale 2, HALF_EVEN); cursor encode/decode incl. malformed input; request-hash canonicalization; validation boundaries. |
| Service (Mockito) | `OrderService` behaviour: idempotency paths (same hash → 200, different hash → 422), 404 vs 409 disambiguation, PATCH to PENDING/CANCELLED → 400, event publication. |
| Integration (Testcontainers: MongoDB + Redis) | Endpoints end-to-end, indexes, Decimal128 round-trip, keyset pagination, idempotency race on the unique index, cache eviction, rate limiting. |
| Race | Cancel and `PendingOrderProcessor` fire simultaneously on one PENDING order using a `CountDownLatch`; `@RepeatedTest(200)`; assert exactly one succeeds and the final status matches the winner. |

The scheduler is **disabled in tests** (`orders.scheduler.enabled=false`); tests call
`PendingOrderProcessor` directly.

---

## 16. Scaling roadmap

Documented only — **not built**.

1. **Replica set read scaling** — route list/get reads to secondaries via read preference.
   Trade-off: replication lag means a client may not see its own recent write; keep `primary` where
   read-your-writes matters.
2. **Sharding on hashed `customerId`** — even write distribution; customer history queries become
   targeted. The scheduler's `status = PENDING` query becomes scatter-gather across shards, which is
   acceptable for a background job.
3. **Kafka + transactional outbox** — write events to an outbox collection in the same MongoDB write
   as the order change, relay to Kafka. Gives reliable event delivery to other services and absorbs
   write bursts.
4. **Archiving old DELIVERED orders** — move to cold storage / archive collection to keep the hot
   working set and indexes small.
5. **Redis sorted-set delay queue for exact per-order timers** — score = due time; workers pop due
   order IDs and apply the same conditional update. Keep the current sweeper as a fallback so
   MongoDB remains authoritative if Redis loses entries.

---

## 17. Rejected alternatives

| Alternative | Reason rejected |
|-------------|-----------------|
| Per-order Redis TTL timers (keyspace expiry notifications) | Expiry events are fire-and-forget and can be lost; Redis cannot be atomic with MongoDB. |
| Holding PENDING orders in Redis first, persisting to MongoDB on promotion | Risk of losing acknowledged orders; risky handoff between stores; reads split across two stores; cancelled orders would never be persisted. |
| Read-check-write in Java (load, check status, save) | Lost updates / both-win races between cancel and scheduler. |
| `skip`/offset pagination | Degrades linearly with depth and is unstable under concurrent inserts. |
| JPA/Hibernate | Not applicable to MongoDB; Spring Data MongoDB is the native fit. |
