# AI Usage Log

This document records how AI tools were used while building this project, what problems were
found in AI-generated output, and how they were fixed.

**How I worked with AI:** I made the design decisions and the AI implemented them one phase at a
time. For each phase it had to show a plan and wait for my approval, then implement, run the full
build (`./mvnw clean verify`) and fix failures before calling the phase done. It was not allowed to
change a design decision without proposing it in `docs/DESIGN.md` and asking me first. Every AI
claim was checked with a test, a real run of the app, or a deliberate code break (see "How AI output
was verified" below).

## Tools used

| Tool | Version / model | Used for |
|------|-----------------|----------|
| Claude Code (CLI) | Claude Opus 5.5 (`claude-opus-5-5`) | Documentation, implementation, tests, running builds and smoke tests, reviewing its own output against the design |
| Postman | n/a | My own manual testing of the running API (no AI) |

## How AI was used (by phase)

### Phase 0 — Documentation setup
- I wrote the specification (stack, 5 endpoints, state machine, concurrency rule, scheduler, cache,
  rate limit, patterns, scaling roadmap). The AI turned it into `CLAUDE.md`, `docs/DESIGN.md`,
  `docs/TASKS.md`, the README skeleton and `.gitignore`.
- I told it to list open questions instead of inventing answers. It raised six (page size, rate-limit
  values, PATCH to CANCELLED, idempotency key reuse, cursor format, validation limits). I answered
  them and it moved each answer into the relevant DESIGN.md section and the README Assumptions.

### Phase 1 — Project skeleton
- Generated the Maven project (Spring Boot 3.5, Java 21), `docker-compose.yml` (MongoDB 7, Redis 7),
  base packages, Actuator health and a Testcontainers context test.
- It found that the machine had only Java 8 and no Maven, and asked before installing JDK 21 and Maven
  with Homebrew. It added the Maven Wrapper (`./mvnw`) so reviewers don't need Maven installed.
- It checked library versions against Spring Boot 3.x (e.g. ShedLock 6.x, because 7.x targets
  Spring 7) instead of just picking the newest.

### Phase 2 — Domain model + state machine
- `Order`, `OrderItem`, the `OrderStatus` state machine (each status lists its allowed next statuses),
  BigDecimal money stored as Decimal128, server-side total with HALF_EVEN rounding.
- Tests for all 25 status pairs (4 valid, 21 invalid) and a MongoDB mapping test that checks money is
  really stored as Decimal128.

### Phase 3 — Create + get order, validation, idempotency, error handling
- POST and GET endpoints, Bean Validation, `Idempotency-Key` handling with a SHA-256 request hash,
  RFC 7807 error responses in one `@RestControllerAdvice`.
- It proposed three extra rules and I approved them: key length 1–128, reject unknown JSON fields
  (so a client cannot send `totalAmount` or `status`), and sort items by productId before hashing so
  item order does not change the hash.
- Concurrency test: many parallel requests with the same key create exactly one order.

### Phase 4 — List + keyset pagination + indexes
- `GET /orders` with keyset pagination (cursor = createdAt + id, Base64), named indexes created at
  startup, and tests that read MongoDB query plans to prove the indexes are used (no collection scan).
- It found a precision bug (see issues table) and I approved the fix.

### Phase 5 — Cancel + PATCH status (conditional atomic updates)
- Every status change is one `findAndModify` filtered on the expected current status. 404 vs 409 is
  decided only after the update fails.
- Concurrency tests: parallel cancels and transitions, exactly one winner.

### Phase 6 — Scheduler + ShedLock + race test
- Config-driven scheduler (validated `SchedulerProperties`), ShedLock with MongoDB so only one
  instance runs the job, batch `updateMulti` filtered on `status = PENDING`.
- Cancel-vs-scheduler race test repeated 200 times: exactly one side wins every time.
- Smoke test on the real app: the job promoted an order and a later cancel returned 409.

### Phase 7 — Redis cache + eviction + rate limiting
- Cache-aside for GET by id (60 s TTL), eviction through application events, Bucket4j per-IP rate
  limiting stored in Redis, 429 with `Retry-After`.
- Both features fail open: with Redis stopped the API keeps working from MongoDB. Tested with an
  integration test and on the real app (stopping and restarting Redis).

### Phase 8 — Swagger, error polish, README
- README completed: run instructions, environment-variable table, design highlights, testing
  summary, and curl examples whose responses were captured from a real run of the app (not
  invented). DESIGN.md class list updated to match the code.
- Not finished yet: OpenAPI annotations with endpoint descriptions, an error-format test for every
  error type, and a test that the API has exactly 5 endpoints. Swagger UI already works with the
  generated documentation.
- AI helped me prepare the trade-off explanation for the scheduler's cancel window (see DESIGN.md §8)
  and the security roadmap (DESIGN.md §18).

## Issues found in AI output

| Phase | What AI produced | Problem | How I fixed it |
|-------|------------------|---------|----------------|
| 2 | Test expected total `322.99`; plan said 5 valid / 20 invalid status pairs | Arithmetic mistakes: real total is `321.99`, and there are 4 valid / 21 invalid pairs | Recomputed by hand, corrected the test and the plan |
| 3 | Assumed `@Digits(fraction = 2)` ignores trailing zeros (so `10.500` would pass) | It rejects `10.500`. The design note it had written was wrong and some test payloads used 3 decimals | Kept the strict rule (matches my "reject, never round" decision), corrected DESIGN.md and the test payloads |
| 3 | Left an unused helper method in `RequestHasher` | Dead code | Removed |
| 4 | Stored `createdAt` with microsecond precision from the Java clock | MongoDB keeps milliseconds, so the in-memory value and the stored value differed and the pagination cursor could skip or repeat rows | Truncate timestamps to milliseconds before saving; DESIGN.md §5 updated after my approval |
| 6 | First cancel-vs-scheduler race test | Cancel won 199 of 200 runs, so the test hardly exercised the race | Added random 0–2 ms jitter; now both sides win regularly (about 49 / 151) |
| 7 | `OrderCache` with two constructors (one for tests) | Spring could not choose a constructor and the app failed to start | Marked the production constructor `@Autowired` |
| 7 | Rate limiter logged Redis connection failures only at DEBUG | An outage would be invisible in production logs | Raised to WARN |
| 7 | After that fix, it logged a WARN on every request while Redis was down | Log flooding | Added a 5-second backoff: one warning per backoff window |
| 1 → 7 | Smoke tests assumed `localhost:6379` was the docker-compose Redis | A Homebrew Redis on the same port answered instead, so "Redis UP" (from Phase 1 on) was misleading and test keys went into my own Redis. Found in Phase 7 when stopping the compose Redis had no effect | Ran smoke tests against a throwaway Redis container on port 6380; README will note the port conflict |

## How AI output was verified

- **Full build every phase:** final count 557 tests (162 unit, 395 integration with Testcontainers),
  all passing.
- **Mutation checks on the concurrency guarantees:** I had the AI deliberately break the code and
  confirm the tests catch it, then restore it.
  - Replacing the atomic status update with read-check-write: `StatusConcurrencyIT` failed in all
    70 runs.
  - Removing the `status = PENDING` filter from the scheduler update: the race test failed 42 of 200
    runs with both cancel and scheduler "winning".
- **Query plans:** index tests assert that MongoDB uses the intended index, not a collection scan.
- **Real app runs:** scheduler, cache, rate limit and Redis-down behaviour were each checked on the
  running application, and I tested the API myself in Postman.

## Prompts that worked well

1. **Per-phase prompt:** "Read CLAUDE.md, docs/DESIGN.md and docs/TASKS.md. We are working on
   Phase N only. First show me your plan: files, dependencies, and how you will test it. Wait for my
   approval. After implementing, run `mvn clean verify`, fix failures, update the checkboxes, then
   summarize what you did and anything you were unsure about." — Kept each step small and
   reviewable, and the "anything you were unsure about" part surfaced real issues.
2. **"If something is unclear, ask me instead of inventing."** — Produced a list of open questions
   instead of hidden assumptions; my answers became documented decisions.
3. **"Never change a design decision silently; propose the DESIGN.md change first."** — Every
   deviation (millisecond timestamps, stricter validation) came to me as a proposal.
4. **Asking for proof, not claims** — e.g. asking it to break the atomic update on purpose to show
   the race tests would catch it.

## Lessons learned

- AI is fast at boilerplate and tests but makes confident arithmetic and library-behaviour mistakes;
  tests caught every one of them, so writing tests first-class mattered.
- A test that passes is not proof it tests anything: the race test passed while barely racing, and
  only checking the win counts showed it.
- Environment details (Java version, a local Redis on the same port) cause misleading results; verify
  what you are actually connected to.
- Keeping decisions in `docs/DESIGN.md` and making the AI ask before changing them kept the design
  consistent across eight phases.
