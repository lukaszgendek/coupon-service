# coupon-service

REST service for discount coupons: create a coupon and register its use by a user.
Java 21, Spring Boot 4, PostgreSQL, Flyway, Gradle.

## Run

```
docker compose up -d --build   # builds the jar inside Docker; starts PostgreSQL and the application (localhost:8080)
```

The database is closed inside the Docker network. Only the application container can connect to it, and its port is
not opened on your computer. To reach it from your computer (psql, a DB client, `bootRun`), uncomment `ports:` and
`- "5432:5432"` under `postgres` in `docker-compose.yml`. The database then listens on `localhost:5432`.

For development you can start only the database and run the application from Gradle. This needs the port opened as
described above. Without `DB_URL`, the application connects to `localhost:5432`.

```
docker compose up -d postgres
./gradlew bootRun
```

The compose file also works with Podman (`podman-compose up -d --build`).

Health: `GET /actuator/health` returns `UP` when the application has started and the database is reachable. It is the
only actuator endpoint that is exposed. The image has a `HEALTHCHECK` on it, so `docker compose up -d --build --wait`
returns when the application is ready.

You can change the settings with environment variables: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `DB_POOL_SIZE`,
`GEO_BASE_URL`, `GEO_PROVIDER`.

## Test

```
./gradlew test
```

Unit tests mock the database. Integration tests use a real PostgreSQL started by Testcontainers, so you need a
container engine (Docker or Podman).

## API

| Method and path | Body | Result |
|---|---|---|
| `POST /api/coupons` | `{"code":"WIOSNA","country":"PL","maxUses":100}` | `201` + `Location` |
| `GET /api/coupons/{code}` | | `200` with `usedCount` |
| `POST /api/coupons/{code}/usages` | `{"userId":"u1"}` | `204` |

Errors are RFC 9457 problem details with a machine-readable `error` property:

| Status | `error` | Meaning |
|---|---|---|
| 400 | `INVALID_INPUT` | invalid code, country, user id or body |
| 403 | `COUNTRY_NOT_ALLOWED` | the caller's country is not the coupon's country |
| 404 | `COUPON_NOT_FOUND` | unknown code |
| 409 | `COUPON_EXHAUSTED` | the maximum number of uses is reached |
| 409 | `COUPON_ALREADY_USED` | this user already used the coupon |
| 409 | `COUPON_CODE_TAKEN` | the code already exists (case-insensitive) |
| 403 | `COUNTRY_NOT_VERIFIABLE` | the geolocation provider does not know the caller's public address |
| 503 | `GEO_UNAVAILABLE` | the geolocation provider failed, timed out or refused the request (`Retry-After: 10`) |
| 503 | `CLIENT_ADDRESS_NOT_PUBLIC` | the service sees only a private address, usually because a proxy does not set `X-Forwarded-For` |
| 503 | `SERVICE_OVERLOADED` | no database connection within 2 s (`Retry-After: 1`) |

Try it locally. Requests from your own machine come from `127.0.0.1`, which the service trusts like a proxy. So it reads
the client address from the `X-Forwarded-For` header. Without that header, you would get `CLIENT_ADDRESS_NOT_PUBLIC`:

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

The `UPDATE` takes a row lock. Two requests for the same coupon cannot run it at the same time: the second one waits
until the first transaction ends, and then checks `used_count < max_uses` again with the new value. The database holds
the lock, so the limit is kept no matter how many application instances run. Example: a coupon has `max_uses = 5` and
`used_count = 4`, and two requests arrive at the same moment:

1. Request A runs the `UPDATE`. PostgreSQL locks the coupon's row until A's transaction ends.
2. Request B runs the same `UPDATE` and waits for the lock.
3. A commits, and `used_count` becomes 5.
4. B's `UPDATE` continues and checks the condition again with the new value: `5 < 5` is false, so it updates 0 rows.
   The service reports this as "exhausted".

The lock belongs to the database, not to the Java process, so it does not matter if the two requests reach the same
instance or two different ones. The application has no locks of its own, so it can scale horizontally. The price: all
uses of one popular coupon wait in line on one row, so its speed is limited by the length of one short transaction.
Different coupons do not block each other.

A `CHECK (used_count <= max_uses)` is the last line of defence: even a bug or a manual SQL statement cannot push the
counter above the limit.

