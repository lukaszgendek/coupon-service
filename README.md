# coupon-service

REST service for discount coupons: create a coupon, register its use by a user.
Java 21, Spring Boot 4, PostgreSQL, Flyway, Gradle.

## Run

```
docker compose up -d --build   # builds the jar inside Docker; PostgreSQL + the application (localhost:8080)
```

The database is closed inside the Docker network: only the application container can connect to it, and its port is not
opened on your computer. To reach it from your computer (psql, a DB client, `bootRun`), uncomment `ports:` and
`- "5432:5432"` under `postgres` in `docker-compose.yml`; it then listens on `localhost:5432`.

For development you can run only the database and start the application from Gradle (this needs the port opened as
described above; without `DB_URL` the application connects to `localhost:5432`):

```
docker compose up -d postgres
./gradlew bootRun
```

The compose file is also compatible with Podman (`podman-compose up -d --build`).

Health: `GET /actuator/health` returns `UP` once the application started and the database is reachable (the only
actuator endpoint exposed). The image has a `HEALTHCHECK` on it, so `docker compose up -d --build --wait` returns when
the application is ready.

Settings can be overridden with environment variables: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `DB_POOL_SIZE`,
`GEO_BASE_URL`.

## Test

```
./gradlew test
```

Unit tests mock the database. Integration tests run on a real PostgreSQL started by Testcontainers, so a container
engine (Docker or Podman) is required.

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
| 403 | `COUNTRY_NOT_VERIFIABLE` | the geo provider does not know the caller's public address |
| 503 | `GEO_UNAVAILABLE` | the geo provider failed, timed out or is rate-limited (`Retry-After: 10`) |
| 503 | `CLIENT_ADDRESS_NOT_PUBLIC` | the service sees only a private address, usually a proxy that does not set `X-Forwarded-For` |
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
matter how many application instances run. For a coupon with `max_uses = 5` and `used_count = 4`, when two requests
arrive at the same moment:

1. Request A runs the `UPDATE`; Postgres locks the coupon's row until A's transaction ends.
2. Request B runs the same `UPDATE` and waits for that lock.
3. A commits, and `used_count` becomes 5.
4. B's `UPDATE` continues and re-checks the condition against the new value: `5 < 5` is false, so it updates 0 rows,
   which the service reports as "exhausted".

The lock is held by the database, not by the Java process, so it does not matter whether the two requests reach the
same instance or two different ones. There are no application-level locks, so the service scales horizontally. The
price is that all uses of one hot coupon queue on one row, so its throughput is bounded by the duration of a single
short transaction; different coupons do not block each other.

A `CHECK (used_count <= max_uses)` is the last line of defence: even a bug or a manual SQL statement cannot push the
counter above the limit. When a request loses the race for the last slot, its `UPDATE` changes 0 rows, the service
answers "exhausted", and the transaction rolls back, so the usage row inserted just before it disappears too. Technically
the row is not removed from the file at once: after a rollback it stays as a dead tuple that no query can see, and
autovacuum reclaims it later. Such rows are rare, because the exhausted check (a `SELECT`) runs before the transaction starts: only
requests that were already past that check when the last slot was taken can leave one, so their number is bounded by
the number of concurrent requests.

**One use per user.** The primary key of `coupon_usage` is `(coupon_id, user_id)`, so the database itself guarantees
that a user can use a coupon only once. There is no surrogate id and no sequence, and the one index also serves
lookups. The insert uses `ON CONFLICT DO NOTHING`: a duplicate is an expected business outcome, so it comes back as
"0 rows inserted" and maps to `COUPON_ALREADY_USED`, without catching a database exception or leaving an `ERROR` line
in the PostgreSQL log.

**Fail fast, write nothing.** Before any write the service checks, in order: the coupon exists, it is not exhausted, the
caller's country matches. Rejected requests (the common case under load) cost one `SELECT` and hold no transaction. An
exhausted coupon is therefore reported before a country mismatch, which also saves a geolocation call.

**Case-insensitive codes** are normalised to upper case in the application (`CouponCode`) before any use, and stored
that way under a unique constraint.

**Hexagonal architecture (ports and adapters).** The business code (`domain`, `application`) defines what it needs from
the outside world as interfaces, the ports (`CouponRepository`: "store and load coupons", `GeoLocationPort`: "give me
the country for this IP"), and knows nothing about HTTP, SQL, JSON or a geolocation provider. Everything technical is an
adapter around the core: `adapter.web` (REST), `adapter.persistence` (PostgreSQL through JDBC) and `adapter.geo`
(the geolocation provider). Dependencies point inwards only, so an adapter can be replaced without touching the
business logic, and tests can use simple fakes. `domain` and `application` are plain Java with no Spring imports; the
wiring is in `CouponConfig`. `ArchitectureTest` fails the build if a dependency points the wrong way.

**Geolocation** is such an adapter. `ResilientGeoLocation` implements the port and adds what every provider needs: it
looks up only public IP addresses (anything else is rejected without a provider request), a cache of answers (10
minutes; "the provider does not know this address" for 1 minute; technical failures are not cached), single-flight
(concurrent requests for one address share one lookup, waiting on a future rather than a lock), lookups on virtual
threads with a hard timeout, and a Resilience4j circuit breaker that fails fast while the provider is down. A provider
only implements `CountryLookup` (currently `IpWhoIsLookup`, 2 s timeouts), selected with `coupon.geo.provider`. If the
country cannot be determined the request is rejected (fail closed).

**Virtual threads** are enabled so that requests waiting for the geolocation service or the database do not occupy
platform threads. The connection pool (20, 2 s timeout) is then the bounded resource: under overload the service
answers 503 quickly instead of queueing without limit.

**Client address.** `server.forward-headers-strategy=native` makes Tomcat trust `X-Forwarded-For` only from private
address ranges (the proxy in front of the service). The design assumes a layer-7 load balancer (API Gateway, Ingress)
that overwrites the header with the original client address, and not appends to it. Layer-4 hops (Docker port mapping,
NodePort, ClusterIP, NLB) do not touch HTTP headers and may replace the source address, so the original address is lost;
without an overwriting proxy callers could spoof their country. Rate limiting is not
implemented: a caller who varies the header over many valid public addresses can still use up the provider's quota.

## Known limitations

- **No authentication or authorisation.** Anyone who can reach the API can create coupons. In a real deployment
  coupon creation would be restricted.
- **`userId` is an opaque identifier.** It comes from the request body and is not verified, because authentication is
  out of scope. The service compares it exactly (`U1` and `u1` are different users). In production it would be the
  authenticated subject, so one-use-per-user is only as strong as that identity.
- **Database credentials are hard-coded** in `docker-compose.yml` (and defaulted in `application.properties`) for
  convenience. Outside local use they belong in a secret store or environment.
- **No data persistence in Docker.** The PostgreSQL container has no volume, so `docker compose down` discards the
  coupons.
- **The geolocation provider is a free tier.** ipwho.is allows 1,000 requests per day per calling IP, has no SLA, and
  answers 429 beyond that. The cache (10 min per address) softens this, but with many distinct client addresses the
  limit is reached and country-restricted coupons answer 503 until it resets. For production use a paid plan or a
  local database (e.g. MMDB); only `CountryLookup` would change.

## Possible next steps

- Rate limiting per IP in front of the service.
- Coupon expiry date and soft delete.
- Observability: metrics for the outcome of each usage attempt and for geolocation latency.
