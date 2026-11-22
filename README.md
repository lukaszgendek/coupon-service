# coupon-service

REST service for discount coupons: create a coupon, register its use by a user.
Java 21, Spring Boot 4, PostgreSQL, Flyway, Gradle.

## Run

```
./gradlew bootJar
docker compose up -d --build   # PostgreSQL (localhost:5432) + the application (localhost:8080)
```

Or only the database, with the application run from Gradle:

```
docker compose up -d postgres
./gradlew bootRun
```

The compose file is also compatible with Podman (`podman-compose up -d --build`).

Settings can be overridden with environment variables: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `DB_POOL_SIZE`,
`GEO_BASE_URL`.

## Test

```
./gradlew test
```

Unit tests mock the database. Integration tests run on a real PostgreSQL started by Testcontainers, so a container
engine (Docker or Podman with a Docker-compatible socket) is required.

## API

| Method and path | Body | Result |
|---|---|---|
| `POST /api/coupons` | `{"code":"WIOSNA","country":"PL","maxUses":100}` | `201` + `Location` |
| `GET /api/coupons/{code}` | | `200` with `usedCount` |
| `POST /api/coupons/{code}/usages` | `{"userId":"u1"}` | `204` |

Errors are RFC 9457 problem details with a machine-readable `error` property:

| Status | `error` | Meaning |
|---|---|---|
| 400 | `INVALID_INPUT` | malformed code, country or body |
| 403 | `COUNTRY_NOT_ALLOWED` | caller's country differs from the coupon's |
| 404 | `COUPON_NOT_FOUND` | unknown code |
| 409 | `COUPON_EXHAUSTED` | maximum number of uses reached |
| 409 | `COUPON_ALREADY_USED` | this user already used the coupon |
| 409 | `COUPON_CODE_TAKEN` | code exists already (case-insensitive) |
| 503 | `COUNTRY_UNKNOWN` | the country of the caller could not be determined |
| 503 | `SERVICE_OVERLOADED` | no database connection within 2 s (`Retry-After: 1`) |

Try it locally (the client address is taken from `X-Forwarded-For` when the request comes from a private-range proxy,
which includes localhost):

```
curl -X POST localhost:8080/api/coupons -H 'Content-Type: application/json' -d '{"code":"wiosna","country":"US","maxUses":1}'
curl -X POST localhost:8080/api/coupons/WIOSNA/usages -H 'Content-Type: application/json' -H 'X-Forwarded-For: 8.8.8.8' -d '{"userId":"u1"}'
```

## Design decisions

**Concurrency, first come first served.** Using a coupon is two statements in one transaction:

```sql
INSERT INTO coupon_usage (coupon_id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING;   -- 0 rows: already used
UPDATE coupon SET used_count = used_count + 1 WHERE id = ? AND used_count < max_uses; -- 0 rows: exhausted
```

The `UPDATE` takes a row lock, so concurrent requests are serialised in the database and the limit cannot be exceeded, no
matter how many application instances run. There are no application-level locks, so the service scales horizontally. A
`CHECK (used_count <= max_uses)` is the last line of defence. If the last slot is lost in a race, the transaction rolls
back and the usage row disappears.

**One use per user.** The primary key of `coupon_usage` is `(coupon_id, user_id)`: no surrogate id, no sequence, one
index. `ON CONFLICT DO NOTHING` turns a duplicate into a normal "0 rows" result instead of a database error.

**Fail fast, write nothing.** Before any write the service checks, in order: the coupon exists, it is not exhausted, the
caller's country matches. Rejected requests (the common case under load) cost one `SELECT` and hold no transaction. An
exhausted coupon is therefore reported before a country mismatch, which also saves a geolocation call.

**Case-insensitive codes** are normalised to upper case in the application (`CouponCode`) before any use, and stored
that way under a unique constraint.

**Geolocation** is behind a port (`GeoLocationPort`). The adapter calls ipwho.is with 2 s timeouts and caches successful
answers for 10 minutes. Failures are not cached. If the country cannot be determined the request is rejected (fail
closed). The cache is not a loading cache, so no lock is held while waiting for the network.

**Virtual threads** are enabled so that requests waiting for the geolocation service or the database do not occupy
platform threads. The connection pool (20, 2 s timeout) is then the bounded resource: under overload the service
answers 503 quickly instead of queueing without limit.

**Client address.** `server.forward-headers-strategy=native` makes Tomcat trust `X-Forwarded-For` only from private
address ranges (the proxy in front of the service). Behind a different proxy setup, configure the trusted proxies, or
callers could spoof their country.

## Possible next steps

- Rate limiting per IP in front of the service.
- Coupon expiry date and soft delete.
- Observability: metrics for the outcome of each usage attempt and for geolocation latency.