When a request loses the race for the last slot, its `UPDATE` changes 0 rows, the service answers "exhausted", and the
transaction rolls back. The usage row inserted just before the `UPDATE` disappears too. Technically the row is not
removed from the file at once. After a rollback it stays as a dead tuple that no query can see, and autovacuum removes
it later. Such rows are rare, because the exhausted check (a `SELECT`) runs before the transaction starts. Only
requests that had already passed that check when the last slot was taken can leave one, so their number is limited by
the number of concurrent requests.

**One use per user.** The primary key of `coupon_usage` is `(coupon_id, user_id)`, so the database itself makes sure
that a user can use a coupon only once. There is no surrogate id and no sequence, and the one index is also used for
lookups. The insert uses `ON CONFLICT DO NOTHING`. A duplicate is an expected business case, so it comes back as
"0 rows inserted" and becomes `COUPON_ALREADY_USED`. The code does not catch a database exception, and PostgreSQL
writes no `ERROR` line to its log.

**Fail fast, write nothing.** Before any write, the service checks in this order:

1. The coupon exists (otherwise `COUPON_NOT_FOUND`).
2. It is not exhausted (otherwise `COUPON_EXHAUSTED`).
3. The caller's country matches (otherwise `COUNTRY_NOT_ALLOWED`).

Only then does the transaction start. A request rejected in step 1 or 2 is the common case under load. It costs one
`SELECT` and holds no transaction. The cheap local checks run before the external one, so a request for an exhausted
coupon is answered without a geolocation call (a network round trip, and the free plan has a daily limit). As a
result, a caller from the wrong country gets `COUPON_EXHAUSTED` and not `COUNTRY_NOT_ALLOWED`. The early checks are
only a shortcut. The atomic `INSERT` and `UPDATE` in the transaction decide whether a use is granted.

**Case-insensitive codes** are changed to upper case in the application (`CouponCode`) before any use, and they are
stored that way under a unique constraint.

**Hexagonal architecture (ports and adapters).** A port is an interface that belongs to the business code (`domain`,
`application`). It says what the application offers or needs, not how it is done. The ports are in
`application.port`:

1. **Inbound** (`port.in`): what the outside world can ask the application to do. `CouponUseCases` (create, get and use
   a coupon) is implemented by `CouponService` and called by `adapter.web` (REST).
2. **Outbound** (`port.out`): what the application needs from the outside world. Adapters implement these ports:
   - `CouponRepository` ("store and load coupons") is implemented by `adapter.persistence` (PostgreSQL through JDBC).
   - `GeoLocationPort` (`Optional<CountryCode> countryOf(String ip)`) is implemented by `adapter.geo`
     (`ResilientGeoLocation`, which wraps `IpWhoIsLookup`).

`CouponService` knows only the outbound interfaces. It knows nothing about HTTP, SQL, JSON, caching or a geolocation
provider. Dependencies point inwards only: an adapter depends on a port and never the other way round, and the web
adapter sees only `CouponUseCases`. `ArchitectureTest` fails the build if one of these rules is broken. `domain` and
`application` are plain Java without Spring imports. The wiring is in `CouponConfig`. This gives three benefits:

- **Replaceable technology.** You can change the provider (another API, a local MMDB file) or the database without
  changing the business logic.
- **Easy tests.** `CouponServiceTest` mocks the ports, so it needs no network, no database and no Spring.
- **A clean core.** Technical things (cache, circuit breaker, timeout, SQL) stay out of the business rules.

**Geolocation** is a port (`GeoLocationPort`). Its adapter, `ResilientGeoLocation`, has these features:

- **Public addresses only.** Any other address (private, loopback, invalid) is rejected without a request to the
  provider.
- **Cache of answers.** A known country is remembered for 10 minutes. "The provider does not know this address" is
  remembered for 1 minute, so repeated requests for an unknown address do not reach the provider. Technical failures
  are not cached, so the next request tries again.
- **Single-flight.** The cache (Caffeine `AsyncCache`) stores, for each address, a `CompletableFuture`: "the answer that
  is being fetched". The first request starts the lookup. Other requests for the same address find the same future and
  wait for it, so they share one provider call. Waiting on a future instead of a lock means there is no risk of
  deadlock, lookups for different addresses never block each other, and a waiting virtual thread is cheap.
