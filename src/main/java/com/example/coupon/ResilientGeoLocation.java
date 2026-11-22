package com.example.coupon;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provider-independent behaviour around a {@link CountryLookup}. Successful answers are cached; failures are not, so
 * a temporary outage heals itself. The cache is asynchronous: concurrent requests for the same address share one
 * in-flight lookup (single-flight) instead of each calling the provider, and they wait on a future rather than on a
 * lock. Lookups run on virtual threads, so blocking calls do not exhaust the common pool. A circuit breaker fails
 * lookups fast while the provider is down or rate-limiting, instead of letting every request wait for the timeout.
 */
class ResilientGeoLocation implements GeoLocationPort {

	private static final Logger log = LoggerFactory.getLogger(ResilientGeoLocation.class);

	private final CountryLookup lookup;

	private final CircuitBreaker breaker;

	private final AsyncCache<String, CountryCode> cache;

	ResilientGeoLocation(CountryLookup lookup, Duration cacheTtl, CircuitBreaker breaker) {
		this.lookup = lookup;
		this.breaker = breaker;
		this.cache = Caffeine.newBuilder()
				.maximumSize(50_000)
				.expireAfterWrite(cacheTtl)
				.executor(Executors.newVirtualThreadPerTaskExecutor())
				.buildAsync();
	}

	/** Opens after half of the recent calls failed technically; "unknown address" is a valid answer, not a failure. */
	static CircuitBreaker newCircuitBreaker() {
		return CircuitBreaker.of("geo", CircuitBreakerConfig.custom()
				.slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
				.slidingWindowSize(20)
				.minimumNumberOfCalls(10)
				.failureRateThreshold(50)
				.waitDurationInOpenState(Duration.ofSeconds(10))
				.permittedNumberOfCallsInHalfOpenState(3)
				.recordExceptions(CountryLookup.Unavailable.class)
				.build());
	}

	@Override
	public Optional<CountryCode> countryOf(String ip) {
		CompletableFuture<CountryCode> pending = cache.get(ip,
				(key, executor) -> CompletableFuture.supplyAsync(() -> breaker.executeSupplier(() -> lookup.lookup(key)), executor));
		try {
			return Optional.of(pending.join());
		}
		catch (CompletionException e) {
			// Caffeine also drops failed futures, but asynchronously; remove it now so the next call retries.
			cache.asMap().remove(ip, pending);
			if (e.getCause() instanceof CallNotPermittedException || e.getCause() instanceof CountryLookup.UnknownAddress) {
				log.debug("No country for {}: {}", ip, e.getCause().getMessage());
			}
			else {
				log.warn("Geolocation lookup failed for {}: {}", ip, e.getCause().toString());
			}
			return Optional.empty();
		}
	}

}
