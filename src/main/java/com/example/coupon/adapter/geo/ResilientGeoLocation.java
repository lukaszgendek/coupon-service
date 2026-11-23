package com.example.coupon.adapter.geo;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.Optional;

import com.example.coupon.application.GeoLocationPort;
import com.example.coupon.domain.CountryCode;
import com.example.coupon.domain.CouponException;
import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provider-independent behaviour around a {@link CountryLookup}. Only public addresses are looked up; anything else
 * is rejected without a provider request. Answers are cached, including "the provider does not know this address"
 * (for a shorter time); technical failures are not, so a temporary outage heals itself. The cache is asynchronous:
 * concurrent requests for the same address share one in-flight lookup (single-flight) instead of each calling the
 * provider, and they wait on a future rather than on a lock. Lookups run on virtual threads, so blocking calls do
 * not exhaust the common pool, and a hard timeout bounds the wait even if the HTTP client's timeout does not.
 * A circuit breaker fails lookups fast while the provider is down or rate-limiting.
 */
class ResilientGeoLocation implements GeoLocationPort, AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(ResilientGeoLocation.class);

	private static final Duration UNKNOWN_ADDRESS_TTL = Duration.ofMinutes(1);

	/** A null country means the provider answered but does not know the address. */
	private record Answer(CountryCode country) {

		static final Answer UNKNOWN = new Answer(null);

	}

	private final CountryLookup lookup;

	private final CircuitBreaker breaker;

	private final Duration hardTimeout;

	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	private final AsyncCache<String, Answer> cache;

	ResilientGeoLocation(CountryLookup lookup, Duration cacheTtl, Duration hardTimeout, CircuitBreaker breaker) {
		this.lookup = lookup;
		this.hardTimeout = hardTimeout;
		this.breaker = breaker;
		this.cache = Caffeine.newBuilder()
				.expireAfter(Expiry.<String, Answer>writing(
						(key, answer) -> answer.country() == null ? UNKNOWN_ADDRESS_TTL : cacheTtl))
				.maximumSize(50_000)
				.executor(executor)
				.buildAsync();
	}

	@Override
	public void close() {
		executor.shutdownNow();
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
		Optional<String> address = ClientAddress.publicAddress(ip);
		if (address.isEmpty()) {
			log.debug("Not a public IP address, skipping the lookup");
			throw new CouponException.ClientAddressNotPublic();
		}
		String key = address.get();
		CompletableFuture<Answer> pending = cache.get(key,
				(k, executor) -> CompletableFuture.supplyAsync(() -> lookupThroughBreaker(k), executor)
						.orTimeout(hardTimeout.toMillis(), TimeUnit.MILLISECONDS));
		try {
			return Optional.ofNullable(pending.join().country());
		}
		catch (CompletionException e) {
			// Caffeine also drops failed futures, but asynchronously; remove it now so the next call retries.
			cache.asMap().remove(key, pending);
			if (e.getCause() instanceof CallNotPermittedException) {
				log.debug("Geolocation circuit is open, no lookup for {}", key);
			}
			else {
				log.warn("Geolocation lookup failed for {}: {}", key, e.getCause().toString());
			}
			throw new CouponException.GeoUnavailable();
		}
	}

	/** "Unknown address" is a valid answer: it counts as a success for the breaker and is cached briefly. */
	private Answer lookupThroughBreaker(String address) {
		return breaker.executeSupplier(() -> {
			try {
				return new Answer(lookup.lookup(address));
			}
			catch (CountryLookup.UnknownAddress e) {
				log.debug("No country for {}: {}", address, e.getMessage());
				return Answer.UNKNOWN;
			}
		});
	}

}
