package com.example.coupon.adapter.geo;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import com.example.coupon.domain.CountryCode;
import com.example.coupon.domain.CouponException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Cache, single-flight and circuit breaker, independent of any provider. */
class ResilientGeoLocationTest {

	private final AtomicInteger calls = new AtomicInteger();

	private ResilientGeoLocation geo(CountryLookup lookup) {
		return new ResilientGeoLocation(lookup, Duration.ofMinutes(10), Duration.ofSeconds(5),
				ResilientGeoLocation.newCircuitBreaker());
	}

	@Test
	void cachesSuccessfulAnswers() {
		ResilientGeoLocation geo = geo(ip -> {
			calls.incrementAndGet();
			return CountryCode.of("PL");
		});

		assertThat(geo.countryOf("203.0.113.7")).contains(CountryCode.of("PL"));
		assertThat(geo.countryOf("203.0.113.7")).contains(CountryCode.of("PL"));

		assertThat(calls).hasValue(1);
	}

	@Test
	void failureIsReportedAndNotCached() {
		ResilientGeoLocation geo = geo(ip -> {
			if (calls.incrementAndGet() == 1) {
				throw new CountryLookup.Unavailable("down", null);
			}
			return CountryCode.of("DE");
		});

		assertThatThrownBy(() -> geo.countryOf("203.0.113.8")).isInstanceOf(CouponException.GeoUnavailable.class);
		assertThat(geo.countryOf("203.0.113.8")).contains(CountryCode.of("DE"));
	}

	@Test
	void unknownAddressIsEmptyAndRememberedSoTheProviderIsAskedOnce() {
		ResilientGeoLocation geo = geo(ip -> {
			calls.incrementAndGet();
			throw new CountryLookup.UnknownAddress("no country");
		});

		assertThat(geo.countryOf("203.0.113.10")).isEmpty();
		assertThat(geo.countryOf("203.0.113.10")).isEmpty();

		assertThat(calls).hasValue(1);
	}

	@Test
	void nonPublicAddressesNeverReachTheProvider() {
		ResilientGeoLocation geo = geo(ip -> {
			calls.incrementAndGet();
			return CountryCode.of("PL");
		});

		for (String ip : List.of("abc", "", "10.0.0.5", "127.0.0.1", "192.168.1.1", "::1", "fd00::1", "1.2.3")) {
			assertThatThrownBy(() -> geo.countryOf(ip)).as(ip).isInstanceOf(CouponException.ClientAddressNotPublic.class);
		}

		assertThat(calls).hasValue(0);
	}

	@Test
	void differentWritingsOfOneAddressShareACacheEntry() {
		ResilientGeoLocation geo = geo(ip -> {
			calls.incrementAndGet();
			return CountryCode.of("PL");
		});

		assertThat(geo.countryOf("2001:db8::1")).contains(CountryCode.of("PL"));
		assertThat(geo.countryOf("2001:0db8:0:0:0:0:0:1")).contains(CountryCode.of("PL"));

		assertThat(calls).hasValue(1);
	}

	@Test
	void lookupSlowerThanTheHardTimeoutIsReportedAndNotCached() {
		ResilientGeoLocation geo = new ResilientGeoLocation(ip -> {
			if (calls.incrementAndGet() == 1) {
				sleep(1_000);
			}
			return CountryCode.of("PL");
		}, Duration.ofMinutes(10), Duration.ofMillis(200), ResilientGeoLocation.newCircuitBreaker());

		assertThatThrownBy(() -> geo.countryOf("203.0.113.11")).isInstanceOf(CouponException.GeoUnavailable.class);
		assertThat(geo.countryOf("203.0.113.11")).contains(CountryCode.of("PL"));
	}

	@Test
	void concurrentLookupsForOneAddressShareASingleCall() throws Exception {
		ResilientGeoLocation geo = geo(ip -> {
			calls.incrementAndGet();
			sleep(300);
			return CountryCode.of("PL");
		});

		try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Future<Optional<CountryCode>>> results = IntStream.range(0, 50)
					.mapToObj(i -> callers.submit(() -> geo.countryOf("203.0.113.9")))
					.toList();
			for (Future<Optional<CountryCode>> result : results) {
				assertThat(result.get()).contains(CountryCode.of("PL"));
			}
		}

		assertThat(calls).hasValue(1);
	}

	@Test
	void circuitOpensAfterRepeatedFailuresAndStopsCallingTheProvider() {
		CircuitBreaker breaker = smallBreaker();
		ResilientGeoLocation geo = new ResilientGeoLocation(ip -> {
			calls.incrementAndGet();
			throw new CountryLookup.Unavailable("down", null);
		}, Duration.ofMinutes(10), Duration.ofSeconds(5), breaker);

		for (int i = 1; i <= 5; i++) {
			String ip = "203.0.113." + i;
			assertThatThrownBy(() -> geo.countryOf(ip)).isInstanceOf(CouponException.GeoUnavailable.class);
		}

		assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
		assertThat(calls).hasValue(4);
		assertThat(breaker.getMetrics().getNumberOfNotPermittedCalls()).isEqualTo(1);
	}

	@Test
	void unknownAddressesDoNotOpenTheCircuit() {
		CircuitBreaker breaker = smallBreaker();
		ResilientGeoLocation geo = new ResilientGeoLocation(ip -> {
			throw new CountryLookup.UnknownAddress("private range");
		}, Duration.ofMinutes(10), Duration.ofSeconds(5), breaker);

		for (int i = 1; i <= 8; i++) {
			assertThat(geo.countryOf("203.0.113." + i)).isEmpty();
		}

		assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
	}

	private static CircuitBreaker smallBreaker() {
		return CircuitBreaker.of("test", CircuitBreakerConfig.custom()
				.slidingWindowSize(4)
				.minimumNumberOfCalls(4)
				.failureRateThreshold(50)
				.waitDurationInOpenState(Duration.ofHours(1))
				.recordExceptions(CountryLookup.Unavailable.class)
				.build());
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

}
