package com.example.coupon.adapter.geo;

import com.example.coupon.domain.CountryCode;

/**
 * Asks one geolocation provider for the country of an address. Implementations know the provider's protocol and
 * nothing else: caching, single-flight and the circuit breaker live in {@link ResilientGeoLocation}.
 */
interface CountryLookup {

	/**
	 * @throws UnknownAddress if the provider answered but has no country for the address (valid answer)
	 * @throws Unavailable if the provider could not answer (network error, timeout, error status, bad payload)
	 */
	CountryCode lookup(String ip);

	class UnknownAddress extends RuntimeException {

		UnknownAddress(String message) {
			super(message);
		}

	}

	class Unavailable extends RuntimeException {

		Unavailable(String message, Throwable cause) {
			super(message, cause);
		}

	}

}