- **Virtual threads.** The provider lookup is a blocking HTTP call. It runs on a virtual thread, which is parked while
  it waits for the network, so slow lookups do not use up the platform threads that serve other requests.
- **Hard timeout.** A geolocation lookup may take only a fixed total time. If the provider does not answer in time, the
  request gets 503 `GEO_UNAVAILABLE` and the failure counts for the circuit breaker, so a slow provider can never make
  requests hang. The HTTP client has its own 2 s timeouts, but they limit single steps (connecting, waiting for the
  next data), not the total time.
- **Circuit breaker** (Resilience4j). While the provider is down, it fails fast.

To use another provider, write a class that implements `CountryLookup` and select it with `coupon.geo.provider` (set with
the environment variable `GEO_PROVIDER`; the default is `ipwhois`). Today it is `IpWhoIsLookup` (2 s timeouts). If we
cannot find the caller's country, we reject the request. We never guess.

**Virtual threads** are enabled so that requests that wait for the geolocation service or the database do not occupy
platform threads. The database connection pool (20 connections, 2 s timeout) is then the limited resource. Under
overload the service answers 503 quickly and does not queue requests without limit.

**Scaling out.** The service is stateless. All coupon state is in PostgreSQL, and the database enforces the limit and
the one use per user (row lock and primary key), so they work across any number of instances. To scale, run more pods
(for example a Kubernetes `Deployment` with an HPA) behind a layer-7 HTTP load balancer (Ingress or API Gateway). Some
things do not scale by themselves:

- **Database connections.** Each pod has its own pool of 20 connections. PostgreSQL has a limit (`max_connections`, 100
  by default), and every connection is costly. With many pods, pods × 20 can exceed the limit. A pooler such as
  PgBouncer keeps a few real connections to the database and shares them between the pods.
- **One very popular coupon.** The row lock handles its uses one after another, so more pods do not make it faster.
- **Geolocation cache and circuit breaker exist per pod.** More pods mean more provider calls for the same address. You
  can reduce this by sending the same client address to the same pod (consistent hashing on the client IP in the
  ingress), or by using a shared cache. This is only an optimisation. Correctness never depends on it, because the
  database decides every use.

**Client address.** `server.forward-headers-strategy=native` makes Tomcat trust `X-Forwarded-For` only from private
address ranges (the proxy in front of the service). The design assumes a layer-7 load balancer (API Gateway, Ingress)
that overwrites the header with the original client address and does not append to it. Layer-4 hops (Docker port
mapping, NodePort, ClusterIP, NLB) do not touch HTTP headers and may replace the source address, so the original
address is lost. Without a proxy that overwrites the header, a caller can fake its country by sending its own
`X-Forwarded-For`. Rate limiting is not implemented: a caller who sends many different valid public addresses can still
use up the provider's daily limit.

## Known limitations

- **No authentication or authorisation.** Anyone who can reach the API can create coupons. In a real deployment, coupon
  creation would be restricted.
- **`userId` is an opaque identifier.** It comes from the request body and is not verified, because authentication is
  out of scope. The service compares it exactly (`U1` and `u1` are different users) and accepts only 1-64 characters
  from `[A-Za-z0-9._@-]` (`UserId`). This keeps control characters out of the database and the logs. A real identity
  provider may need more characters. In production, `userId` would be the authenticated subject, so one use per user is
  only as strong as that identity.
- **Database credentials are hard-coded** in `docker-compose.yml` (and set as defaults in `application.properties`) for
  convenience. Outside local use they belong in a secret store or in the environment.
- **No data persistence in Docker.** The PostgreSQL container has no volume, so `docker compose down` deletes the
  coupons.
- **The geolocation provider is on a free plan.** ipwho.is allows 1,000 requests per day for each IP address our
  service calls from. It has no uptime guarantee, and it answers with an error (429) when the limit is exceeded. The
  cache helps, because the same client address is looked up only once every 10 minutes. But with many different client
  addresses the daily limit is still reached. Then coupon uses fail with 503 until the limit resets. In production, use
  a paid plan or a local database file (such as MMDB). Only `CountryLookup` would have to change.

## Possible next steps

- Rate limiting per IP address in front of the service.
- A coupon expiry date and soft delete.
- Observability: metrics for the result of each usage attempt and for geolocation latency.
