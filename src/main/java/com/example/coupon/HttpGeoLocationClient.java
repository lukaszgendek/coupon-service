package com.example.coupon;

import java.time.Duration;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Resolves the country through an ipwho.is-compatible HTTP API. Successful answers are cached; failures are not, so a
 * temporary outage heals itself. The cache is read and written around the HTTP call rather than with a loading
 * function, so no lock is held while a virtual thread waits for the network.
 */
class HttpGeoLocationClient implements GeoLocationPort {

	private static final Logger log = LoggerFactory.getLogger(HttpGeoLocationClient.class);

	private final RestClient client;

	private final Cache<String, CountryCode> cache;

	HttpGeoLocationClient(RestClient client, Duration cacheTtl) {
		this.client = client;
		this.cache = Caffeine.newBuilder().maximumSize(50_000).expireAfterWrite(cacheTtl).build();
	}

	@Override
	public Optional<CountryCode> countryOf(String ip) {
		CountryCode cached = cache.getIfPresent(ip);
		if (cached != null) {
			return Optional.of(cached);
		}
		try {
			GeoResponse response = client.get()
					.uri("/{ip}?fields=success,country_code", ip)
					.retrieve()
					.body(GeoResponse.class);
			if (response == null || !response.success() || response.countryCode() == null) {
				return Optional.empty();
			}
			CountryCode country = CountryCode.of(response.countryCode());
			cache.put(ip, country);
			return Optional.of(country);
		}
		catch (RestClientException | CouponException.InvalidInput e) {
			log.warn("Geolocation lookup failed for {}: {}", ip, e.toString());
			return Optional.empty();
		}
	}

	record GeoResponse(boolean success, @JsonProperty("country_code") String countryCode) {
	}

}
